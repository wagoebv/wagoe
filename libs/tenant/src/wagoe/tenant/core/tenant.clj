(ns wagoe.tenant.core.tenant
  (:require
   [clojure.string :as str]))

(defn valid-slug?
  "Check if slug is a valid tenant identifier (lowercase alphanumeric with hyphens, 2-100 chars)."
  [slug]
  (boolean
   (and
    (string? slug)
    (re-matches #"^[a-z0-9][a-z0-9-]{0,98}[a-z0-9]$" slug))))

(defn slug->schema-name
  "Convert tenant slug to database schema name (hyphens become underscores, prefixed with tenant_)."
  [slug]
  (str "tenant_" (str/replace slug "-" "_")))

(defn valid-schema-name?
  "True if schema-name is a safe tenant schema identifier — exactly the shape
   slug->schema-name produces: a `tenant_` prefix followed by lowercase
   alphanumerics and underscores, within PostgreSQL's 63-char identifier limit.

   SQL identifiers cannot be parameterized, so schema names are interpolated
   into DDL. This predicate is the defense-in-depth guard callers apply at the
   DDL boundary before any interpolation."
  [schema-name]
  (boolean
   (and (string? schema-name)
        (<= (count schema-name) 63)
        (re-matches #"^tenant_[a-z0-9_]+$" schema-name))))

(defn prepare-tenant
  "Build a complete tenant entity from creation input, id, and timestamp."
  [{:keys [slug name settings]} tenant-id now]
  (let [schema-name (slug->schema-name slug)]
    {:id tenant-id
     :slug slug
     :name name
     :schema-name schema-name
     :status :active
     :settings settings
     :created-at now
     :updated-at now
     :deleted-at nil}))

(defn create-tenant-decision
  "Decide whether a tenant with the given slug can be created. Returns {:valid? bool}."
  [slug existing-slugs]
  (cond
    (not (valid-slug? slug))
    {:valid? false
     :error "Invalid tenant slug (must be lowercase alphanumeric with hyphens, 2-100 chars)"}

    (contains? existing-slugs slug)
    {:valid? false
     :error "Tenant slug already exists"}

    :else
    {:valid? true
     :schema-name (slug->schema-name slug)}))

(defn update-tenant-decision
  "Decide whether the proposed update-data is valid for existing-tenant."
  [existing-tenant update-data]
  (cond
    (nil? existing-tenant)
    {:valid? false
     :error "Tenant not found"}

    (and (:status update-data)
         (not (#{:active :suspended :deleted} (:status update-data))))
    {:valid? false
     :error "Invalid status"}

    :else
    {:valid? true
     :changes update-data}))

(defn prepare-tenant-update
  "Merge approved update-data into existing-tenant with a fresh :updated-at."
  [existing-tenant update-data now]
  (-> existing-tenant
      (merge (select-keys update-data [:name :status :settings]))
      (assoc :updated-at now)))

(defn tenant-deleted?
  "True if tenant status is :deleted or :deleted-at is set."
  [tenant]
  (or (= :deleted (:status tenant))
      (some? (:deleted-at tenant))))

(defn tenant-active?
  "True if tenant is :active and not soft-deleted."
  [tenant]
  (and (= :active (:status tenant))
       (nil? (:deleted-at tenant))))

(defn tenant-suspended?
  "True if tenant status is :suspended."
  [tenant]
  (= :suspended (:status tenant)))

(defn can-delete-tenant?
  "True if tenant is not already deleted."
  [tenant]
  (not (tenant-deleted? tenant)))

(defn prepare-tenant-deletion
  "Mark tenant as deleted with :status :deleted and :deleted-at timestamp."
  [tenant now]
  (assoc tenant
         :status :deleted
         :deleted-at now
         :updated-at now))
