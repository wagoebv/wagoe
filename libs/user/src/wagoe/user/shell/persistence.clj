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
            [wagoe.platform.database :as db]
            [next.jdbc.transaction :as jdbc-tx]
            [wagoe.platform.ports.database :as protocols]
            [wagoe.user.ports :as ports]
            [wagoe.user.schema :as user-schema]
            [wagoe.platform.shell.persistence-interceptors :as persistence-interceptors]
            [buddy.core.codecs :as codecs]
            [buddy.core.hash :as hash]
            [cheshire.core]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.walk]
            [clojure.tools.logging :as log])
  (:import [java.util UUID]))

;; =============================================================================
;; Session token hashing
;; =============================================================================

(defn session-token-hash
  "What user_sessions stores for `token`: its SHA-256, in hex. The token is 256
   random bits, so an unsalted fast hash is enough, and a read of the table
   yields nothing a client can present (BOU-696)."
  [token]
  (some-> token hash/sha256 codecs/bytes->hex))

;; =============================================================================
;; Schema Initialization
;; =============================================================================

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

(defn- hash-plaintext-session-tokens!
  "Replace each session token stored in plaintext with its hash, so a session
   opened before BOU-696 still validates. A hash is 64 characters and an issued
   token 43, so a row is only ever hashed once; the guard on the old value keeps
   two booting replicas from hashing the same row twice."
  [ctx]
  (db/with-transaction [tx ctx]
    (doseq [{:keys [id session-token]} (db/execute-query! tx {:select [:id :session_token]
                                                              :from   [:user_sessions]
                                                              :where  [:<> [:length :session_token] 64]})]
      (db/execute-update! tx {:update :user_sessions
                              :set    {:session_token (session-token-hash session-token)}
                              :where  [:and [:= :id id] [:= :session_token session-token]]}))))

(def ^:private audit-action-check "chk_user_audit_log_action")

