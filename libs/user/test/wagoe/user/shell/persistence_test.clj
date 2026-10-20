(ns wagoe.user.shell.persistence-test
  (:require [wagoe.platform.database :as db-core]
            [wagoe.platform.shell.adapters.database.factory :as factory]
            [wagoe.platform.shell.adapters.database.sqlite.core :as sqlite]
            [wagoe.platform.shell.adapters.database.utils.schema :as schema-utils]
            [wagoe.user.shell.persistence :as sut]
            [support.test-db :as test-db]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]])
  (:import [java.time Instant]
           [java.util UUID]
           [org.postgresql.util PGobject]))

(deftest ^:contract db->user-entity-normalizes-types-and-preferences
  (let [ctx {:adapter (sqlite/new-adapter)}
        user-id (str (UUID/randomUUID))
        tenant-id (str (UUID/randomUUID))
        result (#'sut/db->user-entity
                ctx
                ;; Keys arrive kebab-case from the execution layer builder-fn
                {:id user-id
                 :tenant-id tenant-id
                 :email "jane@example.com"
                 :role "admin"
                 :active 1
                 :created-at "2026-03-25T10:00:00Z"
                 :updated-at "2026-03-25T11:00:00Z"
                 :deleted-at nil
                 :last-login "2026-03-25T12:00:00Z"
                 :notifications-email 1
                 :notifications-push 0
                 :notifications-sms nil
                 :theme "dark"
                 :date-format "dmy"
                 :time-format "24h"
                 :mfa-backup-codes (json/generate-string ["abc" "def"])
                 :mfa-backup-codes-used (json/generate-string ["abc"])})]
    (is (= (UUID/fromString user-id) (:id result)))
    (is (= (UUID/fromString tenant-id) (:tenant-id result)))
    (is (= :admin (:role result)))
    (is (true? (:active result)))
    (is (= (Instant/parse "2026-03-25T10:00:00Z") (:created-at result)))
    (is (true? (:notifications-email result)))
    (is (false? (:notifications-push result)))
    (is (nil? (:notifications-sms result)))
    (is (= :dark (:theme result)))
    (is (= :dmy (:date-format result)))
    (is (= :24h (:time-format result)))
    (is (= ["abc" "def"] (:mfa-backup-codes result)))
    (is (= ["abc"] (:mfa-backup-codes-used result)))))

(deftest ^:contract session-and-audit-transformations-handle-serialized-fields
  (testing "session entities are converted to DB field names"
    (let [session-id (UUID/randomUUID)
          user-id (UUID/randomUUID)
          expires-at (Instant/parse "2026-03-25T15:00:00Z")
          created-at (Instant/parse "2026-03-25T10:00:00Z")
          result (#'sut/session-entity->db
                  {:id session-id
                   :user-id user-id
                   :session-token "token-123"
                   :expires-at expires-at
                   :created-at created-at
                   :remember-me true
                   :device-info {:ignored true}
                   :ip-address "127.0.0.1"})]
      (is (= (str session-id) (:id result)))
      (is (= (str user-id) (:user_id result)))
      (is (= (sut/session-token-hash "token-123") (:session_token result))
          "the hash is stored, never the token (BOU-696)")
      (is (= expires-at (:expires_at result)))
      (is (not (contains? result :remember-me)))
      (is (not (contains? result :device-info)))))

  (testing "audit records parse both string and PGobject JSON payloads"
    (let [audit-id (str (UUID/randomUUID))
          target-id (str (UUID/randomUUID))
          pg-json (doto (PGobject.)
                    (.setType "jsonb")
                    (.setValue "{\"source\":\"pg\"}"))
          result (#'sut/db->audit-log-entity
                  {:id audit-id
                   :action "user-updated"
                   :target_user_id target-id
                   :result "success"
                   :created_at "2026-03-25T10:00:00Z"
                   :changes "{\"field\":\"name\"}"
                   :metadata pg-json})]
      (is (= :user-updated (:action result)))
      (is (= :success (:result result)))
      (is (= {:field "name"} (:changes result)))
      (is (= {:source "pg"} (:metadata result)))))

  (testing "unexpected JSON field types fail with useful ex-data"
    (let [ex (is (thrown? clojure.lang.ExceptionInfo
                          (#'sut/db->audit-log-entity
                           {:id (str (UUID/randomUUID))
                            :action "user-updated"
                            :target_user_id (str (UUID/randomUUID))
                            :result "success"
                            :created_at "2026-03-25T10:00:00Z"
                            :changes 42})))]
      (is (= "Unexpected JSON field type" (ex-message ex)))
      (is (= java.lang.Long (type (:value (ex-data ex))))))))

