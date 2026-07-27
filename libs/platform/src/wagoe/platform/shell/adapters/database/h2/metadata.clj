(ns wagoe.platform.shell.adapters.database.h2.metadata
  "H2 metadata and table introspection utilities."
  (:require [clojure.string :as str]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:private default-schema
  "Default schema name for H2 database queries."
  "public")

;; =============================================================================
;; Table Introspection
;; =============================================================================

(defn table-exists?
  "Check if a table exists in H2.

   Args:
     datasource: H2 datasource
     table-name: Table name (string or keyword)

   Returns:
     Boolean - true if table exists"
  [datasource table-name]
  (let [table-str (str/lower-case (name table-name))  ; Use lowercase for PostgreSQL compatibility
        query     {:select [:%count.*]
                   :from   [:information_schema.tables]
                   :where  [:and
                            [:= :table_schema default-schema]
                            [:= :table_name table-str]]}
        result    (first (jdbc/execute! datasource
                                        (sql/format query {:dialect :ansi})
                                        {:builder-fn rs/as-unqualified-lower-maps}))]
    (> (or (:count result) (get result :?column?) 0) 0)))

(defn get-table-info
  "Get H2 table column information.

   Args:
     datasource: H2 datasource
     table-name: Table name (string or keyword)

   Returns:
     Vector of column information maps"
  [datasource table-name]
  (let [table-str     (str/lower-case (name table-name))  ; Use lowercase for PostgreSQL compatibility
        ;; Get column information
        columns-query {:select   [:column_name :data_type :is_nullable :column_default]
                       :from     [:information_schema.columns]
                       :where    [:and
                                  [:= :table_schema default-schema]
                                  [:= :table_name table-str]]
                       :order-by [:ordinal_position]}
        ;; Get primary key information
        pk-query      {:select [:kcu.column_name]
                       :from   [[:information_schema.table_constraints :tc]]
                       :join   [[:information_schema.key_column_usage :kcu]
                                [:and
                                 [:= :tc.constraint_name :kcu.constraint_name]
                                 [:= :tc.table_schema :kcu.table_schema]]]
                       :where  [:and
                                [:= :tc.table_schema default-schema]
                                [:= :tc.table_name table-str]
                                [:= :tc.constraint_type "PRIMARY KEY"]]}

        columns       (jdbc/execute! datasource
                                     (sql/format columns-query {:dialect :ansi})
                                     {:builder-fn rs/as-unqualified-lower-maps})
        pk-columns    (set (map :column_name
                                (jdbc/execute! datasource
                                               (sql/format pk-query {:dialect :ansi})
                                               {:builder-fn rs/as-unqualified-lower-maps})))]

    (mapv (fn [col]
            {:name        (str/lower-case (:column_name col))
             :type        (:data_type col)
             :not-null    (= "NO" (:is_nullable col))
             :default     (:column_default col)
             :primary-key (contains? pk-columns (:column_name col))})
          columns)))
