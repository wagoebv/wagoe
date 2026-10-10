(ns wagoe.user.shell.audit-email-filter-test
  "The audit log's email filters ignore case on SQLite too. They emitted ILIKE,
   which SQLite does not have, so filtering failed there (ADR-039)."
  (:require [clojure.test :refer [deftest is]]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.user.shell.persistence :as persistence])
  (:import [java.io File]
           [java.util UUID]))

(defn- entry [target actor]
  {:action :create :actor-id (UUID/randomUUID) :actor-email actor
   :target-user-id (UUID/randomUUID) :target-user-email target
   :changes {:created true} :metadata {} :result :success})

(deftest ^:integration audit-email-filters-ignore-case-on-sqlite
  (let [file (File/createTempFile "audit-email" ".db")
        ctx  (db-factory/db-context {:adapter :sqlite :database-path (.getPath file)})]
    (try
      (persistence/initialize-user-schema! ctx)
      (let [repo   (persistence/create-audit-repository ctx {:default-limit 20})
            emails (fn [opts] (set (map :target-user-email (:audit-logs (.find-audit-logs repo opts)))))]
        (.create-audit-log repo (entry "Jane@Example.com" "Admin@Example.com"))
        (.create-audit-log repo (entry "bob@example.com" "root@example.com"))
        (is (= #{"Jane@Example.com"} (emails {:filter-target-email "jane"})))
        (is (= #{"Jane@Example.com"} (emails {:filter-actor-email "ADMIN"}))))
      (finally
        (db-factory/close-db-context! ctx)
        (.delete file)))))
