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
  (is (= :email (constraint/not-null-field {:sql-state "23502" :message "ERROR: null value in column \"email\" of relation \"users\" violates not-null constraint"}))
      "PostgreSQL")
  (is (= :invoice-number (constraint/not-null-field {:sql-state "23502" :message "NULL not allowed for column \"INVOICE_NUMBER\"; SQL statement:"}))
      "H2")
  (is (= :email (constraint/not-null-field {:message "[SQLITE_CONSTRAINT_NOTNULL] A NOT NULL constraint failed (NOT NULL constraint failed: users.email)"}))
      "SQLite")
  (is (= :email (constraint/not-null-field {:sql-state "23000" :error-code 1048 :message "Column 'email' cannot be null"})) "MySQL")
  (is (nil? (constraint/not-null-field {:message "Duplicate entry 'a' for key 'users.email'"})))
  (is (nil? (constraint/not-null-field {:sql-state "22P02" :message "ERROR: invalid input syntax for type uuid: \"Column 'email' cannot be null\""}))
      "a value that reads like one is not"))

(deftest ^:unit redacted-unless-known-to-quote-no-values
  ;; One redacted and one kept state per engine (BOU-629).
  (testing "PostgreSQL"
    (is (= "Database error (SQLState P0001)"
           (constraint/redacted-message {:sql-state "P0001" :error-code 0 :message "ERROR: card 4111111111111111 refused"})))
    (is (nil? (constraint/redacted-message {:sql-state "42P01" :message "ERROR: relation \"users\" does not exist"})))
    (is (= "Database error (SQLState P0001)"
           (constraint/redacted-message {:sql-state "P0001" :error-code 0 :message "ERROR: violates constraint \"4111111111111111\""}))
        "a RAISE's text is not read for a name"))
  (testing "MySQL"
    (is (= "Database error (SQLState HY000, code 1366)"
           (constraint/redacted-message {:sql-state "HY000" :error-code 1366
                                         :message "Incorrect integer value: 'alice' for column 'age' at row 1"})))
    (is (nil? (constraint/redacted-message {:sql-state "42S02" :error-code 1146 :message "Table 'app.users' doesn't exist"})))
    (is (= "Database error (SQLState 42000, code 1064)"
           (constraint/redacted-message {:sql-state "42000" :error-code 1064
                                         :message "You have an error in your SQL syntax; check the manual near ''alice@example.com', 3)' at line 1"}))
        "a syntax error quotes the statement, parameters filled in"))
  (testing "H2"
    (is (= "Invalid data (SQLState 22018)"
           (constraint/redacted-message {:sql-state "22018" :error-code 22018 :message "Data conversion error converting \"alice\""})))
    (is (nil? (constraint/redacted-message {:sql-state "42S02" :error-code 42102 :message "Table \"USERS\" not found"}))))
  (testing "SQLite gives no state and quotes no values"
    (is (nil? (constraint/redacted-message {:error-code 1 :message "[SQLITE_ERROR] SQL error or missing database (no such table: users)"})))))

(deftest ^:unit a-redacted-message-is-kept
  (let [once (constraint/redacted-message {:sql-state "23505" :message "ERROR: duplicate key value violates unique constraint \"users_email_key\""})]
    (is (= "Integrity constraint violated (SQLState 23505, constraint users_email_key)" once))
    (is (= once (constraint/redacted-message {:sql-state "23505" :message once})))))

(deftest ^:unit the-referenced-side-is-read-from-the-tables-not-the-language
  ;; PostgreSQL names the referencing table either way; a statement on another
  ;; one refused because its row is still referenced (BOU-627).
  (let [german {:sql-state "23503" :error-code 0 :table "lines"
                :message "FEHLER: Aktualisieren oder Löschen in Tabelle »invoices« verletzt Fremdschlüssel-Constraint »lines_invoice_id_fkey« von Tabelle »lines«"}]
    (is (:referenced? (constraint/violation (assoc german :target "invoices"))) "re-keying the parent")
    (is (not (:referenced? (constraint/violation (assoc german :target "lines")))) "a child with a missing parent")))

(deftest ^:unit a-self-referencing-key-is-read-from-its-message
  ;; Same table on both sides: only the message says which way.
  (is (:referenced? (constraint/violation
                     {:sql-state "23503" :error-code 0 :table "categories" :target "categories"
                      :message "ERROR: update or delete on table \"categories\" violates foreign key constraint \"categories_parent_id_fkey\" on table \"categories\""}))))

(deftest ^:unit postgresql-names-are-read-in-any-language
  (let [german {:sql-state "23502" :column "email" :constraint "users_email_not_null"
                :message "FEHLER: NULL-Wert in Spalte »email« von Relation »users« verletzt Not-Null-Constraint"}]
    (is (= :email (constraint/not-null-field german)))
    (is (= "users_email_not_null" (constraint/constraint-name german)))))

(deftest ^:unit the-statement-names-its-table
  (is (= "invoices" (constraint/statement-table "UPDATE invoices SET id = ? WHERE id = ?")))
  (is (= "invoices" (constraint/statement-table "  delete from \"public\".\"invoices\" where id = ?")))
  (is (= "lines" (constraint/statement-table "INSERT INTO lines (id) VALUES (?)")))
  (is (= "invoices" (constraint/statement-table "UPDATE ONLY invoices SET id = ?")))
  (is (= "order-lines" (constraint/statement-table "INSERT INTO \"Order-Lines\" (id) VALUES (?)")))
  (is (= "rechnungsposten_ä" (constraint/statement-table "INSERT INTO rechnungsposten_ä (id) VALUES (?)")))
  (is (nil? (constraint/statement-table "SELECT * FROM lines"))))

(deftest ^:unit an-english-message-wins-over-the-tables
  ;; A partition, view or trigger writes another table than the statement names.
  (is (not (:referenced? (constraint/violation
                          {:sql-state "23503" :error-code 0 :table "lines_2026" :target "lines"
                           :message "ERROR: insert or update on table \"lines_2026\" violates foreign key constraint \"lines_invoice_id_fkey\""})))))

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
