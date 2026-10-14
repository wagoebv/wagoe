(ns wagoe.platform.shell.adapters.database.common.redaction-test
  "An exception on its way to an error reporter or log carries no value a
   driver quoted, wherever it was thrown (BOU-630)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.platform.shell.adapters.database.common.execution :as execution])
  (:import [java.sql SQLException]))

(defn- chain [^Throwable t] (take-while some? (iterate ex-cause t)))

(deftest ^:unit a-quoted-value-leaves-the-chain
  (let [driver   (SQLException. "Unique index or primary key violation: \"PUBLIC.UK_EMAIL ON PUBLIC.USERS(EMAIL) VALUES ( /* 1 */ 'alice@example.com' )\"" "23505" 23505)
        wrapped  (RuntimeException. "jdbc failed" (ex-info "save failed" {:type :conflict :entity "user"} driver))
        redacted (execution/redacted-exception wrapped)]
    (testing "no message in the chain quotes the value"
      (is (not-any? #(str/includes? (str (ex-message %)) "alice@example.com") (chain redacted))))
    (testing "the rest is kept: messages, ex-data, SQLState and stack"
      (let [[outer info sql] (chain redacted)]
        (is (= "java.lang.RuntimeException: jdbc failed" (ex-message outer)))
        (is (= {:type :conflict :entity "user"} (ex-data info)))
        (is (= ["23505" 23505] [(.getSQLState ^SQLException sql) (.getErrorCode ^SQLException sql)]))
        (is (= (seq (.getStackTrace driver)) (seq (.getStackTrace ^Throwable sql))))))))

(deftest ^:unit nothing-to-redact-is-the-same-exception
  (let [e (ex-info "not found" {:type :not-found} (SQLException. "relation \"users\" does not exist" "42P01"))]
    (is (identical? e (execution/redacted-exception e)))))
