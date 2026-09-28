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
