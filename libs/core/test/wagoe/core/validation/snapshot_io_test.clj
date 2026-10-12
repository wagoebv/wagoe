(ns wagoe.core.validation.snapshot-io-test
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [wagoe.core.validation.snapshot-io :as sut]))

(defn- reported
  "What `f` reports to clojure.test, captured instead of counted."
  [f]
  (let [reports (atom [])]
    (binding [t/report #(swap! reports conj %)]
      (f))
    @reports))

(deftest ^:unit a-missing-baseline-fails-and-names-its-path
  ;; It used to be written and pass, so a test whose path drifted compared
  ;; against nothing (BOU-619).
  (let [[r] (reported #(sut/check-snapshot! {:any "result"} {:test 'never-recorded}))]
    (is (= :fail (:type r)))
    (testing "the path comes from the test that is running, not *ns*"
      (is (str/includes? (str (:message r))
                         "wagoe/core/validation/snapshot_io_test/never_recorded.edn")
          (:message r)))))
