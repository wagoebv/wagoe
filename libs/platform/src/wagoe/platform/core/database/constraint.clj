(ns wagoe.platform.core.database.constraint
  "Which integrity constraint a database refused a write on, read from what the
   driver reports: SQLState, vendor code and message. H2, PostgreSQL, SQLite
   and MySQL."
  (:require [clojure.string :as str]))

(defn- kind
  [{:keys [sql-state error-code message]}]
  (let [message (str message)]
    (cond
      (or (= "23505" sql-state) (= 1062 error-code)
          (re-find #"SQLITE_CONSTRAINT_(?:UNIQUE|PRIMARYKEY)" message))
      :unique

      (or (#{"23503" "23506"} sql-state) (#{1451 1452} error-code)
          (re-find #"SQLITE_CONSTRAINT_FOREIGNKEY" message))
      :foreign-key)))

(def ^:private column-patterns
  [#"UNIQUE constraint failed: ([^()]+)"          ; SQLite, table.column[, ...]
   #"Key \(([^)]+)\)="                            ; PostgreSQL Detail
   #"FOREIGN KEY ?\(([^)]+)\)"                    ; H2, MySQL
   #"ON [^\s(]+\(([^)]+)\)"])                     ; H2 unique index

(defn- column->field
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

(def ^:private statement-table-pattern
  (let [id "(?:\"[^\"]+\"|`[^`]+`|[\\p{L}_][\\p{L}\\p{N}_$]*)"]
    (re-pattern (str "(?i)^\\s*(?:insert\\s+into|update(?:\\s+only)?|delete\\s+from(?:\\s+only)?)\\s+"
                     "(?:" id "\\.)?(" id ")"))))

(defn statement-table
  "The table an INSERT, UPDATE or DELETE in `sql` writes, lower case, or nil."
  [sql]
  (some-> (re-find statement-table-pattern (str sql))
          second
          (str/replace #"[\"`]" "")
          str/lower-case))

(defn- still-referenced?
  "Whether a foreign key refused removing or re-keying a row another row
   references, rather than a reference to a missing row. Read from H2's and
   MySQL's codes, PostgreSQL's English message, or else, in any language, its
   referencing `:table` against the statement's `:target` (BOU-627)."
  [{:keys [error-code message table target]}]
  (let [message (str message)]
    ;; The message wins when it is English: a trigger, partition or view writes
    ;; another table than the statement names. A self-referencing key in another
    ;; language reads as a missing reference.
    (boolean (cond
               (#{23503 1451} error-code)                     true
               (re-find #"update or delete on table" message) true
               (re-find #"insert or update on table" message) false
               :else (and table target (not= (str/lower-case table) target))))))

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

(defn redacted-message
  "What to log in place of the driver's message, or nil when that can be
   logged as is. Data and integrity errors (SQLState class 22, 23) quote the
   values sent: MySQL's duplicate entry, H2's VALUES, PostgreSQL's bad input
   (BOU-626). Names, which hold no values, are kept."
  [{:keys [sql-state message] :as reported}]
  (when-let [what (condp re-find (str sql-state)
                    #"^22" "Invalid data"
                    #"^23" "Integrity constraint violated"
                    nil)]
    ;; A message this already redacted is kept, so the names it holds survive
    ;; a second pass over the same cause chain.
    (if (re-find #"^(?:Invalid data|Integrity constraint violated) \(SQLState " (str message))
      message
      (str what " (SQLState " sql-state
           (some->> (constraint-name reported) (str ", constraint "))
           (some->> (not-null-field reported) name (str ", field "))
           ")"))))

(defn violation
  "`{:kind :unique|:foreign-key :field kw}` for a unique or foreign-key
   violation, `:field` only when the driver names one column, and
   `:referenced? true` when the refused row is one another still references;
   nil for any other failure."
  [{:keys [message detail] :as reported}]
  (when-let [k (kind reported)]
    ;; PostgreSQL names the column in its Detail, which `reported` keeps apart.
    (let [text  (str message "\n" detail)
          field (some #(some-> (re-find % text) second column->field) column-patterns)]
      (cond-> {:kind k}
        field (assoc :field field)
        (and (= :foreign-key k) (still-referenced? reported)) (assoc :referenced? true)))))

(defn already-exists?
  "Whether the driver reports that the index or column a statement adds is
   already there (BOU-607)."
  [{:keys [error-code message]}]
  (boolean (or (#{1060 1061} error-code)
               (re-find #"duplicate column name" (str message)))))

(defn already-gone?
  "Whether the driver reports that the index or column a statement drops is not
   there (BOU-607)."
  [{:keys [error-code message]}]
  (boolean (or (= 1091 error-code)
               ;; Anchored: a view or trigger that breaks on the drop reports
               ;; "(error in view v after drop column: no such column: c)".
               (re-find #"\(no such column: " (str message)))))
