(ns wagoe.platform.core.database.connection-loss
  "Whether an exception says the database cannot be reached, rather than that
   it refused a statement. One definition, read by `wagoe.platform.database`,
   the loops that stop at a lost connection, and devtools' WGE-303 (BOU-970)."
  (:require [clojure.string :as str]))

(def ^:private lost-states
  "PostgreSQL shutting down or starting up; H2's broken or closed database."
  #{"57P01" "57P02" "57P03" "90067" "90098" "90121"})

(defn lost-itself?
  "Whether `t` itself, not its causes, says the database cannot be reached:
   SQLState class 08 (PostgreSQL, MySQL), a state in `lost-states`, or the
   JDBC connection exceptions HikariCP and H2 throw, a pool timeout with no
   state among them."
  [t]
  (or (instance? java.sql.SQLTransientConnectionException t)
      (instance? java.sql.SQLNonTransientConnectionException t)
      (when (instance? java.sql.SQLException t)
        (let [state (str (.getSQLState ^java.sql.SQLException t))]
          (or (str/starts-with? state "08")
              (contains? lost-states state))))))

(defn connection-lost?
  "Whether `e`, or anything that caused it, is `lost-itself?`. A failed
   rollback's ex-info from next.jdbc has no cause: it names the exceptions
   under :handling and :rollback."
  [e]
  ;; A set compares throwables by identity, so a cyclic chain ends
  (loop [todo (list e) seen #{}]
    (if-let [t (first todo)]
      (cond
        (seen t)         (recur (rest todo) seen)
        (lost-itself? t) true
        :else            (recur (into (rest todo)
                                      (filter #(instance? Throwable %))
                                      [(ex-cause t) (:handling (ex-data t)) (:rollback (ex-data t))])
                                (conj seen t)))
      false)))