(defn- column-types [ctx table]
  (into {} (map (juxt :name :type)) (db-core/get-table-info ctx table)))

(defn- columns [ctx table] (set (keys (column-types ctx table))))

(deftest ^:integration an-existing-table-gains-the-missing-columns
  ;; A table from before a field was added to the schema: CREATE TABLE IF NOT
  ;; EXISTS leaves it alone, so the builder adds the column (BOU-622).
  (doseq [[engine open] [[:sqlite #(let [f (java.io.File/createTempFile "user-cols-" ".db")]
                                    (.deleteOnExit f)
                                    (factory/db-context (factory/sqlite-config (str f))))]
                         [:h2 #(factory/db-context
                                (factory/h2-config (str "mem:user_cols_" (System/nanoTime) ";DB_CLOSE_DELAY=-1")))]]]
    (testing (name engine)
      (let [ctx (open)]
        (try
          (db-core/execute-ddl! ctx "CREATE TABLE auth_users (id VARCHAR(36) PRIMARY KEY, email VARCHAR(255))")
          (db-core/execute-ddl! ctx "CREATE TABLE users (id VARCHAR(36) PRIMARY KEY, theme VARCHAR(50))")
          (db-core/execute-ddl! ctx "CREATE TABLE user_sessions (id VARCHAR(36) PRIMARY KEY)")
          (dotimes [_ 2] (sut/initialize-user-schema! ctx))
          (is (every? (columns ctx :auth_users) ["created_at" "updated_at" "deleted_at" "mfa_secret"])
              "created_at too, though required: added nullable")
          (is (every? (columns ctx :users) ["theme" "notifications_email" "notifications_push" "notifications_sms"
                                            "language" "timezone" "date_format"])
              "added once, and a second run changes nothing")
          (testing "an added session_token is unique, as in a fresh table (BOU-632)"
            (is (contains? (columns ctx :user_sessions) "session_token"))
            (db-core/execute-update! ctx {:insert-into :user_sessions :values [{:id (str (UUID/randomUUID)) :session_token "t1"}]})
            (is (thrown? Exception
                         (db-core/execute-update! ctx {:insert-into :user_sessions
                                                       :values [{:id (str (UUID/randomUUID)) :session_token "t1"}]}))))
          (finally (factory/close-db-context! ctx)))))))

(deftest ^:integration an-upgraded-database-has-the-column-types-of-a-fresh-one
  ;; Before BOU-622 the upgrades picked their own types, so an upgraded database
  ;; differed from a fresh one (BOU-610). WAGOE_TEST_DB picks the engine.
  (test-db/with-db "user_fresh"
    (fn [fresh]
      (sut/initialize-user-schema! fresh)
      (test-db/with-db "user_upgraded"
        (fn [upgraded]
          ;; Each table as an older release left it: its first three fields,
          ;; each after the tables it references.
          (doseq [[table schema] (#'schema-utils/creation-order sut/tables)]
            (db-core/execute-ddl! upgraded (schema-utils/generate-table-ddl
                                            upgraded table (into [:map] (take 3 (filter vector? (rest schema)))))))
          (sut/initialize-user-schema! upgraded)
          (doseq [table (keys sut/tables)]
            (is (= (column-types fresh table) (column-types upgraded table)) table)))))))
