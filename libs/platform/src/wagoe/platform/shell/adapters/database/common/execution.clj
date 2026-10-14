(ns wagoe.platform.shell.adapters.database.common.execution
  "Query execution with I/O, logging, and error handling.
   
   This is part of the imperative shell - it performs database I/O,
   manages transactions, and handles side effects like logging."
  (:require [wagoe.platform.core.database.constraint :as constraint]
            [wagoe.platform.core.database.query :as core-query]
            [wagoe.platform.shell.database.validation :as core-validation]
            [wagoe.core.utils.type-conversion :as type-conversion]
            [wagoe.platform.ports.database :as protocols]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.walk :as walk]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [clojure.lang Reflector]
           [java.sql SQLException]))

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

(defn- prepare-sql-query
  "Normalize supported query inputs into a JDBC-ready `[sql & params]` vector."
  [ctx query]
  (cond
    (map? query)
    (let [adapter-dialect (protocols/dialect (:adapter ctx))
          normalized-query (normalize-query-params query)]
      {:query normalized-query
       :sql-query (core-query/format-sql adapter-dialect normalized-query)})

    (string? query)
    {:query query
     :sql-query [query]}

    (vector? query)
    {:query query
     :sql-query query}

    :else
    (throw (IllegalArgumentException.
            (str "Unsupported query input. Expected HoneySQL map, SQL string, or JDBC vector. Got: "
                 (type query))))))

(defn- server-detail
  "PostgreSQL's Detail, which logServerErrorDetail=false keeps out of the
   message. By reflection: the driver is loaded at runtime, not compiled against."
  [^SQLException se]
  (when (= "org.postgresql.util.PSQLException" (.getName (class se)))
    (try
      (some-> (Reflector/invokeInstanceMethod se "getServerErrorMessage" (object-array 0))
              (Reflector/invokeInstanceMethod "getDetail" (object-array 0)))
      (catch Exception _ nil))))

(defn reported
  "What the driver said, from the first SQLException in `e`'s causes, or nil.
   `:message` and `:detail` may quote the values sent: parse them, do not log them."
  [e]
  (when-let [^SQLException se (some #(when (instance? SQLException %) %)
                                    (take-while some? (iterate ex-cause e)))]
    (cond-> {:sql-state  (.getSQLState se)
             :error-code (.getErrorCode se)
             :message    (.getMessage se)}
      (server-detail se) (assoc :detail (server-detail se)))))

(defn loggable-message
  "`e`'s message, or a summary without the values when the driver's quotes them.
   Pass `reported` when it is already at hand."
  ([e] (loggable-message e (reported e)))
  ([^Throwable e reported]
   (or (some-> reported constraint/redacted-message) (.getMessage e))))

(defn- deletes?
  "Whether `sql` is a DELETE, read from its text so raw SQL counts too."
  [sql]
  (boolean (re-find #"(?i)^\s*delete\b" (str sql))))

(defn- conflict
  "A refused unique or foreign key as a :conflict, which HTTP answers 409
   (BOU-590). Its data reaches the client, so it names at most a field: the
   driver's text holds the SQL and the values sent."
  [{:keys [kind field referenced?]} sql e]
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
               field (assoc :field field))
             e)))

(defn redacted-cause
  "`e`, or when the driver's message quotes the values sent, a SQLException in
   its place that says what `loggable-message` says, with the same state, code
   and stack. Whatever logs or reports the cause chain then reads no values
   (BOU-628)."
  ([e] (redacted-cause e (reported e)))
  ([^Throwable e {:keys [sql-state error-code] :as reported}]
   (if-let [msg (some-> reported constraint/redacted-message)]
     (doto (SQLException. ^String msg ^String sql-state (int error-code))
       (.setStackTrace (.getStackTrace e)))
     e)))

(defn- fail!
  "Log `sql-query`'s failure `e` and throw it on: a :conflict for a refused key,
   a :database-error for anything else. `what` is \"Query\" or \"Update\"."
  [what adapter-dialect sql-query e]
  (let [r     (reported e)
        sql   (first sql-query)
        cause (redacted-cause e r)]
    (when-let [v (some-> r constraint/violation)]
      (log/info (str what " refused by a constraint")
                {:adapter adapter-dialect :sql sql :violation v :constraint-name (constraint/constraint-name r)})
      (throw (conflict v sql cause)))
    (let [msg (loggable-message e r)]
      (log/error (str what " failed") {:adapter adapter-dialect :sql sql :error msg})
      (throw (ex-info (str "Database " (str/lower-case what) " failed")
                      {:type           :database-error
                       :adapter        adapter-dialect
                       :sql            sql
                       :param-count    (count (rest sql-query))
                       :original-error msg}
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
                                  {:builder-fn rs/as-unqualified-kebab-maps})
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
        (fail! "Query" adapter-dialect sql-query e)))))

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
        (fail! "Update" adapter-dialect sql-query e)))))

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
        (binding [*transaction-datasource* tx-conn]
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
      (binding [*transaction-datasource* tx-conn]
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
            (log/error "Transaction failed, rolling back"
                       {:adapter (protocols/dialect (:adapter ctx))
                        :error (loggable-message e)})
            (throw e)))))))
