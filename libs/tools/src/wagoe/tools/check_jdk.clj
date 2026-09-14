#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/check_jdk.clj
;;
;; One JDK baseline. Before this gate there were four answers to "which Java
;; does Wagoe need": the installer required 21, the docs said 21, the root and
;; dev Dockerfiles built and ran 17, `wagoe doctor` accepted 17, and CI pinned
;; nothing at all outside the e2e job — every other job ran whatever the runner
;; image defaults to (BOU-446).
;;
;; Truth is `wagoe.tools.doctor-env/java-min`, because that is the number a user
;; is actually held to. Every location below must agree with it.

(ns wagoe.tools.check-jdk
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [wagoe.tools.ansi :as ansi]
            [wagoe.tools.doctor-env :as doctor-env]))

(def jdk-re
  "Every way this repository names a JDK version.

   One pattern rather than one per file: the review that found `publish.yml`
   unpinned also found `BUILD.md` telling users to install 17, in three
   different spellings. A gate with a narrow pattern per location is a gate
   that stops looking the moment a file says it a new way."
  #"(?:clojure:temurin-|clojure:openjdk-|eclipse-temurin:|java-version:\s*[\"']|JAVA_MIN=|Java )(\d+)")

(def locations
  "Where the baseline is written.

   `jdk-re` may match more than once in a file — a Dockerfile names a JDK in
   both its build and its runtime stage — and every match must agree. A file
   listed here and missing from disk is a failure, not a skip: a gate that
   quietly stops looking at a renamed file reports clean because it looked at
   nothing."
  [{:path "Dockerfile" :what "production image"}
   {:path "resources/conf/dev/Dockerfile" :what "dev image"}
   {:path "examples/shop/Dockerfile" :what "examples/shop"}
   {:path "libs/wagoe-cli/resources/wagoe/cli/templates/Dockerfile.tmpl"
    :what "generated project image"}
   ;; Its own pattern: the installer explains that "1.8.0_402" is how a JDK
   ;; before Java 9 spells its version, and the shared prose rule reads that
   ;; sentence as a second baseline.
   {:path "scripts/install.sh" :re #"JAVA_MIN=(\d+)" :what "installer"}
   {:path ".github/actions/clojure-deps/action.yml"
    :what "CI (every job that resolves deps)"}
   {:path ".github/workflows/ci.yml" :what "CI (jobs with their own setup-java)"}
   ;; The released artifacts are compiled here, and this workflow set up no JDK
   ;; at all — `bb deploy` ran `clojure -T:build` on the runner default while
   ;; the gate watched only ci.yml and reported green.
   {:path ".github/workflows/publish.yml" :what "release build"}
   ;; What a user is told to install. BUILD.md said 17 in prose, in a Dockerfile
   ;; example and in a workflow example.
   {:path "BUILD.md" :what "build instructions"}
   {:path "libs/jobs/README.md" :what "jobs deployment example"}])

(defn- read-file [path]
  (when (fs/exists? path) (slurp path)))

(defn findings
  "Locations whose written version disagrees with `expected`, plus locations
   that could not be read at all.

   Pure in `read-file`, so a test can drive it without touching the repo. A
   location may override `jdk-re` with its own `:re`."
  [expected locs read-file]
  (reduce
   (fn [acc {:keys [path re what]}]
     (if-let [content (read-file path)]
       (let [versions (map (comp parse-long second) (re-seq (or re jdk-re) content))]
         (cond
           (empty? versions)
           (conj acc {:path path :what what
                      :problem (str "no JDK version found — has " path " changed shape?")})

           (some #(not= expected %) versions)
           (conj acc {:path path :what what
                      :problem (str "declares JDK "
                                    (str/join ", " (sort (distinct versions)))
                                    " — expected " expected)})

           :else acc))
       (conj acc {:path path :what what :problem "file not found"})))
   []
   locs))

(defn -main [& _args]
  (let [expected doctor-env/java-min
        problems (findings expected locations read-file)]
    (if (seq problems)
      (do
        (println (ansi/red (str "JDK baseline is " expected ", and these disagree:")))
        (println)
        (doseq [{:keys [path what problem]} problems]
          (println (format "  %-58s %s" (str path " (" what ")") problem)))
        (println)
        (println (str "The baseline lives in wagoe.tools.doctor-env/java-min. "
                      "Change it there and here together."))
        (System/exit 1))
      (do
        (println (ansi/green (str "JDK baseline " expected " agreed in "
                                  (count locations) " locations.")))
        (System/exit 0)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
