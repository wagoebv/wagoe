(ns wagoe.mcp.core.verify-test
  (:require [wagoe.mcp.core.verify :as verify]
            [clojure.test :refer [deftest is testing]]))

(deftest ^:unit clean-run-passes
  (let [r (verify/build-report
           {:generate {:success true :files [{:path "src/boundary/foo/core/foo.clj" :action :create}]}
            :kondo    {:findings []}
            :fcis     {:violations []}
            :tests    {:status :passed :passed 7 :failed 0}})]
    (is (= :pass (:status r)))
    (is (empty? (:issues r)))
    (is (= {:errors 0 :warnings 0} (:counts r)))
    (is (= {:generate :ok :kondo :ok :fcis :ok :tests :passed} (:steps r)))
    (is (verify/passed? r))))

(deftest ^:unit kondo-error-fails-and-is-not-overridable
  (let [steps {:kondo {:findings [{:filename "a.clj" :row 3 :col 5 :level :error
                                   :type :unresolved-symbol :message "Unresolved symbol: foo"}]}}
        r     (verify/build-report steps)
        r-ovr (verify/build-report steps {:overridden? true})]
    (is (= :fail (:status r)))
    (is (= 1 (count (:issues r))))
    (is (= :error (:severity (first (:issues r)))))
    (is (nil? (:overridable? r)) "kondo errors are hard — never overridable")
    (is (= :fail (:status r-ovr)) "override cannot rescue a hard failure")
    (is (not (verify/passed? r)))))

(deftest ^:unit kondo-warning-only-passes
  (let [r (verify/build-report
           {:kondo {:findings [{:filename "a.clj" :row 1 :col 1 :level :warning
                                :type :unused-binding :message "unused binding x"}]}})]
    (is (= :pass (:status r)))
    (is (= {:errors 0 :warnings 1} (:counts r)))
    (is (= :ok (get-in r [:steps :kondo])))))

(deftest ^:unit fcis-violation-is-soft-and-overridable
  (let [steps {:fcis {:violations [{:file "src/boundary/foo/core/foo.clj"
                                    :ns "wagoe.foo.core.foo" :req "wagoe.foo.shell.db"
                                    :kind :require}]}}
        r     (verify/build-report steps)
        r-ovr (verify/build-report steps {:overridden? true})]
    (testing "blocks by default, flagged overridable, tagged BND-806"
      (is (= :fail (:status r)))
      (is (true? (:overridable? r)))
      (is (= verify/fcis-code (:code (first (:issues r))))))
    (testing "audited override turns it into :overridden"
      (is (= :overridden (:status r-ovr)))
      (is (verify/passed? r-ovr)))))

(deftest ^:unit hard-and-soft-mix-is-not-overridable
  ;; A soft FC/IS violation plus a hard kondo error: the run cannot be
  ;; overridden, because not every blocking issue is soft.
  (let [r (verify/build-report
           {:kondo {:findings [{:filename "a.clj" :row 1 :col 1 :level :error
                                :type :syntax :message "boom"}]}
            :fcis  {:violations [{:file "a.clj" :ns "n.core" :req "n.shell" :kind :require}]}}
           {:overridden? true})]
    (is (= :fail (:status r)))
    (is (= 2 (get-in r [:counts :errors])))))

(deftest ^:unit failing-tests-surface-expected-actual
  (let [r (verify/build-report
           {:tests {:status :failed
                    :failures [{:ns "wagoe.foo.core.foo-test" :var "calc-test"
                                :file "test/boundary/foo/core/foo_test.clj" :line 12
                                :message "calc wrong" :expected 10 :actual 7}]}})]
    (is (= :fail (:status r)))
    (let [issue (first (:issues r))]
      (is (= :tests (:step issue)))
      (is (= 10 (:expected issue)))
      (is (= 7 (:actual issue)))
      (is (= "test/boundary/foo/core/foo_test.clj" (:file issue))))))

(deftest ^:unit generation-error-fails
  (let [r (verify/build-report {:generate {:success false :errors ["Invalid request"]}})]
    (is (= :fail (:status r)))
    (is (= :error (get-in r [:steps :generate])))
    (is (= :generation (:kind (first (:issues r)))))))

(deftest ^:unit unavailable-tests-do-not-block-but-are-incomplete
  (let [r (verify/build-report {:tests {:status :unavailable :note "not wired"}})]
    (is (= :pass (:status r)))
    (is (false? (:complete? r)) "tests did not run → not fully verified")
    (is (= :unavailable (get-in r [:steps :tests])))
    (is (zero? (get-in r [:counts :warnings])) ":unavailable is expected — no warning issue")))

(deftest ^:unit completeness-tracks-whether-tests-ran
  (testing "tests ran (passed) → complete"
    (is (true? (:complete? (verify/build-report {:tests {:status :passed :passed 3}})))))
  (testing "tests ran (failed) → complete"
    (is (true? (:complete? (verify/build-report {:tests {:status :failed :failures []}})))))
  (testing "runner errored → incomplete + a verify-incomplete warning (never blocks)"
    (let [r (verify/build-report {:tests {:status :error :note "module not wired into tests.edn"}})]
      (is (= :pass (:status r)))
      (is (false? (:complete? r)))
      (is (some #(= :verify-incomplete (:kind %)) (:issues r)))
      (is (= 1 (get-in r [:counts :warnings])))))
  (testing "no tests step at all → complete (loop did not include tests)"
    (is (true? (:complete? (verify/build-report {:kondo {:findings []}}))))))
