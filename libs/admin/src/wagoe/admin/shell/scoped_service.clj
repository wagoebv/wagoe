(ns wagoe.admin.shell.scoped-service
  "The admin service as one user may use it under `:enforce` (ADR-041 §6):
   lists, counts and overviews show only rows inside their scopes; a record
   outside them is not found; a write that would leave a record outside them
   is refused. Built per request around the shared service, so every list the
   handlers ask for, a foreign key's options included, is scoped."
  (:require [clojure.string :as str]
            [wagoe.core.http.access :as access]
            [wagoe.admin.core.display :as display]
            [wagoe.admin.core.permissions :as permissions]
            [wagoe.admin.ports :as ports]
            [wagoe.admin.shell.service :as service]
            [wagoe.platform.core.scope :as scope]))

(defn- narrow
  "`scope` kept to tenant `t`, or nil when it holds none of it."
  [scope t]
  (let [ts (if-let [v (:tenant-id scope)] (set (filter #(access/same-id? % t) v)) #{(str t)})]
    (when (seq ts) (assoc scope :tenant-id ts))))

(defn- in-tenant
  "`scopes` kept to tenant `t`, for an entity that maps `:tenant-id` under `:scope` (BOU-954)."
  [scopes t entity-config]
  (if (and t (contains? (:scope entity-config) :tenant-id))
    (into [] (keep #(narrow % t)) scopes)
    scopes))

(defn- scopes-of
  "Where `user` may take `action`: their rights' scopes under `:enforce`, else
   everywhere, kept to the tenant they are bound to."
  [schema-provider user entity-name action]
  (let [entity-config (ports/get-entity-config schema-provider entity-name)]
    (in-tenant (if (= :enforce (permissions/mode user))
                 (permissions/scopes user entity-name action entity-config)
                 [{}])
               (::tenant-id user) entity-config)))

(defn- scoped
  "`options` keeping a list to the rows `user` may take `action` on; as they
   are when they may everywhere."
  [schema-provider user entity-name action options]
  (let [scopes (scopes-of schema-provider user entity-name action)]
    (cond-> options
      (not (scope/everywhere? scopes)) (assoc :scopes scopes))))

(defn- inside!
  "Throw :not-found unless every one of `ids` lies inside `user`'s scopes for
   `action`, so a record outside them does not leak its existence. The records
   when that took a query; nil when the scopes hold everywhere."
  [inner schema-provider user entity-name action ids]
  (let [options (scoped schema-provider user entity-name action {:ids ids :limit (max 1 (count ids))})]
    (when (contains? options :scopes)
      (let [{:keys [records total-count]} (ports/list-entities inner entity-name options)]
        (when (not= (count (distinct ids)) total-count)
          (throw (ex-info "Entity not found" {:type :not-found :entity-name entity-name})))
        records))))

(defn- writable!
  "Throw a :validation-error on the scope fields unless `record` (or a delay
   of it), as a write would leave it, lies inside `user`'s write scopes."
  [schema-provider user entity-name record]
  (let [entity-config (ports/get-entity-config schema-provider entity-name)
        scopes        (scopes-of schema-provider user entity-name :write)
        message       "Outside the scope you may change"]
    (when-not (or (scope/everywhere? scopes)
                  (permissions/record-in-scope? scopes entity-config (force record)))
      (throw (ex-info message
                      {:type   :validation-error
                       :entity entity-name
                       :errors (into {} (map (fn [field] [field [message]]))
                                     (vals (permissions/scope-fields entity-config)))})))))

(defn- stored
  "The record `id` as stored, once `inside!` admits it for writing: a delay,
   read only when the write's scopes need it."
  [inner schema-provider user entity-name id]
  (let [records (inside! inner schema-provider user entity-name :write [id])]
    (delay (or (first records) (ports/get-entity inner entity-name id)))))

(defn- link-scope
  "The scopes in which `user` may read what relation `rel` points to; nil
   when everywhere."
  [schema-provider user rel]
  (let [scopes (scopes-of schema-provider user (:entity rel) :read)]
    (when-not (scope/everywhere? scopes) scopes)))

(defn- link-scopes
  "{relation-key scopes} for each many-to-many of `entity-name` whose linked
   records `user` may read only inside scopes."
  [schema-provider user entity-name]
  (into {}
        (keep (fn [rel] (some->> (link-scope schema-provider user rel) (vector (:key rel)))))
        (:many-to-many (ports/get-entity-config schema-provider entity-name))))

(defn- title-scopes
  "{field scopes} for each belongs-to of `entity-name` whose target `user` may
   read only inside scopes: the title of a target outside them stays unshown."
  [schema-provider user entity-name]
  (into {}
        (keep (fn [[field rel]]
                (when (ports/validate-entity-exists schema-provider (:entity rel))
                  (some->> (link-scope schema-provider user rel) (vector field)))))
        (display/belongs-to-fields (ports/get-entity-config schema-provider entity-name))))

(defn- canonical
  "`data` with each plain key as the inner service writes it: `:tenant_id` and
   `:tenant-id` name one column, so the checks read what is written (BOU-997)."
  [data]
  (into {} (map (fn [[k v]] [(if (namespace k) k (keyword (str/replace (name k) "_" "-"))) v])) data))

(defn- targets-readable!
  "Throw a :validation-error on the field unless each belongs-to value in `data`
   that differs from `before` names a record `user` may read: a foreign key
   into another tenant (BOU-997). A target the admin does not expose is not checked."
  [inner schema-provider user entity-name data & [before]]
  ;; ponytail: one query per field and row; batch per field if wide nested creates get slow
  (doseq [[field rel] (display/belongs-to-fields (ports/get-entity-config schema-provider entity-name))
          :let [id (get data field)]
          :when (and (some? id)
                     (not (and before (access/same-id? id (get @before field))))
                     (ports/validate-entity-exists schema-provider (:entity rel)))]
    (try (inside! inner schema-provider user (:entity rel) :read [id])
         (catch clojure.lang.ExceptionInfo e
           (if (= :not-found (:type (ex-data e)))
             (throw (ex-info (str "No such record: " (name field))
                             {:type :validation-error :entity entity-name :field field
                              :errors {field ["No such record"]}}
                             e))
             (throw e))))))

(defn- with-link-scopes
  "`data` with the scopes its `:admin/links` must keep to: a link to a record
   the user may not read is neither added nor removed."
  [schema-provider user entity-name data]
  (cond-> data
    (:admin/links data) (assoc :admin/link-scopes (link-scopes schema-provider user entity-name))))

(defrecord ScopedAdminService [inner schema-provider user]
  ports/IListOverview
  (list-overview [_ entity-name options records]
    (when (satisfies? ports/IListOverview inner)
      (ports/list-overview inner entity-name
                           (assoc (scoped schema-provider user entity-name :read options)
                                  :overview/link-scopes (link-scopes schema-provider user entity-name)
                                  :overview/title-scopes (title-scopes schema-provider user entity-name))
                           records)))

  ports/ILinks
  (linked-records [_ entity-name rel-key ids opts]
    (if (satisfies? ports/ILinks inner)
      (let [rel    (display/link-rel (ports/get-entity-config schema-provider entity-name) rel-key)
            scopes (some->> rel (link-scope schema-provider user))]
        (ports/linked-records inner entity-name rel-key ids (cond-> opts scopes (assoc :scopes scopes))))
      {}))

  ports/IAdminService
  (list-entities [_ entity-name options]
    (ports/list-entities inner entity-name (scoped schema-provider user entity-name :read options)))

  (count-entities [_ entity-name filters]
    (let [options (scoped schema-provider user entity-name :read {:filters filters :limit 1})]
      (if (contains? options :scopes)
        (:total-count (ports/list-entities inner entity-name options))
        (ports/count-entities inner entity-name filters))))

  ;; nil outside the scopes, as for a missing record: the port's contract
  (get-entity [_ entity-name id]
    (let [options (scoped schema-provider user entity-name :read {:ids [id] :limit 1})]
      ;; The list only says whether it is inside: it reads list columns, and
      ;; a record has every field
      (when (or (not (contains? options :scopes))
                (seq (:records (ports/list-entities inner entity-name options))))
        (ports/get-entity inner entity-name id))))

  (validate-entity-data [_ entity-name data]
    (ports/validate-entity-data inner entity-name data))

  (list-related-entities [_ parent-entity-name parent-id relationship]
    ;; A parent's children show only where their own entity's read admits them
    (let [child   (:entity relationship)
          rows    (ports/list-related-entities inner parent-entity-name parent-id relationship)
          options (scoped schema-provider user child :read {:ids (mapv :id rows) :limit (max 1 (count rows))})]
      (if (and (seq rows) (contains? options :scopes))
        (let [inside (set (map (comp str :id) (:records (ports/list-entities inner child options))))]
          (filterv #(contains? inside (str (:id %))) rows))
        rows)))

  (create-entity [_ entity-name data]
    (let [data (canonical data)]
      (writable! schema-provider user entity-name data)
      (targets-readable! inner schema-provider user entity-name data)
      (ports/create-entity inner entity-name (with-link-scopes schema-provider user entity-name data))))

  (create-entity-with-children [_ entity-name data children]
    (let [data     (canonical data)
          children (update-vals children #(mapv canonical %))]
      (writable! schema-provider user entity-name data)
      (targets-readable! inner schema-provider user entity-name data)
      ;; Each row against its own entity's scopes: a child was created in any tenant (BOU-992).
      ;; A child the parent does not create is left to the inner service to refuse.
      (let [nested (set (map :entity (:has-many (ports/get-entity-config schema-provider entity-name))))]
        (doseq [[child rows] children
                :when (nested child)
                [index row] (map-indexed vector rows)]
          (service/with-child-index child index #(do (writable! schema-provider user child row)
                                                     (targets-readable! inner schema-provider user child row)))))
      (ports/create-entity-with-children inner entity-name (with-link-scopes schema-provider user entity-name data) children)))

  (update-entity [_ entity-name id data]
    (let [data   (canonical data)
          record (stored inner schema-provider user entity-name id)]
      (writable! schema-provider user entity-name (delay (merge @record data)))
      (targets-readable! inner schema-provider user entity-name data record)
      (ports/update-entity inner entity-name id (with-link-scopes schema-provider user entity-name data))))

  (update-entity-field [_ entity-name id field value]
    (let [record (stored inner schema-provider user entity-name id)]
      (writable! schema-provider user entity-name (delay (assoc @record field value)))
      (targets-readable! inner schema-provider user entity-name {field value} record))
    (ports/update-entity-field inner entity-name id field value))

  (delete-entity [_ entity-name id]
    (inside! inner schema-provider user entity-name :write [id])
    (ports/delete-entity inner entity-name id))

  (bulk-delete-entities [_ entity-name ids]
    (inside! inner schema-provider user entity-name :write ids)
    (ports/bulk-delete-entities inner entity-name ids)))

(defn for-user
  "`admin-service` as `user` may use it: scoped under `:enforce`, else as it is."
  [admin-service schema-provider user]
  (if (= :enforce (permissions/mode user))
    (->ScopedAdminService admin-service schema-provider user)
    admin-service))

(defn for-request
  "`admin-service` as `request`'s caller may use it: as `for-user`, and kept to
   one tenant's records when the caller is bound to one (BOU-954); nil when they
   may reach no tenant's."
  [admin-service schema-provider request]
  ;; Under :enforce their rights decide who is an admin; otherwise the role does
  (when-let [reach (if (= :enforce (permissions/mode (:user request)))
                     (access/tenant-reach request)
                     (access/admin-reach request))]
    (let [user (:user request)]
      (if-let [t (:tenant-id reach)]
        (->ScopedAdminService admin-service schema-provider (assoc user ::tenant-id t))
        (for-user admin-service schema-provider user)))))
