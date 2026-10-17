(ns wagoe.table-column-length-test
  "A table's columns hold what its schema lets a field hold (BOU-715). MFA
   backup codes, a serialised vector, were a VARCHAR(255) that ten hashed codes
   overflow, so enabling MFA failed on PostgreSQL, MySQL and H2. Boot and
   `migrate up` widen a database created before, its rows kept.

   Needs MySQL: `bb test:services up`."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging.test :as log-test]
            [support.embedded-pg :as epg]
            [support.mysql :as mysql]
            [wagoe.platform.database :as db]
            [wagoe.platform.ports.database :as protocols]
            [wagoe.platform.shell.adapters.database.factory :as factory]
            [wagoe.platform.shell.adapters.database.h2.core :as h2]
            [wagoe.platform.shell.adapters.database.mysql.core :as mysql-adapter]
            [wagoe.platform.shell.adapters.database.postgresql.core :as postgresql]
            [wagoe.platform.shell.adapters.database.sqlite.core :as sqlite]
            [wagoe.platform.shell.adapters.database.utils.schema :as schema]
            [wagoe.tenant.shell.tenant-tables :as tenant-tables]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.persistence :as persistence]))

(def ^:private collection-types #{:vector :sequential :set :tuple :map-of :map :any})

(def ^:private json-types
  "Collections the builder always stored as JSON, so never as VARCHAR(255)."
  #{:map})

(def ^:private column-type #'schema/column-type)
(def ^:private extract-field-info #'schema/extract-field-info)

(def ^:private table-schemas
  (merge persistence/tables tenant-tables/tables))

(defn- fields []
  (for [[table s] table-schemas
        f (keep extract-field-info (rest s))]
    (assoc f :table table)))

(defn- varchar-length [ddl-type]
  (some-> (re-find #"(?i)^VARCHAR\((\d+)\)$" ddl-type) second parse-long))

(deftest ^:unit no-field-is-shorter-than-its-schema
  (doseq [make [h2/new-adapter postgresql/new-adapter mysql-adapter/new-adapter sqlite/new-adapter]
          {:keys [table type max] field-name :name :as f} (fields)
          :let [ctx    {:adapter (make)}
                length (varchar-length (column-type ctx f))
                where  (str (name (protocols/engine (:adapter ctx))) " " table "." field-name)]]
    (when (collection-types type)
      (is (nil? length) (str where ": a collection is no VARCHAR")))
    (when (and max length)
      (is (<= max length) (str where ": its schema allows " max)))))

(def ^:private bou-715-column-types
  "The fields BOU-715 gave a :column-type."
  #{:avatar-url :user-agent :error-message})

(def ^:private before-bou-715
  "Each table as a database created before BOU-715 has it: no :column-type,
   and a collection or a string with a :max as a plain string."
  (update-vals table-schemas
               (fn [s]
                 (into [(first s) (second s)]
                       (map (fn [field]
                              (let [{:keys [type max properties]} (extract-field-info field)
                                    props (cond-> properties
                                            (bou-715-column-types (first field)) (dissoc :column-type))]
                                (if (or (and (collection-types type) (not (json-types type)))
                                        (and (= :string type) max))
                                  [(first field) props :string]
                                  [(first field) props (last field)]))))
                       (drop 2 s)))))

(def ^:private hashed-codes
  (vec (repeatedly 10 #(apply str "$2a$12$" (repeatedly 53 (fn [] (rand-nth "abcdefghij")))))))

(defn- column [ctx table col]
  (first (filter #(= col (:name %)) (db/get-table-info ctx table))))

(defn- max-length [ctx table col]
  (:max-length (column ctx table col)))

(def ^:private widening #"Widening column")

(def ^:private schema-ns 'wagoe.platform.shell.adapters.database.common.schema)

(defn- boot-widens? [ctx]
  (log-test/with-log
    (persistence/initialize-user-schema! ctx)
    (tenant-tables/ensure-tables! ctx)
    (log-test/logged? schema-ns :info widening)))

(def ^:private engines
  "Each is [name (fn [f]) calling `f` with a db-context on a fresh database]."
  [["h2" (fn [f]
           (let [ctx (factory/db-context
                      (factory/h2-config (str "mem:wide_cols_" (System/nanoTime) ";DB_CLOSE_DELAY=-1")))]
             (try (f ctx) (finally (factory/close-db-context! ctx)))))]
   ["postgresql" (fn [f]
                   (let [pg (epg/start!) ctx (epg/db-context pg)]
                     (try (f ctx) (finally (factory/close-db-context! ctx) (epg/stop! pg)))))]
   ["mysql" #(mysql/with-database "wide_cols" %)]])

(deftest ^:integration boot-widens-a-database-created-before
  (doseq [[engine with-db] engines]
    (testing engine
      (with-db
       (fn [ctx]
        (db/initialize-tables-from-schemas! ctx before-bou-715)
        (is (= 255 (max-length ctx :auth_users "mfa_backup_codes")) "the old column, or this proves nothing")
        (let [users (persistence/create-user-repository ctx)
              user  (ports/create-user users {:email "old@example.com" :name "Old" :role :user :active true
                                              :password-hash (apply str (repeat 60 "h"))
                                              :mfa-backup-codes ["kept"]})]
          (is (thrown? Exception (ports/update-user users {:id (:id user) :mfa-backup-codes hashed-codes}))
              "ten codes do not fit the old column")
          (is (boot-widens? ctx))
          (is (not (boot-widens? ctx)) "a widened column is left alone")
          (is (= ["kept"] (:mfa-backup-codes (ports/find-user-by-id users (:id user)))) "the row is kept")
          (ports/update-user users {:id (:id user) :mfa-backup-codes hashed-codes})
          (is (= hashed-codes (:mfa-backup-codes (ports/find-user-by-id users (:id user)))))
          (testing "the other fields that grew"
            (is (not= 255 (max-length ctx :user_sessions "user_agent")))
            (is (not= 255 (max-length ctx :user_audit_log "error_message")))
            (is (not= 255 (max-length ctx :users "avatar_url")))
            (is (= 320 (max-length ctx :tenant_member_invites "email"))))
          (is (:not-null (column ctx :tenant_member_invites "email")) "a NOT NULL column stays NOT NULL")))))))
