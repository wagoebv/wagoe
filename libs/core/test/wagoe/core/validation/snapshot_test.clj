(ns wagoe.core.validation.snapshot-test
  "Tests for pure snapshot capture, comparison, and serialization."
  (:require [wagoe.core.validation.snapshot :as snapshot]
            [clojure.test :refer [deftest is testing]]))

;; Tag all tests for Phase 3
(alter-meta! *ns* assoc :kaocha/tags [:phase3])

;; -----------------------------------------------------------------------------
;; Capture Tests
;; -----------------------------------------------------------------------------

(deftest ^:unit ^:phase3 capture-basic-test
  (testing "Capture creates snapshot with result and metadata"
    (let [result {:status :success :data {:id 123}}
          opts {:schema-version "1.0" :seed 42 :meta {:test "example"}}
          snap (snapshot/capture result opts)]
      (is (= result (:result snap)))
      (is (= "1.0" (get-in snap [:meta :schema-version])))
      (is (= 42 (get-in snap [:meta :seed])))
      (is (= "example" (get-in snap [:meta :test]))))))

(deftest ^:unit ^:phase3 capture-minimal-test
  (testing "Capture works with minimal options"
    (let [result {:status :failure}
          snap (snapshot/capture result {})]
      (is (= result (:result snap)))
      (is (= "1.0" (get-in snap [:meta :schema-version])))
      (is (nil? (get-in snap [:meta :seed]))))))

(deftest ^:unit ^:phase3 capture-with-seed-test
  (testing "Capture includes seed when provided"
    (let [result {:status :success}
          snap (snapshot/capture result {:seed 12345})]
      (is (= 12345 (get-in snap [:meta :seed]))))))

