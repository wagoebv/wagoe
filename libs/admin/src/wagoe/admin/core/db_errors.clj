(ns wagoe.admin.core.db-errors
  "Reading database constraint errors the admin can explain to a user."
  (:require [clojure.string :as str]))

(def ^:private not-null-patterns
  [#"NOT NULL constraint failed: (?:[^.\s()]+\.)?([^\s()]+)" ; SQLite
   #"null value in column \"([^\"]+)\""                      ; PostgreSQL
   #"NULL not allowed for column \"([^\"]+)\""               ; H2
   #"Column '([^']+)' cannot be null"                        ; MySQL, explicit NULL
   #"Field '([^']+)' doesn't have a default value"])         ; MySQL, column left out

(defn not-null-violation-column
  "The column a NOT NULL violation `message` names, in lower case, or nil when
   the message is not one. Reads the H2, PostgreSQL, SQLite and MySQL texts."
  [message]
  (when message
    (some (fn [pattern]
            (some-> (re-find pattern message) second str/lower-case))
          not-null-patterns)))

(def ^:private foreign-key-patterns
  [#"Referential integrity constraint violation: \".*?FOREIGN KEY\(\"?([^)\"]+)\"?\)" ; H2
   #"(?s)violates foreign key constraint.*?Key \(([^)]+)\)="                             ; PostgreSQL
   #"a foreign key constraint fails .*?FOREIGN KEY \(`([^`]+)`\)"                         ; MySQL
   #"FOREIGN KEY constraint failed()"                                                     ; SQLite, names no column
   #"violates foreign key constraint()"])                                                 ; PostgreSQL without its Detail

(defn foreign-key-violation
  "`{:column c}` when `message` is a foreign-key violation, c in lower case or
   nil when the driver does not name it (SQLite); nil when it is not one."
  [message]
  (when message
    (some (fn [pattern]
            (when-let [[_ column] (re-find pattern message)]
              {:column (not-empty (str/lower-case column))}))
          foreign-key-patterns)))
