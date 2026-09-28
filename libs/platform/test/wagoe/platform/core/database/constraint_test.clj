(ns wagoe.platform.core.database.constraint-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.platform.core.database.constraint :as constraint]))

(deftest ^:unit unique-violations-by-driver
  (testing "SQLite: its result code, and the column after the table"
    (is (= {:kind :unique :field :number}
           (constraint/violation {:error-code 19
                                  :message "[SQLITE_CONSTRAINT_UNIQUE] A UNIQUE constraint failed (UNIQUE constraint failed: invoices.number)"})))
    (is (= {:kind :unique :field :invoice-number}
           (constraint/violation {:message "[SQLITE_CONSTRAINT_UNIQUE] A UNIQUE constraint failed (UNIQUE constraint failed: invoices.invoice_number)"}))))
  (testing "PostgreSQL: 23505, the column from its Detail"
    (is (= {:kind :unique :field :number}
           (constraint/violation {:sql-state "23505"
                                  :message "ERROR: duplicate key value violates unique constraint \"invoices_number_key\"\n  Detail: Key (number)=(A-1) already exists."})))
    (is (= {:kind :unique}
           (constraint/violation {:sql-state "23505"
                                  :message "ERROR: duplicate key value violates unique constraint \"invoices_number_key\""}))
        "without a Detail, no field"))
  (testing "H2: 23505, the column from the index"
    (is (= {:kind :unique :field :number}
           (constraint/violation {:sql-state "23505" :error-code 23505
                                  :message "Unique index or primary key violation: \"PUBLIC.CONSTRAINT_INDEX_9 ON PUBLIC.INVOICES(NUMBER NULLS FIRST) VALUES ( /* 1 */ 'A-1' )\"; SQL statement:\nINSERT INTO invoices (id, number) VALUES (?, ?) [23505-224]"}))))
  (testing "MySQL: 1062, which names an index, not a column"
    (is (= {:kind :unique}
           (constraint/violation {:sql-state "23000" :error-code 1062
                                  :message "Duplicate entry 'A-1' for key 'invoices.number'"}))))
  (testing "a key over several columns names none"
    (is (= {:kind :unique}
           (constraint/violation {:message "[SQLITE_CONSTRAINT_UNIQUE] A UNIQUE constraint failed (UNIQUE constraint failed: lines.invoice_id, lines.position)"})))
    (is (= {:kind :unique}
           (constraint/violation {:sql-state "23505" :message "Detail: Key (invoice_id, position)=(x, 1) already exists."})))))

(deftest ^:unit foreign-key-violations-by-driver
  (is (= {:kind :foreign-key}
         (constraint/violation {:error-code 19
                                :message "[SQLITE_CONSTRAINT_FOREIGNKEY] A foreign key constraint failed (FOREIGN KEY constraint failed)"}))
      "SQLite names no column")
  (is (= {:kind :foreign-key :field :invoice-id}
         (constraint/violation {:sql-state "23503"
                                :message "ERROR: insert or update on table \"lines\" violates foreign key constraint \"lines_invoice_id_fkey\"\n  Detail: Key (invoice_id)=(abc) is not present in table \"invoices\"."})))
  (is (= {:kind :foreign-key :field :invoice-id}
         (constraint/violation {:sql-state "23506" :error-code 23506
                                :message "Referential integrity constraint violation: \"CONSTRAINT_4: PUBLIC.LINES FOREIGN KEY(INVOICE_ID) REFERENCES PUBLIC.INVOICES(ID) ('abc')\"; SQL statement:"})))
  (is (= {:kind :foreign-key :field :invoice-id}
         (constraint/violation {:sql-state "23000" :error-code 1452
                                :message "Cannot add or update a child row: a foreign key constraint fails (`app`.`lines`, CONSTRAINT `fk` FOREIGN KEY (`invoice_id`) REFERENCES `invoices` (`id`))"}))))

(deftest ^:unit other-failures-are-not-violations
  (is (nil? (constraint/violation {:sql-state "23502" :message "null value in column \"number\" violates not-null constraint"})))
  (is (nil? (constraint/violation {:error-code 19 :message "[SQLITE_CONSTRAINT_NOTNULL] A NOT NULL constraint failed (NOT NULL constraint failed: invoices.number)"})))
  (is (nil? (constraint/violation {:sql-state "42P01" :message "relation \"nope\" does not exist"})))
  (is (nil? (constraint/violation {:sql-state "23000" :error-code 1048 :message "Column 'number' cannot be null"})))
  (is (nil? (constraint/violation {}))))

(deftest ^:unit a-field-is-only-ever-an-identifier
  ;; Anything else in the message may be a value the client sent, or SQL.
  (is (= {:kind :unique}
         (constraint/violation {:sql-state "23505" :message "Detail: Key (lower(email))=(a@b.c) already exists."}))))
