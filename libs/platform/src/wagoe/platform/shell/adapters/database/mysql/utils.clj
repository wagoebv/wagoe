(ns wagoe.platform.shell.adapters.database.mysql.utils
  "MySQL utility functions and DDL helpers."
  (:require [clojure.tools.logging :as log]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

;; =============================================================================
;; Validation Utilities
;; =============================================================================

(defn- validate-datasource
  "Validate datasource parameter.

   Args:
     datasource: Datasource to validate

   Returns:
     datasource if valid

   Throws:
     IllegalArgumentException if invalid"
  [datasource]
  (when (nil? datasource)
    (throw (IllegalArgumentException. "Datasource cannot be nil")))
  datasource)

;; =============================================================================
;; DDL Type Helpers
;; =============================================================================

(defn boolean-column-type
  "Get MySQL boolean column type definition."
  []
  "TINYINT(1)")

(defn uuid-column-type
  "Get MySQL UUID column type definition."
  []
  "CHAR(36)")

(defn varchar-uuid-column-type
  "Get MySQL varchar UUID column type definition."
  []
  "VARCHAR(36)")

(defn timestamp-column-type
  "Get MySQL timestamp column type definition."
  []
  "TIMESTAMP")

(defn datetime-column-type
  "Get MySQL datetime column type definition."
  []
  "DATETIME")

(defn auto-increment-primary-key
  "Get MySQL auto-increment primary key definition."
  []
  "BIGINT AUTO_INCREMENT PRIMARY KEY")

;; =============================================================================
;; Performance and Maintenance
;; =============================================================================

(defn explain-query
  "Get MySQL query execution plan."
  ([datasource query-map]
   (explain-query datasource query-map :traditional))
  ([datasource query-map format-type]
   (validate-datasource datasource)
   (when (or (nil? query-map) (not (map? query-map)))
     (throw (IllegalArgumentException. "query-map must be a non-nil map")))
   (let [sql-query (sql/format query-map {:dialect :mysql})
         format-clause (case format-type
                         :json "FORMAT=JSON"
                         :tree "FORMAT=TREE"
                         "")
         explain-sql (str "EXPLAIN " format-clause " " (first sql-query))
         params (rest sql-query)]
     (jdbc/execute! datasource (cons explain-sql params)
                    {:builder-fn rs/as-unqualified-lower-maps}))))

(defn analyze-table
  "Update MySQL table statistics for better query planning."
  [datasource table-name]
  (validate-datasource datasource)
  (when (nil? table-name)
    (throw (IllegalArgumentException. "table-name cannot be nil")))
  (let [table-str (name table-name)]
    (jdbc/execute! datasource [(str "ANALYZE TABLE " table-str)])
    (log/debug "Analyzed MySQL table" {:table table-str})))

(defn optimize-table
  "Optimize MySQL table to reclaim space and update indexes."
  [datasource table-name]
  (validate-datasource datasource)
  (when (nil? table-name)
    (throw (IllegalArgumentException. "table-name cannot be nil")))
  (let [table-str (name table-name)]
    (jdbc/execute! datasource [(str "OPTIMIZE TABLE " table-str)])
    (log/debug "Optimized MySQL table" {:table table-str})))

;; =============================================================================
;; Server Information
;; =============================================================================

(defn server-info
  "Get MySQL server information."
  [datasource]
  (validate-datasource datasource)
  (let [version-result (jdbc/execute-one! datasource ["SELECT VERSION() as version"]
                                          {:builder-fn rs/as-unqualified-lower-maps})
        vars-result (jdbc/execute-one! datasource
                                       ["SELECT @@hostname as hostname, @@port as port, DATABASE() as current_db, USER() as current_user"]
                                       {:builder-fn rs/as-unqualified-lower-maps})]
    {:version (:version version-result)
     :hostname (:hostname vars-result)
     :port (:port vars-result)
     :current-database (:current_db vars-result)
     :current-user (:current_user vars-result)}))

(defn show-engines
  "List available MySQL storage engines."
  [datasource]
  (validate-datasource datasource)
  (let [results (jdbc/execute! datasource ["SHOW ENGINES"]
                               {:builder-fn rs/as-unqualified-lower-maps})]
    (mapv (fn [row]
            {:engine (:engine row)
             :support (:support row)
             :comment (:comment row)
             :transactions (:transactions row)
             :xa (:xa row)
             :savepoints (:savepoints row)})
          results)))

(defn show-variables
  "Show MySQL system variables."
  ([datasource]
   (show-variables datasource nil))
  ([datasource pattern]
   (validate-datasource datasource)
   (let [query (if pattern
                 (str "SHOW VARIABLES LIKE '" pattern "'")
                 "SHOW VARIABLES")
         results (jdbc/execute! datasource [query]
                                {:builder-fn rs/as-unqualified-lower-maps})]
     (mapv (fn [row]
             {:name (:variable_name row)
              :value (:value row)})
           results))))

(defn show-processlist
  "Show active MySQL connections and queries."
  [datasource]
  (validate-datasource datasource)
  (let [results (jdbc/execute! datasource ["SHOW PROCESSLIST"]
                               {:builder-fn rs/as-unqualified-lower-maps})]
    (mapv (fn [row]
            {:id (:id row)
             :user (:user row)
             :host (:host row)
             :db (:db row)
             :command (:command row)
             :time (:time row)
             :state (:state row)
             :info (:info row)})
          results)))

(defn show-table-status
  "Show MySQL table status and statistics."
  ([datasource]
   (show-table-status datasource nil))
  ([datasource table-name]
   (validate-datasource datasource)
   (let [query (if table-name
                 (str "SHOW TABLE STATUS LIKE '" (name table-name) "'")
                 "SHOW TABLE STATUS")
         results (jdbc/execute! datasource [query]
                                {:builder-fn rs/as-unqualified-lower-maps})]
     (mapv (fn [row]
             {:name (:name row)
              :engine (:engine row)
              :rows (:rows row)
              :avg-row-length (:avg_row_length row)
              :data-length (:data_length row)
              :index-length (:index_length row)
              :auto-increment (:auto_increment row)
              :collation (:collation row)})
           results))))
