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
