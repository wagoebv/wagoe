(ns wagoe.observability.core.sql-redaction
  "Keeping the values a database driver quotes out of what is logged, traced
   and reported (BOU-626, BOU-628, BOU-629). Pure, on the driver's SQLState,
   vendor code and message: every library reporting through observability gets
   it, whatever threw (BOU-633)."
  (:require [clojure.string :as str]
            [clojure.walk :as walk])
  (:import [java.sql SQLException]))

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
   PostgreSQL trigger's RAISE (BOU-629). Names, which hold no values, are kept."
  [{:keys [sql-state error-code message] :as reported}]
  (let [state-class (some-> sql-state (subs 0 (min 2 (count sql-state))))
        what        (case state-class "22" "Invalid data" "23" "Integrity constraint violated" "Database error")
        prefix      (str what " (SQLState " sql-state)]
    (when (and state-class (not (value-free-classes state-class)) (not (value-free-states sql-state)))
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
             (some->> (not-null-field reported) name (str ", field "))
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

(defn- summary
  "What `redacted-message` says for `se`, or nil."
  [^SQLException se]
  (redacted-message {:sql-state  (.getSQLState se)
                     :error-code (.getErrorCode se)
                     :message    (.getMessage se)}))

(defn redacted-exception
  "`t`, or a copy of its cause chain in which each SQLException that quotes the
   values sent says what `redacted-message` says instead, for an
   error reporter or log. Covers what the platform did not throw itself: a
   library calling next.jdbc directly (BOU-630). Same messages, data and stack
   otherwise; a cause that is neither SQLException nor ex-info becomes a
   RuntimeException naming its class."
  [^Throwable t]
  (let [chain    (causes t)
        ;; Only what would change: an already-redacted chain comes back as is.
        redacted (into {} (keep #(when (instance? SQLException %)
                                   (let [s (summary %)]
                                     (when (and s (not= s (.getMessage ^SQLException %))) [% s]))))
                       chain)
        ;; A wrapper's message can carry the driver's: RuntimeException(cause)
        ;; and ExecutionException use cause.toString().
        scrub    (fn [msg]
                   (when msg
                     (reduce-kv (fn [m ^SQLException se s]
                                  (if-let [quoted (.getMessage se)] (str/replace m quoted s) m))
                                msg redacted)))]
    (if (empty? redacted)
      t
      ;; Innermost first, so each copy is built around its copied cause.
      (reduce (fn [cause ^Throwable x]
                (doto ^Throwable
                 (cond
                   (instance? SQLException x)
                   (let [^SQLException se x]
                     (SQLException. ^String (get redacted x (scrub (.getMessage se)))
                                    (.getSQLState se) (.getErrorCode se) ^Throwable cause))
                   ;; ex-data too: `{:error (.getMessage e)}` is a common shape.
                   (instance? clojure.lang.ExceptionInfo x)
                   (ex-info (scrub (ex-message x))
                            (walk/postwalk #(if (string? %) (scrub %) %) (ex-data x))
                            cause)
                   :else (RuntimeException. (str (.getName (class x)) ": " (scrub (.getMessage x))) ^Throwable cause))
                  (.setStackTrace (.getStackTrace x))))
              nil
              (reverse chain)))))

(defn loggable-message
  "`e`'s message, or a summary without the values when the driver's quotes them."
  [e]
  (or (some-> (first-sql-exception e) summary) (ex-message e)))
