(ns wagoe.audience.shell.adapters.user-sql-test
  (:require [clojure.test :refer [deftest is]]
            [next.jdbc :as jdbc]
            [wagoe.audience.ports :as ports]
            [wagoe.audience.shell.adapters.user-sql :as user-sql]))

(deftest ^:unit ^:security loaded-users-carry-no-credentials
  ;; A custom predicate reads whatever key it likes off these rows, so the
  ;; field allowlist alone does not keep a password hash out of its reach.
  (let [ds (jdbc/get-datasource "jdbc:h2:mem:audience-user-sql;DB_CLOSE_DELAY=-1")
        id (random-uuid)]
    (jdbc/execute! ds ["DROP TABLE IF EXISTS users"])
    (jdbc/execute! ds ["CREATE TABLE users (id UUID PRIMARY KEY, name VARCHAR(64), password_hash VARCHAR(64), mfa_secret VARCHAR(64))"])
    (jdbc/execute! ds ["INSERT INTO users VALUES (?, 'Alice', 'x', 'y')" id])
    (is (= [{:id id :name "Alice"}]
           (ports/load-users (user-sql/create-sql-user-data-source ds) [id])))))
