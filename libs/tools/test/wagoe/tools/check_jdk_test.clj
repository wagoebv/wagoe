(ns wagoe.tools.check-jdk-test
  "The baseline gate has to fail on each way the numbers can drift apart.

   All four shapes below were live at once before BOU-446: a Dockerfile on an
   older JDK than the installer demands, and a CI pin that did not exist at
   all — which is the same defect wearing the other face, because a location
   nobody writes a version into is a location nobody can see disagree."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.tools.check-jdk :as check-jdk]
            [wagoe.tools.doctor-env :as doctor-env]))

(def ^:private locs
  [{:path "Dockerfile" :re #"eclipse-temurin:(\d+)" :what "image"}
   {:path "scripts/install.sh" :re #"JAVA_MIN=(\d+)" :what "installer"}])

(defn- stub [m] (fn [path] (get m path)))

(deftest ^:unit a-disagreeing-location-is-reported
  (testing "an older JDK in one file, the baseline in the other"
    (let [found (check-jdk/findings 21 locs
                                    (stub {"Dockerfile"        "FROM eclipse-temurin:17-jre"
                                           "scripts/install.sh" "JAVA_MIN=21"}))]
      (is (= 1 (count found)))
      (is (= "Dockerfile" (:path (first found))))
      (is (re-find #"declares JDK 17" (:problem (first found))))))

  (testing "two stages of one file disagreeing with each other"
    (let [found (check-jdk/findings 21 [(first locs)]
                                    (stub {"Dockerfile" "FROM eclipse-temurin:21-jre\nFROM eclipse-temurin:17-jre"}))]
      (is (re-find #"declares JDK 17, 21" (:problem (first found))))))

  (testing "everything agreeing is silence"
    (is (empty? (check-jdk/findings 21 locs
                                    (stub {"Dockerfile"         "FROM eclipse-temurin:21-jre"
                                           "scripts/install.sh" "JAVA_MIN=21"}))))))

(deftest ^:unit the-shared-pattern-reads-every-spelling-in-use
  ;; publish.yml pinned nothing and BUILD.md said 17 three different ways, both
  ;; while the gate reported green — it had a narrow pattern per file and those
  ;; files were not in the list at all (BOU-446 review).
  (testing "prose, a Dockerfile FROM and a workflow pin"
    (doseq [[what text] [["prose"      "- Java 17 or higher"]
                         ["image"      "FROM eclipse-temurin:17-jre-alpine"]
                         ["build image" "FROM clojure:temurin-17-tools-deps"]
                         ["workflow"   "          java-version: '17'"]
                         ["workflow"   "          java-version: \"17\""]
                         ["installer"  "JAVA_MIN=17"]]]
      (is (= [17] (map (comp parse-long second) (re-seq check-jdk/jdk-re text)))
          (str what " went unread: " text))))

  (testing "a location may override the shared pattern"
    ;; install.sh explains that a JDK before Java 9 spells its version
    ;; \"1.8.0_402\"; the prose rule reads that sentence as a second baseline.
    (let [sh "JAVA_MIN=21\n# Two spellings: since Java 9, and 1.8.0_402 before it."]
      (is (seq (check-jdk/findings 21 [{:path "i" :what "x"}] (fn [_] sh)))
          "the shared pattern should trip on the explanation")
      (is (empty? (check-jdk/findings 21
                                      [{:path "i" :re #"JAVA_MIN=(\d+)" :what "x"}]
                                      (fn [_] sh)))))))

(deftest ^:unit a-location-that-cannot-be-read-is-a-failure-not-a-skip
  (testing "a file that moved or was renamed"
    (let [found (check-jdk/findings 21 locs (stub {"scripts/install.sh" "JAVA_MIN=21"}))]
      (is (= ["Dockerfile"] (map :path found)))
      (is (= "file not found" (:problem (first found))))))

  (testing "a file that no longer names a JDK at all — the pin was deleted"
    (let [found (check-jdk/findings 21 [(first locs)]
                                    (stub {"Dockerfile" "FROM debian:bookworm"}))]
      (is (re-find #"no JDK version found" (:problem (first found)))))))

(deftest ^:unit the-gate-covers-the-places-the-baseline-is-actually-written
  (testing "each configured location exists and carries a version"
    ;; Runs against the real tree: the unit cases above would all pass with an
    ;; empty `locations`, which is the gate going quiet.
    (is (<= 10 (count check-jdk/locations)))
    (is (empty? (check-jdk/findings doctor-env/java-min
                                    check-jdk/locations
                                    #(when (.exists (java.io.File. ^String %)) (slurp %))))))

  (testing "and the baseline itself is a number a JDK actually has"
    (is (int? doctor-env/java-min))
    (is (<= 21 doctor-env/java-min))))
