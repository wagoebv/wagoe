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
  (testing "a key over several columns names each of them (BOU-712)"
    (is (= {:kind :unique :fields [:invoice-id :position]}
           (constraint/violation {:message "[SQLITE_CONSTRAINT_UNIQUE] A UNIQUE constraint failed (UNIQUE constraint failed: lines.invoice_id, lines.position)"})))
    (is (= {:kind :unique :fields [:invoice-id :position]}
           (constraint/violation {:sql-state "23505" :message "Detail: Key (invoice_id, position)=(x, 1) already exists."})))
    (is (= {:kind :unique :fields [:invoice-id :position]}
           (constraint/violation {:sql-state "23505" :error-code 23505
                                  :message "Unique index or primary key violation: \"PUBLIC.UQ_LINES_INDEX_4 ON PUBLIC.LINES(INVOICE_ID NULLS FIRST, POSITION NULLS FIRST) VALUES ( /* 2 */ 'x', 1 )\""}))))
  (testing "H2 on a named constraint, which it names before its index"
    (is (= {:kind :unique :fields [:nc-invoice-id :description]}
           (constraint/violation {:sql-state "23505" :error-code 23505
                                  :message "Unique index or primary key violation: \"public.uq_nc_lines INDEX public.uq_nc_lines_INDEX_C ON public.nc_lines(nc_invoice_id NULLS LAST, description NULLS LAST) VALUES ( /* key:3 */ UUID '79a7511a', 'a')\"; SQL statement:\nINSERT INTO nc_lines VALUES (?, ?, 'a', 1) [23505-250]"}))))
  (testing "a key on an expression names no field"
    (is (= {:kind :unique}
           (constraint/violation {:sql-state "23505" :message "Detail: Key (lower(email), tenant_id)=(a, 1) already exists."})))))

(deftest ^:unit with-columns-names-one-field-or-several
  (is (= {:kind :unique :field :number} (constraint/with-columns {:kind :unique} ["NUMBER"])))
  (is (= {:kind :unique :fields [:template-key :domain-id]}
         (constraint/with-columns {:kind :unique} ["template_key" "domain_id"])))
  (is (= {:kind :unique} (constraint/with-columns {:kind :unique} nil)))
  (is (= {:kind :unique} (constraint/with-columns {:kind :unique} ["a" "lower(b)"]))))

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

(deftest ^:unit a-value-is-never-read-as-a-column
  ;; Each pattern reads its engine's column list only, not the value beside it
  ;; (BOU-721).
  (testing "PostgreSQL: the Detail's own key list, not one a value spells"
    (is (= {:kind :unique}
           (constraint/violation {:sql-state "23505"
                                  :detail "Key (lower(email))=(Key (role)=x) already exists."})))
    (is (= {:kind :unique}
           (constraint/violation {:sql-state "23505"
                                  :message "ERROR: duplicate key value violates unique constraint \"u\"\n  Detail: Key (lower(email))=(x\nDetail: Key (role)=y) already exists."}))
        "a value with a Detail line of its own")
    (is (= {:kind :unique}
           (constraint/violation {:sql-state "23505"
                                  :detail "Schlüssel »(lower(email))=(UNIQUE constraint failed: t.role)« existiert bereits."}))))
  (testing "H2: the index's columns, not those in its VALUES"
    (is (= {:kind :unique :field :number}
           (constraint/violation {:sql-state "23505" :error-code 23505
                                  :message "Unique index or primary key violation: \"PUBLIC.IDX ON PUBLIC.INVOICES(NUMBER NULLS FIRST) VALUES ( /* 1 */ 'ON PUBLIC.X(ROLE)' )\""})))
    (is (= {:kind :unique}
           (constraint/violation {:sql-state "23505" :error-code 23505
                                  :message "Unique index or primary key violation: \"PUBLIC.IDX ON PUBLIC.INVOICES(LOWER(EMAIL)) VALUES ( /* 1 */ 'Key (role)=' )\""}))))
  (testing "MySQL: the key only, whatever the value holds"
    (is (= {:kind :unique}
           (constraint/violation {:sql-state "23000" :error-code 1062
                                  :message "Duplicate entry 'Key (role)=x FOREIGN KEY (role) UNIQUE constraint failed: t.role' for key 'invoices.number'"}))))
  (testing "SQLite: the tail of its message"
    (is (= {:kind :unique :field :number}
           (constraint/violation {:error-code 19
                                  :message "[SQLITE_CONSTRAINT_UNIQUE] A UNIQUE constraint failed (UNIQUE constraint failed: invoices.number)"})))))
