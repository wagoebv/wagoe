(ns wagoe.admin.core.db-errors
  "Reading database constraint errors the admin can explain to a user."
  (:require [clojure.string :as str]))

(def ^:private not-null-patterns
  [#"NOT NULL constraint failed: (?:[^.\s()]+\.)?([^\s()]+)" ; SQLite
   #"null value in column \"([^\"]+)\""                     ; PostgreSQL
   #"NULL not allowed for column \"([^\"]+)\""])            ; H2

(defn not-null-violation-column
  "The column a NOT NULL violation `message` names, in lower case, or nil when
   the message is not one. Reads the H2, PostgreSQL and SQLite texts."
  [message]
  (when message
    (some (fn [pattern]
            (some-> (re-find pattern message) second str/lower-case))
          not-null-patterns)))
