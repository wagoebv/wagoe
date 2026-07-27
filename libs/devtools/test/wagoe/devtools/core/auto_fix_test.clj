(ns wagoe.devtools.core.auto-fix-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.devtools.core.auto-fix :as auto-fix]))

(deftest ^:unit match-fix-known-codes-test
  (testing "BND-301 missing migration → :apply-migration (safe)"
    (let [fix (auto-fix/match-fix {:code "BND-301" :data {}})]
      (is (= :apply-migration (:fix-id fix)))
      (is (= :migrate-up (:action fix)))
      (is (true? (:safe? fix)))))

  (testing "BND-101 missing env var → :set-env-var (safe)"
    (let [fix (auto-fix/match-fix {:code "BND-101" :data {:var-name "DATABASE_URL"}})]
      (is (= :set-env-var (:fix-id fix)))
      (is (true? (:safe? fix)))))

  (testing "BND-103 missing JWT secret → :set-jwt-secret (safe)"
    (let [fix (auto-fix/match-fix {:code "BND-103" :data {}})]
      (is (= :set-jwt-secret (:fix-id fix)))
      (is (true? (:safe? fix)))))

  (testing "BND-601 FC/IS violation → :refactor-fcis (not safe)"
    (let [fix (auto-fix/match-fix {:code "BND-601" :data {}})]
      (is (= :refactor-fcis (:fix-id fix)))
      (is (false? (:safe? fix))))))

(deftest ^:unit match-fix-unknown-code-test
  (testing "unknown error code returns nil"
    (is (nil? (auto-fix/match-fix {:code "BND-999" :data {}}))))

  (testing "nil code returns nil"
    (is (nil? (auto-fix/match-fix {:code nil :data {}})))))

(deftest ^:unit fix-descriptor-shape-test
  (testing "all fix descriptors have required keys"
    (doseq [code ["BND-301" "BND-101" "BND-103" "BND-601"]]
      (let [fix (auto-fix/match-fix {:code code :data {}})]
        (is (contains? fix :fix-id) (str "missing :fix-id for " code))
        (is (contains? fix :action) (str "missing :action for " code))
        (is (contains? fix :safe?) (str "missing :safe? for " code))
        (is (contains? fix :label) (str "missing :label for " code))))))
