(ns wagoe.admin.core.db-errors-test
  (:require [wagoe.admin.core.db-errors :as db-errors]
            [clojure.test :refer [deftest is testing]]))

^{:kaocha.testable/meta {:unit true :admin true}}

(deftest ^:unit not-null-violation-column-test
  ;; The texts each driver raises for an INSERT that omits a NOT NULL column
  ;; (BOU-494), captured from H2 2.5, sqlite-jdbc 3.53 and PostgreSQL 18.
  (testing "H2"
    (is (= "status" (db-errors/not-null-violation-column
                     "NULL not allowed for column \"status\"; SQL statement:\nINSERT INTO invoices (id) VALUES (?) [23502-250]")))
    (is (= "status" (db-errors/not-null-violation-column
                     "NULL not allowed for column \"STATUS\"; SQL statement:"))
        "H2 without DATABASE_TO_LOWER reports the column in upper case"))

  (testing "PostgreSQL"
    (is (= "status" (db-errors/not-null-violation-column
                     "ERROR: null value in column \"status\" of relation \"invoices\" violates not-null constraint\n  Detail: Failing row contains (a, null).")))
    (is (= "status" (db-errors/not-null-violation-column
                     "ERROR: null value in column \"status\" violates not-null constraint"))
        "PostgreSQL before 13 does not name the relation"))

  (testing "SQLite"
    (is (= "status" (db-errors/not-null-violation-column
                     "[SQLITE_CONSTRAINT_NOTNULL] A NOT NULL constraint failed (NOT NULL constraint failed: invoices.status)"))))

  (testing "anything else"
    (is (nil? (db-errors/not-null-violation-column "UNIQUE constraint failed: invoices.number")))
    (is (nil? (db-errors/not-null-violation-column "Database query failed")))
    (is (nil? (db-errors/not-null-violation-column nil)))))
