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

(defn- still-referenced?
  "Whether a foreign key refused removing or re-keying a row another row
   references, rather than a reference to a missing row: H2's 23503 vendor code,
   MySQL's 1451, PostgreSQL's message. SQLite does not say."
  [{:keys [error-code message]}]
  (boolean (or (#{23503 1451} error-code)
               (re-find #"update or delete on table" (str message)))))

(def ^:private not-null-patterns
  [#"NOT NULL constraint failed: (?:[^.\s()]+\.)?([^\s()]+)" ; SQLite
   #"null value in column \"([^\"]+)\""                      ; PostgreSQL
   #"NULL not allowed for column \"([^\"]+)\""               ; H2
   #"Column '([^']+)' cannot be null"                        ; MySQL, explicit NULL
   #"Field '([^']+)' doesn't have a default value"])         ; MySQL, column left out

(defn not-null-field
  "The field a refused NOT NULL names, as a kebab keyword, or nil when
   `reported` is not one. Read only when the state or code says NOT NULL, or
   there is no state (SQLite): other messages quote values a pattern could match."
  [{:keys [sql-state error-code message]}]
  (when (or (nil? sql-state) (= "23502" sql-state) (#{1048 1364} error-code))
    (some #(some-> (re-find % (str message)) second column->field) not-null-patterns)))

(def ^:private name-patterns
  [#"constraint \"([\w$.]+)\""              ; PostgreSQL
   #"CONSTRAINT `([\w$.]+)`"                ; MySQL foreign key
   #"for key '([\w$.]+)'"                   ; MySQL unique
   #"violation: \"(?:\w+\.)?(\w+)[: ]"])    ; H2

(defn constraint-name
  "The constraint or index the driver says refused the write, or nil. The name
   only, never the values beside it, so it can be logged."
  [{:keys [message]}]
  (some #(second (re-find % (str message))) name-patterns))

(def ^:private value-free-classes
  "SQLState classes whose messages name things, never the values sent:
   connection, authorization, catalog, rollback (deadlock, serialization),
   syntax and access rules, resources, operator intervention (timeouts),
   unsupported features, transaction state (BOU-629)."
  #{"08" "28" "3D" "40" "42" "53" "57" "0A" "25"})

(defn redacted-message
  "What to log in place of the driver's message, or nil when that can be
   logged as is. Redacted unless its SQLState is known to quote no values, or
   it has none (SQLite, whose messages name only columns): data and integrity
   errors quote what was sent (BOU-626), and so may MySQL's HY000 or a
   PostgreSQL trigger's RAISE (BOU-629). Names, which hold no values, are kept."
  [{:keys [sql-state error-code message] :as reported}]
  (let [class (some-> sql-state (subs 0 (min 2 (count sql-state))))]
    (when (and class (not (value-free-classes class)))
      ;; A message this already redacted is kept, so the names it holds survive
      ;; a second pass over the same cause chain.
      (if (re-find #"^(?:Invalid data|Integrity constraint violated|Database error) \(SQLState " (str message))
        message
        (str (case class "22" "Invalid data" "23" "Integrity constraint violated" "Database error")
             " (SQLState " sql-state
             (when (and (not (#{"22" "23"} class)) error-code (not (zero? error-code)))
               (str ", code " error-code))
             (some->> (constraint-name reported) (str ", constraint "))
             (some->> (not-null-field reported) name (str ", field "))
             ")")))))

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
