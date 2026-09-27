(ns wagoe.platform.core.database.migration-sql-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.platform.core.database.migration-sql :as sql]))

(deftest ^:unit mysql-gets-types-it-has
  (is (= ["CREATE TABLE IF NOT EXISTS t (\n  id CHAR(36) PRIMARY KEY,\n  at DATETIME(6) NOT NULL,\n  doc JSON\n)"]
         (sql/for-mysql "CREATE TABLE IF NOT EXISTS t (\n  id UUID PRIMARY KEY,\n  at TIMESTAMP WITH TIME ZONE NOT NULL,\n  doc JSONB\n)")))
  (is (= ["ALTER TABLE t ADD COLUMN ref CHAR(36)"]
         (sql/for-mysql "ALTER TABLE t ADD COLUMN ref UUID"))))

(deftest ^:unit mysql-gets-the-foreign-keys-it-would-ignore
  (testing "a column REFERENCES becomes a table constraint, which MySQL enforces"
    (is (= [(str "CREATE TABLE lines (\n  id CHAR(36) PRIMARY KEY,\n"
                 "  invoice_id CHAR(36) NOT NULL,\n  qty INTEGER,\n"
                 "  FOREIGN KEY (invoice_id) REFERENCES invoices(id) ON DELETE CASCADE\n)")]
           (sql/for-mysql (str "CREATE TABLE lines (\n  id UUID PRIMARY KEY,\n"
                               "  invoice_id UUID NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,\n"
                               "  qty INTEGER\n)")))))
  (testing "a table constraint is left as it is"
    (let [s "CREATE TABLE a (\n  b_id INT,\n  FOREIGN KEY (b_id) REFERENCES b(id)\n)"]
      (is (= [s] (sql/for-mysql s)))))
  (testing "an added column gets its constraint in the same statement"
    (is (= ["ALTER TABLE t ADD COLUMN owner_id CHAR(36) NOT NULL, ADD FOREIGN KEY (owner_id) REFERENCES owners(id) ON DELETE RESTRICT"]
           (sql/for-mysql "ALTER TABLE t ADD COLUMN owner_id UUID NOT NULL REFERENCES owners(id) ON DELETE RESTRICT")))))

(deftest ^:unit mysql-indexes
  (is (= ["CREATE INDEX idx_t_a ON t(a)"] (sql/for-mysql "CREATE INDEX IF NOT EXISTS idx_t_a ON t(a)")))
  (is (= ["CREATE UNIQUE INDEX idx_t_a ON t(a)"] (sql/for-mysql "CREATE UNIQUE INDEX IF NOT EXISTS idx_t_a ON t(a)")))
  (testing "a DROP INDEX naming no table is dropped: MySQL drops a column's index with the column"
    (is (= [] (sql/for-mysql "DROP INDEX IF EXISTS idx_t_a")))))

(deftest ^:unit sql-mysql-takes-is-left-alone
  (doseq [s ["INSERT INTO t (id) VALUES (UUID())"
             "UPDATE t SET uuid = 'x'"
             "CREATE TABLE t (id CHAR(36) DEFAULT (UUID()), uuid VARCHAR(36))"
             "DROP INDEX idx_t_a ON t"
             "CREATE INDEX idx_t_a ON t(a)"]]
    (is (= [s] (sql/for-mysql s)) s)))
