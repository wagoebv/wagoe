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

(defn violation
  "`{:kind :unique|:foreign-key :field kw}` for a unique or foreign-key
   violation, `:field` only when the driver names one column, and
   `:referenced? true` when the refused row is one another still references;
   nil for any other failure."
  [{:keys [message] :as reported}]
  (when-let [k (kind reported)]
    (let [field (some #(some-> (re-find % (str message)) second column->field) column-patterns)]
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
