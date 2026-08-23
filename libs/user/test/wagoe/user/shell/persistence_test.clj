(ns wagoe.user.shell.persistence-test
  (:require [wagoe.platform.core.database.query :as core-query]
            [wagoe.platform.database :as db-core]
            [wagoe.platform.ports.database :as protocols]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.persistence :as sut]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]])
  (:import [java.time Instant]
           [java.util UUID]
           [org.postgresql.util PGobject]))

(defn- adapter-stub
  [{:keys [dialect jdbc-driver]}]
  (reify protocols/DBAdapter
    (dialect [_] dialect)
    (jdbc-driver [_] jdbc-driver)
    (jdbc-url [_ _] nil)
    (pool-defaults [_] {})
    (init-connection! [_ _ _] nil)
    (build-where [_ _] nil)
    (boolean->db [_ value] (when (some? value) (if value 1 0)))
    (db->boolean [_ value]
      (cond
        (nil? value) nil
        (number? value) (not (zero? value))
        :else (boolean value)))
    (table-exists? [_ _ _] false)
    (get-table-info [_ _ _] [])))

(deftest ^:contract db->user-entity-normalizes-types-and-preferences
  (let [ctx {:adapter (adapter-stub {:dialect :sqlite
                                     :jdbc-driver "org.sqlite.JDBC"})}
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
      (is (= "token-123" (:session_token result)))
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

(deftest ^:contract schema-upgrade-helpers-add-only-missing-columns
  (testing "auth_users audit columns use sqlite text columns when missing"
    (let [ddl-statements (atom [])
          ctx {:adapter (adapter-stub {:dialect :sqlite
                                       :jdbc-driver "org.sqlite.JDBC"})}]
      (with-redefs [db-core/table-exists? (constantly true)
                    db-core/get-table-info
                    (fn [_ _] [{:name "created_at"}])
                    db-core/execute-ddl!
                    (fn [_ sql] (swap! ddl-statements conj sql))]
        (#'sut/ensure-auth-users-audit-columns! ctx)
        (is (= ["ALTER TABLE auth_users ADD COLUMN updated_at TEXT"
                "ALTER TABLE auth_users ADD COLUMN deleted_at TEXT"]
               @ddl-statements)))))

  (testing "users preference columns use mysql boolean representation when missing"
    (let [ddl-statements (atom [])
          ctx {:adapter (adapter-stub {:dialect :mysql
                                       :jdbc-driver "com.mysql.cj.jdbc.Driver"})}]
      (with-redefs [db-core/table-exists? (constantly true)
                    db-core/get-table-info
                    (fn [_ _] [{:name "notifications_email"} {:name "theme"}])
                    db-core/execute-ddl!
                    (fn [_ sql] (swap! ddl-statements conj sql))]
        (#'sut/ensure-users-preference-columns! ctx)
        (is (= ["ALTER TABLE users ADD COLUMN notifications_push TINYINT(1)"
                "ALTER TABLE users ADD COLUMN notifications_sms TINYINT(1)"
                "ALTER TABLE users ADD COLUMN language VARCHAR(255)"
                "ALTER TABLE users ADD COLUMN timezone VARCHAR(255)"]
               @ddl-statements))))))

(deftest ^:contract business-specific-queries-are-bounded
  (let [ctx {:adapter (adapter-stub {:dialect :sqlite
                                     :jdbc-driver "org.sqlite.JDBC"})}
        repo (sut/->DatabaseUserRepository ctx)
        captured (atom nil)]
    (with-redefs [db-core/execute-query! (fn [_ query]
                                           (reset! captured query)
                                           [])]
      (testing "base arity applies the platform max pagination bound"
        (doseq [[label invoke-query] [["find-active-users-by-role"
                                       #(ports/find-active-users-by-role repo :admin)]
                                      ["find-users-created-since"
                                       #(ports/find-users-created-since
                                         repo (Instant/parse "2026-01-01T00:00:00Z"))]
                                      ["find-users-by-email-domain"
                                       #(ports/find-users-by-email-domain repo "example.com")]]]
          (reset! captured nil)
          (doall (invoke-query))
          (testing label
            (is (= core-query/max-pagination-limit (:limit @captured)))
            (is (= 0 (:offset @captured))))))

      (testing "options arity threads :limit and :offset into the query"
        (doall (ports/find-active-users-by-role repo :admin {:limit 5 :offset 10}))
        (is (= 5 (:limit @captured)))
        (is (= 10 (:offset @captured))))

      (testing "explicit nil :limit/:offset fall back to safe bounds"
        (doall (ports/find-users-by-email-domain repo "example.com"
                                                 {:limit nil :offset nil}))
        (is (= core-query/max-pagination-limit (:limit @captured)))
        (is (= 0 (:offset @captured)))))))

(deftest ^:contract create-user-repository-runs-preference-upgrade
  (let [ctx {:adapter (adapter-stub {:dialect :postgresql
                                     :jdbc-driver "org.postgresql.Driver"})}
        called? (atom false)
        repo (with-redefs [wagoe.user.shell.persistence/ensure-users-preference-columns!
                           (fn [arg]
                             (reset! called? true)
                             (is (= ctx arg)))]
               (sut/create-user-repository ctx))]
    (is @called?)
    (is (= ctx (:ctx repo)))))
