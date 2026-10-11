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

(defn violation
  "`{:kind :unique|:foreign-key :field kw}` for a unique or foreign-key
   violation, `:field` only when the driver names one column; nil for any
   other failure."
  [{:keys [message] :as reported}]
  (when-let [k (kind reported)]
    (let [field (some #(some-> (re-find % (str message)) second column->field) column-patterns)]
      (cond-> {:kind k}
        field (assoc :field field)))))

(defn already-exists?
  "Whether the driver reports that the index or column a statement adds is
   already there. Only engines without IF NOT EXISTS for it are asked: MySQL
   (ER_DUP_KEYNAME 1061, ER_DUP_FIELDNAME 1060) and SQLite for a column (BOU-607)."
  [{:keys [error-code message]}]
  (boolean (or (#{1060 1061} error-code)
               (re-find #"duplicate column name" (str message)))))

(defn already-gone?
  "Whether the driver reports that the index or column a statement drops is not
   there: MySQL ER_CANT_DROP_FIELD_OR_KEY (1091), SQLite \"no such column\"."
  [{:keys [error-code message]}]
  (boolean (or (= 1091 error-code)
               (re-find #"no such column" (str message)))))
