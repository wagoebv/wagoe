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

(defn- engine
  "Which driver reported this: its adapter's `:engine`, or else read from its
   codes. Each pattern below reads only its own engine's messages, where the
   column list has a fixed place."
  [{:keys [engine error-code message]}]
  (cond
    engine                                      engine
    (str/starts-with? (str message) "[SQLITE_") :sqlite
    (#{1062 1451 1452} error-code)              :mysql
    (#{23503 23505 23506} error-code)           :h2
    :else                                       :postgresql))

(defn- pg-key
  "The column list of PostgreSQL's Detail, `Key (cols)=(vals)`: its `:detail`,
   or the Detail line of a message that carries it."
  [{:keys [message detail]}]
  (some->> (or detail (second (re-find #"(?m)^\s*Detail: (.*)" (str message))))
           (re-find #"^Key \(([^)]+)\)=")
           second))

(defn- named-columns
  "The column names the driver's message gives, read only from where its
   engine puts them, so a value it quotes is never taken for one (BOU-721).
   MySQL's unique refusal names the key only."
  [reported]
  (let [message (str (:message reported))]
    (some-> (case (engine reported)
              ;; ... (UNIQUE constraint failed: t.a, t.b), at the end.
              :sqlite     (second (re-find #"\(UNIQUE constraint failed: ([^()]+)\)\s*$" message))
              :postgresql (pg-key reported)
              ;; The first quoted part, "[KEY INDEX ]IDX ON T(A, B) VALUES ..."
              ;; or "FK: T FOREIGN KEY(A) REFERENCES ...", in any language.
              :h2         (some #(second (re-find % message))
                                [#"^[^\"]*\"\S+(?: INDEX \S+)? ON [^\s(]+\(([^)]+)\)"
                                 #"^[^\"]*\"\S+: \S+ FOREIGN KEY\(([^)]+)\)"])
              :mysql      (second (re-find #"^[^(]*\([^,]+, CONSTRAINT `[^`]+` FOREIGN KEY \(([^)]+)\)" message)))
            (str/split #","))))

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

(defn with-columns
  "`v` naming the key's `columns`: `:field` for one, `:fields` for several
   (BOU-712). Unchanged when any of them is not a plain identifier."
  [v columns]
  (let [fields (mapv redaction/column->field columns)]
    (cond
      (or (empty? fields) (some nil? fields)) v
      (= 1 (count fields))                    (assoc v :field (first fields))
      :else                                   (assoc v :fields fields))))

(defn violation
  "`{:kind :unique|:foreign-key :field kw}` for a unique or foreign-key
   violation, `:field` when the driver names one column, `:fields` when it
   names several, and `:referenced? true` when the refused row is one another
   still references; nil for any other failure."
  [{:keys [authored?] :as reported}]
  ;; A RAISE claiming a key's state is not one: its author wrote it (BOU-635).
  (when-let [k (when-not authored? (kind reported))]
    (cond-> (with-columns {:kind k} (named-columns reported))
      (and (= :foreign-key k) (still-referenced? reported)) (assoc :referenced? true))))

(defn already-exists?
  "Whether the driver reports that the index, column or constraint a statement
   adds is already there (BOU-607, BOU-632)."
  [{:keys [sql-state error-code message]}]
  (boolean (or (#{1060 1061 3822 90045} error-code)
               (= "42710" sql-state)
               (re-find #"duplicate column name" (str message)))))

(defn already-gone?
  "Whether the driver reports that the index or column a statement drops is not
   there (BOU-607)."
  [{:keys [error-code message]}]
  (boolean (or (= 1091 error-code)
               ;; Anchored: a view or trigger that breaks on the drop reports
               ;; "(error in view v after drop column: no such column: c)".
               (re-find #"\(no such column: " (str message)))))