(def ^:private audit-action-check-clause
  (str "CHECK(action IN (" (str/join ", " (map #(str "'" (name %) "'") user-schema/audit-actions)) "))"))

(defn- takes-every-action?
  "Whether a CHECK's (or table's) SQL names every `audit-actions` value."
  [sql]
  (every? #(str/includes? sql (str "'" (name %) "'")) user-schema/audit-actions))

(defn- audit-action-check-sql
  "The SQL of user_audit_log's action CHECK, or for SQLite of the whole table,
   or nil when there is none to read."
  [ctx engine]
  (some-> (case engine
            :postgresql (db/execute-one! ctx [(str "SELECT pg_get_constraintdef(oid) AS definition FROM pg_constraint"
                                                   " WHERE conrelid = to_regclass('user_audit_log') AND conname = ?")
                                              audit-action-check])
            :mysql      (db/execute-one! ctx [(str "SELECT CHECK_CLAUSE AS definition FROM information_schema.CHECK_CONSTRAINTS"
                                                   " WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = ?")
                                              audit-action-check])
            :sqlite     (db/execute-one! ctx ["SELECT sql AS definition FROM sqlite_master WHERE type = 'table' AND name = 'user_audit_log'"])
            nil)
          :definition
          ;; MySQL's CHECK_CLAUSE escapes the quotes: _utf8mb4\'create\'.
          (str/replace "\\" "")))

(defn- rebuild-sqlite-audit-log!
  "SQLite cannot alter a CHECK, so the table is copied into one created with
   the current check."
  [ctx table-sql]
  (let [create (-> table-sql
                   (str/replace-first #"(?i)^CREATE TABLE\s+(IF NOT EXISTS\s+)?\"?user_audit_log\"?"
                                      "CREATE TABLE user_audit_log_new")
                   (str/replace-first #"CHECK\s*\(\s*action IN \([^)]*\)\s*\)"
                                      audit-action-check-clause))]
    (if (takes-every-action? create)
      (db/with-transaction [tx ctx]
        ;; DROP TABLE takes the table's indexes and triggers with it.
        (let [dependents (db/execute-query! tx [(str "SELECT sql FROM sqlite_master WHERE tbl_name = 'user_audit_log'"
                                                     " AND type IN ('index', 'trigger') AND sql IS NOT NULL")])]
          (db/execute-ddl! tx create)
          (db/execute-ddl! tx "INSERT INTO user_audit_log_new SELECT * FROM user_audit_log")
          (db/execute-ddl! tx "DROP TABLE user_audit_log")
          (db/execute-ddl! tx "ALTER TABLE user_audit_log_new RENAME TO user_audit_log")
          (doseq [{:keys [sql]} dependents]
            (db/execute-ddl! tx sql))))
      (log/warn "user_audit_log's action check has an unexpected shape and was left as it is"
                {:sql table-sql}))))

(defn- widen-audit-action-check!
  "Let user_audit_log's action CHECK take every `audit-actions` value on a
   database created before one was added: `:permanent-delete` (BOU-682),
   `:session-revoked` (BOU-698), `:data-export` (BOU-735),
   `:mfa-disabled` (BOU-782).
   Without it that audit entry is refused, and the refusal only warns. H2
   re-applies its checks at every boot already."
  [ctx]
  (let [engine (protocols/engine (:adapter ctx))
        sql    (audit-action-check-sql ctx engine)]
    (when (and sql (not (takes-every-action? sql)))
      (log/info "Widening user_audit_log's action check" {:engine engine})
      (case engine
        :sqlite     (rebuild-sqlite-audit-log! ctx sql)
        ;; NOT VALID, then VALIDATE: the scan runs without blocking writes.
        :postgresql (do (db/with-transaction [tx ctx]
                          (db/execute-ddl! tx (str "ALTER TABLE user_audit_log DROP CONSTRAINT " audit-action-check))
                          (db/execute-ddl! tx (str "ALTER TABLE user_audit_log ADD CONSTRAINT " audit-action-check
                                                   " " audit-action-check-clause " NOT VALID")))
                        (db/execute-ddl! ctx (str "ALTER TABLE user_audit_log VALIDATE CONSTRAINT " audit-action-check)))
        ;; One statement: MySQL DDL does not roll back, so a failed ADD after
        ;; a separate DROP would leave no check at all.
        :mysql      (db/execute-ddl! ctx (str "ALTER TABLE user_audit_log DROP CHECK " audit-action-check
                                              ", ADD CONSTRAINT " audit-action-check " " audit-action-check-clause))))))

(defn- unique-group-names!
  "Make user_groups.name unique, which an import matches on (BOU-718). Where
   names already repeat, say which and go on booting."
  [ctx]
  (try
    (db/create-index-if-not-exists! ctx "uk_user_groups_name" :user_groups [:name] {:unique? true})
    (catch clojure.lang.ExceptionInfo e
      (when-not (= :conflict (:type (ex-data e))) (throw e))
      (log/error (str "Group names repeat, so user_groups.name is not unique and an import may"
                      " create a group twice. Rename or delete the duplicates at /web/admin/groups,"
                      " then restart.")
                 {:names (mapv :name (db/execute-query! ctx {:select   [:name]
                                                             :from     [:user_groups]
                                                             :group-by [:name]
                                                             :having   [:> [:count :*] 1]}))}))))

(def tables
  "The user module's tables, by name, and the Malli schema each is built from."
  {"auth_users"      user-schema/AuthUser
   "users"           user-schema/TenantUser
   "user_sessions"   user-schema/UserSession
   "user_audit_log"  user-schema/UserAuditLog
   "user_identities" user-schema/UserIdentity
   "user_groups"        user-schema/UserGroup
   "user_group_members" user-schema/UserGroupMember
   "user_group_grants"  user-schema/UserGroupGrant
   "user_roles"         user-schema/UserRole
   "user_group_roles"   user-schema/UserGroupRole})

(defn initialize-user-schema!
  "Initialize database schema for user entities using Malli schema definitions.

   Creates the following tables:
   - auth_users: Global authentication records (email, password, MFA, lockout)
   - users: Tenant-specific user profiles (name, role, avatar, preferences)
   - user_sessions: User authentication sessions
   - user_audit_log: Audit trail for user operations
   - user_identities: OpenID Connect (issuer, subject) links to users
   - user_groups, user_group_members: groups
   - user_roles, user_group_roles: managed roles and the roles of groups
   - user_group_grants: what groups held before roles, kept until 2.0

   Includes indexes for:
   - Foreign keys (user_id)
   - Unique constraints (email, session tokens)
   - Query performance (role, active status, expiration dates)
   - Audit trail queries (target_user_id, actor_id, created_at)

   Where the adapter claims :full-text (PostgreSQL), also creates:
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

  (db/initialize-tables-from-schemas! ctx tables)
  (db/create-index-if-not-exists! ctx "uk_user_identities_issuer_subject" :user_identities
                                  [:issuer :subject] {:unique? true})
  (db/create-index-if-not-exists! ctx "uk_user_group_members_group_user" :user_group_members
                                  [:group_id :user_id] {:unique? true})
  (unique-group-names! ctx)
  ;; For the grants the migration moves onto roles. user_id and group_id are indexed already,
  ;; as every -id column is.
  (db/create-index-if-not-exists! ctx "idx_user_group_grants_permission" :user_group_grants [:permission])
  (db/create-index-if-not-exists! ctx "uk_user_roles_name" :user_roles [:name] {:unique? true})
  (db/create-index-if-not-exists! ctx "idx_user_group_roles_role" :user_group_roles [:role])
  (widen-audit-action-check! ctx)
  (hash-plaintext-session-tokens! ctx)

  ;; Retire any MFA rows still holding a pre-encryption plaintext secret. After
  ;; the tables, which add the mfa columns an older auth_users lacks.
  (invalidate-legacy-plaintext-mfa! ctx)

  ;; Full-text search vector, where the database has it (ADR-039)
  (when (protocols/capable? (:adapter ctx) :full-text)
    (log/info "Adding full-text search vector to users table")
    (db/add-column-if-not-exists! ctx :users :search_vector
                                  (str "tsvector GENERATED ALWAYS AS"
                                       " (setweight(to_tsvector('english', coalesce(name, '')), 'A')) STORED"))
    (db/execute-ddl! ctx
                     "CREATE INDEX IF NOT EXISTS users_search_vector_idx ON users USING GIN(search_vector)")))

;; =============================================================================
;; Entity Transformations
;; =============================================================================

(defn- db->role
  "Superuser or nothing: \"user\", and \"viewer\" before the migration, are
   no role."
  [s]
  (when (= "admin" s) :admin))

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
          (update :role db->role)
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

(defn- role->db
  "The column stores \"user\" for no role, which the previous version reads."
  [role]
  (if (= :admin role) "admin" "user"))

(defn- update-present
  "`update` only when `m` has `k`: a key a partial update leaves out must not
   reach the SET clause as NULL (BOU-665)."
  [m k f]
  (if (contains? m k) (update m k f) m))

(defn- session-entity->db
  "Transform session domain entity to database format."
  [session-entity]
  (-> session-entity
      (dissoc :device-info) ;; Remove nested device-info before DB conversion
      (dissoc :remember-me) ;; Remove remember-me flag (used for expiry calculation only)
      (update-present :session-token session-token-hash)
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
        (update :revoked-at type-conversion/string->instant)
        ;; The stored hash is no use to a caller, and update-session must not
        ;; write it back hashed again.
        (dissoc :session-token))))

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

                ;; H2 returns a CLOB for a JSON column
                (instance? java.sql.Clob value)
                (let [^java.sql.Clob clob value]
                  (cheshire.core/parse-string (.getSubString clob 1 (int (.length clob))) true))

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
          contains    #(protocols/like (:adapter ctx) %1 :contains %2)
          where-parts (cond-> where-parts
                        (:filter-email-contains options)
                        (conj (contains :a.email (:filter-email-contains options)))

                        (:filter-name-or-email-contains options)
                        (conj (let [q (:filter-name-or-email-contains options)]
                                [:or (contains :a.email q) (contains :u.name q)]))

                        (:exclude-group-members options)
                        (conj [:not [:in :a.id {:select [:user_id]
                                                :from   [:user_group_members]
                                                :where  [:= :group_id (type-conversion/uuid->string (:exclude-group-members options))]}]]))

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
                                (update :role role->db)
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
                                 (update-present :active #(when (some? %) (protocols/boolean->db adapter %)))
                                 (update-present :mfa-backup-codes #(when % (cheshire.core/generate-string %)))
                                 (update-present :mfa-backup-codes-used #(when % (cheshire.core/generate-string %)))
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
                                    (update-present :tenant-id type-conversion/uuid->string)
                                    (update-present :role role->db)
                                    (update-present :notifications-email #(when (some? %) (protocols/boolean->db adapter %)))
                                    (update-present :notifications-push #(when (some? %) (protocols/boolean->db adapter %)))
                                    (update-present :notifications-sms #(when (some? %) (protocols/boolean->db adapter %)))
                                    (update-present :theme type-conversion/keyword->string)
                                    (update-present :date-format type-conversion/keyword->string)
                                    (update-present :time-format type-conversion/keyword->string)
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
                               (update-present :active #(when (some? %) (protocols/boolean->db adapter %)))
                               (update-present :mfa-backup-codes #(when % (cheshire.core/generate-string %)))
                               (update-present :mfa-backup-codes-used #(when % (cheshire.core/generate-string %)))
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
                                  (update-present :tenant-id type-conversion/uuid->string)
                                  (update-present :role role->db)
                                  (update-present :notifications-email #(when (some? %) (protocols/boolean->db adapter %)))
                                  (update-present :notifications-push #(when (some? %) (protocols/boolean->db adapter %)))
                                  (update-present :notifications-sms #(when (some? %) (protocols/boolean->db adapter %)))
                                  (update-present :theme type-conversion/keyword->string)
                                  (update-present :date-format type-conversion/keyword->string)
                                  (update-present :time-format type-conversion/keyword->string)
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

  (claim-login-attempts [_ user-id n lockout]
    (persistence-interceptors/execute-persistence-operation
     :claim-login-attempts
     {:user-id user-id :n n :lockout lockout}
     (fn [{:keys [params]}]
       ;; The UPDATE is the lockout check: it matches only an unlocked row, and
       ;; locks it until commit, so a parallel claim waits and then sees this
       ;; one. No RETURNING: MySQL has none. lockout_until is assigned first:
       ;; MySQL evaluates SET left to right, so later terms see earlier writes.
       (let [id   (type-conversion/uuid->string (:user-id params))
             next [:+ [:coalesce :failed_login_count 0] (:n params)]
             {:keys [max-attempts until now]} (:lockout params)]
         (db/with-transaction [tx ctx]
           (let [claimed? (pos? (db/execute-update!
                                 tx {:update :auth_users
                                     :set    (array-map
                                              :lockout_until      [:case [:>= next max-attempts]
                                                                   until
                                                                   :else :lockout_until]
                                              :failed_login_count next
                                              :updated_at         (java.time.Instant/now))
                                     :where  [:and [:= :id id]
                                              [:or [:= :lockout_until nil] [:<= :lockout_until now]]]}))
                 row      (db/execute-one! tx {:select [:failed_login_count :lockout_until]
                                               :from   [:auth_users]
                                               :where  [:= :id id]})]
             (when row
               (if claimed?
                 {:claimed? true :failed-login-count (:failed-login-count row)}
                 {:claimed? false
                  :lockout-until (type-conversion/string->instant (:lockout-until row))}))))))
     {:db-ctx ctx}))

  (release-login-attempt [_ user-id unlock?]
    (persistence-interceptors/execute-persistence-operation
     :release-login-attempt
     {:user-id user-id :unlock? unlock?}
     (fn [{:keys [params]}]
       (db/execute-update!
        ctx {:update :auth_users
             :set    (cond-> {:failed_login_count [:case [:> [:coalesce :failed_login_count 0] 0]
                                                   [:+ :failed_login_count -1]
                                                   :else 0]
                              :updated_at         (java.time.Instant/now)}
                       (:unlock? params) (assoc :lockout_until nil))
             :where  [:= :id (type-conversion/uuid->string (:user-id params))]})
       nil)
     {:db-ctx ctx}))

  (consume-backup-code [_ user code-hash]
    (persistence-interceptors/execute-persistence-operation
     :consume-backup-code
     {:user-id (:id user)}
     (fn [_]
       ;; Compare-and-set on the JSON both columns hold, written by the same
       ;; generate-string, so the update matches only if neither has changed.
       (let [{:keys [mfa-backup-codes mfa-backup-codes-used]} user
             json cheshire.core/generate-string]
         (pos? (db/execute-update!
                ctx {:update :auth_users
                     :set    {:mfa_backup_codes_used (json (conj (vec mfa-backup-codes-used) code-hash))
                              :updated_at            (java.time.Instant/now)}
                     :where  [:and
                              [:= :id (type-conversion/uuid->string (:id user))]
                              [:= :mfa_backup_codes (json mfa-backup-codes)]
                              (if (seq mfa-backup-codes-used)
                                [:= :mfa_backup_codes_used (json mfa-backup-codes-used)]
                                [:or [:is :mfa_backup_codes_used nil] [:= :mfa_backup_codes_used "[]"]])]}))))
     {:db-ctx ctx}))

  (with-admins-locked [_ f]
    ;; A no-op write rather than SELECT … FOR UPDATE: it locks the rows on
    ;; PostgreSQL, MySQL and H2 alike, and takes SQLite's write lock. A second
    ;; caller waits here, holding nothing, then counts what the first
    ;; committed. One table and no subquery: a subquery's shared locks
    ;; deadlocked two callers on MySQL. A write in `f` that opens its own
    ;; transaction joins this one rather than committing it early.
    (binding [jdbc-tx/*nested-tx* :ignore]
      (db/with-transaction [tx ctx]
        (db/execute-update! tx {:update :users :set {:role :role} :where [:= :role "admin"]})
        (f))))

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
                      :set {:deleted_at now
                            :updated_at now
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

                 ;; Sign-in links are personal data too.
                 _ (db/execute-update! tx {:delete-from :user_identities
                                           :where [:= :user_id user-id-str]})

                 ;; The key cascades, but SQLite enforces keys only on
                 ;; connections that ask.
                 _ (db/execute-update! tx {:delete-from :user_group_members
                                           :where [:= :user_id user-id-str]})

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
                            [:= :session_token (session-token-hash session-token)]
                            [:> :expires_at now]
                            [:is :revoked_at nil]]}
             result (db/execute-one! ctx query)]
         ;; A read only: validate-session decides whether the access is worth
         ;; a write (BOU-666).
         (some-> result db->session-entity)))
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
                            [:> :expires_at now]
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
                    :set {:revoked_at now}
                    :where [:and
                            [:= :session_token (session-token-hash session-token)]
                            [:is :revoked_at nil]]}
             affected-rows (db/execute-update! ctx query)]
         (> affected-rows 0)))
     {:db-ctx ctx}))

  (invalidate-user-session [_ user-id session-id]
    (persistence-interceptors/execute-persistence-operation
     :invalidate-user-session
     {:user-id user-id :session-id session-id}
     (fn [{:keys [params]}]
       (pos? (db/execute-update! ctx {:update :user_sessions
                                      :set    {:revoked_at (java.time.Instant/now)}
                                      :where  [:and
                                               [:= :id (type-conversion/uuid->string (:session-id params))]
                                               [:= :user_id (type-conversion/uuid->string (:user-id params))]
                                               [:is :revoked_at nil]]})))
     {:db-ctx ctx}))

  (invalidate-all-user-sessions [_ user-id]
    (persistence-interceptors/execute-persistence-operation
     :invalidate-all-user-sessions
     {:user-id user-id}
     (fn [{:keys [params]}]
       (let [user-id (:user-id params)
             now (java.time.Instant/now)
             query {:update :user_sessions
                    :set {:revoked_at now}
                    :where [:and
                            [:= :user_id (type-conversion/uuid->string user-id)]
                            [:is :revoked_at nil]]}
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
             id-match   [:= :id (type-conversion/uuid->string (:id session-entity))]
             ;; A session read as live must not overwrite a revocation that
             ;; landed since the read (BOU-666).
             query (if (:revoked-at session-entity)
                     {:update :user_sessions :set (dissoc db-session :id) :where id-match}
                     {:update :user_sessions
                      :set    (dissoc db-session :id :revoked_at)
                      :where  [:and id-match [:is :revoked_at nil]]})
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

  (delete-sessions-expired-before [_ cutoff]
    (persistence-interceptors/execute-persistence-operation
     :delete-sessions-expired-before
     {:cutoff cutoff}
     (fn [{:keys [params]}]
       ;; No :revoked_at predicate: a revoked session still holds a row, and it
       ;; is removed on the same terms as any other once it has expired.
       (let [query {:delete-from :user_sessions
                    :where [:< :expires_at
                            (:cutoff params)]}]
         (db/execute-update! ctx query)))
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
                     filter-target-email filter-actor-email after-id]
              count? :count? :or {count? true}} (:options params)
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
                             (conj (protocols/like (:adapter ctx) :target_user_email :contains filter-target-email))

                             filter-actor-email
                             (conj (protocols/like (:adapter ctx) :actor_email :contains filter-actor-email))

                             filter-created-after
                             (conj [:>= :created_at filter-created-after])

                             filter-created-before
                             (conj [:<= :created_at filter-created-before])

                             after-id
                             (conj [:> :id (type-conversion/uuid->string after-id)]))
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
             results (db/execute-query! ctx query)]
         (cond-> {:audit-logs (map db->audit-log-entity results)}
           count? (assoc :total-count (:count (db/execute-one! ctx count-query))))))
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

  (delete-audit-logs-before [_ cutoff]
    (persistence-interceptors/execute-persistence-operation
     :delete-audit-logs-before
     {:cutoff cutoff}
     (fn [{:keys [params]}]
       (db/delete-in-batches! ctx :user_audit_log
                              [:< :created_at (:cutoff params)]))
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
