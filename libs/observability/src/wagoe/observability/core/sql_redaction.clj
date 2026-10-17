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
  [{:keys [sql-state error-code message column authored?]}]
  (when (and (not authored?)
             (or (nil? sql-state) (= "23502" sql-state) (#{1048 1364} error-code)))
    (or (some-> column column->field)
        (some #(some-> (re-find % (str message)) second column->field) not-null-patterns))))

(def ^:private name-patterns
  ;; MySQL's unique first: it ends the message, after the value it quotes,
  ;; which may hold anything the other patterns look for.
  [#"for key '([\w$.]+)'$"                  ; MySQL unique
   #"constraint \"([\w$.]+)\""              ; PostgreSQL
   #"CONSTRAINT `([\w$.]+)`"                ; MySQL foreign key
   #"violation: \"(?:\w+\.)?(\w+)[: ]"])    ; H2

(defn constraint-name
  "The constraint or index the driver says refused the write, or nil. The name
   only, never the values beside it, so it can be logged. PostgreSQL's
   `:constraint`, in any language, before its message. Nil for a RAISE, whose
   names are its author's text."
  [{:keys [message constraint authored?]}]
  (when-not authored?
    (or constraint (some #(second (re-find % (str message))) name-patterns))))

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

(def ^:private redacted-labels
  ["Invalid data" "Integrity constraint violated" "Database error"])

(defn redacted-message
  "What to log in place of the driver's message, or nil when that can be
   logged as is. Redacted unless its SQLState is known to quote no values, or
   it has none (SQLite, whose messages name only columns): data and integrity
   errors quote what was sent (BOU-626), and so may MySQL's HY000 or a
   PostgreSQL trigger's RAISE (BOU-629). Names, which hold no values, are kept.
   A RAISE (`:authored?`) is redacted whatever SQLState it claims, names and
   all: they are its author's text (BOU-635)."
  [{:keys [sql-state error-code message authored?] :as reported}]
  (let [state-class (some-> sql-state (subs 0 (min 2 (count sql-state))))
        what        (case state-class "22" "Invalid data" "23" "Integrity constraint violated" "Database error")]
    (cond
      (nil? state-class) nil
      ;; A message this already redacted is kept, so the names it holds survive
      ;; a second pass over the same cause chain, a RAISE's relabelled included.
      (some #(str/starts-with? (str message) (str % " (SQLState " sql-state)) redacted-labels) message
      authored? (str "Database error (SQLState " sql-state ")")
      (or (value-free-classes state-class) (value-free-states sql-state)) nil
      :else
      (str what " (SQLState " sql-state
           (if (= "Database error" what)
             (when (and error-code (not (zero? error-code)))
               (str ", code " error-code))
             ;; Only a data or integrity error's text is the driver's own: a
             ;; SIGNAL could put a value where a name would be.
             (some->> (constraint-name reported) (str ", constraint ")))
           (some->> (not-null-field reported) name (str ", field "))
           ")"))))

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

(defn- server-error
  "PostgreSQL's Detail, which logServerErrorDetail=false keeps out of the
   message, and the names and routine it reports in any language. By
   reflection: the driver is loaded at runtime, not compiled against."
  [^SQLException se]
  (when (= "org.postgresql.util.PSQLException" (.getName (class se)))
    (try
      (when-let [m (Reflector/invokeInstanceMethod se "getServerErrorMessage" (object-array 0))]
        (into {} (for [[k getter] {:detail "getDetail" :schema "getSchema" :table "getTable"
                                   :constraint "getConstraint" :column "getColumn"
                                   :routine "getRoutine"}
                       :let [v (Reflector/invokeInstanceMethod m getter (object-array 0))]
                       :when v]
                   [k v])))
      (catch Exception _ nil))))

(defn- reported-by
  "What `se` says. `:authored?` when user code wrote it: PostgreSQL reports a
   RAISE from `exec_stmt_raise`, and its author picks the state and names
   (BOU-635)."
  [^SQLException se]
  (let [r (merge {:sql-state  (.getSQLState se)
                  :error-code (.getErrorCode se)
                  :message    (.getMessage se)}
                 (server-error se))]
    (cond-> r (= "exec_stmt_raise" (:routine r)) (assoc :authored? true))))

(defn reported
  "What the driver said, from the first SQLException in `e`'s causes, or nil.
   `:message` and `:detail` may quote the values sent: parse them, do not log them."
  [e]
  (some-> (first-sql-exception e) reported-by))

(defn- summary
  "What `redacted-message` says for `se`, or nil."
  [^SQLException se]
  (redacted-message (reported-by se)))

(defn- reachable
  "`t`, its causes and what is suppressed on any of them, each once."
  [^Throwable t]
  ;; A set compares throwables by identity: they do not override equals.
  (loop [todo [t] seen #{}]
    (if-let [^Throwable x (first todo)]
      (if (seen x)
        (recur (rest todo) seen)
        (recur (concat (rest todo) (remove nil? (cons (ex-cause x) (.getSuppressed x))))
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
                           named (if-let [r (redacted x)]
                                   (cond-> r (not= SQLException (class x)) (str " (" (.getName (class x)) ")"))
                                   (scrub (.getMessage se)))]
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
                                         :when (not (seen s))]
                                   (.addSuppressed c (copy s seen)))
                                 c))
                             nil
                             (reverse chain))))]
    (if (empty? redacted) t (copy t #{}))))

(defn loggable-message
  "`e`'s message, or a summary without the values when the driver's quotes them."
  [e]
  (or (some-> (first-sql-exception e) summary) (ex-message e)))
