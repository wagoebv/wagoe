(ns wagoe.admin.shell.scoped-service
  "The admin service as one user may use it under `:enforce` (ADR-041 §6):
   lists, counts and overviews show only rows inside their scopes; a record
   outside them is not found; a write that would leave a record outside them
   is refused. Built per request around the shared service, so every list the
   handlers ask for, a foreign key's options included, is scoped."
  (:require [wagoe.admin.core.permissions :as permissions]
            [wagoe.admin.ports :as ports]
            [wagoe.platform.core.scope :as scope]))

(defn- scopes-of [schema-provider user entity-name action]
  (permissions/scopes user entity-name action (ports/get-entity-config schema-provider entity-name)))

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
        scopes        (permissions/scopes user entity-name :write entity-config)
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

(defrecord ScopedAdminService [inner schema-provider user]
  ports/IListOverview
  (list-overview [_ entity-name options records]
    (when (satisfies? ports/IListOverview inner)
      (ports/list-overview inner entity-name (scoped schema-provider user entity-name :read options) records)))

  ports/ILinks
  (linked-records [_ entity-name rel-key ids opts]
    (if (satisfies? ports/ILinks inner)
      (ports/linked-records inner entity-name rel-key ids opts)
      {}))

  ports/IAdminService
  (list-entities [_ entity-name options]
    (ports/list-entities inner entity-name (scoped schema-provider user entity-name :read options)))

  (count-entities [_ entity-name filters]
    (let [options (scoped schema-provider user entity-name :read {:filters filters :limit 1})]
      (if (contains? options :scopes)
        (:total-count (ports/list-entities inner entity-name options))
        (ports/count-entities inner entity-name filters))))

  (get-entity [_ entity-name id]
    (inside! inner schema-provider user entity-name :read [id])
    (ports/get-entity inner entity-name id))

  (validate-entity-data [_ entity-name data]
    (ports/validate-entity-data inner entity-name data))

  (list-related-entities [_ parent-entity-name parent-id relationship]
    (ports/list-related-entities inner parent-entity-name parent-id relationship))

  (create-entity [_ entity-name data]
    (writable! schema-provider user entity-name data)
    (ports/create-entity inner entity-name data))

  (create-entity-with-children [_ entity-name data children]
    (writable! schema-provider user entity-name data)
    (ports/create-entity-with-children inner entity-name data children))

  (update-entity [_ entity-name id data]
    (let [record (stored inner schema-provider user entity-name id)]
      (writable! schema-provider user entity-name (delay (merge @record data))))
    (ports/update-entity inner entity-name id data))

  (update-entity-field [_ entity-name id field value]
    (let [record (stored inner schema-provider user entity-name id)]
      (writable! schema-provider user entity-name (delay (assoc @record field value))))
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
