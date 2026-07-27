(ns wagoe.search.shell.service
  "SearchService orchestrates document indexing and search queries.

   Implements ISearchEngine by coordinating:
   - core/index     — document construction
   - core/query     — query sanitization
   - shell/registry — index definition registry
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

(defrecord SearchService [store]
  ports/ISearchEngine

  (index-document! [_ index-id entity-id field-values opts]
    (log/debug "Indexing document" {:index-id index-id :entity-id entity-id})
    (let [definition (registry/get-search index-id)]
      (when-not definition
        (throw (ex-info "Search index not registered"
                        {:type     :not-found
                         :index-id index-id
                         :message  (str "No search definition found for: " (name index-id))})))
      (let [doc-opts (merge opts
                            {:id (generate-document-id)
                             :updated-at (current-timestamp)})
            doc (index/build-document* definition entity-id field-values doc-opts)]
        (ports/upsert-document! store doc))))

  (remove-document! [_ index-id entity-id]
    (log/debug "Removing document from index"
               {:index-id index-id :entity-id entity-id})
    (ports/delete-document! store index-id entity-id))

  (search [_ index-id query opts]
    (log/debug "Search request" {:index-id index-id :query query})
    (let [start      (System/currentTimeMillis)
          definition (registry/get-search index-id)]
      (when-not definition
        (throw (ex-info "Search index not registered"
                        {:type     :not-found
                         :index-id index-id
                         :message  (str "No search definition found for: " (name index-id))})))
      (let [entity-type (:entity-type definition)
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
         :took-ms took-ms})))

  (suggest [_ index-id partial-query opts]
    (log/debug "Suggest request" {:index-id index-id :query partial-query})
    (let [definition (registry/get-search index-id)]
      (when-not definition
        (throw (ex-info "Search index not registered"
                        {:type     :not-found
                         :index-id index-id
                         :message  (str "No search definition found for: " (name index-id))})))
      (let [entity-type (:entity-type definition)
            sanitized   (qry/sanitize-query partial-query)]
        (if (qry/empty-query? partial-query)
          []
          (ports/suggest-documents store index-id entity-type sanitized opts)))))

  (list-indices [_]
    (let [index-ids (registry/list-searches)]
      (mapv (fn [index-id]
              (let [definition (registry/get-search index-id)
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
      (doseq [[entity-id field-values] documents]
        (ports/index-document! this index-id entity-id field-values {})
        (swap! indexed inc))
      {:indexed @indexed})))

(defn create-search-service
  "Create a SearchService.

   Args:
     store - ISearchStore implementation

   Returns:
     SearchService implementing ISearchEngine"
  [store]
  (->SearchService store))
