(ns wagoe.platform.core.database.constraint
  "Which integrity constraint a database refused a write on, read from what the
   driver reports: SQLState, vendor code and message. H2, PostgreSQL, SQLite
   and MySQL."
  (:require [clojure.string :as str]
            [wagoe.observability.core.sql-redaction :as redaction]))

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

(def not-null-field
  "The field a refused NOT NULL names. See `redaction/not-null-field`."
  redaction/not-null-field)

(def constraint-name
  "The constraint that refused the write. See `redaction/constraint-name`."
  redaction/constraint-name)

(def redacted-message
  "What to log in place of the driver's message. See `redaction/redacted-message`."
  redaction/redacted-message)

(defn violation
  "`{:kind :unique|:foreign-key :field kw}` for a unique or foreign-key
   violation, `:field` only when the driver names one column, and
   `:referenced? true` when the refused row is one another still references;
   nil for any other failure."
  [{:keys [message detail] :as reported}]
  (when-let [k (kind reported)]
    ;; PostgreSQL names the column in its Detail, which `reported` keeps apart.
    (let [text  (str message "\n" detail)
          field (some #(some-> (re-find % text) second redaction/column->field) column-patterns)]
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
