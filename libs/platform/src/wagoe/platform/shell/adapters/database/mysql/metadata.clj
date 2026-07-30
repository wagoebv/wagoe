(ns wagoe.platform.shell.adapters.database.mysql.metadata
  "MySQL metadata and table introspection utilities."
  (:require [clojure.string :as str]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

;; =============================================================================
;; Table Introspection
;; =============================================================================

(defn table-exists?
  "Check if a table exists in MySQL.

   Args:
     datasource: MySQL datasource
     table-name: Table name (string or keyword)

   Returns:
     Boolean - true if table exists"
  [datasource table-name]
  (let [table-str (name table-name)
        query {:select [:%count.*]
               :from [:information_schema.tables]
               :where [:and
                       [:= :table_schema [:database]]
                       [:= :table_name table-str]]}
        result (first (jdbc/execute! datasource
                                     (sql/format query {:dialect :mysql})
                                     {:builder-fn rs/as-unqualified-lower-maps}))]
    (> (get result :count 0) 0)))

(defn get-table-info
  "Get MySQL table column information.

   Args:
     datasource: MySQL datasource
     table-name: Table name (string or keyword)

   Returns:
     Vector of column information maps"
  [datasource table-name]
  (let [table-str (name table-name)
        ;; Get column information
        columns-query {:select [:column_name :data_type :is_nullable :column_default :extra]
                       :from [:information_schema.columns]
                       :where [:and
                               [:= :table_schema [:database]]
                               [:= :table_name table-str]]
                       :order-by [:ordinal_position]}
        ;; Get primary key information
        pk-query {:select [:kcu.column_name]
                  :from [[:information_schema.table_constraints :tc]]
                  :join [[:information_schema.key_column_usage :kcu]
                         [:and
                          [:= :tc.constraint_name :kcu.constraint_name]
                          [:= :tc.table_schema :kcu.table_schema]]]
                  :where [:and
                          [:= :tc.table_schema [:database]]
                          [:= :tc.table_name table-str]
                          [:= :tc.constraint_type "PRIMARY KEY"]]}

        columns (jdbc/execute! datasource
                               (sql/format columns-query {:dialect :mysql})
                               {:builder-fn rs/as-unqualified-lower-maps})
        pk-columns (set (map :column_name
                             (jdbc/execute! datasource
                                            (sql/format pk-query {:dialect :mysql})
                                            {:builder-fn rs/as-unqualified-lower-maps})))]

    (mapv (fn [col]
            {:name (:column_name col)
             :type (:data_type col)
             :not-null (= "NO" (:is_nullable col))
             :default (:column_default col)
             :primary-key (contains? pk-columns (:column_name col))
             :auto-increment (str/includes? (str (:extra col)) "auto_increment")})
          columns)))
