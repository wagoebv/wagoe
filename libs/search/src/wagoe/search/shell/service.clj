(ns wagoe.search.shell.service
  "SearchService orchestrates document indexing and search queries.

   Implements ISearchEngine by coordinating:
   - core/index     — document construction
   - core/query     — query sanitization and filter terms
   - definitions    — the index definitions this service was built with, then
                      the deprecated global registry
   - ISearchStore   — persistence operations"
  (:require [wagoe.search.ports :as ports]
            [wagoe.search.core.index :as index]
            [wagoe.search.core.query :as qry]
            [wagoe.search.shell.registry :as registry]
            [clojure.tools.logging :as log])
  (:import [java.time Instant]
           [java.util UUID]))

(defn- generate-document-id []
  (str (UUID/randomUUID)))

(defn- current-timestamp []
  (Instant/now))

(defn- lookup
  "Read per call rather than merged at init: a namespace using the deprecated
   registry may load after the system starts."
  [definitions index-id]
  (or (get definitions index-id) (registry/fallback index-id)))

(defn unknown-index
  "The :not-found for an index this engine does not hold. HTTP answers a
   refused index with it too, so the two cannot be told apart."
  [index-id]
  (ex-info "Search index not registered"
           {:type     :not-found
            :index-id index-id
            :message  (str "No search definition found for: " (name index-id))}))

(defn- definition!
  [definitions index-id]
  (or (lookup definitions index-id)
      (throw (unknown-index index-id))))

(defn- tenant-scope!
  "`definition`, after refusing a call on it that names no tenant when it is
   tenant-scoped (BOU-1114). A :validation-error, so HTTP answers a 400."
  [definition index-id names-tenant? what]
  (when (and (index/tenant-scoped? definition) (not names-tenant?))
    (let [message (str "Search index " index-id " is tenant-scoped: " what)]
      (throw (ex-info message {:type :validation-error :field :tenant-id
                               :index-id index-id :message message}))))
  definition)

(defn- read-scope!
  "`tenant-scope!` for a search or suggest: `opts` name a tenant in :filters
   or say `:all-tenants? true`."
  [definition index-id opts]
  (tenant-scope! definition index-id
                 (or (true? (:all-tenants? opts)) (index/names-tenant? (:filters opts) true))
                 "pass a :tenant-id in :filters, or :all-tenants? true to read every tenant"))

(defrecord SearchService [store definitions]
  ports/ISearchEngine

  (index-document! [_ index-id entity-id field-values opts]
    (log/debug "Indexing document" {:index-id index-id :entity-id entity-id})
    (let [definition (tenant-scope! (definition! definitions index-id) index-id
                                    (index/names-tenant? (:filter-values opts) false)
                                    "pass a :tenant-id in :filter-values")
          doc-opts   (merge opts
                              {:id (generate-document-id)
                               :updated-at (current-timestamp)})
          doc        (index/build-document* definition entity-id field-values doc-opts)]
      (ports/upsert-document! store doc)))

  (remove-document! [_ index-id entity-id]
    (log/debug "Removing document from index"
               {:index-id index-id :entity-id entity-id})
    (ports/delete-document! store index-id entity-id))

  (search [_ index-id query opts]
    (log/debug "Search request" {:index-id index-id :query query})
    (let [start       (System/currentTimeMillis)
          definition  (read-scope! (definition! definitions index-id) index-id opts)
          entity-type (:entity-type definition)
          language    (name (or (:language definition) :english))
          max-results (or (get-in definition [:options :max-results])
                          (:limit opts 20))
          highlight?  (or (get-in definition [:options :highlight?]) false)
          merged-opts (merge {:language   language
                              :limit      max-results
                              :highlight? highlight?}
                             opts)
          sanitized   (qry/sanitize-query query)
          results     (if (qry/empty-query? query)
                        []
                        (ports/search-documents
                         store index-id entity-type sanitized merged-opts))
          total       (if (qry/empty-query? query)
                        0
                        (ports/count-results
                         store entity-type sanitized
                         (assoc merged-opts :index-id index-id)))
          took-ms     (- (System/currentTimeMillis) start)]
      {:results results
       :total   total
       :query   (or sanitized "")
       :took-ms took-ms}))

  (suggest [_ index-id partial-query opts]
    (log/debug "Suggest request" {:index-id index-id :query partial-query})
    (let [entity-type (:entity-type (read-scope! (definition! definitions index-id) index-id opts))
          sanitized   (qry/sanitize-query partial-query)]
      (if (qry/empty-query? partial-query)
        []
        (ports/suggest-documents store index-id entity-type sanitized opts))))

  (list-indices [_]
    (let [index-ids (distinct (concat (keys definitions) (registry/list-searches)))]
      (mapv (fn [index-id]
              (let [definition (lookup definitions index-id)
                    doc-count  (try
                                 (ports/count-documents store index-id)
                                 (catch Exception _ 0))]
                {:id          index-id
                 :entity-type (:entity-type definition)
                 :language    (or (:language definition) :english)
                 :fields      (:fields definition)
                 :doc-count   doc-count}))
            index-ids)))

  (reindex! [this index-id documents]
    (log/info "Reindexing documents" {:index-id index-id :count (count documents)})
    (let [indexed (atom 0)]
      (doseq [[entity-id field-values opts] documents]
        (ports/index-document! this index-id entity-id field-values (or opts {}))
        (swap! indexed inc))
      {:indexed @indexed}))

  ports/ISearchDefinitions
  (index-definition [_ index-id]
    (lookup definitions index-id))

  ports/ISearchTenantRemoval
  (remove-tenant-document! [_ index-id entity-id tenant-id]
    (when-not (index/names-tenant? {:tenant-id tenant-id} false)
      (throw (ex-info "remove-tenant-document! needs a tenant id"
                      {:type :validation-error :field :tenant-id :index-id index-id})))
    ;; Checked here, not at boot: a store of an app's own is fine until this is called.
    (when-not (satisfies? ports/ISearchStoreTenantDeletion store)
      (throw (ex-info "WGE-107: the search store does not implement ISearchStoreTenantDeletion"
                      {:type :configuration-error :wagoe/error-code "WGE-107"
                       :missing ['delete-tenant-document!]})))
    (log/debug "Removing tenant document from index"
               {:index-id index-id :entity-id entity-id})
    (ports/delete-tenant-document! store index-id entity-id tenant-id)))

(defn create-search-service
  "Create a SearchService.

   Args:
     store       - ISearchStore implementation
     definitions - map of index id -> SearchDefinition

   Returns:
     SearchService implementing ISearchEngine"
  ([store] (create-search-service store {}))
  ([store definitions]
   (->SearchService store definitions)))
