(ns wagoe.search.shell.persistence
  "Database persistence for search documents.

   Implements ISearchStore using next.jdbc + HoneySQL for DML
   and raw parameterized SQL for FTS-specific queries.

   Two query strategies selected by db-type:
   - :postgresql — to_tsvector/plainto_tsquery + ts_rank + ts_headline
   - :h2 / :sqlite — LOWER/LIKE fallback (used in tests and dev)"
  (:require [wagoe.search.ports :as ports]
            [wagoe.search.core.index :as index]
            [wagoe.search.core.query :as qry]
            [cheshire.core :as json]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [clojure.tools.logging :as log]
            [clojure.edn :as edn])
  (:import [java.util UUID]))

;; =============================================================================
;; Type conversion helpers
;; =============================================================================

(defn- uuid->str [u] (when u (str u)))
(defn- str->uuid [s] (when s (UUID/fromString s)))
(defn- kw->str   [k] (when k (name k)))
(defn- str->kw   [s] (when s (keyword s)))

(defn- filters->json
  "Serialize a filter map to a JSON string with snake_case keys.

   {:tenant-id \"abc\" :status \"active\"} => \"{\\\"tenant_id\\\":\\\"abc\\\",\\\"status\\\":\\\"active\\\"}\"

   Returns nil when filters is nil or empty."
  [filters]
  (when (seq filters)
    (json/generate-string
     (into {} (map (fn [[k v]] [(index/filter-key->json-key k) (str v)]) filters)))))

;; =============================================================================
;; Row <-> Entity mapping
;; =============================================================================

(defn- doc->db
  "Convert a SearchDocument map to a DB row map (snake_case strings)."
  [doc]
  {:id          (:id doc)
   :index_id    (kw->str (:index-id doc))
   :entity_type (kw->str (:entity-type doc))
   :entity_id   (uuid->str (:entity-id doc))
   :language    (or (:language doc) "english")
   :weight_a    (or (:weight-a doc) "")
   :weight_b    (or (:weight-b doc) "")
   :weight_c    (or (:weight-c doc) "")
   :weight_d    (or (:weight-d doc) "")
   :content_all (or (:content-all doc) "")
   :metadata    (when-let [m (:metadata doc)] (pr-str m))
   :filters     (filters->json (:filters doc))
   :updated_at  (str (:updated-at doc))})

(defn- db->result
  "Convert a DB row map to a SearchResult map."
  [row]
  (when row
    (cond-> {:entity-type (str->kw (:entity_type row))
             :entity-id   (str->uuid (:entity_id row))
             :rank        (double (or (:rank row) 1.0))
             :snippet     (:snippet row)}
      (:metadata row)
      (assoc :metadata (edn/read-string (:metadata row))))))

;; =============================================================================
;; ISearchStore implementation
;; =============================================================================

(defrecord SearchStore [datasource db-type]
  ports/ISearchStore

  (upsert-document! [_ doc]
    (log/debug "Upserting search document"
               {:index-id (:index-id doc) :entity-id (:entity-id doc)})
    (let [row (doc->db doc)]
      (if (= db-type :postgresql)
        ;; PostgreSQL: native ON CONFLICT ... DO UPDATE SET ... = EXCLUDED.*
        (jdbc/execute-one!
         datasource
         (sql/format
          {:insert-into   :search_documents
           :values        [row]
           :on-conflict   [:index_id :entity_id]
           :do-update-set [:language :weight_a :weight_b :weight_c :weight_d
                           :content_all :metadata :filters :updated_at]})
         {:builder-fn rs/as-unqualified-lower-maps})
        ;; H2 / SQLite: delete existing row then insert fresh (atomic transaction)
        (jdbc/with-transaction [tx datasource]
          (jdbc/execute-one!
           tx
           (sql/format {:delete-from :search_documents
                        :where       [:and
                                      [:= :index_id (:index_id row)]
                                      [:= :entity_id (:entity_id row)]]})
           {:builder-fn rs/as-unqualified-lower-maps})
          (jdbc/execute-one!
           tx
           (sql/format {:insert-into :search_documents :values [row]})
           {:builder-fn rs/as-unqualified-lower-maps}))))
    doc)

  (delete-document! [_ index-id entity-id]
    (log/debug "Deleting search document"
               {:index-id index-id :entity-id entity-id})
    (jdbc/execute-one!
     datasource
     (sql/format {:delete-from :search_documents
                  :where       [:and
                                [:= :index_id (kw->str index-id)]
                                [:= :entity_id (uuid->str entity-id)]]})
     {:builder-fn rs/as-unqualified-lower-maps})
    nil)

  (search-documents [_ index-id entity-type query opts]
    (log/debug "Searching documents"
               {:index-id index-id :entity-type entity-type :query query})
    (let [{:keys [language limit offset highlight? filters]
           :or   {limit 20 offset 0 language "english" highlight? false}} opts
          sql-vec (if (= db-type :postgresql)
                    (qry/build-postgres-search-sql
                     (kw->str index-id) (kw->str entity-type)
                     language query limit offset highlight? filters)
                    (qry/build-fallback-search-sql
                     (kw->str index-id) (kw->str entity-type)
                     query limit offset filters))
          rows (jdbc/execute! datasource sql-vec
                              {:builder-fn rs/as-unqualified-lower-maps})]
      (mapv db->result rows)))

  (count-results [_ entity-type query opts]
    (log/debug "Counting search results"
               {:entity-type entity-type :query query})
    (let [{:keys [language index-id filters] :or {language "english"}} opts
          sql-vec (if (= db-type :postgresql)
                    (qry/build-postgres-count-sql
                     (kw->str index-id) (kw->str entity-type)
                     language query filters)
                    (qry/build-fallback-count-sql
                     (kw->str index-id) (kw->str entity-type)
                     query filters))
          row (jdbc/execute-one! datasource sql-vec
                                 {:builder-fn rs/as-unqualified-lower-maps})]
      (int (or (:cnt row) 0))))

  (suggest-documents [_ index-id entity-type query opts]
    (log/debug "Suggesting documents"
               {:index-id index-id :entity-type entity-type :query query})
    (let [{:keys [limit threshold]
           :or   {limit 5 threshold 0.15}} opts
          sql-vec (if (= db-type :postgresql)
                    (qry/build-postgres-suggest-sql
                     (kw->str index-id) (kw->str entity-type)
                     query limit threshold)
                    (qry/build-fallback-suggest-sql
                     (kw->str index-id) (kw->str entity-type)
                     query limit))
          rows (jdbc/execute! datasource sql-vec
                              {:builder-fn rs/as-unqualified-lower-maps})]
      (mapv db->result rows)))

  (count-documents [_ index-id]
    (log/debug "Counting documents" {:index-id index-id})
    (let [row (jdbc/execute-one!
               datasource
               (sql/format {:select [:%count.*]
                            :from   [:search_documents]
                            :where  [:= :index_id (kw->str index-id)]})
               {:builder-fn rs/as-unqualified-lower-maps})]
      (int (or (first (vals row)) 0)))))

(defn create-search-store
  "Create a SearchStore backed by a JDBC datasource.

   Args:
     datasource - javax.sql.DataSource
     db-type    - :postgresql (FTS path) or :h2 / :sqlite (LIKE fallback)

   Returns:
     SearchStore implementing ISearchStore"
  [datasource db-type]
  (->SearchStore datasource db-type))
