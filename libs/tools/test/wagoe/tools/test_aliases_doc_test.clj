(ns wagoe.tools.test-aliases-doc-test
  "Every `clojure -M:test... :<suite>` command in AGENTS.md must name the
   aliases that suite's tests load, or it fails on ClassNotFoundException
   before a test runs (BOU-536)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- repo-root []
  (let [cwd (System/getProperty "user.dir")]
    (or (first (filter #(.isDirectory (io/file % "libs"))
                       [cwd (str cwd "/../..")]))
        (throw (ex-info "cannot locate repo root (no libs/ dir)" {:cwd cwd})))))

(def ^:private alias-markers
  "What a test source has to contain to need each alias from deps.edn."
  {:test/pg   #"io\.zonky|embedded-pg"
   :test/otel #"io\.opentelemetry\.sdk\.testing"
   :test/http #"clj-http\.lite"})

(defn- suite-test-paths []
  (let [cfg (edn/read-string {:default (fn [_ v] v)}
                             (slurp (io/file (repo-root) "tests.edn")))]
    (into {} (map (juxt :id :test-paths)) (:tests cfg))))

(defn- needed-aliases [test-paths]
  (set (for [dir   test-paths
             f     (file-seq (io/file (repo-root) dir))
             :when (str/ends-with? (str f) ".clj")
             :let  [src (slurp f)]
             [alias re] alias-markers
             :when (re-find re src)]
         alias)))

(defn- documented-commands
  "[aliases suite] for each `clojure -M:test<:alias>* :<suite>` in AGENTS.md."
  []
  (for [[_ aliases suite] (re-seq #"clojure -M:test((?::test/[a-z-]+)*) :([a-z0-9-]+)"
                                  (slurp (io/file (repo-root) "AGENTS.md")))]
    [(set (map keyword (re-seq #"test/[a-z-]+" aliases))) (keyword suite)]))

(deftest ^:unit documented-suite-commands-name-the-aliases-they-need
  (let [paths    (suite-test-paths)
        commands (documented-commands)]
    (is (seq commands))
    (doseq [[aliases suite] commands
            :when (contains? paths suite)
            :let  [missing (remove (conj aliases :test/all)
                                   (needed-aliases (paths suite)))]]
      (testing (str suite)
        (is (or (contains? aliases :test/all) (empty? missing))
            (str "AGENTS.md runs " suite " without " (vec missing)))))))

(deftest ^:unit markers-still-find-the-known-users
  (testing "a marker that stops matching would pass every command"
    (let [paths (suite-test-paths)]
      (is (contains? (needed-aliases (paths :admin)) :test/pg))
      (is (contains? (needed-aliases (paths :observability)) :test/otel))
      (is (contains? (needed-aliases (paths :devtools)) :test/http)))))
