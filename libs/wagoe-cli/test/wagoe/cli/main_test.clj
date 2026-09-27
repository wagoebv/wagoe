(ns wagoe.cli.main-test
  "`wagoe version` reported 1.0.0-beta-5 four releases after beta-5.

   The number came from a string literal, and `bb check:versions` reads
   coordinates, `(def … -version)` forms, git tags and documented claims — a
   printed banner is none of those. So the one version a user asks the tool for
   directly was the only one nothing verified, and `bb bump` never touched it.

   It now comes from the catalogue the CLI already ships, which the gate reads."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.cli.catalogue :as cat]
            [wagoe.cli.main :as main]))

(deftest ^:unit version-command-reports-the-shipped-version
  (let [out (with-out-str (main/-main "version"))]
    (testing "the command answers with the version in the catalogue"
      (is (= (str "wagoe CLI version " (:cli-version (cat/load-catalogue)))
             (str/trim out))))

    (testing "and that version is a release, not a placeholder"
      ;; A catalogue read that silently yielded nil would print
      ;; "wagoe CLI version " and this test would still compare equal.
      (is (re-find #"\d+\.\d+\.\d+" out)))))

(defn- wagoe
  "Run the CLI the way install.sh does, in a scratch directory."
  [dir & args]
  (apply sh/sh "bb" "--classpath"
         (str (.getAbsolutePath (io/file "src")) ":" (.getAbsolutePath (io/file "resources")))
         "-m" "wagoe.cli.main" (concat args [:dir (str dir)])))

(deftest ^:integration every-command-answers-help-with-usage
  ;; `wagoe --help` said "Unknown command" and `wagoe new --help` said the
  ;; project name must be kebab-case (BOU-580).
  (let [dir (doto (io/file (System/getProperty "java.io.tmpdir") (str "wagoe-help-" (System/nanoTime)))
              (.mkdirs))]
    (try
      (doseq [[args usage] [[["--help"] "wagoe new <project-name>"]
                            [["-h"] "wagoe new <project-name>"]
                            [["help"] "wagoe new <project-name>"]
                            [["help" "add"] "Usage: wagoe add <module>"]
                            [["new" "--help"] "Usage: wagoe new <project-name>"]
                            [["new" "-h"] "Usage: wagoe new <project-name>"]
                            [["add" "--help"] "Usage: wagoe add <module>"]
                            [["doctor" "--help"] "wagoe doctor --env prod"]
                            [["list" "--help"] "Usage: wagoe list modules"]
                            [["list" "modules" "--help"] "Usage: wagoe list modules"]
                            [["agents" "--help"] "Usage: wagoe agents update"]
                            [["agents" "update" "--help"] "Usage: wagoe agents update"]
                            [["version" "--help"] "Usage: wagoe version"]]]
        (let [{:keys [exit out err]} (apply wagoe dir args)]
          (is (= 0 exit) (str args " " out err))
          (is (str/includes? out usage) (str args " " out))))
      (is (empty? (.list dir)) "help creates nothing, and no project named --help")
      (finally (doseq [f (reverse (file-seq dir))] (.delete f))))))
