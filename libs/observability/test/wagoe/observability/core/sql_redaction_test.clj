(ns wagoe.observability.core.sql-redaction-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.observability.core.sql-redaction :as redaction])
  (:import [java.sql SQLException]))

(def ^:private quoting
  (SQLException. "Duplicate entry 'alice@example.com' for key 'users.email'" "23000" 1062))

(defn- chain [^Throwable t] (take-while some? (iterate ex-cause t)))

(deftest ^:unit a-quoted-value-leaves-the-chain
  (let [redacted (redaction/redacted-exception (ex-info "save failed" {:type :conflict} quoting))]
    (is (not-any? #(str/includes? (str (ex-message %)) "alice@example.com") (chain redacted)))
    (is (= {:type :conflict} (ex-data redacted)))
    (is (= "Integrity constraint violated (SQLState 23000, constraint users.email)"
           (redaction/loggable-message quoting)))))

(deftest ^:unit a-value-free-message-is-kept
  (testing "a missing table names the table, and the same exception comes back"
    (let [e (SQLException. "ERROR: relation \"users\" does not exist" "42P01")]
      (is (identical? e (redaction/redacted-exception e)))
      (is (= "ERROR: relation \"users\" does not exist" (redaction/loggable-message e))))))

(deftest ^:unit the-constraint-is-named-without-the-values
  (is (= "lines_invoice_id_fkey"
         (redaction/constraint-name
          {:message "ERROR: insert or update on table \"lines\" violates foreign key constraint \"lines_invoice_id_fkey\"\n  Detail: Key (invoice_id)=(abc) is not present in table \"invoices\"."}))
      "PostgreSQL")
  (is (= "CONSTRAINT_4"
         (redaction/constraint-name
          {:message "Referential integrity constraint violation: \"CONSTRAINT_4: PUBLIC.LINES FOREIGN KEY(INVOICE_ID) REFERENCES PUBLIC.INVOICES(ID) ('abc')\""}))
      "H2 foreign key")
  (is (= "CONSTRAINT_INDEX_9"
         (redaction/constraint-name
          {:message "Unique index or primary key violation: \"PUBLIC.CONSTRAINT_INDEX_9 ON PUBLIC.INVOICES(NUMBER NULLS FIRST) VALUES ( /* 1 */ 'A-1' )\""}))
      "H2 unique")
  (is (= "fk" (redaction/constraint-name
               {:message "Cannot add or update a child row: a foreign key constraint fails (`app`.`lines`, CONSTRAINT `fk` FOREIGN KEY (`invoice_id`) REFERENCES `invoices` (`id`))"}))
      "MySQL foreign key")
  (is (= "invoices.number" (redaction/constraint-name {:message "Duplicate entry 'A-1' for key 'invoices.number'"}))
      "MySQL unique")
  (is (= "invoices.number"
         (redaction/constraint-name {:message "Duplicate entry 'x constraint \"users_email\"' for key 'users.email' for key 'invoices.number'"}))
      "MySQL unique: the key after the value, not one the value names")
  (is (nil? (redaction/constraint-name {:message "[SQLITE_CONSTRAINT_UNIQUE] A UNIQUE constraint failed (UNIQUE constraint failed: invoices.number)"}))
      "SQLite names none"))

(deftest ^:unit data-and-integrity-errors-are-logged-without-their-values
  (is (= "Integrity constraint violated (SQLState 23514, constraint users_age_check)"
         (redaction/redacted-message
          {:sql-state "23514"
           :message "ERROR: new row for relation \"users\" violates check constraint \"users_age_check\"\n  Detail: Failing row contains (1, -1, alice@example.com)."}))
      "PostgreSQL's whole row")
  (is (= "Integrity constraint violated (SQLState 23000, constraint invoices.number)"
         (redaction/redacted-message {:sql-state "23000" :error-code 1062
                                      :message "Duplicate entry 'alice@example.com' for key 'invoices.number'"}))
      "MySQL's duplicate")
  (is (= "Invalid data (SQLState 22P02)"
         (redaction/redacted-message {:sql-state "22P02" :message "ERROR: invalid input syntax for type uuid: \"alice\""})))
  (is (= "Integrity constraint violated (SQLState 23502, field email)"
         (redaction/redacted-message {:sql-state "23502" :message "ERROR: null value in column \"email\" of relation \"users\" violates not-null constraint"}))
      "a NOT NULL keeps its field")
  (is (nil? (redaction/redacted-message {:sql-state "42P01" :message "ERROR: relation \"users\" does not exist"}))
      "anything else is logged as the driver said it")
  (is (nil? (redaction/redacted-message {:message "[SQLITE_CONSTRAINT_NOTNULL] NOT NULL constraint failed: users.email"}))
      "SQLite gives no state, and quotes no values"))

(deftest ^:unit a-not-null-names-its-field
  (is (= :email (redaction/not-null-field {:sql-state "23502" :message "ERROR: null value in column \"email\" of relation \"users\" violates not-null constraint"}))
      "PostgreSQL")
  (is (= :invoice-number (redaction/not-null-field {:sql-state "23502" :message "NULL not allowed for column \"INVOICE_NUMBER\"; SQL statement:"}))
      "H2")
  (is (= :email (redaction/not-null-field {:message "[SQLITE_CONSTRAINT_NOTNULL] A NOT NULL constraint failed (NOT NULL constraint failed: users.email)"}))
      "SQLite")
  (is (= :email (redaction/not-null-field {:sql-state "23000" :error-code 1048 :message "Column 'email' cannot be null"})) "MySQL")
  (is (nil? (redaction/not-null-field {:message "Duplicate entry 'a' for key 'users.email'"})))
  (is (nil? (redaction/not-null-field {:sql-state "22P02" :message "ERROR: invalid input syntax for type uuid: \"Column 'email' cannot be null\""}))
      "a value that reads like one is not"))

(deftest ^:unit redacted-unless-known-to-quote-no-values
  ;; One redacted and one kept state per engine (BOU-629).
  (testing "PostgreSQL"
    (is (= "Database error (SQLState P0001)"
           (redaction/redacted-message {:sql-state "P0001" :error-code 0 :message "ERROR: card 4111111111111111 refused"})))
    (is (nil? (redaction/redacted-message {:sql-state "42P01" :message "ERROR: relation \"users\" does not exist"})))
    (is (= "Database error (SQLState P0001)"
           (redaction/redacted-message {:sql-state "P0001" :error-code 0 :message "ERROR: violates constraint \"4111111111111111\""}))
        "a RAISE's text is not read for a name"))
  (testing "MySQL"
    (is (= "Database error (SQLState HY000, code 1366)"
           (redaction/redacted-message {:sql-state "HY000" :error-code 1366
                                        :message "Incorrect integer value: 'alice' for column 'age' at row 1"})))
    (is (nil? (redaction/redacted-message {:sql-state "42S02" :error-code 1146 :message "Table 'app.users' doesn't exist"})))
    (is (= "Database error (SQLState 42000, code 1064)"
           (redaction/redacted-message {:sql-state "42000" :error-code 1064
                                        :message "You have an error in your SQL syntax; check the manual near ''alice@example.com', 3)' at line 1"}))
        "a syntax error quotes the statement, parameters filled in"))
  (testing "H2"
    (is (= "Invalid data (SQLState 22018)"
           (redaction/redacted-message {:sql-state "22018" :error-code 22018 :message "Data conversion error converting \"alice\""})))
    (is (nil? (redaction/redacted-message {:sql-state "42S02" :error-code 42102 :message "Table \"USERS\" not found"}))))
  (testing "SQLite gives no state and quotes no values"
    (is (nil? (redaction/redacted-message {:error-code 1 :message "[SQLITE_ERROR] SQL error or missing database (no such table: users)"})))))

(deftest ^:unit a-redacted-message-is-kept
  (let [once (redaction/redacted-message {:sql-state "23505" :message "ERROR: duplicate key value violates unique constraint \"users_email_key\""})]
    (is (= "Integrity constraint violated (SQLState 23505, constraint users_email_key)" once))
    (is (= once (redaction/redacted-message {:sql-state "23505" :message once})))))

(deftest ^:unit postgresql-names-are-read-in-any-language
  (let [german {:sql-state "23502" :column "email" :constraint "users_email_not_null"
                :message "FEHLER: NULL-Wert in Spalte »email« von Relation »users« verletzt Not-Null-Constraint"}]
    (is (= :email (redaction/not-null-field german)))
    (is (= "users_email_not_null" (redaction/constraint-name german)))))

(deftest ^:unit a-quoted-value-leaves-the-chain-through-wrappers
  (let [driver   (SQLException. "Unique index or primary key violation: \"PUBLIC.UK_EMAIL ON PUBLIC.USERS(EMAIL) VALUES ( /* 1 */ 'alice@example.com' )\"" "23505" 23505)
        wrapped  (RuntimeException. "jdbc failed" (ex-info "save failed" {:type :conflict :entity "user"} driver))
        redacted (redaction/redacted-exception wrapped)]
    (testing "no message in the chain quotes the value"
      (is (not-any? #(str/includes? (str (ex-message %)) "alice@example.com") (chain redacted))))
    (testing "the rest is kept: messages, ex-data, SQLState and stack"
      (let [[outer info sql] (chain redacted)]
        (is (= "java.lang.RuntimeException: jdbc failed" (ex-message outer)))
        (is (= {:type :conflict :entity "user"} (ex-data info)))
        (is (= ["23505" 23505] [(.getSQLState ^SQLException sql) (.getErrorCode ^SQLException sql)]))
        (is (= (seq (.getStackTrace driver)) (seq (.getStackTrace ^Throwable sql))))))))

(deftest ^:unit a-wrapper-quoting-the-driver-is-redacted-too
  (let [driver   (SQLException. "Duplicate entry 'alice@example.com' for key 'users.email'" "23000" 1062)
        redacted (redaction/redacted-exception (RuntimeException. driver))]
    (is (not-any? #(str/includes? (str (ex-message %)) "alice@example.com") (chain redacted)))))

(deftest ^:unit a-cyclic-chain-ends
  (let [inner (SQLException. "Duplicate entry 'alice@example.com' for key 'users.email'" "23000" 1062)
        outer (RuntimeException. "outer" inner)]
    (.initCause inner outer)
    (is (= 2 (count (chain (redaction/redacted-exception outer)))))))

(deftest ^:unit nothing-to-redact-is-the-same-exception
  (let [e (ex-info "not found" {:type :not-found} (SQLException. "relation \"users\" does not exist" "42P01"))]
    (is (identical? e (redaction/redacted-exception e)))))

(deftest ^:unit ex-data-quoting-the-driver-is-scrubbed
  (let [driver   (SQLException. "Duplicate entry 'alice@example.com' for key 'users.email'" "23000" 1062)
        redacted (redaction/redacted-exception
                  (ex-info "Migration failed" {:type :migration-failed :error (.getMessage driver)} driver))]
    (is (= :migration-failed (:type (ex-data redacted))))
    (is (not (str/includes? (pr-str (ex-data redacted)) "alice@example.com")))))

(deftest ^:unit a-raise-is-redacted-whatever-state-it-claims
  ;; BOU-635: a RAISE's author picks the state, names included.
  (is (= "Database error (SQLState 42501)"
         (redaction/redacted-message {:sql-state "42501" :authored? true
                                      :message "ERROR: card 4111111111111111 refused"})))
  (is (= "Database error (SQLState 23505)"
         (redaction/redacted-message {:sql-state "23505" :authored? true
                                      :message "ERROR: violates constraint \"4111111111111111\""})))
  (is (= "Database error (SQLState 42P01)"
         (redaction/redacted-message {:sql-state "42P01" :authored? true
                                      :message "ERROR: card 4111111111111111 refused"}))
      "even a state whose own messages are kept")
  (is (nil? (redaction/redacted-message {:sql-state "42P01"
                                         :message "ERROR: relation \"users\" does not exist"}))
      "the server's own 42P01 keeps its text"))

(deftest ^:unit a-suppressed-exception-is-redacted-too
  (let [outer    (doto (RuntimeException. "close failed") (.addSuppressed quoting))
        redacted (redaction/redacted-exception outer)
        [s]      (.getSuppressed ^Throwable redacted)]
    (is (some? s))
    (is (not (.contains (str (ex-message s)) "alice@example.com")))
    (is (= "java.lang.RuntimeException: close failed" (ex-message redacted)))))

(deftest ^:unit a-driver-subclass-is-named
  (let [driver   (java.sql.SQLIntegrityConstraintViolationException.
                  "Duplicate entry 'alice@example.com' for key 'users.email'" "23000" 1062)
        redacted (redaction/redacted-exception (ex-info "save failed" {} driver))]
    (is (= "Integrity constraint violated (SQLState 23000, constraint users.email) (java.sql.SQLIntegrityConstraintViolationException)"
           (ex-message (ex-cause redacted))))
    (is (identical? redacted (redaction/redacted-exception redacted)) "and a second pass changes nothing")))

(deftest ^:unit a-redacted-raise-stays-as-it-is
  ;; The copy no longer says it was a RAISE; a second pass must not relabel it.
  (let [once (redaction/redacted-message {:sql-state "23505" :authored? true :message "card 4111"})]
    (is (= "Database error (SQLState 23505)" once))
    (is (= once (redaction/redacted-message {:sql-state "23505" :message once})))))

(deftest ^:unit a-raise-names-no-constraint-or-field
  (is (nil? (redaction/constraint-name {:authored? true :constraint "4111111111111111"})))
  (is (nil? (redaction/not-null-field {:authored? true :sql-state "23502" :column "4111111111111111"}))))

