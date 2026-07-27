(ns wagoe.mcp.core.execute-test
  "Pure policy for the Tier 2 execute tools (BOU-102): SQL read-only
   classification, row-limit clamping, migration-direction validation."
  (:require [wagoe.mcp.core.execute :as execute]
            [clojure.test :refer [deftest is testing]]))

(deftest ^:unit sql-violation-classifies-read-only
  (testing "single read-only statements are accepted (nil violation)"
    (is (nil? (execute/sql-violation "SELECT * FROM users")))
    (is (nil? (execute/sql-violation "  select id from users where id = 1  ")))
    (is (nil? (execute/sql-violation "WITH t AS (SELECT 1) SELECT * FROM t")))
    (is (nil? (execute/sql-violation "EXPLAIN SELECT 1")))
    (is (nil? (execute/sql-violation "SHOW TABLES")))
    (is (nil? (execute/sql-violation "SELECT 1;")) "a single trailing semicolon is fine"))
  (testing "write keyword as an identifier substring is not flagged"
    (is (nil? (execute/sql-violation "SELECT id, updated_at, created_by FROM users"))))
  (testing "empty / blank"
    (is (= :empty (execute/sql-violation "")))
    (is (= :empty (execute/sql-violation "   ")))
    (is (= :empty (execute/sql-violation nil))))
  (testing "multiple statements"
    (is (= :multiple-statements (execute/sql-violation "SELECT 1; SELECT 2")))
    (is (= :multiple-statements (execute/sql-violation "SELECT 1; DROP TABLE users")))
    (is (= :multiple-statements (execute/sql-violation "SELECT 1; SELECT 2;"))))
  (testing "writes / DDL are refused"
    (is (= :not-read-only (execute/sql-violation "DELETE FROM users")))
    (is (= :not-read-only (execute/sql-violation "UPDATE users SET name = 'x'")))
    (is (= :not-read-only (execute/sql-violation "INSERT INTO users VALUES (1)")))
    (is (= :not-read-only (execute/sql-violation "DROP TABLE users")))
    (is (= :not-read-only (execute/sql-violation "TRUNCATE users")))
    (is (= :not-read-only
           (execute/sql-violation "WITH x AS (DELETE FROM users RETURNING *) SELECT * FROM x"))
        "a data-modifying CTE is caught despite leading with WITH")))

(deftest ^:unit read-only-sql?-mirrors-violation
  (is (true? (execute/read-only-sql? "SELECT 1")))
  (is (false? (execute/read-only-sql? "DELETE FROM users")))
  (is (false? (execute/read-only-sql? "SELECT 1; SELECT 2"))))

(deftest ^:unit clamp-limit-bounds
  (testing "nil / non-numeric → default"
    (is (= execute/default-row-limit (execute/clamp-limit nil)))
    (is (= execute/default-row-limit (execute/clamp-limit :nope))))
  (testing "within range is preserved"
    (is (= 5 (execute/clamp-limit 5)))
    (is (= 50 (execute/clamp-limit "50")) "numeric strings parse"))
  (testing "clamped to [1, max]"
    (is (= 1 (execute/clamp-limit 0)))
    (is (= 1 (execute/clamp-limit -3)))
    (is (= execute/max-row-limit (execute/clamp-limit 99999)))))

(deftest ^:unit valid-direction?-allowlist
  (is (true? (execute/valid-direction? "up")))
  (is (true? (execute/valid-direction? "status")))
  (is (false? (execute/valid-direction? "down")))
  (is (false? (execute/valid-direction? "drop")))
  (is (false? (execute/valid-direction? nil))))
