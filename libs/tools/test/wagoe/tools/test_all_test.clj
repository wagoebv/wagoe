(ns wagoe.tools.test-all-test
  "Guards the surface registry itself.

   `bb test:all` is only worth trusting if its list of surfaces keeps up with the
   repo. A standalone library added later, with its own deps.edn `:test` alias
   and no kaocha suite in tests.edn, would otherwise be invisible to both
   `clojure -M:test:db/h2` AND `bb test:all` — the same silent gap the task was
   created to close, one level up (BOU-246 / BOU-250)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [wagoe.tools.test-all :as test-all]))

(defn- repo-root
  "bb test:tools runs from the repo root; a standalone run inside libs/tools does
   not — try both, then fail rather than silently skipping."
  []
  (let [cwd (System/getProperty "user.dir")]
    (or (first (filter #(.isDirectory (io/file % "libs"))
                       [cwd (str cwd "/../..")]))
        (throw (ex-info "cannot locate repo root (no libs/ dir)" {:cwd cwd})))))

(defn- lib-dirs []
  (->> (.listFiles (io/file (repo-root) "libs"))
       (filter #(.isDirectory ^java.io.File %))
       (map #(.getName ^java.io.File %))
       sort))

(defn- has-own-test-alias?
  "True when libs/<lib>/deps.edn declares a :test alias — i.e. the lib can be
   tested standalone, independently of the app classpath."
  [lib]
  (let [f (io/file (repo-root) "libs" lib "deps.edn")]
    (and (.exists f) (str/includes? (slurp f) ":test"))))

(defn- tests-edn-suites []
  (let [f (io/file (repo-root) "tests.edn")]
    (->> (re-seq #":id\s+:([a-z0-9-]+)" (slurp f))
         (map second)
         set)))

(deftest ^:unit surfaces-are-well-formed
  (testing "every surface has an id, label, dir and command"
    (is (seq test-all/surfaces))
    (doseq [{:keys [id label dir cmd]} test-all/surfaces]
      (is (keyword? id))
      (is (string? label))
      (is (string? dir))
      (is (vector? cmd) (str id " :cmd must be a vector"))
      (is (seq cmd)))))

(deftest ^:unit surface-directories-exist
  (testing "no surface points at a directory that is not there"
    (doseq [{:keys [id dir]} test-all/surfaces]
      (is (.isDirectory (io/file (repo-root) dir))
          (str "surface " id " points at a missing dir: " dir)))))

(deftest ^:unit every-standalone-lib-is-covered-or-explicitly-excluded
  (testing "a lib with its own :test alias and no tests.edn suite must be a surface"
    ;; This is the actual guard. Such a lib is invisible to the main suite, so if
    ;; it is not a surface here it is tested nowhere.
    (let [suites    (tests-edn-suites)
          covered   (->> test-all/surfaces
                         (map :dir)
                         (map #(last (str/split % #"/")))
                         set)
          excluded  (->> test-all/excluded (map :id) (map name) set)
          orphaned  (for [lib  (lib-dirs)
                          :when (and (has-own-test-alias? lib)
                                     (not (contains? suites lib))
                                     (not (contains? covered lib))
                                     (not (contains? excluded lib)))]
                      lib)]
      (is (empty? orphaned)
          (str "these libs are tested by neither tests.edn nor bb test:all: "
               (str/join ", " orphaned)
               " — add a surface in wagoe.tools.test-all, or an entry in its"
               " `excluded` list saying why not")))))

(deftest ^:unit exclusions-carry-a-reason
  (testing "anything deliberately skipped says why, so it is a decision not an oversight"
    (doseq [{:keys [id label reason]} test-all/excluded]
      (is (keyword? id))
      (is (string? label))
      (is (and (string? reason) (seq reason))
          (str "excluded surface " id " needs a :reason")))))
