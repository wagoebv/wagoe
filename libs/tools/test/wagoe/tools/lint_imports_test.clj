(ns wagoe.tools.lint-imports-test
  "The import step has one job and one interesting failure.

   The job: copy the clj-kondo configs dependencies export, so `defworkflow`
   and its siblings lint as definitions rather than unresolved symbols.

   The failure: clj-kondo prints `No configs copied because config dir
   (.clj-kondo) does not exist.` and exits 0. A generated project has no
   .clj-kondo/ until something makes one, so that no-op was the default
   outcome — an import that reported success while the next lint kept calling
   the macro unresolved. Both halves are tested here: the directory is created
   before importing, and the marker is a hard error if it ever appears anyway."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [wagoe.tools.lint-imports :as lint-imports]))

(def ^:private copied-output
  "clj-kondo's real output shape, taken from a run against a project depending
   on wagoe-workflow."
  (str "Configs copied:\n"
       "- .clj-kondo/imports/com.github.seancorfield/next.jdbc\n"
       "- .clj-kondo/imports/com.wagoe/wagoe-workflow\n"
       "- .clj-kondo/imports/metosin/malli\n"))

(defn- stub
  "A runner that answers `-Spath` with a classpath and the import with `result`."
  [result]
  (fn [cmd]
    (if (= ["clojure" "-Spath"] cmd)
      {:exit 0 :out "/tmp/a.jar:/tmp/b.jar\n" :err ""}
      result)))

(deftest ^:unit copied-configs-parses-clj-kondos-output
  (testing "every reported import path is returned, sorted"
    (is (= [".clj-kondo/imports/com.github.seancorfield/next.jdbc"
            ".clj-kondo/imports/com.wagoe/wagoe-workflow"
            ".clj-kondo/imports/metosin/malli"]
           (lint-imports/copied-configs copied-output))))
  (testing "output with no copied section yields nothing"
    (is (= [] (lint-imports/copied-configs "linting took 20ms\n")))
    (is (= [] (lint-imports/copied-configs nil)))))

(deftest ^:unit import-cmd-passes-the-classpath-and-both-flags
  (let [cmd (lint-imports/import-cmd "/tmp/a.jar")]
    (testing "the classpath is linted, not the source paths"
      (is (= "/tmp/a.jar" (nth cmd (inc (.indexOf ^java.util.List cmd "--lint"))))))
    (testing "--copy-configs is what makes exports apply at all"
      (is (some #{"--copy-configs"} cmd)))
    (testing "--dependencies keeps every jar's own warnings out of the output"
      (is (some #{"--dependencies"} cmd)))))

(deftest ^:unit the-silent-no-op-is-an-error
  (testing "clj-kondo exits 0 while copying nothing; that must not read as success"
    (let [run (stub {:exit 0
                     :out  (str lint-imports/no-op-marker
                                " because config dir (.clj-kondo) does not exist.")
                     :err  ""})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"copied nothing"
                            (lint-imports/import! run))))))

(deftest ^:unit a-failed-invocation-is-an-error
  (testing "a non-zero clj-kondo exit is reported, not swallowed"
    (let [run (stub {:exit 1 :out "" :err "boom"})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"--copy-configs failed"
                            (lint-imports/import! run)))))
  (testing "an unresolvable classpath is reported before clj-kondo runs"
    (let [run (fn [_] {:exit 1 :out "" :err "could not resolve deps"})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"-Spath failed"
                            (lint-imports/classpath run))))))

(deftest ^:unit a-successful-import-returns-what-was-copied
  (testing "clj-kondo writes the copied list to stderr, not stdout"
    ;; Reading stdout alone reported "No dependency on the classpath exports a
    ;; clj-kondo config" against the real monorepo while seven configs had just
    ;; been copied. The stub therefore puts the list where clj-kondo puts it.
    (let [run (stub {:exit 0
                     :out  ""
                     :err  (str copied-output
                                "[clj-kondo] aero-1.1.6.jar was already linted, skipping\n")})]
      (is (some #(str/includes? % "wagoe-workflow") (lint-imports/import! run)))
      (is (= 3 (count (lint-imports/import! run))))))
  (testing "stdout is still read, so a future clj-kondo that switches streams works"
    (let [run (stub {:exit 0 :out copied-output :err ""})]
      (is (some #(str/includes? % "wagoe-workflow") (lint-imports/import! run))))))

(deftest ^:unit the-config-dir-is-created-when-absent
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "wagoe-lint-imports-" (System/currentTimeMillis)))
        target (io/file dir ".clj-kondo")]
    (try
      (.mkdirs dir)
      (testing "a project without .clj-kondo/ gets one, so the import cannot no-op"
        (is (true? (lint-imports/ensure-config-dir! (.getPath target))))
        (is (.isDirectory target)))
      (testing "an existing directory is left alone"
        (is (false? (lint-imports/ensure-config-dir! (.getPath target)))))
      (finally
        (when (.isDirectory target) (.delete target))
        (when (.isDirectory dir) (.delete dir))))))