(deftest ^:unit ^:phase3 capture-merges-metadata-test
  (testing "Capture merges additional metadata"
    (let [result {:status :failure}
          opts {:meta {:test-ns 'wagoe.user.validation-test
                       :test-name 'email-required
                       :case-name 'missing}}
          snap (snapshot/capture result opts)]
      (is (= 'wagoe.user.validation-test (get-in snap [:meta :test-ns])))
      (is (= 'email-required (get-in snap [:meta :test-name])))
      (is (= 'missing (get-in snap [:meta :case-name]))))))

;; -----------------------------------------------------------------------------
;; Serialization Tests
;; -----------------------------------------------------------------------------

(deftest ^:unit ^:phase3 stable-serialize-deterministic-test
  (testing "Serialization is deterministic for same data"
    (let [snap1 {:meta {:seed 42 :test "a"} :result {:x 1 :y 2}}
          snap2 {:meta {:test "a" :seed 42} :result {:y 2 :x 1}} ;; Different order
          str1 (snapshot/stable-serialize snap1)
          str2 (snapshot/stable-serialize snap2)]
      (is (= str1 str2) "Same data should produce identical string regardless of order"))))

(deftest ^:unit ^:phase3 stable-serialize-nested-maps-test
  (testing "Serialization handles nested maps"
    (let [snap {:meta {:seed 42}
                :result {:status :failure}
                :errors [{:code :user.email/required}
                         :path [:email]
                         :message "Email is required"]}
          serialized (snapshot/stable-serialize snap)]
      (is (string? serialized))
      (is (pos? (count serialized)))
      ;; Should be valid EDN
      (is (map? (read-string serialized))))))

(deftest ^:unit ^:phase3 stable-serialize-vectors-test
  (testing "Serialization preserves vectors"
    (let [snap {:meta {:seed 42} :result {:errors [{:code :a} {:code :b}]}}
          serialized (snapshot/stable-serialize snap)
          parsed (read-string serialized)]
      (is (vector? (get-in parsed [:result :errors])))
      (is (= 2 (count (get-in parsed [:result :errors])))))))

(deftest ^:unit ^:phase3 stable-serialize-sets-test
  (testing "Serialization handles sets"
    (let [snap {:meta {:seed 42} :result {:codes #{:a :b :c}}}
          serialized (snapshot/stable-serialize snap)
          parsed (read-string serialized)]
      (is (set? (get-in parsed [:result :codes])))
      (is (= #{:a :b :c} (get-in parsed [:result :codes]))))))

(deftest ^:unit ^:phase3 parse-snapshot-roundtrip-test
  (testing "Parse roundtrips with serialize"
    (let [original {:meta {:seed 42 :test "example"}
                    :result {:status :success :data {:id 123}}}
          serialized (snapshot/stable-serialize original)
          parsed (snapshot/parse-snapshot serialized)]
      (is (= original parsed)))))

;; -----------------------------------------------------------------------------
;; Path Computation Tests
;; -----------------------------------------------------------------------------

(deftest ^:unit ^:phase3 path-for-basic-test
  (testing "Path computation for basic test"
    (let [path (snapshot/path-for {:ns 'wagoe.user.validation-test
                                   :test 'email-required})]
      (is (= "test/snapshots/validation/wagoe/user/validation_test/email_required.edn" path)))))

(deftest ^:unit ^:phase3 path-for-with-case-test
  (testing "Path computation with case name"
    (let [path (snapshot/path-for {:ns 'wagoe.user.validation-test
                                   :test 'email-required
                                   :case 'missing})]
      (is (= "test/snapshots/validation/wagoe/user/validation_test/email_required__missing.edn" path)))))

(deftest ^:unit ^:phase3 path-for-custom-base-test
  (testing "Path computation with custom base"
    (let [path (snapshot/path-for {:ns 'wagoe.user.validation-test
                                   :test 'email-format
                                   :base "custom/snapshots"})]
      (is (= "custom/snapshots/wagoe/user/validation_test/email_format.edn" path)))))

(deftest ^:unit ^:phase3 path-for-replaces-hyphens-test
  (testing "Path computation replaces hyphens with underscores"
    (let [path (snapshot/path-for {:ns 'my-module.sub-module.test-ns
                                   :test 'my-test-name
                                   :case 'my-case-name})]
      (is (= "test/snapshots/validation/my_module/sub_module/test_ns/my_test_name__my_case_name.edn" path)))))

(deftest ^:unit ^:phase3 path-for-missing-ns-throws-test
  (testing "Path computation throws on missing :ns"
    (is (thrown? Exception
                 (snapshot/path-for {:test 'email-required})))))

(deftest ^:unit ^:phase3 path-for-missing-test-throws-test
  (testing "Path computation throws on missing :test"
    (is (thrown? Exception
                 (snapshot/path-for {:ns 'wagoe.user.validation-test})))))

;; -----------------------------------------------------------------------------
;; Comparison Tests
;; -----------------------------------------------------------------------------

(deftest ^:unit ^:phase3 compare-equal-snapshots-test
  (testing "Compare returns equal? true for identical snapshots"
    (let [snap1 {:meta {:seed 42} :result {:status :success}}
          snap2 {:meta {:seed 42} :result {:status :success}}
          result (snapshot/compare-snapshots snap1 snap2)]
      (is (true? (:equal? result)))
      (is (= [nil nil snap1] (:diff result))))))

(deftest ^:unit ^:phase3 compare-different-results-test
  (testing "Compare detects differences in results"
    (let [snap1 {:meta {:seed 42} :result {:status :success}}
          snap2 {:meta {:seed 42} :result {:status :failure}}
          result (snapshot/compare-snapshots snap1 snap2)]
      (is (false? (:equal? result)))
      (is (some? (first (:diff result)))) ;; only-in-expected
      (is (some? (second (:diff result))))))) ;; only-in-actual

(deftest ^:unit ^:phase3 compare-different-metadata-test
  (testing "Compare detects differences in metadata"
    (let [snap1 {:meta {:seed 42} :result {:status :success}}
          snap2 {:meta {:seed 99} :result {:status :success}}
          result (snapshot/compare-snapshots snap1 snap2)]
      (is (false? (:equal? result)))
      (is (some? (first (:diff result))))))) ;; only-in-expected

(deftest ^:unit ^:phase3 compare-nested-differences-test
  (testing "Compare detects nested differences"
    (let [snap1 {:meta {:seed 42}
                 :result {:status :failure
                          :errors [{:code :user.email/required :path [:email]}]}}
          snap2 {:meta {:seed 42}
                 :result {:status :failure
                          :errors [{:code :user.name/required :path [:name]}]}}
          result (snapshot/compare-snapshots snap1 snap2)]
      (is (false? (:equal? result)))
      (is (some? (first (:diff result))))))) ;; Different error codes

;; -----------------------------------------------------------------------------
;; Format Diff Tests
;; -----------------------------------------------------------------------------

(deftest ^:unit ^:phase3 format-diff-equal-test
  (testing "Format diff for equal snapshots"
    (let [snap {:meta {:seed 42} :result {:status :success}}
          comparison (snapshot/compare-snapshots snap snap)
          formatted (snapshot/format-diff comparison)]
      (is (= "Snapshots are equal." formatted)))))

(deftest ^:unit ^:phase3 format-diff-with-differences-test
  (testing "Format diff shows differences"
    (let [snap1 {:meta {:seed 42} :result {:status :success}}
          snap2 {:meta {:seed 42} :result {:status :failure}}
          comparison (snapshot/compare-snapshots snap1 snap2)
          formatted (snapshot/format-diff comparison)]
      (is (string? formatted))
      (is (re-find #"Differences found" formatted))
      (is (re-find #"Only in expected" formatted))
      (is (re-find #"Only in actual" formatted)))))

;; -----------------------------------------------------------------------------
;; Edge Cases
;; -----------------------------------------------------------------------------

(deftest ^:unit ^:phase3 capture-empty-result-test
  (testing "Capture handles empty result"
    (let [snap (snapshot/capture {} {})]
      (is (= {} (:result snap)))
      (is (map? (:meta snap))))))

(deftest ^:unit ^:phase3 capture-nil-result-test
  (testing "Capture handles nil result"
    (let [snap (snapshot/capture nil {})]
      (is (nil? (:result snap)))
      (is (map? (:meta snap))))))

(deftest ^:unit ^:phase3 serialize-complex-nested-structure-test
  (testing "Serialization handles deeply nested structures"
    (let [complex {:meta {:seed 42 :nested {:deep {:value 123}}}
                   :result {:errors [{:code :a :nested {:x [{:y 1} {:y 2}]}}]}}
          serialized (snapshot/stable-serialize complex)
          parsed (snapshot/parse-snapshot serialized)]
      (is (= complex parsed)))))

(deftest ^:unit ^:phase3 compare-with-nil-values-test
  (testing "Compare handles nil values in snapshots"
    (let [snap1 {:meta {:seed 42} :result nil}
          snap2 {:meta {:seed 42} :result {:status :success}}
          result (snapshot/compare-snapshots snap1 snap2)]
      (is (false? (:equal? result)))
      (is (some? (first (:diff result))))))) ;; nil vs map difference
