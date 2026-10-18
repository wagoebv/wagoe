(ns wagoe.platform.shell.adapters.database.common.introspection
  "Table introspection for every adapter.

   PostgreSQL, MySQL and H2 all read `information_schema` and differ only in
   which schema predicate identifies \"this database\", which HoneySQL dialect
   formats the query, and whether the table name may be folded to lower case.
   SQLite has no `information_schema` and reads `sqlite_master` and
   `PRAGMA table_info` instead.

   Both forms return the same column maps — `:name` (lower case), `:type`,
   `:not-null`, `:default`, `:primary-key`, `:generated`, `:max-length` (a
   character column's declared length, nil on SQLite) — which is what the
   port promises and what the four separate copies did not consistently
   deliver."
  (:require [wagoe.core.utils.text :as text]
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
    fold-table-name? text/lower-case))

;; =============================================================================
;; information_schema — PostgreSQL, MySQL, H2
;; =============================================================================

(defn- in-schema
  "Where-clause placing a row's table in this engine's schema. `:search-path`
   means the table an unqualified name resolves to, so a tenant schema's table
   is read rather than the public one it shadows (BOU-616)."
  [schema schema-col table]
  (if (= :search-path schema)
    ;; Resolved once, not cast per row: a per-row cast throws on a schema
    ;; dropped while the catalog is read.
    [:= schema-col {:select [:n.nspname]
                    :from   [[:pg_class :c]]
                    :join   [[:pg_namespace :n] [:= :n.oid :c.relnamespace]]
                    :where  [:= :c.oid [:to_regclass [:quote_ident table]]]}]
    [:= schema-col schema]))

(defn information-schema-table-exists?
  "Whether `table-name` exists, per `information_schema.tables`.

   `spec` is {:schema <predicate-value or :search-path> :honey <sql/format opts or nil>
              :fold-table-name? bool}."
  [{:keys [schema honey] :as spec} datasource table-name]
  ;; The count is aliased because an unaliased COUNT(*) comes back under the
  ;; column name `count(*)` on MySQL and SQLite. Reading :count found nothing
  ;; and every table was reported absent (BOU-430).
  (let [query  {:select [[[:count :*] :table_count]]
                :from   [:information_schema.tables]
                :where  [:and
                         (in-schema schema :table_schema (table-key spec table-name))
                         [:= :table_name (table-key spec table-name)]]}
        result (first (rows datasource query honey))]
    (> (get result :table_count 0) 0)))

(defn information-schema-key-columns
  "The columns of index `key-name` on `table-name`, in key order, per
   `information_schema.statistics` (MySQL's). The table is matched ignoring
   case: MySQL 5.7's refusal names no table, so it is the statement's, lower
   cased, and the server may or may not fold case (lower_case_table_names).
   Nil when two tables match."
  [{:keys [schema honey] :as spec} datasource table-name key-name]
  (let [found (rows datasource
                    {:select   [[:table_name :table_name] [:column_name :column_name]]
                     :from     [:information_schema.statistics]
                     :where    [:and
                                (in-schema schema :table_schema (table-key spec table-name))
                                [:= [:lower :table_name] (text/lower-case (name table-name))]
                                [:= :index_name key-name]]
                     :order-by [:seq_in_index]}
                    honey)]
    (when (>= 1 (count (distinct (map :table_name found))))
      (mapv :column_name found))))

(defn pg-key-columns
  "The columns of index or constraint `key-name` on `table-name`, in key
   order, from PostgreSQL's catalog: its refusal names them only in a Detail
   that follows lc_messages. `table-name` namespaced with its schema, or else
   the table the search path resolves. Nil for a key on an expression."
  [datasource table-name key-name]
  (let [schema (when (keyword? table-name) (namespace table-name))
        [regclass & params] (if schema
                              ["to_regclass(format('%I.%I', ?::text, ?::text))" schema (name table-name)]
                              ["to_regclass(quote_ident(?))" (name table-name)])
        found  (jdbc/execute! datasource
                              (into [(str "SELECT a.attname FROM pg_index i"
                                          " JOIN pg_class ic ON ic.oid = i.indexrelid"
                                          " CROSS JOIN LATERAL unnest(i.indkey::int2[]) WITH ORDINALITY AS k(attnum, ord)"
                                          " LEFT JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum"
                                          " WHERE ic.relname = ? AND k.ord <= i.indnkeyatts"
                                          " AND i.indrelid = " regclass
                                          " ORDER BY k.ord")
                                     key-name]
                                    params)
                              {:builder-fn rs/as-unqualified-lower-maps})]
    (when (every? :attname found)
      (mapv :attname found))))

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
   the columns this engine reports identity and generated columns in, and its
   `:type-column` the one with the full type (default `data_type`)."
  [{:keys [schema honey generated-columns type-column] :or {type-column :data_type} :as spec}
   datasource table-name]
  (let [table-str     (table-key spec table-name)
        columns-query {:select   (into [:column_name type-column :is_nullable :column_default
                                        :character_maximum_length]
                                       generated-columns)
                       :from     [:information_schema.columns]
                       :where    [:and
                                  (in-schema schema :table_schema table-str)
                                  [:= :table_name table-str]]
                       :order-by [:ordinal_position]}
        pk-query      {:select [:kcu.column_name]
                       :from   [[:information_schema.table_constraints :tc]]
                       :join   [[:information_schema.key_column_usage :kcu]
                                [:and
                                 [:= :tc.constraint_name :kcu.constraint_name]
                                 [:= :tc.table_schema :kcu.table_schema]]]
                       :where  [:and
                                (in-schema schema :tc.table_schema table-str)
                                [:= :tc.table_name table-str]
                                [:= :tc.constraint_type "PRIMARY KEY"]]}
        columns       (rows datasource columns-query honey)
        pk-columns    (set (map :column_name (rows datasource pk-query honey)))]
    (mapv (fn [col]
            {:name        (text/lower-case (:column_name col))
             :type        (get col type-column)
             :not-null    (= "NO" (:is_nullable col))
             :default     (:column_default col)
             :primary-key (contains? pk-columns (:column_name col))
             :generated   (generated-column? col)
             :max-length  (some-> (:character_maximum_length col) long)})
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
                                 (= "INTEGER" (text/upper-case (str (:type (first pks))))))
                        (:name (first pks))))]
    (mapv (fn [row]
            {:name        (text/lower-case (:name row))
             :type        (:type row)
             :not-null    (= (:notnull row) 1)
             :default     (:dflt_value row)
             :primary-key (= (:pk row) 1)
             :generated   (= rowid-alias (:name row))
             :max-length  nil})
          results)))
