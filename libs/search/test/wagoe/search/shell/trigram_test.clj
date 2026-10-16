(ns wagoe.search.shell.trigram-test
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [wagoe.platform.database :as db]
            [wagoe.search.shell.trigram :as sut]))

(deftest ^:unit a-refused-create-extension-does-not-fail-the-migration
  (testing "a user without the privilege gets a warning, not a failed migration (BOU-661)"
    (with-redefs [db/engine-of  (constantly :postgresql)
                  jdbc/execute! (fn [& _] (throw (java.sql.SQLException. "permission denied to create extension")))]
      (is (nil? (sut/up {:db {:datasource :unused}}))))))

(deftest ^:unit an-index-pg-trgm-cannot-serve-does-not-fail-the-migration
  (testing "gin_trgm_ops not on the search_path gets a warning (BOU-676)"
    (with-redefs [db/engine-of   (constantly :postgresql)
                  sut/installed? (constantly true)
                  jdbc/execute!  (fn [& _] (throw (java.sql.SQLException. "operator class \"gin_trgm_ops\" does not exist")))]
      (is (nil? (sut/index-up {:db {:datasource :unused}}))))))

(deftest ^:unit no-index-without-pg-trgm
  (let [ran (atom [])]
    (with-redefs [db/engine-of   (constantly :postgresql)
                  sut/installed? (constantly false)
                  jdbc/execute!  (fn [_ sql] (swap! ran conj sql))]
      (sut/index-up {:db {:datasource :unused}})
      (is (empty? @ran)))))

(deftest ^:unit the-migration-touches-only-postgresql
  (let [ran (atom [])]
    (with-redefs [db/engine-of  (constantly :mysql)
                  jdbc/execute! (fn [_ sql] (swap! ran conj sql))]
      (sut/up {:db {:datasource :unused}})
      (is (empty? @ran)))))
