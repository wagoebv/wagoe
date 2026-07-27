(ns wagoe.tenant.schema
  (:require
   [malli.core :as m]
   [malli.transform :as mt]
   [wagoe.core.utils.type-conversion :as type-conversion]
   [wagoe.core.utils.case-conversion :as case-conversion]))

;; =============================================================================
;; Domain Entity Schemas
;; =============================================================================

(def slug-regex
  "Regular expression for valid tenant slugs.
   Must be lowercase alphanumeric with hyphens, 2-100 chars."
  #"^[a-z0-9][a-z0-9-]{0,98}[a-z0-9]$")

(def Tenant
  "Schema for Tenant entity.
   
   Represents a tenant in the multi-tenant system. Each tenant gets:
   - A unique slug (URL-friendly identifier)
   - A dedicated PostgreSQL schema for data isolation
   - Configuration settings
   
   Note: Timestamps use :inst (java.time.Instant) internally."
  [:map {:title "Tenant"}
   [:id :uuid]
   [:slug [:re {:error/message "Invalid slug (must be lowercase alphanumeric with hyphens, 2-100 chars)"}
           slug-regex]]
   [:name [:string {:min 1 :max 255}]]
   [:schema-name [:string {:min 1 :max 63}]] ; PostgreSQL schema name limit
   [:status [:enum :active :suspended :deleted]]
   [:settings {:optional true} [:maybe :map]] ; JSON configuration
   [:created-at inst?]
   [:updated-at {:optional true} [:maybe inst?]]
   [:deleted-at {:optional true} [:maybe inst?]]])

(def TenantSettings
  "Schema for tenant configuration settings."
  [:map {:title "Tenant Settings"}
   [:features
    {:optional true}
    [:map
     [:mfa-enabled {:optional true} :boolean]
     [:api-enabled {:optional true} :boolean]
     [:webhooks-enabled {:optional true} :boolean]]]
   [:limits
    {:optional true}
    [:map
     [:max-users {:optional true} :int]
     [:max-storage-mb {:optional true} :int]
     [:max-api-calls-per-day {:optional true} :int]]]
   [:branding
    {:optional true}
    [:map
     [:logo-url {:optional true} :string]
     [:primary-color {:optional true} :string]
     [:custom-domain {:optional true} :string]]]])

;; =============================================================================
;; Service Layer Schemas (For internal use)
;; =============================================================================

(def TenantInput
  "Schema for creating a new tenant (service layer input).
   Used by create-new-tenant in service layer."
  [:map {:title "Tenant Input"}
   [:slug [:re {:error/message "Invalid slug"} slug-regex]]
   [:name [:string {:min 1 :max 255}]]
   [:status {:optional true} [:enum :active :suspended]]
   [:settings {:optional true} TenantSettings]])

(def TenantUpdate
  "Schema for updating an existing tenant (service layer input).
   Used by update-existing-tenant in service layer."
  [:map {:title "Tenant Update"}
   [:name {:optional true} [:string {:min 1 :max 255}]]
   [:slug {:optional true} [:re {:error/message "Invalid slug"} slug-regex]]
   [:status {:optional true} [:enum :active :suspended :deleted]]
   [:settings {:optional true} TenantSettings]])

;; =============================================================================
;; API Request Schemas
;; =============================================================================

(def CreateTenantRequest
  "Schema for create tenant API requests."
  [:map {:title "Create Tenant Request"}
   [:slug [:re {:error/message "Invalid slug"} slug-regex]]
   [:name [:string {:min 1 :max 255}]]
   [:settings {:optional true} TenantSettings]])

(def UpdateTenantRequest
  "Schema for update tenant API requests."
  [:map {:title "Update Tenant Request"}
   [:name {:optional true} [:string {:min 1 :max 255}]]
   [:status {:optional true} [:enum :active :suspended :deleted]]
   [:settings {:optional true} TenantSettings]])

;; =============================================================================
;; API Response Schemas
;; =============================================================================

(def TenantResponse
  "Schema for tenant responses (camelCase for API compatibility)."
  [:map {:title "Tenant Response"}
   [:id :string] ; UUID as string
   [:slug :string]
   [:name :string]
   [:schemaName :string]
   [:status :string] ; Enum as string
   [:settings {:optional true} :map]
   [:createdAt :string] ; Instant as ISO string
   [:updatedAt {:optional true} :string]]) ; Instant as ISO string

;; =============================================================================
;; Tenant-Specific Transformation Functions
;; =============================================================================

(defn tenant-specific-camel->kebab
  "Transforms tenant-specific camelCase API keys to kebab-case internal keys."
  [value]
  (-> value
      case-conversion/camel-case->kebab-case-map
      (cond->
       (:schema-name value) (assoc :schema-name (:schemaName value))
       (:schemaName value) (dissoc :schemaName))))

(defn tenant-specific-kebab->camel
  "Transforms tenant-specific kebab-case internal keys to camelCase API keys.
   Removes null values for cleaner API responses."
  [value]
  (-> value
      case-conversion/kebab-case->camel-case-map
      (cond->
       (:id value) (assoc :id (type-conversion/uuid->string (:id value)))
       (:created-at value) (assoc :createdAt (type-conversion/instant->string (:created-at value)))
       (:updated-at value) (assoc :updatedAt (type-conversion/instant->string (:updated-at value)))
       (:deleted-at value) (assoc :deletedAt (type-conversion/instant->string (:deleted-at value)))
       (:schema-name value) (assoc :schemaName (:schema-name value))
       (:status value) (assoc :status (type-conversion/keyword->string (:status value))))
      ;; Remove null values for cleaner API responses
      (#(into {} (remove (fn [[_ v]] (nil? v)) %)))))

;; =============================================================================
;; Schema Transformers
;; =============================================================================

(def tenant-request-transformer
  "Transforms external API data to internal domain format."
  (mt/transformer
   mt/strip-extra-keys-transformer
   mt/string-transformer
   {:name :tenant-request
    :transformers
    {:map {:compile (fn [_schema _options] tenant-specific-camel->kebab)}
     :enum {:compile (fn [_schema _options] type-conversion/string->enum)}
     :boolean {:compile (fn [_schema _options] type-conversion/string->boolean)}}}))

(def tenant-response-transformer
  "Transforms internal domain data to external API format."
  (mt/transformer
   {:name :tenant-response
    :transformers
    {:map {:compile (fn [_schema _options] tenant-specific-kebab->camel)}
     :uuid {:compile (fn [_schema _options] type-conversion/uuid->string)}
     :inst {:compile (fn [_schema _options] type-conversion/instant->string)}
     :enum {:compile (fn [_schema _options] type-conversion/keyword->string)}}}))

(def ^:private tenant-validator (m/validator Tenant))
(def ^:private tenant-explainer (m/explainer Tenant))

(defn validate-tenant
  "Validates a tenant entity against the Tenant schema."
  [tenant-data]
  (tenant-validator tenant-data))

(defn explain-tenant
  "Provides detailed validation errors for tenant data."
  [tenant-data]
  (tenant-explainer tenant-data))

;; =============================================================================
;; Membership Schemas
;; =============================================================================

(def TenantMembership
  [:map {:title "TenantMembership"}
   [:id :uuid]
   [:tenant-id :uuid]
   [:user-id :uuid]
   [:role [:enum :admin :member :viewer :contractor]]
   [:status [:enum :invited :active :suspended :revoked]]
   [:invited-at inst?]
   [:accepted-at {:optional true} [:maybe inst?]]
   [:created-at inst?]
   [:updated-at {:optional true} [:maybe inst?]]])

(def CreateMembershipRequest
  [:map {:title "Create Membership Request"}
   [:user-id :uuid]
   [:role [:enum :admin :member :viewer :contractor]]])

(def UpdateMembershipRequest
  [:map {:title "Update Membership Request"}
   [:role {:optional true} [:enum :admin :member :viewer :contractor]]
   [:status {:optional true} [:enum :suspended :revoked]]])

(def MembershipResponse
  "Schema for membership responses (camelCase for API compatibility)."
  [:map {:title "Membership Response"}
   [:id :string]
   [:tenantId :string]
   [:userId :string]
   [:role :string]
   [:status :string]
   [:invitedAt :string]
   [:acceptedAt {:optional true} [:maybe :string]]
   [:createdAt :string]
   [:updatedAt {:optional true} [:maybe :string]]])

;; =============================================================================
;; Membership-Specific Transformation Functions
;; =============================================================================

(defn membership-specific-kebab->camel
  "Transforms membership-specific kebab-case internal keys to camelCase API keys.
   Removes null values for cleaner API responses."
  [value]
  (-> value
      case-conversion/kebab-case->camel-case-map
      (cond->
       (:id value) (assoc :id (type-conversion/uuid->string (:id value)))
       (:tenant-id value) (assoc :tenantId (type-conversion/uuid->string (:tenant-id value)))
       (:user-id value) (assoc :userId (type-conversion/uuid->string (:user-id value)))
       (:role value) (assoc :role (type-conversion/keyword->string (:role value)))
       (:status value) (assoc :status (type-conversion/keyword->string (:status value)))
       (:invited-at value) (assoc :invitedAt (type-conversion/instant->string (:invited-at value)))
       (:accepted-at value) (assoc :acceptedAt (type-conversion/instant->string (:accepted-at value)))
       (:created-at value) (assoc :createdAt (type-conversion/instant->string (:created-at value)))
       (:updated-at value) (assoc :updatedAt (type-conversion/instant->string (:updated-at value))))
      (#(into {} (remove (fn [[_ v]] (nil? v)) %)))))

(def membership-response-transformer
  "Transforms internal membership data to external API format."
  (mt/transformer
   {:name :membership-response
    :transformers
    {:map {:compile (fn [_schema _options] membership-specific-kebab->camel)}
     :uuid {:compile (fn [_schema _options] type-conversion/uuid->string)}
     :inst {:compile (fn [_schema _options] type-conversion/instant->string)}
     :enum {:compile (fn [_schema _options] type-conversion/keyword->string)}}}))

;; =============================================================================
;; Invite Schemas
;; =============================================================================

(def TenantInvite
  [:map {:title "TenantInvite"}
   [:id :uuid]
   [:tenant-id :uuid]
   [:email [:string {:min 3 :max 320}]]
   [:role [:enum :admin :member :viewer :contractor]]
   [:status [:enum :pending :accepted :revoked]]
   [:token-hash [:string {:min 32 :max 255}]]
   [:expires-at inst?]
   [:accepted-at {:optional true} [:maybe inst?]]
   [:revoked-at {:optional true} [:maybe inst?]]
   [:accepted-by-user-id {:optional true} [:maybe :uuid]]
   [:metadata {:optional true} [:maybe :map]]
   [:created-at inst?]
   [:updated-at {:optional true} [:maybe inst?]]])

(def CreateInviteRequest
  [:map {:title "Create Invite Request"}
   [:email :string]
   [:role [:enum :admin :member :viewer :contractor]]
   [:expires-at {:optional true} [:maybe inst?]]
   [:metadata {:optional true} [:maybe :map]]])

(def InviteResponse
  [:map {:title "Invite Response"}
   [:id :string]
   [:tenantId :string]
   [:email :string]
   [:role :string]
   [:status :string]
   [:expiresAt :string]
   [:acceptedAt {:optional true} [:maybe :string]]
   [:revokedAt {:optional true} [:maybe :string]]
   [:acceptedByUserId {:optional true} [:maybe :string]]
   [:metadata {:optional true} :map]
   [:createdAt :string]
   [:updatedAt {:optional true} [:maybe :string]]])

(defn invite-specific-kebab->camel
  "Transforms invite-specific kebab-case internal keys to camelCase API keys."
  [value]
  (-> value
      case-conversion/kebab-case->camel-case-map
      (cond->
       (:id value) (assoc :id (type-conversion/uuid->string (:id value)))
       (:tenant-id value) (assoc :tenantId (type-conversion/uuid->string (:tenant-id value)))
       (:role value) (assoc :role (type-conversion/keyword->string (:role value)))
       (:status value) (assoc :status (type-conversion/keyword->string (:status value)))
       (:expires-at value) (assoc :expiresAt (type-conversion/instant->string (:expires-at value)))
       (:accepted-at value) (assoc :acceptedAt (type-conversion/instant->string (:accepted-at value)))
       (:revoked-at value) (assoc :revokedAt (type-conversion/instant->string (:revoked-at value)))
       (:accepted-by-user-id value) (assoc :acceptedByUserId (type-conversion/uuid->string (:accepted-by-user-id value)))
       (:created-at value) (assoc :createdAt (type-conversion/instant->string (:created-at value)))
       (:updated-at value) (assoc :updatedAt (type-conversion/instant->string (:updated-at value))))
      (#(into {} (remove (fn [[_ v]] (nil? v)) %)))))

(def invite-response-transformer
  (mt/transformer
   {:name :invite-response
    :transformers
    {:map {:compile (fn [_schema _options] invite-specific-kebab->camel)}
     :uuid {:compile (fn [_schema _options] type-conversion/uuid->string)}
     :inst {:compile (fn [_schema _options] type-conversion/instant->string)}
     :enum {:compile (fn [_schema _options] type-conversion/keyword->string)}}}))

;; =============================================================================
;; Schema Registry
;; =============================================================================

(def schema-registry
  "Registry of all tenant module schemas for easy access."
  {:domain-entities
   {:tenant Tenant
    :tenant-settings TenantSettings
    :tenant-membership TenantMembership
    :tenant-invite TenantInvite}
   :api-requests
   {:create-tenant CreateTenantRequest
    :update-tenant UpdateTenantRequest
    :create-membership CreateMembershipRequest
    :update-membership UpdateMembershipRequest
    :create-invite CreateInviteRequest}
   :api-responses
   {:tenant TenantResponse
    :membership MembershipResponse
    :invite InviteResponse}})

(defn get-schema
  "Retrieves a schema from the registry by category and name."
  [category schema-name]
  (get-in schema-registry [category schema-name]))
