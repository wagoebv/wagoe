(ns wagoe.platform.database-connection-lost-test
  "What the drivers threw on an unreachable database or an exhausted pool,
   read on HikariCP 7.1, PostgreSQL 42.7, MySQL Connector/J and H2 2.x."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.platform.database :as db])
  (:import (java.sql SQLException SQLNonTransientConnectionException SQLTransientConnectionException)))

(defn- sql [state] (SQLException. "x" ^String state))

(deftest ^:unit connection-lost?
  (testing "the database cannot be reached"
    (doseq [[what e] {"HikariCP pool timeout, no state"    (SQLTransientConnectionException. "Connection is not available")
                      "HikariCP over PostgreSQL refused"   (SQLTransientConnectionException. "x" "08001" (sql "08001"))
                      "PostgreSQL connection failure"      (sql "08006")
                      "MySQL communications link failure"  (sql "08S01")
                      "H2 connection broken"               (SQLNonTransientConnectionException. "x" "90067")
                      "H2 broken, redacted to a state"     (sql "90067")
                      "PostgreSQL shutting down"           (sql "57P01")
                      "under an audit entry's ex-info"     (ex-info "Could not audit" {:type :internal-error}
                                                                    (ex-info "Database query failed" {:type :database-error}
                                                                             (sql "08001")))
                      "next.jdbc's failed rollback"        (ex-info "Rollback failed handling \"x\""
                                                                    {:rollback (sql "08003") :handling (sql "08006")})}]
      (is (db/connection-lost? e) what)))
  (testing "a cyclic cause chain ends"
    (let [a (Exception. "a") b (Exception. "b" a)]
      (.initCause a b)
      (is (not (db/connection-lost? a)))))
  (testing "the database refused one statement"
    (doseq [[what e] {"a unique key"            (sql "23505")
                      "a deadlock"              (sql "40001")
                      "SQLite, no state"        (SQLException. "[SQLITE_CONSTRAINT_UNIQUE]")
                      "wrapped constraint"      (ex-info "Could not audit" {:type :internal-error} (sql "23503"))
                      "not a database error"    (ex-info "audit down" {:type :internal-error})}]
      (is (not (db/connection-lost? e)) what))))
