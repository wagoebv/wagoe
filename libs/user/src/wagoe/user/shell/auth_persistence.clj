(ns wagoe.user.shell.auth-persistence
  "Authentication persistence layer - manages public.auth_users table.
   
   This namespace handles persistence for global authentication credentials
   in the schema-per-tenant multi-tenancy architecture (ADR-004).
   
   Table: public.auth_users (shared across all tenants)
   Contains: email, password_hash, mfa_*, failed_login_count, lockout_until, active
   
   Usage:
   - Login authentication (verify email + password)
   - MFA operations (setup, enable, disable, verify)
   - Account security (lockout, failed login tracking)
   - Password management (reset, change)
   
   Does NOT contain:
   - User profile data (name, role, avatar) → in tenant_<slug>.users
   - Tenant-specific settings → in tenant_<slug>.users"
  (:require [wagoe.core.utils.type-conversion :as type-conversion]
            [wagoe.platform.shell.adapters.database.common.core :as db]
            [wagoe.platform.shell.adapters.database.protocols :as protocols]
            [wagoe.platform.shell.persistence-interceptors :as persistence-interceptors]
            [wagoe.user.shell.mfa-crypto :as mfa-crypto]
            [cheshire.core]
            [clojure.set])
  (:import [java.util UUID]))

(defprotocol IAuthUserRepository
  "Authentication user repository interface for public.auth_users table.
   
   Manages global authentication credentials shared across all tenants."

  (find-auth-user-by-id [this user-id]
    "Find authentication record by user ID.
     
     Args:
       user-id: UUID of the user
     
     Returns:
       AuthUser entity or nil if not found")

  (find-auth-user-by-email [this email]
    "Find authentication record by email (for login).
     
     Args:
       email: User's email address
     
     Returns:
       AuthUser entity or nil if not found")

  (create-auth-user [this auth-user-entity]
    "Create new authentication record.
     
     Args:
       auth-user-entity: AuthUser entity conforming to schema/AuthUser
     
     Returns:
       Created AuthUser entity with generated ID and timestamps")

  (update-auth-user [this auth-user-entity]
    "Update existing authentication record.
     
     Args:
       auth-user-entity: AuthUser entity with :id and fields to update
     
     Returns:
       Updated AuthUser entity or throws if not found")

  (update-password [this user-id password-hash]
    "Update user password hash.
     
     Args:
       user-id: UUID of the user
       password-hash: New bcrypt password hash
     
     Returns:
       true if updated, false if user not found")

  (increment-failed-login [this user-id]
    "Increment failed login count for user.
     
     Args:
       user-id: UUID of the user
     
     Returns:
       true if updated")

  (reset-failed-login [this user-id]
    "Reset failed login count to 0.
     
     Args:
       user-id: UUID of the user
     
     Returns:
       true if updated")

  (set-lockout [this user-id lockout-until]
    "Set account lockout until specified time.
     
     Args:
       user-id: UUID of the user
       lockout-until: Instant when lockout expires
     
     Returns:
       true if updated")

  (clear-lockout [this user-id]
    "Clear account lockout.
     
     Args:
       user-id: UUID of the user
     
     Returns:
       true if updated")

  (enable-mfa [this user-id mfa-secret mfa-backup-codes]
    "Enable MFA for user.
     
     Args:
       user-id: UUID of the user
       mfa-secret: TOTP secret (base32 encoded)
       mfa-backup-codes: Vector of backup codes
     
     Returns:
       true if updated")

  (disable-mfa [this user-id]
    "Disable MFA for user.
     
     Args:
       user-id: UUID of the user
     
     Returns:
       true if updated")

  (mark-backup-code-used [this user-id backup-code]
    "Mark a backup code as used.
     
     Args:
       user-id: UUID of the user
       backup-code: The backup code that was used
     
     Returns:
       true if updated")

  (soft-delete-auth-user [this user-id]
    "Soft delete authentication record.
     
     Args:
       user-id: UUID of the user
     
     Returns:
       true if deleted, false if not found")

  (hard-delete-auth-user [this user-id]
    "Hard delete authentication record (permanent).
     
     Args:
       user-id: UUID of the user
     
     Returns:
       true if deleted, false if not found"))

(defn- auth-user-entity->db
  "Transform AuthUser domain entity to database format."
  [ctx auth-user-entity]
  (let [adapter (:adapter ctx)]
    (-> auth-user-entity
        (update :id type-conversion/uuid->string)
        (update :active #(protocols/boolean->db adapter %))
        ;; Encrypt the reversible TOTP secret at the persistence boundary. Backup
        ;; codes are already bcrypt-hashed before they reach here.
        (update :mfa-secret #(when % (mfa-crypto/encrypt-secret %)))
        (update :mfa-backup-codes #(when % (cheshire.core/generate-string %)))
        (update :mfa-backup-codes-used #(when % (cheshire.core/generate-string %)))
        (clojure.set/rename-keys {:mfa-enabled :mfa_enabled
                                  :mfa-secret :mfa_secret
                                  :mfa-backup-codes :mfa_backup_codes
                                  :mfa-backup-codes-used :mfa_backup_codes_used
                                  :mfa-enabled-at :mfa_enabled_at
                                  :failed-login-count :failed_login_count
                                  :lockout-until :lockout_until
                                  :password-hash :password_hash
                                  :created-at :created_at
                                  :updated-at :updated_at
                                  :deleted-at :deleted_at}))))

(defn- db->auth-user-entity
  "Transform database record to AuthUser domain entity."
  [ctx db-record]
  (when db-record
    (let [adapter (:adapter ctx)]
      (-> db-record
          (clojure.set/rename-keys {:mfa_enabled :mfa-enabled
                                    :mfa_secret :mfa-secret
                                    :mfa_backup_codes :mfa-backup-codes
                                    :mfa_backup_codes_used :mfa-backup-codes-used
                                    :mfa_enabled_at :mfa-enabled-at
                                    :failed_login_count :failed-login-count
                                    :lockout_until :lockout-until
                                    :password_hash :password-hash
                                    :created_at :created-at
                                    :updated_at :updated-at
                                    :deleted_at :deleted-at})
          (update :id type-conversion/string->uuid)
          (update :active #(protocols/db->boolean adapter %))
          (update :created-at type-conversion/string->instant)
          (update :updated-at type-conversion/string->instant)
          (update :deleted-at type-conversion/string->instant)
          (update :mfa-enabled-at type-conversion/string->instant)
          (update :lockout-until type-conversion/string->instant)
          ;; Decrypt the TOTP secret. A legacy plaintext value decrypts to nil,
          ;; so pre-encryption MFA rows read as having no usable secret and must
          ;; be re-enrolled (they are also cleared by the invalidation migration).
          (update :mfa-secret mfa-crypto/decrypt-secret)
          (update :mfa-backup-codes #(when % (vec (cheshire.core/parse-string % true))))
          (update :mfa-backup-codes-used #(when % (vec (cheshire.core/parse-string % true))))))))

(defrecord DatabaseAuthUserRepository [ctx]
  IAuthUserRepository

  (find-auth-user-by-id [_ user-id]
    (persistence-interceptors/execute-persistence-operation
     :find-auth-user-by-id
     {:user-id user-id}
     (fn [{:keys [params]}]
       (let [user-id (:user-id params)
             query {:select [:*]
                    :from [:auth_users]
                    :where [:and
                            [:= :id (type-conversion/uuid->string user-id)]
                            [:is :deleted_at nil]]}
             result (db/execute-one! ctx query)]
         (db->auth-user-entity ctx result)))
     {:db-ctx ctx}))

  (find-auth-user-by-email [_ email]
    (persistence-interceptors/execute-persistence-operation
     :find-auth-user-by-email
     {:email email}
     (fn [{:keys [params]}]
       (let [email (:email params)
             query {:select [:*]
                    :from [:auth_users]
                    :where [:and
                            [:= :email email]
                            [:is :deleted_at nil]]}
             result (db/execute-one! ctx query)]
         (db->auth-user-entity ctx result)))
     {:db-ctx ctx}))

  (create-auth-user [_ auth-user-entity]
    (persistence-interceptors/execute-persistence-operation
     :create-auth-user
     {:auth-user-entity auth-user-entity}
     (fn [{:keys [params]}]
       (let [auth-user-entity (:auth-user-entity params)
             now (java.time.Instant/now)
             auth-user-with-metadata (-> auth-user-entity
                                         (assoc :id (or (:id auth-user-entity) (UUID/randomUUID)))
                                         (assoc :created-at now)
                                         (assoc :updated-at nil)
                                         (assoc :deleted-at nil))
             db-auth-user (auth-user-entity->db ctx auth-user-with-metadata)
             query {:insert-into :auth_users
                    :values [db-auth-user]}]
         (db/execute-update! ctx query)
         auth-user-with-metadata))
     {:db-ctx ctx}))

  (update-auth-user [_ auth-user-entity]
    (persistence-interceptors/execute-persistence-operation
     :update-auth-user
     {:auth-user-entity auth-user-entity}
     (fn [{:keys [params]}]
       (let [auth-user-entity (:auth-user-entity params)
             db-auth-user (auth-user-entity->db ctx auth-user-entity)
             query {:update :auth_users
                    :set (dissoc db-auth-user :id :created_at)
                    :where [:= :id (:id db-auth-user)]}
             affected-rows (db/execute-update! ctx query)]
         (if (> affected-rows 0)
           auth-user-entity
           (throw (ex-info "Auth user not found or update failed"
                           {:type :user-not-found
                            :user-id (:id auth-user-entity)})))))
     {:db-ctx ctx}))

  (update-password [_ user-id password-hash]
    (persistence-interceptors/execute-persistence-operation
     :update-password
     {:user-id user-id :password-hash password-hash}
     (fn [{:keys [params]}]
       (let [user-id (:user-id params)
             password-hash (:password-hash params)
             now (java.time.Instant/now)
             query {:update :auth_users
                    :set {:password_hash password-hash
                          :updated_at (type-conversion/instant->string now)}
                    :where [:and
                            [:= :id (type-conversion/uuid->string user-id)]
                            [:is :deleted_at nil]]}
             affected-rows (db/execute-update! ctx query)]
         (> affected-rows 0)))
     {:db-ctx ctx}))

  (increment-failed-login [_ user-id]
    (persistence-interceptors/execute-persistence-operation
     :increment-failed-login
     {:user-id user-id}
     (fn [{:keys [params]}]
       (let [user-id (:user-id params)
             now (java.time.Instant/now)
             query {:update :auth_users
                    :set {:failed_login_count [:+ :failed_login_count 1]
                          :updated_at (type-conversion/instant->string now)}
                    :where [:= :id (type-conversion/uuid->string user-id)]}
             affected-rows (db/execute-update! ctx query)]
         (> affected-rows 0)))
     {:db-ctx ctx}))

  (reset-failed-login [_ user-id]
    (persistence-interceptors/execute-persistence-operation
     :reset-failed-login
     {:user-id user-id}
     (fn [{:keys [params]}]
       (let [user-id (:user-id params)
             now (java.time.Instant/now)
             query {:update :auth_users
                    :set {:failed_login_count 0
                          :updated_at (type-conversion/instant->string now)}
                    :where [:= :id (type-conversion/uuid->string user-id)]}
             affected-rows (db/execute-update! ctx query)]
         (> affected-rows 0)))
     {:db-ctx ctx}))

  (set-lockout [_ user-id lockout-until]
    (persistence-interceptors/execute-persistence-operation
     :set-lockout
     {:user-id user-id :lockout-until lockout-until}
     (fn [{:keys [params]}]
       (let [user-id (:user-id params)
             lockout-until (:lockout-until params)
             now (java.time.Instant/now)
             query {:update :auth_users
                    :set {:lockout_until (type-conversion/instant->string lockout-until)
                          :updated_at (type-conversion/instant->string now)}
                    :where [:= :id (type-conversion/uuid->string user-id)]}
             affected-rows (db/execute-update! ctx query)]
         (> affected-rows 0)))
     {:db-ctx ctx}))

  (clear-lockout [_ user-id]
    (persistence-interceptors/execute-persistence-operation
     :clear-lockout
     {:user-id user-id}
     (fn [{:keys [params]}]
       (let [user-id (:user-id params)
             now (java.time.Instant/now)
             query {:update :auth_users
                    :set {:lockout_until nil
                          :failed_login_count 0
                          :updated_at (type-conversion/instant->string now)}
                    :where [:= :id (type-conversion/uuid->string user-id)]}
             affected-rows (db/execute-update! ctx query)]
         (> affected-rows 0)))
     {:db-ctx ctx}))

  (enable-mfa [_ user-id mfa-secret mfa-backup-codes]
    (persistence-interceptors/execute-persistence-operation
     :enable-mfa
     {:user-id user-id :mfa-secret mfa-secret :mfa-backup-codes mfa-backup-codes}
     (fn [{:keys [params]}]
       (let [user-id (:user-id params)
             mfa-secret (:mfa-secret params)
             mfa-backup-codes (:mfa-backup-codes params)
             now (java.time.Instant/now)
             adapter (:adapter ctx)
             ;; Encrypt the TOTP secret and bcrypt-hash the backup codes at rest
             ;; (this port takes plaintext), matching the update-user path.
             query {:update :auth_users
                    :set {:mfa_enabled (protocols/boolean->db adapter true)
                          :mfa_secret (mfa-crypto/encrypt-secret mfa-secret)
                          :mfa_backup_codes (cheshire.core/generate-string
                                             (mapv mfa-crypto/hash-backup-code mfa-backup-codes))
                          :mfa_backup_codes_used (cheshire.core/generate-string [])
                          :mfa_enabled_at (type-conversion/instant->string now)
                          :updated_at (type-conversion/instant->string now)}
                    :where [:= :id (type-conversion/uuid->string user-id)]}
             affected-rows (db/execute-update! ctx query)]
         (> affected-rows 0)))
     {:db-ctx ctx}))

  (disable-mfa [_ user-id]
    (persistence-interceptors/execute-persistence-operation
     :disable-mfa
     {:user-id user-id}
     (fn [{:keys [params]}]
       (let [user-id (:user-id params)
             now (java.time.Instant/now)
             adapter (:adapter ctx)
             query {:update :auth_users
                    :set {:mfa_enabled (protocols/boolean->db adapter false)
                          :mfa_secret nil
                          :mfa_backup_codes nil
                          :mfa_backup_codes_used nil
                          :mfa_enabled_at nil
                          :updated_at (type-conversion/instant->string now)}
                    :where [:= :id (type-conversion/uuid->string user-id)]}
             affected-rows (db/execute-update! ctx query)]
         (> affected-rows 0)))
     {:db-ctx ctx}))

  (mark-backup-code-used [_ user-id backup-code]
    (persistence-interceptors/execute-persistence-operation
     :mark-backup-code-used
     {:user-id user-id :backup-code backup-code}
     (fn [{:keys [params]}]
       (let [user-id (:user-id params)
             backup-code (:backup-code params)
             auth-user (find-auth-user-by-id _ user-id)
             used-codes (or (:mfa-backup-codes-used auth-user) [])
             updated-used-codes (conj used-codes backup-code)
             now (java.time.Instant/now)
             query {:update :auth_users
                    :set {:mfa_backup_codes_used (cheshire.core/generate-string updated-used-codes)
                          :updated_at (type-conversion/instant->string now)}
                    :where [:= :id (type-conversion/uuid->string user-id)]}
             affected-rows (db/execute-update! ctx query)]
         (> affected-rows 0)))
     {:db-ctx ctx}))

  (soft-delete-auth-user [_ user-id]
    (persistence-interceptors/execute-persistence-operation
     :soft-delete-auth-user
     {:user-id user-id}
     (fn [{:keys [params]}]
       (let [user-id (:user-id params)
             now (java.time.Instant/now)
             adapter (:adapter ctx)
             query {:update :auth_users
                    :set {:deleted_at (type-conversion/instant->string now)
                          :updated_at (type-conversion/instant->string now)
                          :active (protocols/boolean->db adapter false)}
                    :where [:and
                            [:= :id (type-conversion/uuid->string user-id)]
                            [:is :deleted_at nil]]}
             affected-rows (db/execute-update! ctx query)]
         (> affected-rows 0)))
     {:db-ctx ctx}))

  (hard-delete-auth-user [_ user-id]
    (persistence-interceptors/execute-persistence-operation
     :hard-delete-auth-user
     {:user-id user-id}
     (fn [{:keys [params]}]
       (let [user-id (:user-id params)
             query {:delete-from :auth_users
                    :where [:= :id (type-conversion/uuid->string user-id)]}
             affected-rows (db/execute-update! ctx query)]
         (> affected-rows 0)))
     {:db-ctx ctx})))

(defn create-auth-user-repository
  "Create an auth user repository instance using database storage.
   
   Args:
     ctx: Database context from wagoe.platform.shell.adapters.database.factory
   
   Returns:
     DatabaseAuthUserRepository implementing IAuthUserRepository"
  [ctx]
  (->DatabaseAuthUserRepository ctx))
