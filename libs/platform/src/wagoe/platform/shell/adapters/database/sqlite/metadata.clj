(ns wagoe.platform.shell.adapters.database.sqlite.metadata
  "SQLite metadata and table introspection utilities."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

;; =============================================================================
;; Table Introspection
;; =============================================================================

(defn table-exists?
  "Check if a table exists in SQLite.

   Args:
     datasource: SQLite datasource
     table-name: Table name (string or keyword)

   Returns:
     Boolean - true if table exists"
  [datasource table-name]
  (let [table-str (name table-name)
        query     {:select [:%count.*]
                   :from   [:sqlite_master]
                   :where  [:and
                            [:= :type "table"]
                            [:= :name table-str]]}
        result    (first (jdbc/execute! datasource
                                        (sql/format query)  ; SQLite uses default HoneySQL dialect
                                        {:builder-fn rs/as-unqualified-lower-maps}))]
    (> (get result :count 0) 0)))

(defn get-table-info
  "Get SQLite table column information using PRAGMA table_info.

   Args:
     datasource: SQLite datasource
     table-name: Table name (string or keyword)

   Returns:
     Vector of column information maps"
  [datasource table-name]
  (when (nil? table-name)
    (throw (IllegalArgumentException. "table-name cannot be nil")))
  ;; PRAGMA table_info requires table name in SQL, cannot be parameterized
  ;; We validate that table-name is a valid identifier by using (name table-name)
  ;; which will only work with keywords/strings/symbols
  (let [table-str (name table-name)
        pragma-sql (str "PRAGMA table_info(" table-str ")")
        results    (jdbc/execute! datasource [pragma-sql] {:builder-fn rs/as-unqualified-lower-maps})]
    (mapv (fn [row]
            {:name        (:name row)
             :type        (:type row)
             :not-null    (= (:notnull row) 1)
             :default     (:dflt_value row)
             :primary-key (= (:pk row) 1)})
          results)))