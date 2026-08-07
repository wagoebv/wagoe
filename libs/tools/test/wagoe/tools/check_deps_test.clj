(ns wagoe.tools.check-deps-test
  "BOU-273: a library could require a third-party namespace without declaring
   the artifact that provides it, and both dependency gates stayed green.

   `check:poms` verifies inter-Wagoe deps only (BOU-202), and the dependency
   graph covers wagoe -> wagoe. Third-party requires were checked by nothing.
   That is how wagoe-ai shipped a POM without tools.cli and every `bb ai`
   subcommand died in a generated project (BOU-272), and how platform and user
   came to resolve tools.cli only through migratus — at 1.0.219, while the
   monorepo pinned 1.4.256."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [wagoe.tools.check-deps :as sut]))

(defn- temp-lib
  "Write a throwaway library dir with the given deps.edn text and one source
   file requiring `required-ns`."
  [deps-text required-ns]
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "wagoe-check-deps-test"
                      (make-array java.nio.file.attribute.FileAttribute 0)))
        src (io/file dir "src" "wagoe" "probe")]
    (.mkdirs src)
    (spit (io/file dir "deps.edn") deps-text)
    (spit (io/file src "core.clj")
          (str "(ns wagoe.probe.core\n  (:require [" required-ns " :as x]))\n"))
    dir))

(defn- delete-tree! [dir]
  (doseq [f (reverse (file-seq dir))] (.delete f)))

(deftest ^:unit third-party-gaps-finds-undeclared-artifacts
  (testing "a required namespace with no declaring artifact is a gap"
    (let [dir (temp-lib "{:deps {org.clojure/clojure {:mvn/version \"1.12.5\"}}}"
                        "clojure.tools.cli")]
      (try
        (let [gaps (sut/third-party-gaps [["probe" dir]])]
          (is (= [["probe" "org.clojure/tools.cli"]]
                 (map (juxt :lib (comp str :artifact)) gaps))
              "this is the shape that shipped a broken wagoe-ai POM"))
        (finally (delete-tree! dir)))))

  (testing "declaring it closes the gap"
    (let [dir (temp-lib (str "{:deps {org.clojure/clojure {:mvn/version \"1.12.5\"}\n"
                             "        org.clojure/tools.cli {:mvn/version \"1.4.256\"}}}")
                        "clojure.tools.cli")]
      (try
        (is (empty? (sut/third-party-gaps [["probe" dir]])))
        (finally (delete-tree! dir)))))

  (testing "a transitive does not count as declared"
    ;; platform resolved tools.cli through migratus and worked — until migratus
    ;; drops it, and meanwhile at migratus's version rather than the pin.
    (let [dir (temp-lib (str "{:deps {org.clojure/clojure {:mvn/version \"1.12.5\"}\n"
                             "        migratus/migratus {:mvn/version \"1.6.6\"}}}")
                        "clojure.tools.cli")]
      (try
        (is (seq (sut/third-party-gaps [["probe" dir]]))
            "resolving by luck is not the same as declaring")
        (finally (delete-tree! dir)))))

  (testing "unmapped namespaces are not guessed at"
    ;; The prefix map is the gate's coverage. Something outside it is ignored
    ;; rather than reported against an artifact name invented here.
    (let [dir (temp-lib "{:deps {org.clojure/clojure {:mvn/version \"1.12.5\"}}}"
                        "some.unknown.library")]
      (try
        (is (empty? (sut/third-party-gaps [["probe" dir]])))
        (finally (delete-tree! dir))))))

(deftest ^:unit an-unreadable-deps-edn-is-reported-not-swallowed
  ;; The first version of this gate read deps.edn inside a lazy `for`, so a
  ;; malformed file threw where nothing realised it and the checker printed
  ;; "0 violations" and exited 0. Then it threw for real, and babashka printed
  ;; the root cause — "EOF while reading" — naming neither the file nor the
  ;; library.
  (testing "a malformed deps.edn is its own violation"
    (let [dir (temp-lib "{:deps {broken" "clojure.tools.cli")]
      (try
        (is (= [:unreadable] [(sut/declared-artifacts dir)]))
        (is (= [["probe" (.getPath (io/file dir "deps.edn"))]]
               (map (juxt :lib :file) (sut/unreadable-deps-files [["probe" dir]])))
            "the message has to name the file, which the exception did not")
        (finally (delete-tree! dir)))))

  (testing "and it does not masquerade as a wall of missing declarations"
    (let [dir (temp-lib "{:deps {broken" "clojure.tools.cli")]
      (try
        (is (empty? (sut/third-party-gaps [["probe" dir]]))
            "treating unreadable as no-deps would blame every require in the lib")
        (finally (delete-tree! dir)))))

  (testing "a readable file is unaffected"
    (let [dir (temp-lib "{:deps {org.clojure/clojure {:mvn/version \"1.12.5\"}}}"
                        "clojure.tools.cli")]
      (try
        (is (set? (sut/declared-artifacts dir)))
        (is (empty? (sut/unreadable-deps-files [["probe" dir]])))
        (finally (delete-tree! dir))))))

(deftest ^:unit the-repository-declares-what-it-requires
  ;; The regression guard for BOU-273 itself: platform and user require
  ;; clojure.tools.cli for `bb migrate` and `bb create-admin`.
  (testing "platform and user declare tools.cli"
    (doseq [lib ["platform" "user"]]
      (let [deps (slurp (str "libs/" lib "/deps.edn"))]
        (is (str/includes? deps "org.clojure/tools.cli")
            (str lib " requires clojure.tools.cli, so its POM must carry it"))))))
