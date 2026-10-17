(ns wagoe.platform.shell.adapters.database.common.execution
  "Query execution with I/O, logging, and error handling.
   
   This is part of the imperative shell - it performs database I/O,
   manages transactions, and handles side effects like logging."
  (:require [wagoe.platform.core.database.constraint :as constraint]
            [wagoe.platform.core.http.problem-details :as problem]
            [wagoe.platform.core.database.query :as core-query]
            [wagoe.platform.shell.database.validation :as core-validation]
            [wagoe.core.utils.type-conversion :as type-conversion]
            [wagoe.platform.ports.database :as protocols]
            [wagoe.observability.core.sql-redaction :as redaction]
            ;; Loaded for its effect: every log call writes its throwable
            ;; redacted, in any process that reaches a database (BOU-634).
            [wagoe.observability.logging.shell.redacting-factory]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.walk :as walk]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [java.sql SQLException]))

;; =============================================================================
;; Context Validation (Re-exported from core)
;; =============================================================================

(def db-context?
  "Check if value is a valid database context.
   
   Delegates to pure core function."
  core-validation/db-context?)

(def validate-context
  "Validate database context and throw if invalid.
   
   Delegates to pure core function."
  core-validation/validate-db-context)

(def validate-adapter
  "Validate that context has a valid adapter.
   
   Delegates to pure core function."
  core-validation/validate-adapter-context)

(defn- add-database-breadcrumb
  "Add database operation breadcrumb for tracking database operations.
   
   Args:
     operation: String describing the database operation
     status: :start, :success, or :error
     details: Map with operation details"
  [_operation _status _details]
  ;; Skip breadcrumb addition since database layer doesn't have error context
  ;; This prevents protocol errors when called from contexts without proper error reporting setup
  nil)

(def ^:dynamic *transaction-datasource*
  "Bound transaction connection used by database execution helpers.

   This ensures all nested database calls inside a transaction share the same
   JDBC connection instead of borrowing a new one from the pool."
  nil)

(defn current-datasource
  "Return the active JDBC executor for the current call.

   Prefer a transaction-bound connection when present so transaction-scoped
   settings like PostgreSQL `search_path` remain in effect for all downstream
   queries."
  [ctx]
  (or *transaction-datasource*
      (:datasource ctx)))

;; =============================================================================
;; Query Execution
;; =============================================================================

(defn- normalize-query-params
  "Convert JDBC-unfriendly values in a query map to database-safe values.

   Instants are stored as ISO-8601 strings throughout the project, so convert
   them recursively before formatting or executing SQL."
  [query-map]
  (walk/postwalk (fn [value]
                   (if (instance? java.time.Instant value)
                     (type-conversion/instant->string value)
                     value))
                 query-map))

