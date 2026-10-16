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

(deftest ^:unit the-migration-touches-only-postgresql
  (let [ran (atom [])]
    (with-redefs [db/engine-of  (constantly :mysql)
                  jdbc/execute! (fn [_ sql] (swap! ran conj sql))]
      (sut/up {:db {:datasource :unused}})
      (is (empty? @ran)))))
