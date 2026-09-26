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

  (testing "MySQL"
    ;; Captured from MySQL 8.4 with STRICT_TRANS_TABLES: an explicit NULL, and
    ;; a column the INSERT leaves out.
    (is (= "status" (db-errors/not-null-violation-column "Column 'status' cannot be null")))
    (is (= "status" (db-errors/not-null-violation-column "Field 'status' doesn't have a default value"))))

  (testing "a foreign-key violation is not one"
    (is (nil? (db-errors/not-null-violation-column "[SQLITE_CONSTRAINT_FOREIGNKEY] A foreign key constraint failed (FOREIGN KEY constraint failed)"))))

  (testing "anything else"
    (is (nil? (db-errors/not-null-violation-column "UNIQUE constraint failed: invoices.number")))
    (is (nil? (db-errors/not-null-violation-column "Database query failed")))
    (is (nil? (db-errors/not-null-violation-column nil)))))

(deftest ^:unit foreign-key-violation-test
  ;; An insert naming a parent row that does not exist (BOU-540). H2 2.x,
  ;; PostgreSQL and SQLite captured from foreign_key_violation_test, MySQL 8 by
  ;; hand (error 1452).
  (testing "H2"
    (is (= {:column "customer_id"}
           (db-errors/foreign-key-violation
            "Referential integrity constraint violation: \"CONSTRAINT_C3: public.orders FOREIGN KEY(customer_id) REFERENCES public.customers(id) ('00000000-0000-0000-0000-00000000dead')\"; SQL statement:")))
    (is (= {:column "customer_id"}
           (db-errors/foreign-key-violation
            "Referential integrity constraint violation: \"FK_ORDERS: PUBLIC.ORDERS FOREIGN KEY(CUSTOMER_ID) REFERENCES PUBLIC.CUSTOMERS(ID) ('x')\"; SQL statement:"))
        "H2 without DATABASE_TO_LOWER"))

  (testing "PostgreSQL"
    (is (= {:column "customer_id"}
           (db-errors/foreign-key-violation
            "ERROR: insert or update on table \"orders\" violates foreign key constraint \"orders_customer_id_fkey\"\n  Detail: Key (customer_id)=(00000000-0000-0000-0000-00000000dead) is not present in table \"customers\".")))
    (is (= {:column nil}
           (db-errors/foreign-key-violation
            "ERROR: insert or update on table \"orders\" violates foreign key constraint \"orders_customer_id_fkey\""))
        "without the Detail line it is still one, on no column"))

  (testing "SQLite names no column"
    (is (= {:column nil}
           (db-errors/foreign-key-violation
            "[SQLITE_CONSTRAINT_FOREIGNKEY] A foreign key constraint failed (FOREIGN KEY constraint failed)"))))

  (testing "MySQL"
    (is (= {:column "customer_id"}
           (db-errors/foreign-key-violation
            "Cannot add or update a child row: a foreign key constraint fails (`shop`.`orders`, CONSTRAINT `orders_ibfk_1` FOREIGN KEY (`customer_id`) REFERENCES `customers` (`id`))"))))

  (testing "anything else"
    (is (nil? (db-errors/foreign-key-violation "NULL not allowed for column \"status\"; SQL statement:")))
    (is (nil? (db-errors/foreign-key-violation "UNIQUE constraint failed: invoices.number")))
    (is (nil? (db-errors/foreign-key-violation nil)))))