(defn encode-params
  "`[sql & params]` with each param as the engine takes it (BOU-648)."
  [adapter [sql & params :as query]]
  (if (instance? wagoe.platform.ports.database.ParamEncoder adapter)
    (into [sql] (map #(protocols/param->db adapter %)) params)
    query))

(defn- row-builder
  "Kebab-case maps, each value decoded as the engine's adapter reads it, where
   it does: while the row is built, not in a second pass over every row."
  [adapter]
  (if-let [decode (and (instance? wagoe.platform.ports.database.ResultDecoder adapter)
                       (protocols/value-decoder adapter))]
    (rs/builder-adapter rs/as-unqualified-kebab-maps
                        (fn [builder ^java.sql.ResultSet rs ^Integer i]
                          (decode (rs/read-column-by-index (.getObject rs i) (:rsmeta builder) i))))
    rs/as-unqualified-kebab-maps))

(defn- prepare-sql-query
  "Normalize supported query inputs into a JDBC-ready `[sql & params]` vector."
  [ctx query]
  (cond
    (map? query)
    (let [adapter-dialect (protocols/dialect (:adapter ctx))
          normalized-query (normalize-query-params query)]
      {:query normalized-query
       :sql-query (encode-params (:adapter ctx)
                                 (core-query/format-sql adapter-dialect normalized-query))})

    (string? query)
    {:query query
     :sql-query [query]}

    (vector? query)
    {:query query
     :sql-query (encode-params (:adapter ctx) query)}

    :else
    (throw (IllegalArgumentException.
            (str "Unsupported query input. Expected HoneySQL map, SQL string, or JDBC vector. Got: "
                 (type query))))))

(def reported
  "What the driver said. See `redaction/reported`."
  redaction/reported)

(defn redacted-cause
  "`e`, or when the driver's message quotes the values sent, a SQLException in
   its place saying what `redaction/redacted-message` says, with the same
   state, code and stack. Whatever logs or reports the cause chain then reads
   no values (BOU-628)."
  ([e] (redacted-cause e (reported e)))
  ([^Throwable e {:keys [sql-state error-code] :as reported}]
   (if-let [msg (some-> reported redaction/redacted-message)]
     (doto (SQLException. ^String msg ^String sql-state (int error-code))
       (.setStackTrace (.getStackTrace e)))
     e)))

(defn- deletes?
  "Whether `sql` is a DELETE, read from its text so raw SQL counts too."
  [sql]
  (boolean (re-find #"(?i)^\s*delete\b" (str sql))))

(defn- conflict
  "A refused unique or foreign key as a :conflict, which HTTP answers 409
   (BOU-590). Its data reaches the client, so it names at most its fields: the
   driver's text holds the SQL and the values sent."
  [{:keys [kind field referenced?] :as v} sql e]
  ;; The driver says which way a foreign key was refused, except SQLite: there
  ;; a delete is the only case the statement itself tells apart.
  (let [referenced? (or referenced? (deletes? sql))
        field       (when-not referenced? field)]
    (ex-info (case kind
               :unique      (if field
                              (str "Another record already has this " (name field))
                              "Another record already has these values")
               :foreign-key (if referenced?
                              "The record is still referenced by another record"
                              "A referenced record does not exist"))
             (cond-> {:type :conflict :constraint kind}
               (not referenced?) (merge (select-keys v [:field :fields])))
             e)))

(def ^:dynamic ^:private *outside-datasource*
  "The datasource the outermost transaction took its connection from. A key's
   columns are read there on PostgreSQL, which refuses every statement in a
   transaction a refused write aborted."
  nil)

(defn- named-columns
  "`v`, a unique violation, with the columns of key `key-name` read from the
   database when the driver's message did not name them. Only a key on the
   statement's table: the message quotes the values sent, so its key name
   may be one a caller wrote. PostgreSQL reports the schema and table apart."
  [ctx sql key-name {:keys [schema] :as reported} {:keys [kind field fields] :as v}]
  (let [target        (constraint/statement-table sql)
        [table index] (cond
                        (nil? key-name)   nil
                        (:table reported) [(:table reported) key-name]
                        :else
                        (if-let [i (str/last-index-of key-name ".")]
                          [(subs key-name 0 i) (subs key-name (inc i))]
                          [target key-name]))]
    (if (and (= :unique kind) (not (or field fields)) table
             (= target (str/lower-case table))
             ;; An English Detail that named no field is a key on an expression.
             (not (str/starts-with? (str (:detail reported)) "Key (")))
      (try
        (let [adapter (:adapter ctx)
              ds      (or (when (= :postgresql (protocols/engine adapter)) *outside-datasource*)
                          (current-datasource ctx))]
          (constraint/with-columns v (protocols/key-columns adapter ds (if schema (keyword schema table) table) index)))
        (catch Exception e
          (log/debug "Could not read the columns of a key" {:key key-name :error (ex-message e)})
          v)
        ;; A reified adapter that predates key-columns.
        (catch AbstractMethodError _ v))
      v)))

(defn- fail!
  "Log `sql-query`'s failure `e` and throw it on: a :conflict for a refused key,
   a :database-error for anything else. `what` is \"Query\" or \"Update\"."
  [what ctx sql-query e]
  (let [adapter-dialect (protocols/dialect (:adapter ctx))
        r        (reported e)
        sql      (first sql-query)
        cause    (redacted-cause e r)
        key-name (some-> r redaction/constraint-name)]
    (when-let [v (some->> (some-> r (assoc :target (constraint/statement-table sql) :engine (protocols/engine (:adapter ctx))) constraint/violation)
                          (named-columns ctx sql key-name r))]
      (log/info (str what " refused by a constraint")
                {:adapter adapter-dialect :sql sql :violation v :constraint-name key-name})
      (throw (conflict v sql cause)))
    (let [msg      (ex-message cause)
          required (some-> r redaction/not-null-field)]
      (log/error (str what " failed") {:adapter adapter-dialect :sql sql :error msg})
      (throw (ex-info (str "Database " (str/lower-case what) " failed")
                      (cond-> {:type           :database-error
                               :adapter        adapter-dialect
                               :sql            sql
                               :param-count    (count (rest sql-query))
                               :original-error msg}
                        ;; Which field, so no caller has to read the driver's text.
                        required (assoc :constraint :not-null :field required))
                      cause)))))

(defn execute-query!
  "Execute SELECT query and return results.
   
   SHELL FUNCTION: Performs database I/O and logging (side effects).
   Uses pure core functions for query formatting.

   Args:
     ctx: Database context {:datasource ds :adapter adapter}
     query-map: HoneySQL query map

   Returns:
     Vector of result maps

   Example:
     (execute-query! ctx {:select [:*] :from [:users] :where [:= :active true]})"
  [ctx query-map]
  (validate-context ctx)
  ;; Validate query input before formatting to avoid wasting resources
  (when (or (nil? query-map)
            (and (map? query-map) (empty? query-map))
            (and (vector? query-map) (empty? query-map))
            (and (string? query-map) (str/blank? query-map)))
    (throw (IllegalArgumentException. "Invalid or empty query map provided")))

  ;; Pure: format query using core function when needed
  (let [adapter-dialect (protocols/dialect (:adapter ctx))
        {:keys [query sql-query]} (prepare-sql-query ctx query-map)
        start-time (System/currentTimeMillis)
        operation-details {:adapter adapter-dialect
                           :operation-type "query"
                           :table (or (when (map? query)
                                        (or (get-in query [:from 0])
                                            (get-in query [:select-from 0])))
                                      "unknown")}]

    ;; Add breadcrumb for operation start
    (add-database-breadcrumb "query" :start operation-details)

    ;; Side effect: log query. Only the parameter count: the values are
    ;; positional, so they cannot be redacted by name (BOU-556).
    (log/debug "Executing query"
               {:adapter adapter-dialect
                :sql (first sql-query)
                :param-count (count (rest sql-query))})

    (try
      ;; Side effect: database I/O
      ;; Kebab-case builder converts column names once per result set —
      ;; a per-row snake->kebab map rebuild would double result-processing cost.
      (let [result (jdbc/execute! (current-datasource ctx) sql-query
                                  {:builder-fn (row-builder (:adapter ctx))})
            duration (- (System/currentTimeMillis) start-time)
            success-details (merge operation-details
                                   {:duration-ms duration
                                    :row-count (count result)})]

        ;; Add breadcrumb for successful completion
        (add-database-breadcrumb "query" :success success-details)

        ;; Side effect: log result
        (log/debug "Query completed"
                   {:adapter adapter-dialect
                    :duration-ms duration
                    :row-count (count result)})
        result)

      (catch Exception e
        (fail! "Query" ctx sql-query e)))))

(defn execute-one!
  "Execute query expecting single result.

   Args:
     ctx: Database context
     query-map: HoneySQL query map

   Returns:
     Single result map or nil

   Example:
     (execute-one! ctx {:select [:*] :from [:users] :where [:= :id \"123\"]})"
  [ctx query-map]
  (first (execute-query! ctx query-map)))

(defn execute-update!
  "Execute UPDATE/INSERT/DELETE query.
   
   SHELL FUNCTION: Performs database I/O and logging (side effects).

   Args:
     ctx: Database context
     query-map: HoneySQL query map

   Returns:
     Number of affected rows

   Example:
     (execute-update! ctx {:update :users :set {:active false} :where [:= :id \"123\"]})"
  [ctx query-map]
  (validate-context ctx)

  ;; Pure: format query using core function when needed
  (let [adapter-dialect (protocols/dialect (:adapter ctx))
        {:keys [query sql-query]} (prepare-sql-query ctx query-map)
        start-time (System/currentTimeMillis)
        operation-type (cond
                         (and (map? query) (contains? query :insert-into)) "insert"
                         (and (map? query) (contains? query :update)) "update"
                         (and (map? query) (contains? query :delete-from)) "delete"
                         :else "modify")
        table-name (or (when (map? query)
                         (or (get query :insert-into)
                             (get query :update)
                             (get query :delete-from)))
                       "unknown")
        operation-details {:adapter adapter-dialect
                           :operation-type operation-type
                           :table table-name}]

    ;; Add breadcrumb for operation start
    (add-database-breadcrumb operation-type :start operation-details)

    ;; Side effect: log update
    (log/debug "Executing update"
               {:adapter adapter-dialect
                :sql (first sql-query)
                :param-count (count (rest sql-query))})

    (try
      ;; Side effect: database I/O
      (let [result (jdbc/execute! (current-datasource ctx) sql-query)
            duration (- (System/currentTimeMillis) start-time)
            affected-rows (::jdbc/update-count (first result))
            success-details (merge operation-details
                                   {:duration-ms duration
                                    :affected-rows affected-rows})]

        ;; Add breadcrumb for successful completion
        (add-database-breadcrumb operation-type :success success-details)

        ;; Side effect: log result
        (log/debug "Update completed"
                   {:adapter adapter-dialect
                    :duration-ms duration
                    :affected-rows affected-rows})
        affected-rows)

      (catch Exception e
        (fail! "Update" ctx sql-query e)))))

(defn execute-batch!
  "Execute multiple queries in a single transaction.

   Args:
     ctx: Database context
     query-maps: Vector of HoneySQL query maps

   Returns:
     Vector of results (for SELECTs) or affected row counts (for updates)

   Example:
     (execute-batch! ctx [{:insert-into :users :values [{:name \"John\"}]}
                         {:insert-into :users :values [{:name \"Jane\"}]}])"
  [ctx query-maps]
  (validate-context ctx)
  (let [operation-details {:adapter (protocols/dialect (:adapter ctx))
                           :operation-type "batch"
                           :query-count (count query-maps)}]

    ;; Add breadcrumb for operation start
    (add-database-breadcrumb "batch" :start operation-details)

    (log/debug "Executing batch operation"
               {:adapter (protocols/dialect (:adapter ctx))
                :query-count (count query-maps)})

    (try
      (jdbc/with-transaction [tx-conn (current-datasource ctx)]
        (binding [*transaction-datasource* tx-conn
                  *outside-datasource*     (or *outside-datasource* (current-datasource ctx))]
          (let [tx-ctx (assoc ctx :datasource tx-conn)
                start-time (System/currentTimeMillis)
                results (mapv (fn [query-map]
                                (if (contains? query-map :select)
                                  (execute-query! tx-ctx query-map)
                                  (execute-update! tx-ctx query-map)))
                              query-maps)
                duration (- (System/currentTimeMillis) start-time)
                success-details (merge operation-details
                                       {:duration-ms duration
                                        :results-count (count results)})]

            ;; Add breadcrumb for successful completion
            (add-database-breadcrumb "batch" :success success-details)

            (log/info "Batch operation completed"
                      {:adapter (protocols/dialect (:adapter ctx))
                       :query-count (count query-maps)
                       :duration-ms duration})
            results)))

      (catch Exception e
        ;; Re-throw to preserve error chain
        (throw e)))))

;; =============================================================================
;; Transaction Management
;; =============================================================================

(defn with-transaction*
  "Execute function within database transaction context.

   Args:
     ctx: Database context
     f: Function that takes transaction context and returns result

   Returns:
     Result of function execution

   Example:
     (with-transaction* ctx (fn [tx] (execute-update! tx query)))"
  [ctx f]
  (validate-context ctx)
  (let [operation-details {:adapter (protocols/dialect (:adapter ctx))
                           :operation-type "transaction"}]

    ;; Add breadcrumb for transaction start
    (add-database-breadcrumb "transaction" :start operation-details)

    (jdbc/with-transaction [tx-conn (current-datasource ctx)]
      (binding [*transaction-datasource* tx-conn
                *outside-datasource*     (or *outside-datasource* (current-datasource ctx))]
        (try
          (let [tx-ctx (assoc ctx :datasource tx-conn)
                result (f tx-ctx)
                success-details (merge operation-details {:status "committed"})]

            ;; Add breadcrumb for successful transaction
            (add-database-breadcrumb "transaction" :success success-details)

            (log/debug "Transaction completed successfully"
                       {:adapter (protocols/dialect (:adapter ctx))})
            result)

          (catch Exception e
            ;; A refused key is the caller's to answer, not a fault (BOU-712).
            (log/logp (problem/exception-log-level e {}) "Transaction failed, rolling back"
                      {:adapter (protocols/dialect (:adapter ctx))
                       :error (redaction/loggable-message e)})
            (throw e)))))))
