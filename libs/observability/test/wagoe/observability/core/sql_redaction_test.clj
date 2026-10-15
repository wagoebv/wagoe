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
