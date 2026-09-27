(ns wagoe.platform.core.database.migration-sql
  "Migration SQL written for PostgreSQL, H2 and SQLite, rewritten for MySQL.

   The scaffolder writes one migration for every database (BOU-569). MySQL has
   no UUID or zoned TIMESTAMP type, takes no IF NOT EXISTS on an index, and
   ignores a REFERENCES written on a column. Only that is rewritten, so SQL
   written for MySQL passes through as it is."
  (:require [clojure.string :as str]))

(def ^:private types
  "Type names MySQL lacks. Upper case only, and `UUID(` is MySQL's function."
  [[#"\bTIMESTAMP WITH TIME ZONE\b" "DATETIME(6)"]
   [#"\bUUID\b(?!\s*\()" "CHAR(36)"]
   [#"\bJSONB\b" "JSON"]])

(def ^:private on-delete
  "(?:\\s+ON\\s+DELETE\\s+(?:CASCADE|RESTRICT|SET\\s+NULL|SET\\s+DEFAULT|NO\\s+ACTION))?")

(def ^:private column-reference
  (re-pattern (str "(?im)^(\\s*)(?!FOREIGN\\b|CONSTRAINT\\b|PRIMARY\\b|UNIQUE\\b|CHECK\\b)(\\w+)(\\s[^,\\n]*?)"
                   "\\s+REFERENCES\\s+(\\w+)\\s*\\((\\w+)\\)(" on-delete ")")))

(def ^:private added-column-reference
  (re-pattern (str "(?is)^(\\s*ALTER\\s+TABLE\\s+\\w+\\s+ADD\\s+(?:COLUMN\\s+)?(\\w+)\\s.*?)"
                   "\\s+REFERENCES\\s+(\\w+)\\s*\\((\\w+)\\)(" on-delete ")")))

(defn- matches? [re s] (boolean (re-find re s)))

(def ^:private literal #"'(?:[^']|'')*'")

(defn- outside-literals
  "`s` with `f` applied to every part outside a '…' string literal."
  [s f]
  (let [m (re-matcher literal s)]
    (loop [from 0 out (StringBuilder.)]
      (if (.find m)
        (recur (.end m) (doto out (.append (f (subs s from (.start m)))) (.append (.group m))))
        (str (.append out (f (subs s from))))))))

(defn- retype [s]
  (outside-literals s #(reduce (fn [s [re to]] (str/replace s re to)) % types)))

(def ^:private expression-default
  "A literal DEFAULT on a TEXT or JSON column: MySQL takes only an expression."
  (re-pattern (str "(?i)\\b(TEXT|JSON)\\b([^,\\n']*?\\bDEFAULT\\s+)(" literal ")")))

(def ^:private zulu-default
  "A timestamp DEFAULT ending in Z, which MySQL refuses; +00:00 it reads."
  #"(?i)(\bDEFAULT\s+'\d{4}-\d{2}-\d{2}[T ][\d:.]+)Z'")

(defn- redefault [s]
  (-> s
      (str/replace expression-default "$1$2($3)")
      (str/replace zulu-default "$1+00:00'")))

(defn- table-foreign-keys
  "A CREATE TABLE with each column REFERENCES moved to a table constraint."
  [s]
  (let [fks (mapv (fn [[_ _ col _ table key action]]
                    (str "FOREIGN KEY (" col ") REFERENCES " table "(" key ")" action))
                  (re-seq column-reference s))]
    (if (empty? fks)
      s
      (let [s     (str/replace s column-reference "$1$2$3")
            close (.lastIndexOf ^String s ")")]
        (str (str/trimr (subs s 0 close)) ",\n  "
             (str/join ",\n  " fks) "\n" (subs s close))))))

(defn for-mysql
  "`statement` as MySQL takes it: a vector of none or more statements. A
   vector of statements, as migratus passes its own batch, is rewritten one by
   one.

   Pure: true"
  [statement]
  (cond
    (sequential? statement)
    (into [] (mapcat for-mysql) statement)

    ;; MySQL drops a column's index with the column, and wants the table here.
    (matches? #"(?is)^\s*DROP\s+INDEX\s+IF\s+EXISTS\s+\w+\s*;?\s*$" statement)
    []

    (matches? #"(?i)^\s*CREATE\s+TABLE\b" statement)
    [(-> statement retype redefault table-foreign-keys)]

    (matches? #"(?i)^\s*ALTER\s+TABLE\b" statement)
    [(-> statement retype redefault
         (str/replace added-column-reference "$1, ADD FOREIGN KEY ($2) REFERENCES $3($4)$5"))]

    :else
    [(str/replace statement #"(?i)\bCREATE\s+(UNIQUE\s+|)INDEX\s+IF\s+NOT\s+EXISTS\b" "CREATE $1INDEX")]))
