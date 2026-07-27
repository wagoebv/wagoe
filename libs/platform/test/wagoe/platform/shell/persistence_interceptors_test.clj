(ns wagoe.platform.shell.persistence-interceptors-test
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.platform.shell.persistence-interceptors :as pi]))

;; ==============================================================================
;; persistence-error-handling :error handler
;; ==============================================================================

(deftest ^:unit persistence-error-handling-sets-error-key-test
  (testing ":error handler preserves :error key so execute-persistence-operation can rethrow"
    (let [exception  (ex-info "SQL error" {:type "constraint-violation"})
          ctx        {:operation-name "update-user"
                      :params         {:id 1}
                      :exception      exception}
          result-ctx ((:error pi/persistence-error-handling) ctx)]
      (is (= exception (:error result-ctx))
          "handler must keep :error so execute-persistence-operation rethrows")
      (is (= "update-user" (get-in result-ctx [:error-info :operation]))
          "error-info should capture operation name")
      (is (= "constraint-violation" (get-in result-ctx [:error-info :error-type]))
          "error-info should capture error type from ex-data")))

  (testing ":error handler falls back to 'database-error' when exception has no :type"
    (let [exception  (Exception. "plain SQL error")
          ctx        {:operation-name "create-thing"
                      :params         {}
                      :exception      exception}
          result-ctx ((:error pi/persistence-error-handling) ctx)]
      (is (= "database-error" (get-in result-ctx [:error-info :error-type]))
          "defaults to 'database-error' when ex-data has no :type"))))

;; ==============================================================================
;; execute-persistence-operation — error propagation
;; ==============================================================================

(deftest ^:unit execute-persistence-operation-rethrows-on-db-error-test
  (testing "rethrows when db-logic-fn throws — exception is NOT swallowed"
    (let [boom (ex-info "DB constraint violation" {:type "unique-constraint"})]
      (is (thrown? clojure.lang.ExceptionInfo
                   (pi/execute-persistence-operation
                    :update-user
                    {:id 1}
                    (fn [_ctx] (throw boom))
                    {}))
          "execute-persistence-operation must rethrow DB exceptions")))

  (testing "rethrows plain Exception from db-logic-fn"
    (is (thrown? Exception
                 (pi/execute-persistence-operation
                  :create-thing
                  {}
                  (fn [_ctx] (throw (Exception. "plain error")))
                  {})))))

;; ==============================================================================
;; execute-persistence-operation — happy path
;; ==============================================================================

(deftest ^:unit execute-persistence-operation-returns-result-test
  (testing "returns db-logic-fn result on success"
    (let [expected {:id 42 :name "Alice"}
          result   (pi/execute-persistence-operation
                    :find-user-by-id
                    {:id 42}
                    (fn [_ctx] expected)
                    {})]
      (is (= expected result)
          "must return the value produced by db-logic-fn")))

  (testing "returns nil when db-logic-fn returns nil (e.g. not-found)"
    (let [result (pi/execute-persistence-operation
                  :find-user-by-id
                  {:id 999}
                  (fn [_ctx] nil)
                  {})]
      (is (nil? result)
          "nil is a valid not-found result and should be returned as-is"))))
