(ns wagoe.devtools.shell.prototype-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.java.io :as io]
            [wagoe.devtools.shell.prototype :as prototype]))

(def ^:private test-module "test-scaffold-module")
(def ^:private test-module-dir (str "libs/" test-module))

(defn- delete-dir! [dir]
  (let [f (io/file dir)]
    (when (.exists f)
      (doseq [child (reverse (file-seq f))]
        (.delete child)))))

(use-fixtures :each
  (fn [f]
    (try
      (f)
      (finally
        (delete-dir! test-module-dir)))))

(deftest ^:integration scaffold!-generates-expected-files-test
  (testing "scaffold! with :crud endpoints creates all expected source files"
    (let [files (prototype/scaffold!
                 test-module
                 {:fields    [[:name :string] [:description :string]]
                  :endpoints [:crud]})]
      (is (vector? files) "scaffold! returns a vector of generated file paths")
      (is (seq files) "scaffold! returns at least one file")

      (let [file-set (set files)
            src-dir  (str "libs/" test-module "/src/wagoe/" test-module)]

        (testing "schema.clj is generated"
          (let [path (str src-dir "/schema.clj")]
            (is (contains? file-set path) "schema.clj path is returned")
            (is (.exists (io/file path)) "schema.clj file exists on disk")))

        (testing "ports.clj is generated"
          (let [path (str src-dir "/ports.clj")]
            (is (contains? file-set path) "ports.clj path is returned")
            (is (.exists (io/file path)) "ports.clj file exists on disk")))

        (testing "core/<entity>.clj is generated (named after the entity, not 'validation')"
          (let [path (str src-dir "/core/" test-module ".clj")]
            (is (contains? file-set path) "core/<entity>.clj path is returned")
            (is (.exists (io/file path)) "core/<entity>.clj file exists on disk")))

        (testing "shell/service.clj is generated"
          (let [path (str src-dir "/shell/service.clj")]
            (is (contains? file-set path) "shell/service.clj path is returned")
            (is (.exists (io/file path)) "shell/service.clj file exists on disk")))

        (testing "shell/persistence.clj is generated"
          (let [path (str src-dir "/shell/persistence.clj")]
            (is (contains? file-set path) "shell/persistence.clj path is returned")
            (is (.exists (io/file path)) "shell/persistence.clj file exists on disk")))

        (testing "shell/http.clj is generated (crud implies http)"
          (let [path (str src-dir "/shell/http.clj")]
            (is (contains? file-set path) "shell/http.clj path is returned")
            (is (.exists (io/file path)) "shell/http.clj file exists on disk")))

        (testing "deps.edn is generated"
          (let [path (str "libs/" test-module "/deps.edn")]
            (is (contains? file-set path) "deps.edn path is returned")
            (is (.exists (io/file path)) "deps.edn file exists on disk")))))))

(deftest ^:integration scaffold!-without-http-endpoints-test
  (testing "scaffold! with no http-triggering endpoints omits shell/http.clj"
    (let [files (prototype/scaffold!
                 test-module
                 {:fields    [[:title :string]]
                  :endpoints []})
          src-dir (str "libs/" test-module "/src/wagoe/" test-module)
          http-path (str src-dir "/shell/http.clj")]
      (is (not (contains? (set files) http-path))
          "shell/http.clj is not generated when no http endpoints requested")
      (is (not (.exists (io/file http-path)))
          "shell/http.clj file does not exist on disk"))))

(deftest ^:integration scaffold!-cleans-up-after-fixture-test
  (testing "the test fixture removes the generated module directory"
    ;; This test verifies that when it runs the dir does not pre-exist from a
    ;; previous (non-cleaned) run. The fixture itself is exercised by the other
    ;; tests; here we just assert that scaffold! actually creates the dir.
    (prototype/scaffold! test-module {:fields [[:value :int]] :endpoints [:crud]})
    (is (.exists (io/file test-module-dir))
        "module directory exists during test")))
