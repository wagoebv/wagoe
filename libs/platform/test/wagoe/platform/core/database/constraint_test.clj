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

(deftest ^:unit a-row-still-referenced-is-told-apart-from-a-missing-one
  ;; Read from the driver, so an UPDATE of a referenced key or a raw-SQL DELETE
  ;; does not answer "A referenced record does not exist".
  (is (:referenced? (constraint/violation
                     {:sql-state "23503"
                      :message "ERROR: update or delete on table \"invoices\" violates foreign key constraint \"lines_invoice_id_fkey\" on table \"lines\"\n  Detail: Key (id)=(abc) is still referenced from table \"lines\"."}))
      "PostgreSQL")
  (is (:referenced? (constraint/violation {:sql-state "23503" :error-code 23503
                                           :message "Referential integrity constraint violation: \"CONSTRAINT_4: PUBLIC.LINES FOREIGN KEY(INVOICE_ID) REFERENCES PUBLIC.INVOICES(ID) ('abc')\""}))
      "H2")
  (is (:referenced? (constraint/violation {:sql-state "23000" :error-code 1451
                                           :message "Cannot delete or update a parent row: a foreign key constraint fails"}))
      "MySQL")
  (is (not (:referenced? (constraint/violation {:sql-state "23506" :error-code 23506
                                                :message "Referential integrity constraint violation"})))
      "H2's missing parent"))

(deftest ^:unit the-constraint-is-named-without-the-values
  (is (= "lines_invoice_id_fkey"
         (constraint/constraint-name
          {:message "ERROR: insert or update on table \"lines\" violates foreign key constraint \"lines_invoice_id_fkey\"\n  Detail: Key (invoice_id)=(abc) is not present in table \"invoices\"."}))
      "PostgreSQL")
  (is (= "CONSTRAINT_4"
         (constraint/constraint-name
          {:message "Referential integrity constraint violation: \"CONSTRAINT_4: PUBLIC.LINES FOREIGN KEY(INVOICE_ID) REFERENCES PUBLIC.INVOICES(ID) ('abc')\""}))
      "H2 foreign key")
  (is (= "CONSTRAINT_INDEX_9"
         (constraint/constraint-name
          {:message "Unique index or primary key violation: \"PUBLIC.CONSTRAINT_INDEX_9 ON PUBLIC.INVOICES(NUMBER NULLS FIRST) VALUES ( /* 1 */ 'A-1' )\""}))
      "H2 unique")
  (is (= "fk" (constraint/constraint-name
               {:message "Cannot add or update a child row: a foreign key constraint fails (`app`.`lines`, CONSTRAINT `fk` FOREIGN KEY (`invoice_id`) REFERENCES `invoices` (`id`))"}))
      "MySQL foreign key")
  (is (= "invoices.number" (constraint/constraint-name {:message "Duplicate entry 'A-1' for key 'invoices.number'"}))
      "MySQL unique")
  (is (nil? (constraint/constraint-name {:message "[SQLITE_CONSTRAINT_UNIQUE] A UNIQUE constraint failed (UNIQUE constraint failed: invoices.number)"}))
      "SQLite names none"))

(deftest ^:unit data-and-integrity-errors-are-logged-without-their-values
  (is (= "Integrity constraint violated (SQLState 23514, constraint users_age_check)"
         (constraint/redacted-message
          {:sql-state "23514"
           :message "ERROR: new row for relation \"users\" violates check constraint \"users_age_check\"\n  Detail: Failing row contains (1, -1, alice@example.com)."}))
      "PostgreSQL's whole row")
  (is (= "Integrity constraint violated (SQLState 23000, constraint invoices.number)"
         (constraint/redacted-message {:sql-state "23000" :error-code 1062
                                       :message "Duplicate entry 'alice@example.com' for key 'invoices.number'"}))
      "MySQL's duplicate")
  (is (= "Invalid data (SQLState 22P02)"
         (constraint/redacted-message {:sql-state "22P02" :message "ERROR: invalid input syntax for type uuid: \"alice\""})))
  (is (= "Integrity constraint violated (SQLState 23502, field email)"
         (constraint/redacted-message {:sql-state "23502" :message "ERROR: null value in column \"email\" of relation \"users\" violates not-null constraint"}))
      "a NOT NULL keeps its field")
  (is (nil? (constraint/redacted-message {:sql-state "42P01" :message "ERROR: relation \"users\" does not exist"}))
      "anything else is logged as the driver said it")
  (is (nil? (constraint/redacted-message {:message "[SQLITE_CONSTRAINT_NOTNULL] NOT NULL constraint failed: users.email"}))
      "SQLite gives no state, and quotes no values"))

(deftest ^:unit a-not-null-names-its-field
  (is (= :email (constraint/not-null-field {:message "ERROR: null value in column \"email\" of relation \"users\" violates not-null constraint"}))
      "PostgreSQL")
  (is (= :invoice-number (constraint/not-null-field {:message "NULL not allowed for column \"INVOICE_NUMBER\"; SQL statement:"}))
      "H2")
  (is (= :email (constraint/not-null-field {:message "[SQLITE_CONSTRAINT_NOTNULL] A NOT NULL constraint failed (NOT NULL constraint failed: users.email)"}))
      "SQLite")
  (is (= :email (constraint/not-null-field {:message "Column 'email' cannot be null"})) "MySQL")
  (is (nil? (constraint/not-null-field {:message "Duplicate entry 'a' for key 'users.email'"}))))

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
