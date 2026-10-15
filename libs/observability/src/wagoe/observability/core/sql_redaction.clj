(ns wagoe.observability.core.sql-redaction
  "Keeping the values a database driver quotes out of what is logged, traced
   and reported (BOU-626, BOU-628, BOU-629). Pure, on the driver's SQLState,
   vendor code and message: every library reporting through observability gets
   it, whatever threw (BOU-633)."
  (:require [clojure.string :as str]
            [clojure.walk :as walk])
  (:import [clojure.lang Reflector]
           [java.sql SQLException]))

(defn column->field
  "The one column `s` names as a kebab keyword, or nil for several columns or
   anything that is not a plain identifier: the rest may be SQL or a value."
  [s]
  (let [col (-> s
                str/trim
                (str/replace #"(?i)\s+(NULLS\s+(FIRST|LAST)|ASC|DESC)\b" "")
                (str/replace #"[`\"]" "")
                (str/split #"\.")
                last
                str/lower-case)]
    (when (and (not (str/includes? s ",")) (re-matches #"[a-z][a-z0-9_]*" col))
      (keyword (str/replace col "_" "-")))))

(def ^:private not-null-patterns
  [#"NOT NULL constraint failed: (?:[^.\s()]+\.)?([^\s()]+)" ; SQLite
   #"null value in column \"([^\"]+)\""                      ; PostgreSQL
   #"NULL not allowed for column \"([^\"]+)\""               ; H2
   #"Column '([^']+)' cannot be null"                        ; MySQL, explicit NULL
   #"Field '([^']+)' doesn't have a default value"])         ; MySQL, column left out

(defn not-null-field
  "The field a refused NOT NULL names, as a kebab keyword, or nil when
   `reported` is not one. Read only when the state or code says NOT NULL, or
   there is no state (SQLite): other messages quote values a pattern could match.
   PostgreSQL's `:column`, in any language, before its message."
  [{:keys [sql-state error-code message column]}]
  (when (or (nil? sql-state) (= "23502" sql-state) (#{1048 1364} error-code))
    (or (some-> column column->field)
        (some #(some-> (re-find % (str message)) second column->field) not-null-patterns))))

(def ^:private name-patterns
  [#"constraint \"([\w$.]+)\""              ; PostgreSQL
   #"CONSTRAINT `([\w$.]+)`"                ; MySQL foreign key
   #"for key '([\w$.]+)'"                   ; MySQL unique
   #"violation: \"(?:\w+\.)?(\w+)[: ]"])    ; H2

(defn constraint-name
  "The constraint or index the driver says refused the write, or nil. The name
   only, never the values beside it, so it can be logged. PostgreSQL's
   `:constraint`, in any language, before its message."
  [{:keys [message constraint]}]
  (or constraint (some #(second (re-find % (str message))) name-patterns)))

(def ^:private value-free-classes
  "SQLState classes whose messages name things, never the values sent:
   connection, authorization, catalog, rollback (deadlock, serialization),
   resources, operator intervention (timeouts), unsupported features,
   transaction state (BOU-629)."
  #{"08" "28" "3D" "40" "53" "57" "0A" "25"})

(def ^:private value-free-states
  "Class 42 states that name an object only. Not the class: a syntax error
   quotes the SQL near it, and MySQL's has the parameters filled in."
  #{"42P01" "42S02" "42703" "42S22" "42883" "42P07" "42S01" "42704"})

(defn redacted-message
  "What to log in place of the driver's message, or nil when that can be
   logged as is. Redacted unless its SQLState is known to quote no values, or
   it has none (SQLite, whose messages name only columns): data and integrity
   errors quote what was sent (BOU-626), and so may MySQL's HY000 or a
   PostgreSQL trigger's RAISE (BOU-629). Names, which hold no values, are kept.
   A RAISE, which PostgreSQL reports from `exec_stmt_raise`, is redacted
   whatever SQLState it claims, names and all: they are its author's text
   (BOU-635)."
  [{:keys [sql-state error-code message routine] :as reported}]
  (let [raise?      (= "exec_stmt_raise" routine)
        state-class (some-> sql-state (subs 0 (min 2 (count sql-state))))
        what        (if raise?
                      "Database error"
                      (case state-class "22" "Invalid data" "23" "Integrity constraint violated" "Database error"))
        prefix      (str what " (SQLState " sql-state)]
    (when (and state-class
               (or raise? (and (not (value-free-classes state-class)) (not (value-free-states sql-state)))))
      ;; A message this already redacted is kept, so the names it holds survive
      ;; a second pass over the same cause chain.
      (if (str/starts-with? (str message) prefix)
        message
        (str prefix
             (if (= "Database error" what)
               (when (and error-code (not (zero? error-code)))
                 (str ", code " error-code))
               ;; Only a data or integrity error's text is the driver's own: a
               ;; RAISE or SIGNAL could put a value where a name would be.
               (some->> (constraint-name reported) (str ", constraint ")))
             (when-not raise? (some->> (not-null-field reported) name (str ", field ")))
             ")")))))

(defn- causes
  "`e` and its causes, outermost first. Stops at a repeat: a cyclic chain would
   otherwise never end."
  [e]
  (loop [x e acc []]
    (if (or (nil? x) (some #(identical? x %) acc))
      acc
      (recur (ex-cause x) (conj acc x)))))

(defn first-sql-exception
  "The first SQLException in `e`'s causes, or nil."
  [e]
  (some #(when (instance? SQLException %) %) (causes e)))

(defn- pg-routine
  "The server routine a PostgreSQL error came from, or nil. By reflection: the
   driver is loaded at runtime, not compiled against."
  [^SQLException se]
  (when (= "org.postgresql.util.PSQLException" (.getName (class se)))
    (try
      (some-> (Reflector/invokeInstanceMethod se "getServerErrorMessage" (object-array 0))
              (Reflector/invokeInstanceMethod "getRoutine" (object-array 0)))
      (catch Exception _ nil))))

(defn- summary
  "What `redacted-message` says for `se`, or nil."
  [^SQLException se]
  (redacted-message {:sql-state  (.getSQLState se)
                     :error-code (.getErrorCode se)
                     :message    (.getMessage se)
                     :routine    (pg-routine se)}))

(defn- reachable
  "`t`, its causes and what is suppressed on any of them, each once."
  [^Throwable t]
  (loop [todo [t] seen []]
    (if-let [^Throwable x (first todo)]
      (if (some #(identical? x %) seen)
        (recur (rest todo) seen)
        (recur (concat (rest todo) (keep identity [(ex-cause x)]) (seq (.getSuppressed x)))
               (conj seen x)))
      seen)))

(defn redacted-exception
  "`t`, or a copy in which each SQLException that quotes the values sent says
   what `redacted-message` says instead, for an error reporter or log: its
   causes and what is suppressed on them alike (BOU-630, BOU-635). Same
   messages, data and stack otherwise. A driver subclass becomes a
   SQLException naming it; a cause that is neither that nor ex-info, a
   RuntimeException naming its class."
  [^Throwable t]
  (let [;; Only what would change: an already-redacted copy comes back as is.
        redacted (into {} (keep #(when (instance? SQLException %)
                                   (let [s (summary %)]
                                     (when (and s (not= s (.getMessage ^SQLException %))) [% s]))))
                       (reachable t))
        ;; A wrapper's message can carry the driver's: RuntimeException(cause)
        ;; and ExecutionException use cause.toString().
        scrub    (fn [msg]
                   (when msg
                     (reduce-kv (fn [m ^SQLException se s]
                                  (if-let [quoted (.getMessage se)] (str/replace m quoted s) m))
                                msg redacted)))
        one      (fn [^Throwable x ^Throwable cause]
                   (cond
                     (instance? SQLException x)
                     (let [^SQLException se x
                           named (if (and (contains? redacted x) (not= SQLException (class x)))
                                   (str (get redacted x) " (" (.getName (class x)) ")")
                                   (get redacted x (scrub (.getMessage se))))]
                       (SQLException. ^String named (.getSQLState se) (.getErrorCode se) cause))
                     ;; ex-data too: `{:error (.getMessage e)}` is a common shape.
                     (instance? clojure.lang.ExceptionInfo x)
                     (ex-info (scrub (ex-message x))
                              (walk/postwalk #(if (string? %) (scrub %) %) (ex-data x))
                              cause)
                     :else (RuntimeException. (str (.getName (class x)) ": " (scrub (.getMessage x))) cause)))
        copy     (fn copy [^Throwable x seen]
                   ;; Innermost first, so each copy is built around its copied
                   ;; cause; `seen` keeps a suppressed cycle from recurring.
                   (let [chain (causes x)
                         seen  (into seen chain)]
                     (reduce (fn [cause ^Throwable e]
                               (let [^Throwable c (doto ^Throwable (one e cause)
                                                    (.setStackTrace (.getStackTrace e)))]
                                 (doseq [^Throwable s (.getSuppressed e)
                                         :when (not-any? #(identical? s %) seen)]
                                   (.addSuppressed c (copy s seen)))
                                 c))
                             nil
                             (reverse chain))))]
    (if (empty? redacted) t (copy t []))))

(defn loggable-message
  "`e`'s message, or a summary without the values when the driver's quotes them."
  [e]
  (or (some-> (first-sql-exception e) summary) (ex-message e)))
