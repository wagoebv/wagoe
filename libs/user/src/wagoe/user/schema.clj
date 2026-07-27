(ns wagoe.user.schema
  (:require
   [malli.core :as m]
   [malli.transform :as mt]
   [wagoe.core.utils.type-conversion :as type-conversion]
   [wagoe.core.utils.case-conversion :as case-conversion]))

;; =============================================================================
;; Domain Entity Schemas
;; =============================================================================

(def email-regex
  "Regular expression for basic email validation."
  #"^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9]+([.-][a-zA-Z0-9]+)*\.[a-zA-Z]{2,}$")

(def ^:private user-preferences-flat-fields
  "Shared flattened preference fields stored on the tenant profile (users table).

   NOTE: This is a sequence of *field definitions* (not a nested schema).
   It is spliced into both TenantUser (persisted profile) and User (merged domain entity)
   so that DDL generation (which expects a top-level [:map ...]) keeps working."
  [[:notifications-email {:optional true} :boolean]
   [:notifications-push {:optional true} :boolean]
   [:notifications-sms {:optional true} :boolean]
   [:theme {:optional true} [:enum :light :dark :auto]]
   [:language {:optional true} :string]
   [:timezone {:optional true} :string]])

(def ^:private user-date-time-preferences-flat-fields
  "Shared *preference* fields for date/time formatting.

   These belong to the tenant profile (TenantUser) and the merged User domain entity.
   They do NOT belong to AuthUser (public.auth_users)."
  [[:date-format {:optional true} [:enum :iso :us :eu]]
   [:time-format {:optional true} [:enum :12h :24h]]])

(def ^:private audit-timestamps-flat-fields
  "Shared audit timestamps.

   These exist on persisted entities (AuthUser, TenantUser) and the merged User domain entity."
  [[:created-at inst?]
   [:updated-at {:optional true} [:maybe inst?]]
   [:deleted-at {:optional true} [:maybe inst?]]])

(def AuthUser
  "Schema for AuthUser entity (public.auth_users table).

   Contains global authentication credentials shared across all tenants.
   Used for login verification, MFA, and account security.

   Part of schema-per-tenant multi-tenancy architecture (ADR-004):
   - Authentication data lives in public.auth_users (shared)
   - Profile data lives in tenant_<slug>.users (isolated)"
  (-> [:map {:title "Auth User"}
       [:id :uuid]
       [:email [:re {:error/message "Invalid email format"} email-regex]]
       [:password-hash :string]
       [:active :boolean]
       [:mfa-enabled {:optional true} :boolean]
       [:mfa-secret {:optional true} [:maybe :string]]
       [:mfa-backup-codes {:optional true} [:maybe [:vector :string]]]
       [:mfa-backup-codes-used {:optional true} [:maybe [:vector :string]]]
       [:mfa-enabled-at {:optional true} [:maybe inst?]]
       [:failed-login-count {:optional true} :int]
       [:lockout-until {:optional true} [:maybe inst?]]]
      (into audit-timestamps-flat-fields)))

(def TenantUser
  "Schema for TenantUser entity (tenant_<slug>.users table).
   
   Contains tenant-specific user profile and preferences.
   User can have different name/role/settings in different tenants.
   
   Part of schema-per-tenant multi-tenancy architecture (ADR-004):
   - Same user (id) can exist in multiple tenant schemas
   - Each tenant schema has isolated user profile data
   
   Note: Timestamps use :inst (java.time.Instant) internally."
  (-> [:map {:title "Tenant User"}
       [:id :uuid]
       [:tenant-id {:optional true} [:maybe :uuid]]
       [:name [:string {:min 1 :max 255}]]
       [:role [:enum :admin :user :viewer]]
       [:avatar-url {:optional true} :string]
       [:login-count {:optional true} :int]
       [:last-login {:optional true} inst?]]
      (into user-preferences-flat-fields)
      (into user-date-time-preferences-flat-fields)
      (into audit-timestamps-flat-fields)))

(def User
  "Schema for User entity (merged AuthUser + TenantUser).
   
   This is the unified user entity used in business logic.
   Combines authentication data (from public.auth_users) with 
   tenant-specific profile data (from tenant_<slug>.users).
   
   Persistence layer is responsible for:
   - Querying both tables and merging results
   - Splitting updates to correct table based on field
   - Maintaining referential integrity (auth_users.id = users.id)
   
   Note: Timestamps use :inst (java.time.Instant) internally. The infrastructure layer
   handles conversion to/from strings for database storage."
  (-> [:map {:title "User"}
       [:id :uuid]
       [:email [:re {:error/message "Invalid email format"} email-regex]]
       [:name [:string {:min 1 :max 255}]]
       [:password-hash {:optional true} [:string {:min 60 :max 60}]]
       [:role [:enum :admin :user :viewer]]
       [:active :boolean]
       [:tenant-id {:optional true} [:maybe :uuid]]
       [:send-welcome {:optional true} :boolean]
       [:login-count {:optional true} :int]
       [:last-login {:optional true} inst?]]
      (into user-preferences-flat-fields)
      (into user-date-time-preferences-flat-fields)
      (into [[:avatar-url {:optional true} :string]
             [:mfa-enabled {:optional true} :boolean]
             [:mfa-secret {:optional true} [:maybe :string]]
             [:mfa-backup-codes {:optional true} [:maybe [:vector :string]]]
             [:mfa-backup-codes-used {:optional true} [:maybe [:vector :string]]]
             [:mfa-enabled-at {:optional true} [:maybe inst?]]
             [:failed-login-count {:optional true} :int]
             [:lockout-until {:optional true} [:maybe inst?]]])
      (into audit-timestamps-flat-fields)))

(def UserSession
  "Schema for UserSession entity."
  [:map {:title "User Session"}
   [:id :uuid]
   [:user-id :uuid]
   [:session-token :string]
   [:expires-at inst?]
   [:created-at inst?]
   [:last-accessed-at {:optional true} [:maybe inst?]]
   [:revoked-at {:optional true} [:maybe inst?]]
   [:user-agent {:optional true} :string]
   [:ip-address {:optional true} :string]])

(def UserAuditLog
  "Schema for User Audit Log entity - tracks all user-related actions for compliance and security.
   
   Captures who did what, when, and what changed for full audit trail visibility."
  [:map {:title "User Audit Log"}
   [:id :uuid]
   [:action [:enum :create :update :delete :activate :deactivate :role-change :bulk-action :login :logout]]
   [:actor-id {:optional true} [:maybe :uuid]] ; User who performed the action (nil for system actions)
   [:actor-email {:optional true} [:maybe :string]] ; Email of actor for easy reference
   [:target-user-id :uuid] ; User who was affected by the action
   [:target-user-email :string] ; Email of affected user for easy reference
   [:changes {:optional true} [:maybe :map]] ; Map of changed fields: {:field "name" :old "John" :new "Jane"}
   [:metadata {:optional true} [:maybe :map]] ; Additional context: {:bulk-count 5, :reason "security"}
   [:ip-address {:optional true} [:maybe :string]]
   [:user-agent {:optional true} [:maybe :string]]
   [:result [:enum :success :failure]]
   [:error-message {:optional true} [:maybe :string]] ; Error details if result is :failure
   [:created-at inst?]])

;; =============================================================================
;; API Request Schemas
;; =============================================================================

(def CreateUserRequest
  "Schema for create user API requests."
  (-> [:map {:title "Create User Request"
             :closed true}
       [:email [:re {:error/message "Invalid email format"} email-regex]]
       [:name [:string {:min 1 :max 255}]]
       [:password [:string {:min 8 :max 255 :error/message "Password must be at least 8 characters"}]]
       [:role [:enum :admin :user :viewer]]
       [:active {:optional true} :boolean]
       [:send-welcome {:optional true} :boolean]]
      (into user-preferences-flat-fields)
      (into user-date-time-preferences-flat-fields)))

(def UpdateUserRequest
  "Schema for update user API requests."
  (-> [:map {:title "Update User Request"
             :closed true}
       [:name {:optional true} [:string {:min 1 :max 255}]]
       [:email {:optional true} [:re {:error/message "Invalid email format"} email-regex]]
       [:role {:optional true} [:enum :admin :user :viewer]]
       [:active {:optional true} :boolean]]
      (into user-preferences-flat-fields)
      (into user-date-time-preferences-flat-fields)))

(def LoginRequest
  "Schema for login API requests."
  [:map {:title "Login Request"
         :closed true}
   [:email [:re {:error/message "Invalid email format"} email-regex]]
   [:password [:string {:min 8 :max 255 :error/message "Password must be at least 8 characters"}]]
   [:remember {:optional true} :boolean]
   [:mfa-code {:optional true} [:maybe :string]] ; 6-digit TOTP code or backup code
   [:ip-address {:optional true} [:maybe :string]]
   [:user-agent {:optional true} [:maybe :string]]])

(def MFASetupRequest
  "Schema for MFA setup initiation."
  [:map {:title "MFA Setup Request"
         :closed true}
   [:user-id :uuid]])

(def MFAEnableRequest
  "Schema for enabling MFA with verification."
  [:map {:title "MFA Enable Request"
         :closed true}
   [:verification-code [:string {:min 6 :max 6 :error/message "Verification code must be 6 digits"}]]])

(def MFAVerifyRequest
  "Schema for MFA code verification."
  [:map {:title "MFA Verify Request"
         :closed true}
   [:code [:string {:min 6 :max 6 :error/message "MFA code must be 6 digits"}]]])

(def MFADisableRequest
  "Schema for disabling MFA."
  [:map {:title "MFA Disable Request"
         :closed true}
   [:password [:string {:min 8 :max 255 :error/message "Password required to disable MFA"}]]
   [:confirmation-code {:optional true} [:maybe :string]]]) ; Current MFA code for extra security

;; =============================================================================
;; API Response Schemas
;; =============================================================================

(def UserResponse
  "Schema for user responses (camelCase for API compatibility)."
  [:map {:title "User Response"}
   [:id :string] ; UUID as string
   [:email :string]
   [:name :string]
   [:role :string] ; Enum as string
   [:active :boolean]
   [:loginCount {:optional true} :int]
   [:lastLogin {:optional true} :string] ; Instant as ISO string
   ;; Preferences (flattened into user entity)
   [:notificationsEmail {:optional true} :boolean]
   [:notificationsPush {:optional true} :boolean]
   [:notificationsSms {:optional true} :boolean]
   [:theme {:optional true} :string]
   [:language {:optional true} :string]
   [:timezone {:optional true} :string]
   [:dateFormat {:optional true} :string] ; Enum as string
   [:timeFormat {:optional true} :string] ; Enum as string
   [:avatarUrl {:optional true} :string]
   [:mfaEnabled {:optional true} :boolean]
   [:createdAt :string] ; Instant as ISO string
   [:updatedAt {:optional true} :string]]) ; Instant as ISO string

(def MFASetupResponse
  "Schema for MFA setup response."
  [:map {:title "MFA Setup Response"}
   [:secret :string] ; Base32-encoded TOTP secret
   [:qrCodeUrl :string] ; Data URL for QR code
   [:backupCodes [:vector :string]] ; List of backup codes
   [:issuer :string] ; Application name for authenticator app
   [:accountName :string]]) ; User identifier for authenticator app

(def MFAStatusResponse
  "Schema for MFA status response."
  [:map {:title "MFA Status Response"}
   [:enabled :boolean]
   [:enabledAt {:optional true} [:maybe :string]] ; ISO timestamp
   [:backupCodesRemaining {:optional true} :int]])

(def LoginResponse
  "Schema for login response."
  [:map {:title "Login Response"}
   [:success :boolean]
   [:requiresMfa {:optional true} :boolean] ; True if MFA verification needed
   [:sessionToken {:optional true} :string] ; Only provided after successful full auth
   [:user {:optional true} :map] ; User details after successful auth
   [:message {:optional true} :string]])

(def MFARequiredResponse
  "Response when MFA verification is required.
   Returned after successful password verification when user has MFA enabled."
  [:map {:title "MFA Required Response"}
   [:requiresMfa [:= true]]
   [:message :string]
   [:user [:map
           [:id :string]
           [:email :string]
           [:name :string]]]])

;; =============================================================================
;; User-Specific Transformation Functions
;; =============================================================================

(defn user-specific-camel->kebab
  "Transforms user-specific camelCase API keys to kebab-case internal keys."
  [value]
  (-> value
      case-conversion/camel-case->kebab-case-map
      (cond->
       (:send-welcome value) (assoc :send-welcome (:sendWelcome value))
       (:sendWelcome value) (dissoc :sendWelcome))))

(def ^:private sensitive-fields
  "Fields that should never be exposed in API responses."
  #{:password-hash :passwordHash
    :mfa-secret :mfaSecret
    :mfa-backup-codes :mfaBackupCodes})

