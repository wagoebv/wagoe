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

(def locations
  "Where the baseline is written, and how to read the major version out.

   Every regex may match more than once in a file — a Dockerfile names a JDK in
   both its build and its runtime stage — and every match must agree. A file
   listed here and missing from disk is a failure, not a skip: a gate that
   quietly stops looking at a renamed file reports clean because it looked at
   nothing."
  [{:path "Dockerfile"
    :re   #"(?:clojure:temurin-|eclipse-temurin:)(\d+)"
    :what "production image"}
   {:path "resources/conf/dev/Dockerfile"
    :re   #"(?:clojure:temurin-|eclipse-temurin:)(\d+)"
    :what "dev image"}
   {:path "examples/shop/Dockerfile"
    :re   #"(?:clojure:temurin-|eclipse-temurin:)(\d+)"
    :what "examples/shop"}
   {:path "libs/wagoe-cli/resources/wagoe/cli/templates/Dockerfile.tmpl"
    :re   #"(?:clojure:temurin-|eclipse-temurin:)(\d+)"
    :what "generated project image"}
   {:path "scripts/install.sh"
    :re   #"JAVA_MIN=(\d+)"
    :what "installer"}
   {:path ".github/actions/clojure-deps/action.yml"
    :re   #"java-version:\s*\"(\d+)\""
    :what "CI (every job that resolves deps)"}
   {:path ".github/workflows/ci.yml"
    :re   #"java-version:\s*\"(\d+)\""
    :what "CI (jobs with their own setup-java)"}])

(defn- read-file [path]
  (when (fs/exists? path) (slurp path)))

(defn findings
  "Locations whose written version disagrees with `expected`, plus locations
   that could not be read at all.

   Pure in `read-file`, so a test can drive it without touching the repo."
  [expected locs read-file]
  (reduce
   (fn [acc {:keys [path re what]}]
     (if-let [content (read-file path)]
       (let [versions (map (comp parse-long second) (re-seq re content))]
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
