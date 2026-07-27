(ns wagoe.user.shell.persistence
  "Shell layer for user module - implements user ports using database storage.
   
   This namespace contains the SHELL (I/O) implementation that handles database persistence.
   In FC/IS architecture, this is the SHELL that:
   - Handles all I/O operations (database reads/writes)
   - Manages external system interactions
   - Contains side effects and impure operations
   - Implements wagoe.user.ports interfaces
   - Transforms between domain entities and database records
   
   Key FC/IS principles:
   - ALL business logic stays in wagoe.user.core.*
   - This is ONLY for I/O and persistence concerns
   - No business decisions made here - only data transformation
   - Shell coordinates with core for business logic
   
   This provides proper FC/IS separation where:
   - Core contains pure business logic (no I/O)
   - Shell handles all I/O and external systems
   - Clean boundary between functional and imperative code"
  (:require [wagoe.core.utils.type-conversion :as type-conversion]
            [wagoe.platform.core.database.query :as core-query]
            [wagoe.platform.shell.adapters.database.common.core :as db]
            [wagoe.platform.shell.adapters.database.protocols :as protocols]
            [wagoe.platform.shell.adapters.database.utils.schema :as db-schema]
            [wagoe.user.ports :as ports]
            [wagoe.user.schema :as user-schema]
            [wagoe.platform.shell.persistence-interceptors :as persistence-interceptors]
            [cheshire.core]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.walk]
            [clojure.tools.logging :as log]
            [next.jdbc :as jdbc])
  (:import [java.util UUID]))

;; =============================================================================
;; Schema Initialization
;; =============================================================================

(defn- ensure-auth-users-audit-columns!
  "Ensure the auth_users table has audit timestamp columns.

  The persistence layer expects these columns for soft deletion and ordering.
  Schema initialization is CREATE TABLE IF NOT EXISTS, so we need this to upgrade
  existing dev/test databases.

  Columns ensured:
  - created_at (required)
  - updated_at (optional)
  - deleted_at (optional)"
  [ctx]
  (when (db/table-exists? ctx :auth_users)
    (let [existing-cols (->> (db/get-table-info ctx :auth_users)
                             (map :name)
                             set)
          dialect (or (protocols/dialect (:adapter ctx)) :postgresql)
          ;; inst? is stored as string in this project; use TEXT for sqlite and VARCHAR for others.
          ts-type (case dialect
                    :sqlite "TEXT"
                    "VARCHAR(255)")
          columns [{:name "created_at" :type ts-type}
                   {:name "updated_at" :type ts-type}
                   {:name "deleted_at" :type ts-type}]]
      (doseq [{:keys [name type]} columns]
        (when-not (contains? existing-cols name)
          (log/info "Adding missing auth_users audit column" {:column name :type type})
          (db/execute-ddl! ctx
                           (str "ALTER TABLE auth_users ADD COLUMN " name " " type)))))))

(defn- ensure-users-preference-columns!
  "Ensure the users table has all preference-related columns.

   This project uses schema-based initialization (CREATE TABLE IF NOT EXISTS),
   but we also need to support adding new columns to existing dev/test databases
   without requiring a manual migration step in every workflow."
  [ctx]
  (when (db/table-exists? ctx :users)
    (let [existing-cols (->> (db/get-table-info ctx :users)
                             (map :name)
                             set)
          dialect (or (protocols/dialect (:adapter ctx)) :postgresql)
          bool-type (case dialect
                      :sqlite "INTEGER"
                      :mysql "TINYINT(1)"
                      "BOOLEAN")
          varchar-255 (case dialect
                        :sqlite "TEXT"
                        "VARCHAR(255)")
          varchar-50 (case dialect
                       :sqlite "TEXT"
                       "VARCHAR(50)")
          columns [{:name "notifications_email" :type bool-type}
                   {:name "notifications_push" :type bool-type}
                   {:name "notifications_sms" :type bool-type}
                   {:name "theme" :type varchar-50}
                   {:name "language" :type varchar-255}
                   {:name "timezone" :type varchar-255}]]
      (doseq [{:keys [name type]} columns]
        (when-not (contains? existing-cols name)
          (log/info "Adding missing users preference column" {:column name :type type})
          (db/execute-ddl! ctx (str "ALTER TABLE users ADD COLUMN " name " " type)))))))

(defn- invalidate-legacy-plaintext-mfa!
  "One-time, idempotent security upgrade: clear MFA on any auth_users row whose
   mfa_secret is a pre-encryption *plaintext* value (i.e. not prefixed with the
   `enc:v1:` marker). Those users must re-enroll — their old TOTP seed and backup
   codes are no longer trusted now that secrets are encrypted and codes hashed.

   Encrypted secrets carry the `enc:v1:` prefix, so this only ever matches legacy
   rows and is a no-op on subsequent startups."
  [ctx]
  (when (db/table-exists? ctx :auth_users)
    (db/execute-ddl! ctx
                     (str "UPDATE auth_users "
                          "SET mfa_enabled = false, mfa_secret = NULL, "
                          "    mfa_backup_codes = NULL, mfa_backup_codes_used = NULL, "
                          "    mfa_enabled_at = NULL "
                          "WHERE mfa_secret IS NOT NULL AND mfa_secret NOT LIKE 'enc:v1:%'"))))

(defn initialize-user-schema!
  "Initialize database schema for user entities using Malli schema definitions.

   Creates the following tables:
   - auth_users: Global authentication records (email, password, MFA, lockout)
   - users: Tenant-specific user profiles (name, role, avatar, preferences)
   - user_sessions: User authentication sessions
   - user_audit_log: Audit trail for user operations

   Includes indexes for:
   - Foreign keys (user_id)
   - Unique constraints (email, session tokens)
   - Query performance (role, active status, expiration dates)
   - Audit trail queries (target_user_id, actor_id, created_at)

   On PostgreSQL, also creates:
   - search_vector GENERATED column for full-text search on users.name
   - GIN index on search_vector for fast full-text search queries

   Note: This creates the split-table architecture for multi-tenancy support.
   Auth data lives in public.auth_users, profile data in tenant-specific users table.

   Args:
     ctx: Database context

   Returns:
     nil

   Example:
     (initialize-user-schema! ctx)"
  [ctx]
  (log/info "Initializing user schema from Malli definitions (split-table architecture)")

  ;; Pre-upgrade existing tables before we try to create indexes that depend on new columns.
  (ensure-auth-users-audit-columns! ctx)
  (ensure-users-preference-columns! ctx)
  ;; Retire any MFA rows still holding a pre-encryption plaintext secret.
  (invalidate-legacy-plaintext-mfa! ctx)

  (db-schema/initialize-tables-from-schemas! ctx
                                             {"auth_users" user-schema/AuthUser
                                              "users" user-schema/TenantUser
                                              "user_sessions" user-schema/UserSession
                                              "user_audit_log" user-schema/UserAuditLog})

  ;; Post-upgrade as a safety net (no-op for fresh databases).
  (ensure-auth-users-audit-columns! ctx)
  (ensure-users-preference-columns! ctx)

  ;; PostgreSQL-only: full-text search vector
  (when (= "org.postgresql.Driver" (protocols/jdbc-driver (:adapter ctx)))
    (log/info "Adding PostgreSQL full-text search vector to users table")
    (db/execute-ddl! ctx
                     "ALTER TABLE users ADD COLUMN IF NOT EXISTS search_vector tsvector GENERATED ALWAYS AS (setweight(to_tsvector('english', coalesce(name, '')), 'A')) STORED")
    (db/execute-ddl! ctx
                     "CREATE INDEX IF NOT EXISTS users_search_vector_idx ON users USING GIN(search_vector)")))