(defn user-specific-kebab->camel
  "Transforms user-specific kebab-case internal keys to camelCase API keys.
   Also removes sensitive fields (password-hash, mfa-secret, mfa-backup-codes)
   and null values for cleaner API responses."
  [value]
  (-> value
      ;; First remove sensitive fields (check both kebab and camel case versions)
      (#(apply dissoc % sensitive-fields))
      case-conversion/kebab-case->camel-case-map
      (cond->
       (:id value) (assoc :id (type-conversion/uuid->string (:id value)))
       (:user-id value) (assoc :userId (type-conversion/uuid->string (:user-id value)))
       (:created-at value) (assoc :createdAt (type-conversion/instant->string (:created-at value)))
       (:updated-at value) (assoc :updatedAt (type-conversion/instant->string (:updated-at value)))
       (:expires-at value) (assoc :expiresAt (type-conversion/instant->string (:expires-at value)))
       (:last-accessed-at value) (assoc :lastAccessedAt (type-conversion/instant->string (:last-accessed-at value)))
       (:revoked-at value) (assoc :revokedAt (type-conversion/instant->string (:revoked-at value)))
       (:last-login value) (assoc :lastLogin (type-conversion/instant->string (:last-login value)))
       (:mfa-enabled-at value) (assoc :mfaEnabledAt (type-conversion/instant->string (:mfa-enabled-at value)))
       (:deleted-at value) (assoc :deletedAt (type-conversion/instant->string (:deleted-at value)))
       (:date-format value) (assoc :dateFormat (type-conversion/keyword->string (:date-format value)))
       (:time-format value) (assoc :timeFormat (type-conversion/keyword->string (:time-format value)))
       (:role value) (assoc :role (type-conversion/keyword->string (:role value)))
       (:theme value) (assoc :theme (type-conversion/keyword->string (:theme value))))
      ;; Additionally convert any remaining Instant objects that weren't handled above
      (#(reduce-kv (fn [acc k v]
                     (if (and v (instance? java.time.Instant v))
                       (assoc acc k (type-conversion/instant->string v))
                       acc))
                   % %))
      ;; Remove null values for cleaner API responses
      (#(into {} (remove (fn [[_ v]] (nil? v)) %)))))

;; =============================================================================
;; Schema Transformers
;; =============================================================================

(def user-request-transformer
  "Transforms external API data to internal domain format.

   NOTE: This transformer does NOT strip unknown keys. Request schemas should
   be :closed to strictly reject unknown keys."
  (mt/transformer
   mt/string-transformer
   {:name :user-request
    :transformers
    {:map {:compile (fn [_schema _options] user-specific-camel->kebab)}
     :enum {:compile (fn [_schema _options] type-conversion/string->enum)}
     :boolean {:compile (fn [_schema _options] type-conversion/string->boolean)}}}))

(def user-response-transformer
  "Transforms internal domain data to external API format."
  (mt/transformer
   {:name :user-response
    :transformers
    {:map {:compile (fn [_schema _options] user-specific-kebab->camel)}
     :uuid {:compile (fn [_schema _options] type-conversion/uuid->string)}
     :inst {:compile (fn [_schema _options] type-conversion/instant->string)}
     :enum {:compile (fn [_schema _options] type-conversion/keyword->string)}}}))

(def ^:private user-validator (m/validator User))
(def ^:private user-explainer (m/explainer User))

(defn validate-user
  "Validates a user entity against the User schema."
  [user-data]
  (user-validator user-data))

(defn explain-user
  "Provides detailed validation errors for user data."
  [user-data]
  (user-explainer user-data))

;; =============================================================================
;; Schema Registry
;; =============================================================================

(def schema-registry
  "Registry of all user module schemas for easy access."
  {:domain-entities
   {:user User
    :user-session UserSession
    :user-audit-log UserAuditLog}})

(defn get-schema
  "Retrieves a schema from the registry by category and name."
  [category schema-name]
  (get-in schema-registry [category schema-name]))
