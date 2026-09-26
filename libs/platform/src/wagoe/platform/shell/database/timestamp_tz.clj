(ns wagoe.platform.shell.database.timestamp-tz
  "Widen timestamp columns declared without a timezone to ones that carry it.
   Text columns holding ISO-8601 instants are converted too; on SQLite, which
   has no type to change, their values are rewritten as epoch millis.

   A column declared `TIMESTAMP` stores wall-clock time and no zone, so what it
   means depends on who reads it. The JVM flag from BOU-431 makes every Wagoe
   process agree that the zone is UTC, and this makes the column say so itself —
   so a report, a psql session or another service reads the same moment.

   Only PostgreSQL and H2 need it. MySQL's `TIMESTAMP` already normalises to UTC
   on the way in and out (its zone-less type is `DATETIME`), and SQLite has no
   timezone concept at all — its column types are storage-class affinities.

   Idempotent: a column that already carries a zone is left alone, so this is
   safe to run from a migration and from a boot-time schema check."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [next.jdbc :as jdbc])
  (:import [java.sql Connection DatabaseMetaData]))

(def ^:private identifier-pattern
  "Table and column names come from our own migrations, but they are
   interpolated into DDL, so they are checked rather than trusted."
  #"^[A-Za-z_][A-Za-z0-9_]*$")

(defn- checked [identifier]
  (if (re-matches identifier-pattern identifier)
    identifier
    (throw (ex-info "Not a plain SQL identifier"
                    {:type :validation-error :identifier identifier}))))

(defn- engine
  "Which engine this connection speaks, from JDBC metadata rather than config —
   a migration is handed a datasource and nothing else."
  [^DatabaseMetaData md]
  (let [product (str/lower-case (or (.getDatabaseProductName md) ""))]
    (cond
      (str/includes? product "postgres") :postgresql
      (str/includes? product "mysql")    :mysql
      (str/includes? product "mariadb")  :mysql
      (str/includes? product "h2")       :h2
      (str/includes? product "sqlite")   :sqlite
      :else                              :unknown)))

(defn- column-type-name
  "What this engine calls the type of `column`, or nil when the column is absent.

   Absent is normal: a database that never ran the migration creating the table
   should be skipped, not failed.

   The name, not `DATA_TYPE` — pgjdbc reports `timestamptz` as
   `Types/TIMESTAMP`, so a code comparison cannot tell the two apart and this
   re-altered an already-widened column on every run."
  [^DatabaseMetaData md table column]
  (letfn [(lookup [t c]
            (with-open [rs (.getColumns md nil nil t c)]
              (when (.next rs) (.getString rs "TYPE_NAME"))))]
    ;; Unquoted identifiers fold to lower case on PostgreSQL and upper on H2,
    ;; and getColumns matches what is stored, so both spellings are tried.
    (or (lookup table column)
        (lookup (str/upper-case table) (str/upper-case column)))))

(defn zone-aware?
  "Whether `type-name` is a timestamp that carries a zone.

   PostgreSQL spells it `timestamptz`, H2 `TIMESTAMP WITH TIME ZONE`."
  [type-name]
  (let [t (str/lower-case (or type-name ""))]
    (or (= "timestamptz" t)
        (str/includes? t "with time zone"))))

(defn- zone-less-timestamp? [type-name]
  (let [t (str/lower-case (or type-name ""))]
    (and (str/starts-with? t "timestamp")
         (not (zone-aware? t)))))

(defn- text-type?
  "Whether `type-name` is a character type. Workflow stored ISO-8601 instants in
   TEXT columns until BOU-502; H2 in PostgreSQL mode reports TEXT as
   CHARACTER VARYING, and in its own mode as CHARACTER LARGE OBJECT."
  [type-name]
  (let [t (str/lower-case (or type-name ""))]
    (or (#{"text" "varchar" "clob"} t)
        (str/starts-with? t "character"))))

(defn- text-statement
  "Convert a column of ISO-8601 strings. The strings carry their own offset, so
   unlike `widen-statement` there is no wall clock to interpret."
  [engine-kw table column]
  (case engine-kw
    :postgresql
    (str "ALTER TABLE " (checked table)
         " ALTER COLUMN " (checked column)
         " TYPE TIMESTAMP WITH TIME ZONE"
         " USING CAST(" (checked column) " AS TIMESTAMP WITH TIME ZONE)")

    :h2
    (str "ALTER TABLE " (checked table)
         " ALTER COLUMN " (checked column)
         " TIMESTAMP WITH TIME ZONE")

    ;; No column types to change, so the values are rewritten as the epoch
    ;; millis sqlite-jdbc stores a Timestamp as — otherwise old and new rows
    ;; would not sort together. Only ISO rows match, so a re-run does nothing.
    :sqlite
    (str "UPDATE " (checked table)
         " SET " (checked column) " = CAST(ROUND((julianday(" (checked column)
         ") - 2440587.5) * 86400000.0) AS INTEGER)"
         " WHERE " (checked column) " LIKE '____-__-__T%'")

    nil))

(defn- widen-statement
  [engine-kw table column]
  (case engine-kw
    :postgresql
    ;; AT TIME ZONE 'UTC' states what the stored wall clock already meant. A
    ;; bare TYPE change would interpret it in the session's zone instead, which
    ;; is the ambiguity being removed.
    (str "ALTER TABLE " (checked table)
         " ALTER COLUMN " (checked column)
         " TYPE TIMESTAMP WITH TIME ZONE"
         " USING " (checked column) " AT TIME ZONE 'UTC'")

    :h2
    (str "ALTER TABLE " (checked table)
         " ALTER COLUMN " (checked column)
         " TIMESTAMP WITH TIME ZONE")

    nil))

(defn- widen-on-connection!
  [^Connection conn columns]
  (let [md      (.getMetaData conn)
        engine- (engine md)]
    (if (#{:postgresql :h2 :sqlite} engine-)
      (reduce
       (fn [altered [table column]]
         (let [type-name (column-type-name md table column)]
           (cond
             (nil? type-name)
             (do (log/debug "No such column, skipping" {:table table :column column})
                 altered)

             (zone-aware? type-name)
             altered

             (text-type? type-name)
             (let [result (jdbc/execute-one! conn [(text-statement engine- table column)])]
               (if (and (= :sqlite engine-) (zero? (:next.jdbc/update-count result 0)))
                 altered
                 (do (log/info "ISO-8601 text column now holds timestamps"
                               {:table table :column column})
                     (inc altered))))

             ;; SQLite has no zone-aware type to widen a timestamp to.
             (= :sqlite engine-)
             altered

             (not (zone-less-timestamp? type-name))
             (do (log/warn "Not a timestamp column, skipping"
                           {:table table :column column :type type-name})
                 altered)

             :else
             (do (jdbc/execute! conn [(widen-statement engine- table column)])
                 (log/info "Column now carries a timezone"
                           {:table table :column column})
                 (inc altered)))))
       0
       columns)
      (do (log/debug "Engine has no zone-aware timestamp to widen to"
                     {:engine engine-})
          0))))

(defn widen-columns!
  "Give each `[table column]` in `columns` a timezone, where the engine has one.

   Returns the number of columns altered. Columns that already carry a zone, and
   engines that have no such type, are counted as nothing to do."
  [datasource columns]
  (with-open [^Connection conn (jdbc/get-connection datasource)]
    (widen-on-connection! conn columns)))

(defn up
  "Migratus entry point. The column list comes from the migration's `:up-fn`
   arguments, so each library names its own tables."
  [config columns]
  ;; Migratus passes the connection its transaction is open on. Using it
  ;; rather than a second one matters on SQLite, where a second connection is
  ;; refused with SQLITE_BUSY while that transaction holds the write lock.
  (let [conn (:conn config)]
    (if (instance? Connection conn)
      (widen-on-connection! conn columns)
      (widen-columns! (:datasource (:db config)) columns))))

(defn down
  "Nothing. Narrowing a column back would discard the zone it just gained, and
   the value is the same moment either way."
  [_config _columns]
  nil)
