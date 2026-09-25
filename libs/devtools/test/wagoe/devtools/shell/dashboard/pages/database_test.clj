(ns wagoe.devtools.shell.dashboard.pages.database-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.devtools.shell.dashboard.pages.database :as database]))

(deftest ^:unit merge-migration-status-test
  (testing "a file whose id the database has applied is applied"
    (is (= [{:name "20260101000000-create-users" :status :applied}]
           (database/merge-migration-status
            #{"20260101000000"}
            ["20260101000000-create-users.up.sql"]))))

  (testing "a file the database has not applied is pending"
    (is (= [{:name "20260101000000-create-users" :status :pending}]
           (database/merge-migration-status
            #{}
            ["20260101000000-create-users.up.sql"]))))

  (testing "an unreadable database is :unknown, not :pending"
    ;; nil means the query failed; treating that as \"not applied\" would state
    ;; something the dashboard does not know.
    (is (= [{:name "20260101000000-create-users" :status :unknown}]
           (database/merge-migration-status
            nil
            ["20260101000000-create-users.up.sql"]))))

  (testing "applied migrations with no discoverable file are still listed"
    ;; BOU-507: file discovery only looks under resources/ and the classpath, so
    ;; a project keeping migrations at the project root found nothing and the
    ;; panel reported "0 applied" while schema_migrations held nine rows.
    (let [rows (database/merge-migration-status
                #{"20260324010000" "20260524000000" "20260923040608"}
                [])]
      (is (= 3 (count rows)))
      (is (every? #(= :applied (:status %)) rows))
      (is (= ["20260324010000" "20260524000000" "20260923040608"]
             (mapv :name rows))
          "listed in id order")))

  (testing "files and database-only entries are not double counted"
    (let [rows (database/merge-migration-status
                #{"20260101000000" "20260202000000"}
                ["20260101000000-create-users.up.sql"])]
      (is (= 2 (count rows)))
      (is (= #{"20260101000000-create-users" "20260202000000"}
             (set (mapv :name rows))))
      (is (every? #(= :applied (:status %)) rows))))

  (testing "no files and nothing applied yields no rows"
    (is (= [] (database/merge-migration-status #{} [])))))
