(ns wagoe.tools.check-jdk-test
  "The baseline gate has to fail on each way the numbers can drift apart.

   All of these were live at once before BOU-446: a Dockerfile on an older JDK
   than the installer demands, and a workflow that pinned nothing at all —
   the same defect wearing the other face, because a file nobody writes a
   version into is a file nobody can see disagree.

   The gate discovers its files rather than listing them. That is not a
   refinement: the listed version was reviewed twice and both passes found
   files missing from the list — publish.yml, BUILD.md, docker-compose.yml,
   first-run-matrix.yml."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [wagoe.tools.check-jdk :as check-jdk]
            [wagoe.tools.doctor-env :as doctor-env]))

(def ^:private prefix-files
  "One file under each exempt prefix, so the prefix burn-down has something to
   find. A prefix that exempts nothing is itself a finding — see below."
  (zipmap (map #(str % "something.adoc") (keys check-jdk/exempt-prefixes))
          (repeat "Java 17")))

(defn- healthy
  "Every must-pin and exempt file answering in a way that produces no finding.

   `findings` always evaluates those two sets, whatever file list it is given —
   that is the point of the must-pin half — so a test that stubs only the file
   under test gets the whole repository reported as missing. install.sh needs
   its own spelling: its pattern reads JAVA_MIN, not prose."
  [expected]
  (merge (zipmap (keys check-jdk/must-name-a-jdk) (repeat (str "Java " expected)))
         (zipmap (keys check-jdk/exempt) (repeat "Java 17"))
         prefix-files
         {"scripts/install.sh" (str "JAVA_MIN=" expected "\n# and Java 8 before that")}))

(defn- with-prefixes
  "`paths` plus the files the exempt prefixes need to still be exempting."
  [& paths]
  (into (vec (keys prefix-files)) paths))

(defn- stub [m] (fn [path] (get m path)))

(deftest ^:unit a-file-naming-another-jdk-is-reported
  (testing "any tracked file, listed or not — this is the discovery"
    (let [found (check-jdk/findings 21 (with-prefixes "some/new/Dockerfile")
                                    (stub (assoc (healthy 21)
                                                 "some/new/Dockerfile"
                                                 "FROM eclipse-temurin:17-jre")))]
      (is (= ["some/new/Dockerfile"] (map :path found)))
      (is (re-find #"declares JDK 17" (:problem (first found))))))

  (testing "two stages of one file disagreeing with each other"
    (let [found (check-jdk/findings 21 (with-prefixes "Dockerfile")
                                    (stub (assoc (healthy 21)
                                                 "Dockerfile"
                                                 (str "FROM eclipse-temurin:21-jre\n"
                                                      "FROM eclipse-temurin:17-jre"))))]
      (is (re-find #"declares JDK 17, 21" (:problem (first found))))))

  (testing "a file at the baseline says nothing"
    (is (empty? (check-jdk/findings 21 (with-prefixes "some/Dockerfile")
                                    (stub (assoc (healthy 21)
                                                 "some/Dockerfile"
                                                 "FROM eclipse-temurin:21-jre")))))))

(deftest ^:unit a-file-that-must-pin-and-does-not-is-reported
  ;; Discovery alone cannot see this: a workflow that sets up no JDK matches
  ;; nothing and reads as clean. publish.yml built every released artifact on
  ;; the runner default for exactly that reason.
  (testing "present, but the pin is gone"
    (let [found (check-jdk/findings
                 21 (with-prefixes)
                 (stub (assoc (healthy 21)
                              ".github/workflows/publish.yml"
                              "steps:\n  - uses: actions/checkout@v6")))]
      (is (= [".github/workflows/publish.yml"] (map :path found)))
      (is (re-find #"names no JDK at all" (:problem (first found))))))

  (testing "and a must-pin file that is missing entirely"
    (let [found (check-jdk/findings 21 (with-prefixes)
                                    (stub (apply dissoc (healthy 21)
                                                 (keys check-jdk/must-name-a-jdk))))]
      (is (= (set (keys check-jdk/must-name-a-jdk))
             (set (map :path (remove #(str/includes? (:problem %) "exemption") found)))))
      (is (some #(= "not found" (:problem %)) found)))))

(deftest ^:unit an-exemption-that-stopped-exempting-anything-is-reported
  ;; The burn-down property every allowlist in this repo has: an entry that no
  ;; longer matches is a claim about a file that has moved on.
  (let [found (check-jdk/findings 21 (with-prefixes)
                                  (stub (assoc (healthy 21)
                                               "CHANGELOG.md" "no versions here")))]
    (is (= ["CHANGELOG.md"] (map :path found)))
    (is (re-find #"drop the exemption" (:problem (first found))))))

(deftest ^:unit the-shared-pattern-reads-every-spelling-in-use
  (testing "prose, a Dockerfile FROM and a workflow pin"
    (doseq [[what text] [["prose"       "- Java 17 or higher"]
                         ["prose (JDK)" "needs JDK 17"]
                         ["image"       "FROM eclipse-temurin:17-jre-alpine"]
                         ["build image" "FROM clojure:temurin-17-tools-deps"]
                         ["old image"   "image: clojure:openjdk-17-tools-deps"]
                         ["workflow"    "          java-version: '17'"]
                         ["workflow"    "          java-version: \"17\""]
                         ["installer"   "JAVA_MIN=17"]]]
      (is (= [17] (check-jdk/versions-in "any/file" text))
          (str what " went unread: " text))))

  (testing "a file may override the shared pattern"
    ;; install.sh explains that a JDK before Java 9 spells its version
    ;; "1.8.0_402"; the shared rule reads that sentence as a second baseline.
    (let [sh "JAVA_MIN=21\n# Two spellings: since Java 9, and 1.8.0_402 before it."]
      (is (= [21] (check-jdk/versions-in "scripts/install.sh" sh)))
      (is (= [21 9] (check-jdk/versions-in "any/other/file" sh))
          "without the override the explanation reads as a second baseline"))))

(deftest ^:unit the-gate-scans-the-real-tree
  (testing "discovery reaches the files, and the repository agrees with itself"
    ;; Not a list this test can drift from: whatever git tracks.
    (let [files (check-jdk/tracked-files)]
      (is (< 500 (count files)) "git ls-files returned almost nothing")
      (is (some #(= "Dockerfile" %) files))
      (is (empty? (check-jdk/findings doctor-env/java-min files
                                      #(when (.exists (java.io.File. ^String %)) (slurp %)))))))

  (testing "every exemption carries a reason"
    (doseq [[path why] (merge check-jdk/exempt check-jdk/exempt-prefixes)]
      (is (and (string? why) (not (str/blank? why)))
          (str path " is exempt without saying why"))))

  (testing "and the baseline is a number a JDK actually has"
    (is (int? doctor-env/java-min))
    (is (<= 21 doctor-env/java-min))))
