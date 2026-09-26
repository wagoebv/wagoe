(ns wagoe.platform.shell.adapters.database.common.introspection
  "Table introspection for every adapter.

   PostgreSQL, MySQL and H2 all read `information_schema` and differ only in
   which schema predicate identifies \"this database\", which HoneySQL dialect
   formats the query, and whether the table name may be folded to lower case.
   SQLite has no `information_schema` and reads `sqlite_master` and
   `PRAGMA table_info` instead.

   Both forms return the same column maps — `:name` (lower case), `:type`,
   `:not-null`, `:default`, `:primary-key`, `:generated` — which is what the
   port promises and what the four separate copies did not consistently
   deliver."
  (:require [clojure.string :as str]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(defn- rows
  [datasource query honey-opts]
  (jdbc/execute! datasource
                 (if honey-opts (sql/format query honey-opts) (sql/format query))
                 {:builder-fn rs/as-unqualified-lower-maps}))

(defn- table-key
  "The table name as this engine records it.

   PostgreSQL folds unquoted identifiers to lower case and H2 runs with
   DATABASE_TO_LOWER; MySQL on a case-sensitive filesystem does not, so folding
   there would fail to find a mixed-case table."
  [{:keys [fold-table-name?]} table-name]
  (cond-> (name table-name)
    fold-table-name? str/lower-case))

;; =============================================================================
;; information_schema — PostgreSQL, MySQL, H2
;; =============================================================================

(defn information-schema-table-exists?
  "Whether `table-name` exists, per `information_schema.tables`.

   `spec` is {:schema <predicate-value> :honey <sql/format opts or nil>
              :fold-table-name? bool}."
  [{:keys [schema honey] :as spec} datasource table-name]
  ;; The count is aliased because an unaliased COUNT(*) comes back under the
  ;; column name `count(*)` on MySQL and SQLite. Reading :count found nothing
  ;; and every table was reported absent (BOU-430).
  (let [query  {:select [[[:count :*] :table_count]]
                :from   [:information_schema.tables]
                :where  [:and
                         [:= :table_schema schema]
                         [:= :table_name (table-key spec table-name)]]}
        result (first (rows datasource query honey))]
    (> (get result :table_count 0) 0)))

(defn- generated-column?
  "Whether the database fills this column itself. Their column_default is
   NULL, so without this an identity column reads as one an INSERT must supply.
   PostgreSQL and H2 report is_identity and is_generated; MySQL has neither and
   says it in `extra` (DEFAULT_GENERATED there is a plain default)."
  [col]
  (boolean
   (or (= "YES" (:is_identity col))
       (= "ALWAYS" (:is_generated col))
       (some->> (:extra col) (re-find #"(?i)auto_increment|(?:virtual|stored) generated")))))

(defn information-schema-table-info
  "Column information for `table-name`, per `information_schema`. See
   `information-schema-table-exists?` for `spec`; its `:generated-columns` are
   the columns this engine reports identity and generated columns in."
  [{:keys [schema honey generated-columns] :as spec} datasource table-name]
  (let [table-str     (table-key spec table-name)
        columns-query {:select   (into [:column_name :data_type :is_nullable :column_default]
                                       generated-columns)
                       :from     [:information_schema.columns]
                       :where    [:and
                                  [:= :table_schema schema]
                                  [:= :table_name table-str]]
                       :order-by [:ordinal_position]}
        pk-query      {:select [:kcu.column_name]
                       :from   [[:information_schema.table_constraints :tc]]
                       :join   [[:information_schema.key_column_usage :kcu]
                                [:and
                                 [:= :tc.constraint_name :kcu.constraint_name]
                                 [:= :tc.table_schema :kcu.table_schema]]]
                       :where  [:and
                                [:= :tc.table_schema schema]
                                [:= :tc.table_name table-str]
                                [:= :tc.constraint_type "PRIMARY KEY"]]}
        columns       (rows datasource columns-query honey)
        pk-columns    (set (map :column_name (rows datasource pk-query honey)))]
    (mapv (fn [col]
            {:name        (str/lower-case (:column_name col))
             :type        (:data_type col)
             :not-null    (= "NO" (:is_nullable col))
             :default     (:column_default col)
             :primary-key (contains? pk-columns (:column_name col))
             :generated   (generated-column? col)})
          columns)))

;; =============================================================================
;; SQLite
;; =============================================================================

(defn sqlite-table-exists?
  [datasource table-name]
  (let [query  {:select [[[:count :*] :table_count]]
                :from   [:sqlite_master]
                :where  [:and
                         [:= :type "table"]
                         [:= :name (name table-name)]]}
        result (first (rows datasource query nil))]
    (> (get result :table_count 0) 0)))

(defn sqlite-table-info
  [datasource table-name]
  (when (nil? table-name)
    (throw (IllegalArgumentException. "table-name cannot be nil")))
  ;; PRAGMA table_info cannot take a parameter, so the name goes into the SQL.
  ;; (name table-name) accepts only a keyword, string or symbol.
  (let [pragma-sql (str "PRAGMA table_info(" (name table-name) ")")
        results    (jdbc/execute! datasource [pragma-sql]
                                  {:builder-fn rs/as-unqualified-lower-maps})
        ;; A lone INTEGER PRIMARY KEY is the rowid, which SQLite numbers.
        ;; Generated columns PRAGMA table_info does not list at all.
        rowid-alias (let [pks (filter #(pos? (:pk %)) results)]
                      (when (and (= 1 (count pks))
                                 (= "INTEGER" (str/upper-case (str (:type (first pks))))))
                        (:name (first pks))))]
    (mapv (fn [row]
            {:name        (str/lower-case (:name row))
             :type        (:type row)
             :not-null    (= (:notnull row) 1)
             :default     (:dflt_value row)
             :primary-key (= (:pk row) 1)
             :generated   (= rowid-alias (:name row))})
          results)))
