(ns wagoe.mcp.shell.verify-test
  (:require [wagoe.mcp.core.verify :as core-verify]
            [wagoe.mcp.shell.verify :as verify]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import (java.io File)))

(def ^:dynamic *tmp* nil)

;; Files live under src/wagoe/tmp/core/ so clj-kondo's path→namespace
;; inference matches the (ns wagoe.tmp.core.*) form (no spurious
;; namespace-name-mismatch error).
(def ^:private core-dir-segments ["src" "wagoe" "tmp" "core"])

(use-fixtures :each
  (fn [t]
    (let [dir (File/createTempFile "mcp-verify" "")]
      (.delete dir)
      (.mkdirs (apply io/file dir core-dir-segments))
      (binding [*tmp* dir]
        (try (t) (finally (run! #(.delete %) (reverse (file-seq dir)))))))))

(defn- core-file [name content]
  (let [f (apply io/file *tmp* (conj core-dir-segments name))]
    (spit f content)
    {:path (.getPath f) :action :create}))

(def ^:private passing-runner (fn [_module] {:status :passed :passed 1 :failed 0}))

(deftest ^:unit clean-module-passes
  (let [file   (core-file "good.clj" "(ns wagoe.tmp.core.good)\n(defn add [a b] (+ a b))\n")
        report (verify/verify-generated
                {:test-runner passing-runner}
                {:success true :module "tmp" :files [file]})]
    (is (= :pass (:status report)))
    (is (= :passed (get-in report [:steps :tests])))))

(deftest ^:unit fcis-violation-blocks-but-is-overridable
  ;; A core namespace requiring clojure.java.io is an FC/IS violation (BND-806).
  (let [file (core-file "bad.clj"
                        "(ns wagoe.tmp.core.bad (:require [clojure.java.io :as io]))\n(defn f [] :ok)\n")
        deps {:test-runner passing-runner}
        r    (verify/verify-generated deps {:success true :module "tmp" :files [file]})
        ovr  (verify/verify-generated deps {:success true :module "tmp" :files [file]}
                                      {:overridden? true})]
    (testing "blocks by default with BND-806"
      (is (= :fail (:status r)))
      (is (true? (:overridable? r)))
      (is (some #(= core-verify/fcis-code (:code %)) (:issues r))))
    (testing "audited override proceeds"
      (is (= :overridden (:status ovr))))))

(deftest ^:unit failing-tests-block
  (let [file   (core-file "good.clj" "(ns wagoe.tmp.core.good)\n(defn add [a b] (+ a b))\n")
        report (verify/verify-generated
                {:test-runner (fn [_m] {:status :failed :passed 0 :failed 1
                                        :failures [{:ns "wagoe.tmp.core.good-test" :var "add-test"
                                                    :file "t.clj" :line 4 :message "nope"}]})}
                {:success true :module "tmp" :files [file]})]
    (is (= :fail (:status report)))
    (is (some #(= :test-failure (:kind %)) (:issues report)))))

(deftest ^:unit absent-test-runner-is-unavailable-not-silent-pass
  (let [file   (core-file "good.clj" "(ns wagoe.tmp.core.good)\n(defn add [a b] (+ a b))\n")
        report (verify/verify-generated {} {:success true :module "tmp" :files [file]})]
    (is (= :pass (:status report)))
    (is (= :unavailable (get-in report [:steps :tests])))))

(defn- test-file
  "A generated unit test, where the scaffolder actually writes one:
   test/<base>/<module>/core/<name>."
  [name content]
  (let [f (apply io/file *tmp* ["test" "wagoe" "tmp" "core" name])]
    (.mkdirs (.getParentFile f))
    (spit f content)
    {:path (.getPath f) :action :create}))

(deftest ^:unit a-generated-test-namespace-is-not-a-core-namespace
  ;; The FC/IS step matched any path containing "/core/", so the module's own
  ;; generated tests were checked as core code and BND-806 refused them for
  ;; requiring clojure.test. scaffold-module then reported status "fail" on a
  ;; correct generation — the verify loop failing a file the same call had just
  ;; written (BOU-515).
  (let [src  (core-file "thing.clj" "(ns wagoe.tmp.core.thing)\n(defn add [a b] (+ a b))\n")
        tst  (test-file "thing_test.clj"
                        (str "(ns wagoe.tmp.core.thing-test\n"
                             "  (:require [clojure.test :refer [deftest is]]\n"
                             "            [wagoe.tmp.core.thing :as sut]))\n"
                             "(deftest ^:unit add-test (is (= 3 (sut/add 1 2))))\n"))
        report (verify/verify-generated
                {:test-runner passing-runner}
                {:success true :module "tmp" :files [src tst]})]
    (is (= :pass (:status report))
        "a module and its generated tests verify clean")
    (is (empty? (filter #(= :fcis (:step %)) (:issues report)))
        "no FC/IS issue is raised against the test namespace")))

(deftest ^:unit test-path-is-judged-from-the-project-root
  ;; test-path? matched "/test/" anywhere in the path. The MCP tools pass
  ;; absolute, canonical paths, so a project that merely LIVES under a
  ;; directory named test — ~/test/myapp, /tmp/test/... — had every core file
  ;; classified as a test and skipped, and FC/IS reported a pass without
  ;; checking anything.
  (let [root "/work/test/acme"]
    (testing "a source file of a project under a directory named test is source"
      (is (not (verify/test-path? root "/work/test/acme/src/acme/order/core/order.clj"))))
    (testing "the project's own test root is a test path"
      (is (verify/test-path? root "/work/test/acme/test/acme/order/core/order_test.clj")))
    (testing "a module that happens to be named test is still source"
      (is (not (verify/test-path? root "/work/test/acme/src/acme/test/core/x.clj"))))
    (testing "monorepo layout: libs/<lib>/test is a test root, libs/<lib>/src is not"
      (is (verify/test-path? root "/work/test/acme/libs/foo/test/wagoe/foo/core/x_test.clj"))
      (is (not (verify/test-path? root "/work/test/acme/libs/foo/src/wagoe/foo/core/x.clj"))))
    (testing "relative paths"
      (is (verify/test-path? root "test/acme/order/core/order_test.clj"))
      (is (not (verify/test-path? root "src/acme/order/core/order.clj"))))))

(deftest ^:unit a-violation-under-a-directory-named-test-is-still-caught
  ;; End to end: the silent pass this guards against. A core namespace that
  ;; requires clojure.test is a BND-806 violation wherever the project lives.
  (let [f (apply io/file *tmp* ["test" "proj" "src" "wagoe" "tmp" "core" "bad.clj"])]
    (.mkdirs (.getParentFile f))
    (spit f (str "(ns wagoe.tmp.core.bad\n"
                 "  (:require [clojure.test :refer [is]]))\n"
                 "(defn f [] (is true))\n"))
    (let [report (verify/verify-generated
                  {:test-runner passing-runner}
                  {:success true :module "tmp" :files [{:path (.getPath f) :action :create}]})]
      (is (seq (filter #(= :fcis (:step %)) (:issues report)))
          "FC/IS must check the file, not skip it because of where the project lives")
      (is (not= :pass (:status report))))))