;; =============================================================================
;; Entity Transformations
;; =============================================================================

(defn- db->user-entity
  "Transform database record to user domain entity."
  [ctx db-record]
  (when db-record
    (let [adapter (:adapter ctx)
          db-bool (fn [v] (when (some? v) (protocols/db->boolean adapter v)))]
      (-> db-record
          ;; Keys are already kebab-case (execution layer builder-fn) —
          ;; only type conversions remain.
          ;; Type conversions
          (update :id type-conversion/string->uuid)
          (update :tenant-id type-conversion/string->uuid)
          (update :role type-conversion/string->keyword)
          (update :active #(protocols/db->boolean adapter %))
          (update :created-at type-conversion/string->instant)
          (update :updated-at type-conversion/string->instant)
          (update :deleted-at type-conversion/string->instant)
          (update :last-login type-conversion/string->instant)
          (update :mfa-enabled-at type-conversion/string->instant)
          (update :lockout-until type-conversion/string->instant)
          ;; Preferences
          (update :notifications-email db-bool)
          (update :notifications-push db-bool)
          (update :notifications-sms db-bool)
          (update :theme type-conversion/string->keyword)
          ;; Convert enum fields to keywords
          (update :date-format type-conversion/string->keyword)
          (update :time-format type-conversion/string->keyword)
          ;; Deserialize MFA vector fields from JSON
          (update :mfa-backup-codes #(when % (vec (cheshire.core/parse-string % true))))
          (update :mfa-backup-codes-used #(when % (vec (cheshire.core/parse-string % true))))))))

(defn- session-entity->db
  "Transform session domain entity to database format."
  [session-entity]
  (-> session-entity
      (dissoc :device-info) ;; Remove nested device-info before DB conversion
      (dissoc :remember-me) ;; Remove remember-me flag (used for expiry calculation only)
      (update :id type-conversion/uuid->string)
      (update :user-id type-conversion/uuid->string)
      ;; Convert kebab-case to snake_case
      (clojure.set/rename-keys {:user-id :user_id
                                :session-token :session_token
                                :expires-at :expires_at
                                :created-at :created_at
                                :user-agent :user_agent
                                :ip-address :ip_address
                                :last-accessed-at :last_accessed_at
                                :revoked-at :revoked_at})))

(defn- db->session-entity
  "Transform database record to session domain entity."
  [db-record]
  (when db-record
    (-> db-record
        ;; Convert snake_case to kebab-case
        (clojure.set/rename-keys {:user_id :user-id
                                  :session_token :session-token
                                  :expires_at :expires-at
                                  :created_at :created-at
                                  :user_agent :user-agent
                                  :ip_address :ip-address
                                  :last_accessed_at :last-accessed-at
                                  :revoked_at :revoked-at})
        (update :id type-conversion/string->uuid)
        (update :user-id type-conversion/string->uuid)
        (update :expires-at type-conversion/string->instant)
        (update :created-at type-conversion/string->instant)
        (update :last-accessed-at type-conversion/string->instant)
        (update :revoked-at type-conversion/string->instant))))

(defn- audit-log-entity->db
  "Transform audit log domain entity to database format."
  [audit-entity]
  (-> audit-entity
      (update :id type-conversion/uuid->string)
      (update :action type-conversion/keyword->string)
      (update :actor-id #(when % (type-conversion/uuid->string %)))
      (update :target-user-id type-conversion/uuid->string)
      (update :result type-conversion/keyword->string)
      ;; Convert maps to JSON strings for JSONB columns
      (update :changes #(when % (cheshire.core/generate-string %)))
      (update :metadata #(when % (cheshire.core/generate-string %)))
      ;; Convert kebab-case to snake_case
      (clojure.set/rename-keys {:actor-id :actor_id
                                :actor-email :actor_email
                                :target-user-id :target_user_id
                                :target-user-email :target_user_email
                                :ip-address :ip_address
                                :user-agent :user_agent
                                :error-message :error_message
                                :created-at :created_at})))

(defn- db->audit-log-entity
  "Transform database record to audit log domain entity."
  [db-record]
  (letfn [(parse-json-field [value]
            ;; Handle both TEXT (string) and JSONB/JSON (PGobject) columns
            (when value
              (cond
                (string? value)
                (cheshire.core/parse-string value true)

                ;; PostgreSQL returns PGobject for JSON/JSONB columns
                ;; Use reflection to avoid compile-time dependency on PostgreSQL driver
                (= "org.postgresql.util.PGobject" (.getName (class value)))
                (cheshire.core/parse-string (.getValue value) true)

                ;; Already a map (shouldn't happen, but handle it)
                (map? value)
                value

                :else
                (throw (ex-info "Unexpected JSON field type"
                                {:type (type value)
                                 :value value})))))]
    (when db-record
      (-> db-record
          ;; Convert snake_case to kebab-case
          (clojure.set/rename-keys {:actor_id :actor-id
                                    :actor_email :actor-email
                                    :target_user_id :target-user-id
                                    :target_user_email :target-user-email
                                    :ip_address :ip-address
                                    :user_agent :user-agent
                                    :error_message :error-message
                                    :created_at :created-at})
          (update :id type-conversion/string->uuid)
          (update :action type-conversion/string->keyword)
          (update :actor-id #(when % (type-conversion/string->uuid %)))
          (update :target-user-id type-conversion/string->uuid)
          (update :result type-conversion/string->keyword)
          (update :created-at type-conversion/string->instant)
          ;; Parse JSON fields - handles both TEXT and JSONB/JSON types
          (update :changes parse-json-field)
          (update :metadata parse-json-field)))))

(defn- execute-user-update-batch!
  "Run per-user UPDATEs against `table` as JDBC prepared-statement batches.

   rows is a seq of {:user-id UUID, :set-map {snake-col value ...}}.
   Rows are grouped by their SET column set so every group shares one
   prepared statement, executed via next.jdbc/execute-batch! on the
   transaction connection in tx-ctx (works on both H2 and PostgreSQL).

   Preserves per-user semantics: any UPDATE that affects zero rows throws
   a :user-not-found ex-info for that user's id."
  [tx-ctx table rows]
  (doseq [[cols entries] (group-by #(vec (sort (keys (:set-map %)))) rows)]
    (let [sql-str (str "UPDATE " (name table) " SET "
                       (str/join ", " (map #(str (name %) " = ?") cols))
                       " WHERE id = ?")
          param-groups (mapv (fn [{:keys [set-map user-id]}]
                               (conj (mapv set-map cols)
                                     (type-conversion/uuid->string user-id)))
                             entries)
          counts (jdbc/execute-batch! (:datasource tx-ctx) sql-str param-groups {})]
      (doseq [[affected-rows entry] (map vector counts entries)]
        (when (zero? affected-rows)
          (throw (ex-info (str "User not found in batch update (" (name table) ")")
                          {:type :user-not-found
                           :user-id (:user-id entry)})))))))

(defn- bounded-pagination
  "Sanitize :limit/:offset for the business-specific find queries.

   Guards against nil values (a nil LIMIT breaks H2/SQLite) and defaults the
   limit to the platform max pagination limit so these queries stay bounded
   even when callers pass no options."
  [options]
  (db/build-pagination
   {:limit (or (:limit options) core-query/max-pagination-limit)
    :offset (or (:offset options) 0)}))

;; =============================================================================
;; User Repository Implementation
;; =============================================================================

(defrecord DatabaseUserRepository [ctx]
  ports/IUserRepository

  ;; Basic CRUD Operations
  (find-user-by-id [_ user-id]
    (persistence-interceptors/execute-persistence-operation
     :find-user-by-id
     {:user-id user-id}
     (fn [{:keys [params]}]
       (let [user-id (:user-id params)
             query {:select [:a.id
                             :a.email
                             :a.password_hash
                             :a.active
                             :a.mfa_enabled
                             :a.mfa_secret
                             :a.mfa_backup_codes
                             :a.mfa_backup_codes_used
                             :a.mfa_enabled_at
                             :a.failed_login_count
                             :a.lockout_until
                             :a.created_at
                             :a.updated_at
                             :a.deleted_at
                             :u.tenant_id
                             :u.name
                             :u.role
                             :u.avatar_url
                             :u.login_count
                             :u.last_login
                             :u.date_format
                             :u.time_format
                             :u.notifications_email
                             :u.notifications_push
                             :u.notifications_sms
                             :u.theme
                             :u.language
                             :u.timezone]
                    :from [[:auth_users :a]]
                    :join [[:users :u] [:= :a.id :u.id]]
                    :where [:and
                            [:= :a.id (type-conversion/uuid->string user-id)]
                            [:is :a.deleted_at nil]]}
             result (db/execute-one! ctx query)
             user-entity (db->user-entity ctx result)]
         user-entity))
     {:db-ctx ctx}))

  (find-user-by-email [_ email]
    (persistence-interceptors/execute-persistence-operation
     :find-user-by-email
     {:email email}
     (fn [{:keys [params]}]
       (let [email (:email params)
             query {:select [:a.id
                             :a.email
                             :a.password_hash
                             :a.active
                             :a.mfa_enabled
                             :a.mfa_secret
                             :a.mfa_backup_codes
                             :a.mfa_backup_codes_used
                             :a.mfa_enabled_at
                             :a.failed_login_count
                             :a.lockout_until
                             :a.created_at
                             :a.updated_at
                             :a.deleted_at
                             :u.tenant_id
                             :u.name
                             :u.role
                             :u.avatar_url
                             :u.login_count
                             :u.last_login
                             :u.date_format
                             :u.time_format
                             :u.notifications_email
                             :u.notifications_push
                             :u.notifications_sms
                             :u.theme
                             :u.language
                             :u.timezone]
                    :from [[:auth_users :a]]
                    :join [[:users :u] [:= :a.id :u.id]]
                    :where [:and
                            [:= :a.email email]
                            [:is :a.deleted_at nil]]}
             result (db/execute-one! ctx query)
             user-entity (db->user-entity ctx result)]
         user-entity))
     {:db-ctx ctx}))

  (find-users [_ options]
    (log/debug "Finding users" {:options options})
    (let [;; Build filters with kebab-case (query builder handles all conversions)
          ;; Note: Some filters apply to auth_users (active, deleted_at, email)
          ;; and some to users table (role, tenant_id)

          ;; Build separate filters for auth_users and users tables
          auth-filters (cond-> {}
                         (not (:include-deleted? options)) (assoc :deleted-at nil)
                         (contains? options :filter-active) (assoc :active (:filter-active options)))

          profile-filters (cond-> {}
                            (:filter-role options) (assoc :role (:filter-role options))
                            (:filter-tenant-id options) (assoc :tenant-id (:filter-tenant-id options)))

          ;; Build WHERE clauses for each table
          auth-where (when (seq auth-filters)
                       (db/build-where-clause ctx auth-filters))
          profile-where (when (seq profile-filters)
                          (db/build-where-clause ctx profile-filters))

          ;; Combine WHERE clauses with table aliases
          where-parts (cond-> []
                        auth-where (conj (clojure.walk/postwalk
                                          #(if (keyword? %) (keyword "a" (name %)) %)
                                          auth-where))
                        profile-where (conj (clojure.walk/postwalk
                                             #(if (keyword? %) (keyword "u" (name %)) %)
                                             profile-where)))

          ;; Handle email LIKE separately (on auth_users table)
          where-parts (if-let [pattern (:filter-email-contains options)]
                        (conj where-parts [:like :a.email (str "%" pattern "%")])
                        where-parts)

          where-clause (when (seq where-parts)
                         (if (= 1 (count where-parts))
                           (first where-parts)
                           (into [:and] where-parts)))

          ;; Build pagination & ordering (default to a.created_at)
          pagination (db/build-pagination options)
          ordering (db/build-ordering options :a.created_at)

          ;; Build query with JOIN - select all fields from both tables.
          ;; COUNT(*) OVER() is a window function that returns the total matching rows
          ;; alongside each data row, eliminating the need for a separate COUNT query.
          query (cond-> {:select [:a.id
                                  :a.email
                                  :a.password_hash
                                  :a.active
                                  :a.mfa_enabled
                                  :a.mfa_secret
                                  :a.mfa_backup_codes
                                  :a.mfa_backup_codes_used
                                  :a.mfa_enabled_at
                                  :a.failed_login_count
                                  :a.lockout_until
                                  :a.created_at
                                  :a.updated_at
                                  :a.deleted_at
                                  :u.tenant_id
                                  :u.name
                                  :u.role
                                  :u.avatar_url
                                  :u.login_count
                                  :u.last_login
                                  :u.date_format
                                  :u.time_format
                                  :u.notifications_email
                                  :u.notifications_push
                                  :u.notifications_sms
                                  :u.theme
                                  :u.language
                                  :u.timezone
                                  [[:raw "COUNT(*) OVER()"] :total_count]]
                         :from [[:auth_users :a]]
                         :join [[:users :u] [:= :a.id :u.id]]
                         :order-by ordering
                         :limit (:limit pagination)
                         :offset (:offset pagination)}
                  where-clause (assoc :where where-clause))

          rows (db/execute-query! ctx query)
          ;; execute-query! converts snake_case → kebab-case, so total_count → :total-count
          total-count (or (some-> rows first :total-count long) 0)
          users (vec (map #(db->user-entity ctx %) rows))]
      {:users users
       :total-count total-count}))

  (create-user [_ user-entity]
    (persistence-interceptors/execute-persistence-operation
     :create-user
     {:user-entity user-entity}
     (fn [{:keys [params]}]
       (let [user-entity (:user-entity params)
             now (java.time.Instant/now)
             user-id (UUID/randomUUID)

             ;; Add metadata to full entity
             user-with-metadata (-> user-entity
                                    (assoc :id user-id)
                                    (assoc :created-at now)
                                    (assoc :updated-at nil)
                                    (assoc :deleted-at nil))

             ;; Split into auth and profile fields
             auth-fields (select-keys user-with-metadata
                                      [:id :email :password-hash :active
                                       :mfa-enabled :mfa-secret :mfa-backup-codes
                                       :mfa-backup-codes-used :mfa-enabled-at
                                       :failed-login-count :lockout-until
                                       :created-at :updated-at :deleted-at])

             profile-fields (select-keys user-with-metadata
                                         [:id :tenant-id :name :role :avatar-url
                                          :login-count :last-login
                                          :notifications-email :notifications-push :notifications-sms
                                          :theme :language :timezone
                                          :date-format :time-format
                                          :created-at :updated-at :deleted-at])

             adapter (:adapter ctx)]

         ;; Use transaction to ensure atomicity
         (db/with-transaction [tx ctx]
           ;; 1. Insert into auth_users first (establishes the ID)
           (let [db-auth (-> auth-fields
                             (update :id type-conversion/uuid->string)
                             (update :active #(protocols/boolean->db adapter %))
                             (update :created-at type-conversion/instant->string)
                             (update :updated-at type-conversion/instant->string)
                             (update :deleted-at type-conversion/instant->string)
                             (update :mfa-enabled-at type-conversion/instant->string)
                             (update :lockout-until type-conversion/instant->string)
                             (update :mfa-backup-codes #(when % (cheshire.core/generate-string %)))
                             (update :mfa-backup-codes-used #(when % (cheshire.core/generate-string %)))
                             (set/rename-keys {:password-hash :password_hash
                                               :mfa-enabled :mfa_enabled
                                               :mfa-secret :mfa_secret
                                               :mfa-backup-codes :mfa_backup_codes
                                               :mfa-backup-codes-used :mfa_backup_codes_used
                                               :mfa-enabled-at :mfa_enabled_at
                                               :failed-login-count :failed_login_count
                                               :lockout-until :lockout_until
                                               :created-at :created_at
                                               :updated-at :updated_at
                                               :deleted-at :deleted_at}))
                 auth-query {:insert-into :auth_users
                             :values [db-auth]}]
             (db/execute-update! tx auth-query))

           ;; 2. Insert into users (profile data with FK to auth_users.id)
           (let [db-profile (-> profile-fields
                                (update :id type-conversion/uuid->string)
                                (update :tenant-id type-conversion/uuid->string)
                                (update :role type-conversion/keyword->string)
                                (update :last-login type-conversion/instant->string)
                                (update :created-at type-conversion/instant->string)
                                (update :updated-at type-conversion/instant->string)
                                (update :deleted-at type-conversion/instant->string)
                                (update :notifications-email #(protocols/boolean->db adapter %))
                                (update :notifications-push #(protocols/boolean->db adapter %))
                                (update :notifications-sms #(protocols/boolean->db adapter %))
                                (update :theme type-conversion/keyword->string)
                                (update :date-format type-conversion/keyword->string)
                                (update :time-format type-conversion/keyword->string)
                                (set/rename-keys {:tenant-id :tenant_id
                                                  :avatar-url :avatar_url
                                                  :login-count :login_count
                                                  :last-login :last_login
                                                  :notifications-email :notifications_email
                                                  :notifications-push :notifications_push
                                                  :notifications-sms :notifications_sms
                                                  :date-format :date_format
                                                  :time-format :time_format
                                                  :created-at :created_at
                                                  :updated-at :updated_at
                                                  :deleted-at :deleted_at}))
                 profile-query {:insert-into :users
                                :values [db-profile]}]
             (db/execute-update! tx profile-query)))

         ;; Return the full user entity
         user-with-metadata))
     {:db-ctx ctx}))

  (update-user [_ user-entity]
    (persistence-interceptors/execute-persistence-operation
     :update-user
     {:user-entity user-entity}
     (fn [{:keys [params]}]
       (let [user-entity (:user-entity params)
             user-id (:id user-entity)
             adapter (:adapter ctx)

             ;; Define which fields belong to each table
             auth-field-keys #{:email :password-hash :active :mfa-enabled :mfa-secret
                               :mfa-backup-codes :mfa-backup-codes-used :mfa-enabled-at
                               :failed-login-count :lockout-until :updated-at :deleted-at}

             profile-field-keys #{:tenant-id :name :role :avatar-url :login-count
                                  :last-login
                                  :notifications-email :notifications-push :notifications-sms
                                  :theme :language :timezone
                                  :date-format :time-format
                                  :updated-at :deleted-at}

             ;; Split updates into auth and profile fields
             auth-updates (select-keys user-entity auth-field-keys)
             profile-updates (select-keys user-entity profile-field-keys)

             has-auth-updates? (seq auth-updates)
             has-profile-updates? (seq profile-updates)]

         ;; Use transaction if updating both tables
         (if (and has-auth-updates? has-profile-updates?)
           (db/with-transaction [tx ctx]
             ;; Update auth_users
             (when has-auth-updates?
               (let [db-auth (-> auth-updates
                                 (update :active #(when % (protocols/boolean->db adapter %)))
                                 (update :updated-at type-conversion/instant->string)
                                 (update :deleted-at type-conversion/instant->string)
                                 (update :mfa-enabled-at type-conversion/instant->string)
                                 (update :lockout-until type-conversion/instant->string)
                                 (update :mfa-backup-codes #(when % (cheshire.core/generate-string %)))
                                 (update :mfa-backup-codes-used #(when % (cheshire.core/generate-string %)))
                                 (set/rename-keys {:password-hash :password_hash
                                                   :mfa-enabled :mfa_enabled
                                                   :mfa-secret :mfa_secret
                                                   :mfa-backup-codes :mfa_backup_codes
                                                   :mfa-backup-codes-used :mfa_backup_codes_used
                                                   :mfa-enabled-at :mfa_enabled_at
                                                   :failed-login-count :failed_login_count
                                                   :lockout-until :lockout_until
                                                   :updated-at :updated_at
                                                   :deleted-at :deleted_at}))
                     query {:update :auth_users
                            :set db-auth
                            :where [:= :id (type-conversion/uuid->string user-id)]}]
                 (db/execute-update! tx query)))

             ;; Update users
             (when has-profile-updates?
               (let [db-profile (-> profile-updates
                                    (update :tenant-id type-conversion/uuid->string)
                                    (update :role type-conversion/keyword->string)
                                    (update :last-login type-conversion/instant->string)
                                    (update :updated-at type-conversion/instant->string)
                                    (update :deleted-at type-conversion/instant->string)
                                    (update :notifications-email #(when (some? %) (protocols/boolean->db adapter %)))
                                    (update :notifications-push #(when (some? %) (protocols/boolean->db adapter %)))
                                    (update :notifications-sms #(when (some? %) (protocols/boolean->db adapter %)))
                                    (update :theme type-conversion/keyword->string)
                                    (update :date-format type-conversion/keyword->string)
                                    (update :time-format type-conversion/keyword->string)
                                    (set/rename-keys {:tenant-id :tenant_id
                                                      :avatar-url :avatar_url
                                                      :login-count :login_count
                                                      :last-login :last_login
                                                      :notifications-email :notifications_email
                                                      :notifications-push :notifications_push
                                                      :notifications-sms :notifications_sms
                                                      :date-format :date_format
                                                      :time-format :time_format
                                                      :updated-at :updated_at
                                                      :deleted-at :deleted_at}))
                     query {:update :users
                            :set db-profile
                            :where [:= :id (type-conversion/uuid->string user-id)]}]
                 (db/execute-update! tx query))))

           ;; Single table update (no transaction needed)
           (cond
             has-auth-updates?
             (let [db-auth (-> auth-updates
                               (update :active #(when % (protocols/boolean->db adapter %)))
                               (update :updated-at type-conversion/instant->string)
                               (update :deleted-at type-conversion/instant->string)
                               (update :mfa-enabled-at type-conversion/instant->string)
                               (update :lockout-until type-conversion/instant->string)
                               (update :mfa-backup-codes #(when % (cheshire.core/generate-string %)))
                               (update :mfa-backup-codes-used #(when % (cheshire.core/generate-string %)))
                               (set/rename-keys {:password-hash :password_hash
                                                 :mfa-enabled :mfa_enabled
                                                 :mfa-secret :mfa_secret
                                                 :mfa-backup-codes :mfa_backup_codes
                                                 :mfa-backup-codes-used :mfa_backup_codes_used
                                                 :mfa-enabled-at :mfa_enabled_at
                                                 :failed-login-count :failed_login_count
                                                 :lockout-until :lockout_until
                                                 :updated-at :updated_at
                                                 :deleted-at :deleted_at}))
                   query {:update :auth_users
                          :set db-auth
                          :where [:= :id (type-conversion/uuid->string user-id)]}
                   affected-rows (db/execute-update! ctx query)]
               (when (= affected-rows 0)
                 (throw (ex-info "User not found or update failed"
                                 {:type :user-not-found
                                  :user-id user-id}))))

             has-profile-updates?
             (let [db-profile (-> profile-updates
                                  (update :tenant-id type-conversion/uuid->string)
                                  (update :role type-conversion/keyword->string)
                                  (update :last-login type-conversion/instant->string)
                                  (update :updated-at type-conversion/instant->string)
                                  (update :deleted-at type-conversion/instant->string)
                                  (update :notifications-email #(when (some? %) (protocols/boolean->db adapter %)))
                                  (update :notifications-push #(when (some? %) (protocols/boolean->db adapter %)))
                                  (update :notifications-sms #(when (some? %) (protocols/boolean->db adapter %)))
                                  (update :theme type-conversion/keyword->string)
                                  (update :date-format type-conversion/keyword->string)
                                  (update :time-format type-conversion/keyword->string)
                                  (set/rename-keys {:tenant-id :tenant_id
                                                    :avatar-url :avatar_url
                                                    :login-count :login_count
                                                    :last-login :last_login
                                                    :notifications-email :notifications_email
                                                    :notifications-push :notifications_push
                                                    :notifications-sms :notifications_sms
                                                    :date-format :date_format
                                                    :time-format :time_format
                                                    :updated-at :updated_at
                                                    :deleted-at :deleted_at}))
                   query {:update :users
                          :set db-profile
                          :where [:= :id (type-conversion/uuid->string user-id)]}
                   affected-rows (db/execute-update! ctx query)]
               (when (= affected-rows 0)
                 (throw (ex-info "User not found or update failed"
                                 {:type :user-not-found
                                  :user-id user-id}))))))

         ;; Return the updated user entity
         user-entity))
     {:db-ctx ctx}))

  (soft-delete-user [_ user-id]
    (persistence-interceptors/execute-persistence-operation
     :soft-delete-user
     {:user-id user-id}
     (fn [{:keys [params]}]
       (let [user-id (:user-id params)
             now     (java.time.Instant/now)
             adapter (:adapter ctx)
             ;; Soft delete only needs to update auth_users (master record)
             ;; The deleted_at flag in auth_users controls visibility across both tables
             query   {:update :auth_users
                      :set {:deleted_at (type-conversion/instant->string now)
                            :updated_at (type-conversion/instant->string now)
                            :active     (protocols/boolean->db adapter false)}
                      :where [:and
                              [:= :id (type-conversion/uuid->string user-id)]
                              [:is :deleted_at nil]]}
             affected-rows (db/execute-update! ctx query)]
         (> affected-rows 0)))
     {:db-ctx ctx}))

  (hard-delete-user [_ user-id]
    (persistence-interceptors/execute-persistence-operation
     :hard-delete-user
     {:user-id user-id}
     (fn [{:keys [params]}]
       (let [user-id (:user-id params)]
         ;; Use transaction to delete from both tables
         ;; Must delete from users first (child with FK constraint)
         (db/with-transaction [tx ctx]
           (let [user-id-str (type-conversion/uuid->string user-id)

                 ;; Delete from users table first (FK constraint)
                 profile-query {:delete-from :users
                                :where [:= :id user-id-str]}
                 _profile-affected (db/execute-update! tx profile-query)

                 ;; Then delete from auth_users (parent table)
                 auth-query {:delete-from :auth_users
                             :where [:= :id user-id-str]}
                 auth-affected (db/execute-update! tx auth-query)]

             ;; Return true if we deleted at least the auth record
             (> auth-affected 0)))))
     {:db-ctx ctx}))

  ;; Business-Specific Queries
  (find-active-users-by-role [this role]
    (ports/find-active-users-by-role this role {}))

  (find-active-users-by-role [_ role options]
    (log/debug "Finding active users by role" {:role role :options options})
    (let [adapter (:adapter ctx)
          pagination (bounded-pagination options)
          query {:select [:a.id
                          :a.email
                          :a.password_hash
                          :a.active
                          :a.mfa_enabled
                          :a.mfa_secret
                          :a.mfa_backup_codes
                          :a.mfa_backup_codes_used
                          :a.mfa_enabled_at
                          :a.failed_login_count
                          :a.lockout_until
                          :a.created_at
                          :a.updated_at
                          :a.deleted_at
                          :u.tenant_id
                          :u.name
                          :u.role
                          :u.avatar_url
                          :u.login_count
                          :u.last_login
                          :u.date_format
                          :u.time_format
                          :u.notifications_email
                          :u.notifications_push
                          :u.notifications_sms
                          :u.theme
                          :u.language
                          :u.timezone]
                 :from [[:auth_users :a]]
                 :join [[:users :u] [:= :a.id :u.id]]
                 :where [:and
                         [:= :u.role (type-conversion/keyword->string role)]
                         [:= :a.active (protocols/boolean->db adapter true)]
                         [:is :a.deleted_at nil]]
                 :order-by [[:a.created_at :desc]]
                 :limit (:limit pagination)
                 :offset (:offset pagination)}
          results (db/execute-query! ctx query)]
      (map #(db->user-entity ctx %) results)))

  (count-users [_]
    (log/debug "Counting users")
    (let [;; Count from auth_users (master table for user records)
          query {:select [[:%count.* :total]]
                 :from [:auth_users]
                 :where [:is :deleted_at nil]}
          result (db/execute-one! ctx query)]
      (or (:total result)
          (:count result)
          (get result (keyword "COUNT(*)"))
          0)))

  (find-users-created-since [this since-date]
    (ports/find-users-created-since this since-date {}))

  (find-users-created-since [_ since-date options]
    (log/debug "Finding users created since" {:since-date since-date :options options})
    (let [pagination (bounded-pagination options)
          query {:select [:a.id
                          :a.email
                          :a.password_hash
                          :a.active
                          :a.mfa_enabled
                          :a.mfa_secret
                          :a.mfa_backup_codes
                          :a.mfa_backup_codes_used
                          :a.mfa_enabled_at
                          :a.failed_login_count
                          :a.lockout_until
                          :a.created_at
                          :a.updated_at
                          :a.deleted_at
                          :u.tenant_id
                          :u.name
                          :u.role
                          :u.avatar_url
                          :u.login_count
                          :u.last_login
                          :u.date_format
                          :u.time_format
                          :u.notifications_email
                          :u.notifications_push
                          :u.notifications_sms
                          :u.theme
                          :u.language
                          :u.timezone]
                 :from [[:auth_users :a]]
                 :join [[:users :u] [:= :a.id :u.id]]
                 :where [:and
                         [:>= :a.created_at (type-conversion/instant->string since-date)]
                         [:is :a.deleted_at nil]]
                 :order-by [[:a.created_at :desc]]
                 :limit (:limit pagination)
                 :offset (:offset pagination)}
          results (db/execute-query! ctx query)]
      (map #(db->user-entity ctx %) results)))

  (find-users-by-email-domain [this email-domain]
    (ports/find-users-by-email-domain this email-domain {}))

  (find-users-by-email-domain [_ email-domain options]
    (log/debug "Finding users by email domain" {:email-domain email-domain :options options})
    (let [pagination (bounded-pagination options)
          query {:select [:a.id
                          :a.email
                          :a.password_hash
                          :a.active
                          :a.mfa_enabled
                          :a.mfa_secret
                          :a.mfa_backup_codes
                          :a.mfa_backup_codes_used
                          :a.mfa_enabled_at
                          :a.failed_login_count
                          :a.lockout_until
                          :a.created_at
                          :a.updated_at
                          :a.deleted_at
                          :u.tenant_id
                          :u.name
                          :u.role
                          :u.avatar_url
                          :u.login_count
                          :u.last_login
                          :u.date_format
                          :u.time_format
                          :u.notifications_email
                          :u.notifications_push
                          :u.notifications_sms
                          :u.theme
                          :u.language
                          :u.timezone]
                 :from [[:auth_users :a]]
                 :join [[:users :u] [:= :a.id :u.id]]
                 :where [:and
                         [:like :a.email (str "%@" email-domain)]
                         [:is :a.deleted_at nil]]
                 :order-by [[:a.created_at :desc]]
                 :limit (:limit pagination)
                 :offset (:offset pagination)}
          results (db/execute-query! ctx query)]
      (map #(db->user-entity ctx %) results)))

  ;; Batch Operations
  (create-users-batch [_ user-entities]
    (persistence-interceptors/execute-persistence-operation
     :create-users-batch
     {:user-entities user-entities}
     (fn [{:keys [params]}]
       (let [user-entities (:user-entities params)
             adapter (:adapter ctx)]
         (db/with-transaction [tx ctx]
           (let [now (java.time.Instant/now)
                 users-with-metadata (mapv (fn [user]
                                             (-> user
                                                 (assoc :id (UUID/randomUUID))
                                                 (assoc :created-at now)
                                                 (assoc :updated-at nil)
                                                 (assoc :deleted-at nil)))
                                           user-entities)

                 ;; Convert all users to DB auth format
                 db-auth-rows (mapv (fn [user]
                                      (let [auth-fields (select-keys user
                                                                     [:id :email :password-hash :active
                                                                      :mfa-enabled :mfa-secret :mfa-backup-codes
                                                                      :mfa-backup-codes-used :mfa-enabled-at
                                                                      :failed-login-count :lockout-until
                                                                      :created-at :updated-at :deleted-at])]
                                        (-> auth-fields
                                            (update :id type-conversion/uuid->string)
                                            (update :active #(protocols/boolean->db adapter %))
                                            (update :created-at type-conversion/instant->string)
                                            (update :updated-at type-conversion/instant->string)
                                            (update :deleted-at type-conversion/instant->string)
                                            (update :mfa-enabled-at type-conversion/instant->string)
                                            (update :lockout-until type-conversion/instant->string)
                                            (update :mfa-backup-codes #(when % (cheshire.core/generate-string %)))
                                            (update :mfa-backup-codes-used #(when % (cheshire.core/generate-string %)))
                                            (set/rename-keys {:password-hash :password_hash
                                                              :mfa-enabled :mfa_enabled
                                                              :mfa-secret :mfa_secret
                                                              :mfa-backup-codes :mfa_backup_codes
                                                              :mfa-backup-codes-used :mfa_backup_codes_used
                                                              :mfa-enabled-at :mfa_enabled_at
                                                              :failed-login-count :failed_login_count
                                                              :lockout-until :lockout_until
                                                              :created-at :created_at
                                                              :updated-at :updated_at
                                                              :deleted-at :deleted_at}))))
                                    users-with-metadata)

                 ;; Convert all users to DB profile format
                 db-profile-rows (mapv (fn [user]
                                         (let [profile-fields (select-keys user
                                                                           [:id :tenant-id :name :role :avatar-url
                                                                            :login-count :last-login
                                                                            :notifications-email :notifications-push :notifications-sms
                                                                            :theme :language :timezone
                                                                            :date-format :time-format])]
                                           (-> profile-fields
                                               (update :id type-conversion/uuid->string)
                                               (update :tenant-id type-conversion/uuid->string)
                                               (update :role type-conversion/keyword->string)
                                               (update :last-login type-conversion/instant->string)
                                               (update :notifications-email #(protocols/boolean->db adapter %))
                                               (update :notifications-push #(protocols/boolean->db adapter %))
                                               (update :notifications-sms #(protocols/boolean->db adapter %))
                                               (update :theme type-conversion/keyword->string)
                                               (update :date-format type-conversion/keyword->string)
                                               (update :time-format type-conversion/keyword->string)
                                               (set/rename-keys {:tenant-id :tenant_id
                                                                 :avatar-url :avatar_url
                                                                 :login-count :login_count
                                                                 :last-login :last_login
                                                                 :notifications-email :notifications_email
                                                                 :notifications-push :notifications_push
                                                                 :notifications-sms :notifications_sms
                                                                 :date-format :date_format
                                                                 :time-format :time_format}))))
                                       users-with-metadata)]

             ;; Single multi-value INSERT for auth_users, then users — two round-trips total
             (db/execute-update! tx {:insert-into :auth_users :values db-auth-rows})
             (db/execute-update! tx {:insert-into :users :values db-profile-rows})

             users-with-metadata))))
     {:db-ctx ctx}))

  (update-users-batch [_ user-entities]
    (persistence-interceptors/execute-persistence-operation
     :update-users-batch
     {:user-entities user-entities}
     (fn [{:keys [params]}]
       (let [user-entities (:user-entities params)
             adapter (:adapter ctx)]
         (db/with-transaction [tx ctx]
           (let [now (java.time.Instant/now)
                 updated-users (map #(assoc % :updated-at now) user-entities)

                 ;; Define field sets
                 auth-field-keys #{:email :password-hash :active :mfa-enabled :mfa-secret
                                   :mfa-backup-codes :mfa-backup-codes-used :mfa-enabled-at
                                   :failed-login-count :lockout-until :updated-at :deleted-at}

                 profile-field-keys #{:tenant-id :name :role :avatar-url :login-count
                                      :last-login
                                      :notifications-email :notifications-push :notifications-sms
                                      :theme :language :timezone
                                      :date-format :time-format}]

             ;; Build per-user SET maps (same conversions as before), then run
             ;; them as prepared-statement batches — one statement per SET-column
             ;; shape instead of one UPDATE round-trip per user.
             (let [auth-rows
                   (into []
                         (keep (fn [user]
                                 (let [auth-updates (select-keys user auth-field-keys)]
                                   (when (seq auth-updates)
                                     {:user-id (:id user)
                                      :set-map (-> auth-updates
                                                   (update :active #(when % (protocols/boolean->db adapter %)))
                                                   (update :updated-at type-conversion/instant->string)
                                                   (update :deleted-at type-conversion/instant->string)
                                                   (update :mfa-enabled-at type-conversion/instant->string)
                                                   (update :lockout-until type-conversion/instant->string)
                                                   (update :mfa-backup-codes #(when % (cheshire.core/generate-string %)))
                                                   (update :mfa-backup-codes-used #(when % (cheshire.core/generate-string %)))
                                                   (set/rename-keys {:password-hash :password_hash
                                                                     :mfa-enabled :mfa_enabled
                                                                     :mfa-secret :mfa_secret
                                                                     :mfa-backup-codes :mfa_backup_codes
                                                                     :mfa-backup-codes-used :mfa_backup_codes_used
                                                                     :mfa-enabled-at :mfa_enabled_at
                                                                     :failed-login-count :failed_login_count
                                                                     :lockout-until :lockout_until
                                                                     :updated-at :updated_at
                                                                     :deleted-at :deleted_at}))}))))
                         updated-users)

                   profile-rows
                   (into []
                         (keep (fn [user]
                                 (let [profile-updates (select-keys user profile-field-keys)]
                                   (when (seq profile-updates)
                                     {:user-id (:id user)
                                      :set-map (-> profile-updates
                                                   (update :tenant-id type-conversion/uuid->string)
                                                   (update :role type-conversion/keyword->string)
                                                   (update :last-login type-conversion/instant->string)
                                                   (update :notifications-email #(when (some? %) (protocols/boolean->db adapter %)))
                                                   (update :notifications-push #(when (some? %) (protocols/boolean->db adapter %)))
                                                   (update :notifications-sms #(when (some? %) (protocols/boolean->db adapter %)))
                                                   (update :theme type-conversion/keyword->string)
                                                   (update :date-format type-conversion/keyword->string)
                                                   (update :time-format type-conversion/keyword->string)
                                                   (set/rename-keys {:tenant-id :tenant_id
                                                                     :avatar-url :avatar_url
                                                                     :login-count :login_count
                                                                     :last-login :last_login
                                                                     :notifications-email :notifications_email
                                                                     :notifications-push :notifications_push
                                                                     :notifications-sms :notifications_sms
                                                                     :date-format :date_format
                                                                     :time-format :time_format}))}))))
                         updated-users)]

               (execute-user-update-batch! tx :auth_users auth-rows)
               (execute-user-update-batch! tx :users profile-rows))

             updated-users))))
     {:db-ctx ctx})))

;; =============================================================================
;; Session Repository Implementation
;; =============================================================================

(defrecord DatabaseUserSessionRepository [ctx]
  ports/IUserSessionRepository

  (create-session [_ session-entity]
    (persistence-interceptors/execute-persistence-operation
     :create-session
     {:session-entity session-entity}
     (fn [{:keys [params]}]
       (let [session-entity (:session-entity params)
             now (java.time.Instant/now)
               ;; Generate id if not provided, add DB-specific metadata
             session-with-metadata (-> session-entity
                                       (update :id #(or % (java.util.UUID/randomUUID)))
                                       (assoc :created-at now)
                                       (assoc :last-accessed-at nil)
                                       (assoc :revoked-at nil))
             db-session (session-entity->db session-with-metadata)
             query {:insert-into :user_sessions
                    :values [db-session]}]
         (db/execute-update! ctx query)
         session-with-metadata))
     {:db-ctx ctx}))

  (find-session-by-token [_ session-token]
    (persistence-interceptors/execute-persistence-operation
     :find-session-by-token
     {:session-token session-token}
     (fn [{:keys [params]}]
       (let [session-token (:session-token params)
             now (java.time.Instant/now)
             query {:select [:*]
                    :from [:user_sessions]
                    :where [:and
                            [:= :session_token session-token]
                            [:> :expires_at (type-conversion/instant->string now)]
                            [:is :revoked_at nil]]}
             result (db/execute-one! ctx query)]
         (when result
           (let [update-query {:update :user_sessions
                               :set {:last_accessed_at (type-conversion/instant->string now)}
                               :where [:= :session_token session-token]}]
             (db/execute-update! ctx update-query))
           (-> result
               db->session-entity
               (assoc :last-accessed-at now)))))
     {:db-ctx ctx}))

  (find-sessions-by-user [_ user-id]
    (persistence-interceptors/execute-persistence-operation
     :find-sessions-by-user
     {:user-id user-id}
     (fn [{:keys [params]}]
       (let [user-id (:user-id params)
             now (java.time.Instant/now)
             query {:select [:*]
                    :from [:user_sessions]
                    :where [:and
                            [:= :user_id (type-conversion/uuid->string user-id)]
                            [:> :expires_at (type-conversion/instant->string now)]
                            [:is :revoked_at nil]]
                    :order-by [[:created_at :desc]]}
             results (db/execute-query! ctx query)]
         (map db->session-entity results)))
     {:db-ctx ctx}))

  (invalidate-session [_ session-token]
    (persistence-interceptors/execute-persistence-operation
     :invalidate-session
     {:session-token session-token}
     (fn [{:keys [params]}]
       (let [session-token (:session-token params)
             now (java.time.Instant/now)
             query {:update :user_sessions
                    :set {:revoked_at (type-conversion/instant->string now)}
                    :where [:and
                            [:= :session_token session-token]
                            [:is :revoked_at nil]]}
             affected-rows (db/execute-update! ctx query)]
         (> affected-rows 0)))
     {:db-ctx ctx}))

  (invalidate-all-user-sessions [_ user-id]
    (persistence-interceptors/execute-persistence-operation
     :invalidate-all-user-sessions
     {:user-id user-id}
     (fn [{:keys [params]}]
       (let [user-id (:user-id params)
             now (java.time.Instant/now)
             query {:update :user_sessions
                    :set {:revoked_at (type-conversion/instant->string now)}
                    :where [:and
                            [:= :user_id (type-conversion/uuid->string user-id)]
                            [:is :revoked_at nil]]}
             affected-rows (db/execute-update! ctx query)]
         affected-rows))
     {:db-ctx ctx}))

  (cleanup-expired-sessions [_ before-timestamp]
    (persistence-interceptors/execute-persistence-operation
     :cleanup-expired-sessions
     {:before-timestamp before-timestamp}
     (fn [{:keys [params]}]
       (let [before-timestamp (:before-timestamp params)
             query {:delete-from :user_sessions
                    :where [:< :expires_at (type-conversion/instant->string before-timestamp)]}
             affected-rows (db/execute-update! ctx query)]
         affected-rows))
     {:db-ctx ctx}))

  (update-session [_ session-entity]
    (persistence-interceptors/execute-persistence-operation
     :update-session
     {:session-entity session-entity}
     (fn [{:keys [params]}]
       (let [session-entity (:session-entity params)
             db-session (session-entity->db session-entity)
             query {:update :user_sessions
                    :set (dissoc db-session :id)
                    :where [:= :id (type-conversion/uuid->string (:id session-entity))]}
             affected-rows (db/execute-update! ctx query)]
         (when (> affected-rows 0)
           session-entity)))
     {:db-ctx ctx}))

  (find-all-sessions [_]
    (persistence-interceptors/execute-persistence-operation
     :find-all-sessions
     {}
     (fn [_]
       (let [query {:select [:*]
                    :from [:user_sessions]
                    :order-by [[:created_at :desc]]}
             results (db/execute-query! ctx query)]
         (map db->session-entity results)))
     {:db-ctx ctx}))

  (delete-session [_ session-id]
    (persistence-interceptors/execute-persistence-operation
     :delete-session
     {:session-id session-id}
     (fn [{:keys [params]}]
       (let [session-id (:session-id params)
             query {:delete-from :user_sessions
                    :where [:= :id (type-conversion/uuid->string session-id)]}
             affected-rows (db/execute-update! ctx query)]
         (> affected-rows 0)))
     {:db-ctx ctx})))

;; =============================================================================
;; Audit Log Repository Implementation
;; =============================================================================

(defrecord DatabaseUserAuditRepository [ctx pagination-config]
  ports/IUserAuditRepository

  (create-audit-log [_ audit-entity]
    (persistence-interceptors/execute-persistence-operation
     :create-audit-log
     {:audit-entity audit-entity}
     (fn [{:keys [params]}]
       (let [audit-entity (:audit-entity params)
             now (java.time.Instant/now)
             audit-with-metadata (-> audit-entity
                                     (assoc :id (or (:id audit-entity) (UUID/randomUUID)))
                                     (assoc :created-at (or (:created-at audit-entity) now)))
             db-audit (audit-log-entity->db audit-with-metadata)
             query {:insert-into :user_audit_log
                    :values [db-audit]}]
         (db/execute-update! ctx query)
         audit-with-metadata))
     {:db-ctx ctx}))

  (find-audit-logs [_ options]
    (persistence-interceptors/execute-persistence-operation
     :find-audit-logs
     {:options options}
     (fn [{:keys [params]}]
       (let [{:keys [limit offset sort-by sort-direction
                     filter-target-user-id filter-actor-id filter-action
                     filter-result filter-created-after filter-created-before
                     filter-target-email filter-actor-email]} (:options params)
             default-limit (get pagination-config :default-limit 20)
             limit (or limit default-limit)
             offset (or offset 0)
             sort-by-kebab (or sort-by :created-at)
             ;; Convert kebab-case to snake_case for database column
             sort-by-db (keyword (.replace (name sort-by-kebab) "-" "_"))
             sort-direction (or sort-direction :desc)
             where-clauses (cond-> []
                             filter-target-user-id
                             (conj [:= :target_user_id (type-conversion/uuid->string filter-target-user-id)])

                             filter-actor-id
                             (conj [:= :actor_id (type-conversion/uuid->string filter-actor-id)])

                             filter-action
                             (conj [:= :action (name filter-action)])

                             filter-result
                             (conj [:= :result (name filter-result)])

                             filter-target-email
                             (conj [:ilike :target_user_email (str "%" filter-target-email "%")])

                             filter-actor-email
                             (conj [:ilike :actor_email (str "%" filter-actor-email "%")])

                             filter-created-after
                             (conj [:>= :created_at (type-conversion/instant->string filter-created-after)])

                             filter-created-before
                             (conj [:<= :created_at (type-conversion/instant->string filter-created-before)]))
             where-clause (when (seq where-clauses)
                            (if (= 1 (count where-clauses))
                              (first where-clauses)
                              (into [:and] where-clauses)))
             query (cond-> {:select [:*]
                            :from [:user_audit_log]
                            :order-by [[sort-by-db sort-direction]]
                            :limit limit
                            :offset offset}
                     where-clause (assoc :where where-clause))
             count-query (cond-> {:select [[:%count.* :count]]
                                  :from [:user_audit_log]}
                           where-clause (assoc :where where-clause))
             results (db/execute-query! ctx query)
             total-count (:count (db/execute-one! ctx count-query))]
         {:audit-logs (map db->audit-log-entity results)
          :total-count total-count}))
     {:db-ctx ctx}))

  (find-audit-logs-by-user [_ user-id options]
    (persistence-interceptors/execute-persistence-operation
     :find-audit-logs-by-user
     {:user-id user-id :options options}
     (fn [{:keys [params]}]
       (let [user-id (:user-id params)
             {:keys [limit offset sort-by sort-direction]} (:options params)
             default-limit (get pagination-config :default-limit 20)
             limit (or limit default-limit)
             offset (or offset 0)
             sort-by-kebab (or sort-by :created-at)
             ;; Convert kebab-case to snake_case for database column
             sort-by-db (keyword (.replace (name sort-by-kebab) "-" "_"))
             sort-direction (or sort-direction :desc)
             query {:select [:*]
                    :from [:user_audit_log]
                    :where [:= :target_user_id (type-conversion/uuid->string user-id)]
                    :order-by [[sort-by-db sort-direction]]
                    :limit limit
                    :offset offset}
             results (db/execute-query! ctx query)]
         (map db->audit-log-entity results)))
     {:db-ctx ctx}))

  (find-audit-logs-by-actor [_ actor-id options]
    (persistence-interceptors/execute-persistence-operation
     :find-audit-logs-by-actor
     {:actor-id actor-id :options options}
     (fn [{:keys [params]}]
       (let [actor-id (:actor-id params)
             {:keys [limit offset sort-by sort-direction]} (:options params)
             default-limit (get pagination-config :default-limit 20)
             limit (or limit default-limit)
             offset (or offset 0)
             sort-by-kebab (or sort-by :created-at)
             ;; Convert kebab-case to snake_case for database column
             sort-by-db (keyword (.replace (name sort-by-kebab) "-" "_"))
             sort-direction (or sort-direction :desc)
             query {:select [:*]
                    :from [:user_audit_log]
                    :where [:= :actor_id (type-conversion/uuid->string actor-id)]
                    :order-by [[sort-by-db sort-direction]]
                    :limit limit
                    :offset offset}
             results (db/execute-query! ctx query)]
         (map db->audit-log-entity results)))
     {:db-ctx ctx}))

  (count-audit-logs [_ filters]
    (persistence-interceptors/execute-persistence-operation
     :count-audit-logs
     {:filters filters}
     (fn [{:keys [params]}]
       (let [{:keys [filter-target-user-id filter-actor-id filter-action filter-created-after]} (:filters params)
             where-clauses (cond-> []
                             filter-target-user-id
                             (conj [:= :target_user_id (type-conversion/uuid->string filter-target-user-id)])

                             filter-actor-id
                             (conj [:= :actor_id (type-conversion/uuid->string filter-actor-id)])

                             filter-action
                             (conj [:= :action (name filter-action)])

                             filter-created-after
                             (conj [:>= :created_at (type-conversion/instant->string filter-created-after)]))
             where-clause (when (seq where-clauses)
                            (if (= 1 (count where-clauses))
                              (first where-clauses)
                              (into [:and] where-clauses)))
             query (cond-> {:select [[:%count.* :count]]
                            :from [:user_audit_log]}
                     where-clause (assoc :where where-clause))
             result (db/execute-one! ctx query)]
         (:count result)))
     {:db-ctx ctx})))

;; =============================================================================
;; Factory Functions
;; =============================================================================

(defn create-user-repository
  "Create a user repository instance using database storage.
   
   Args:
     ctx: Database context from wagoe.platform.shell.adapters.database.factory
     
   Returns:
     DatabaseUserRepository implementing IUserRepository
     
   Example:
     (create-user-repository ctx)"
  [ctx]
  ;; Ensure preference columns exist even in test setups that create the users
  ;; table without running :wagoe/user-db-schema.
  (ensure-users-preference-columns! ctx)
  (->DatabaseUserRepository ctx))

(defn create-session-repository
  "Create a user session repository instance using database storage.
   
   Args:
     ctx: Database context from wagoe.platform.shell.adapters.database.factory
     
   Returns:
     DatabaseUserSessionRepository implementing IUserSessionRepository
     
   Example:
     (create-session-repository ctx)"
  [ctx]
  (->DatabaseUserSessionRepository ctx))

(defn create-audit-repository
  "Create a user audit log repository instance using database storage.
   
   Args:
     ctx: Database context from wagoe.platform.shell.adapters.database.factory
     pagination-config: Pagination configuration map with :default-limit
     
   Returns:
     DatabaseUserAuditRepository implementing IUserAuditRepository
     
   Example:
     (create-audit-repository ctx pagination-config)"
  [ctx pagination-config]
  (->DatabaseUserAuditRepository ctx pagination-config))
