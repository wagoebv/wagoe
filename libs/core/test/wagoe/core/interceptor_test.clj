(ns wagoe.core.interceptor-test
  "Tests for the interceptor pipeline execution engine."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.core.interceptor :as ic]))

;; Test fixtures and utilities

(defn create-test-interceptor
  "Creates a test interceptor that records its execution."
  [name & {:keys [enter leave error halt? fail-on]}]
  (let [execution-log (atom [])]
    {:name name
     :execution-log execution-log
     :enter (when enter
              (fn [ctx]
                (swap! execution-log conj [:enter name])
                (cond
                  (= fail-on :enter) (throw (ex-info "Intentional enter failure" {}))
                  halt? (-> ctx
                            (assoc :halt? true)
                            (assoc name :executed)) ; Both halt AND mark as executed
                  :else (assoc ctx name :executed))))
     :leave (when leave
              (fn [ctx]
                (swap! execution-log conj [:leave name])
                (if (= fail-on :leave)
                  (throw (ex-info "Intentional leave failure" {}))
                  ctx)))
     :error (when error
              (fn [ctx]
                (swap! execution-log conj [:error name])
                (if (= fail-on :error)
                  (throw (ex-info "Intentional error failure" {}))
                  (assoc ctx :error-handled true))))}))

(defn get-execution-log
  "Extracts execution log from test interceptor."
  [interceptor]
  @(:execution-log interceptor))

;; Pipeline Runner Tests

(deftest ^:unit run-pipeline-happy-path-test
  (testing "successful pipeline execution with enter and leave phases"
    (let [shared-log (atom [])
          interceptor1 {:name :int1
                        :enter (fn [ctx]
                                 (swap! shared-log conj [:enter :int1])
                                 (assoc ctx :int1 :executed))
                        :leave (fn [ctx]
                                 (swap! shared-log conj [:leave :int1])
                                 ctx)}
          interceptor2 {:name :int2
                        :enter (fn [ctx]
                                 (swap! shared-log conj [:enter :int2])
                                 (assoc ctx :int2 :executed))
                        :leave (fn [ctx]
                                 (swap! shared-log conj [:leave :int2])
                                 ctx)}
          interceptor3 {:name :int3
                        :enter (fn [ctx]
                                 (swap! shared-log conj [:enter :int3])
                                 (assoc ctx :int3 :executed))
                        :leave (fn [ctx]
                                 (swap! shared-log conj [:leave :int3])
                                 ctx)}
          pipeline [interceptor1 interceptor2 interceptor3]
          initial-ctx {:test true}
          result (ic/run-pipeline initial-ctx pipeline)]

      ;; Check final context
      (is (= true (:test result)))
      (is (= :executed (:int1 result)))
      (is (= :executed (:int2 result)))
      (is (= :executed (:int3 result)))

      ;; Check execution order: enter forward, leave reverse
      (is (= [[:enter :int1] [:enter :int2] [:enter :int3]
              [:leave :int3] [:leave :int2] [:leave :int1]]
             @shared-log)))))

(deftest ^:unit run-pipeline-halt-test
  (testing "pipeline halts when interceptor sets :halt? true"
    (let [shared-log (atom [])
          interceptor1 {:name :int1
                        :enter (fn [ctx]
                                 (swap! shared-log conj [:enter :int1])
                                 (assoc ctx :int1 :executed))
                        :leave (fn [ctx]
                                 (swap! shared-log conj [:leave :int1])
                                 ctx)}
          interceptor2 {:name :int2
                        :enter (fn [ctx]
                                 (swap! shared-log conj [:enter :int2])
                                 (-> ctx
                                     (assoc :halt? true)
                                     (assoc :int2 :executed)))
                        :leave (fn [ctx]
                                 (swap! shared-log conj [:leave :int2])
                                 ctx)}
          interceptor3 {:name :int3
                        :enter (fn [ctx]
                                 (swap! shared-log conj [:enter :int3])
                                 (assoc ctx :int3 :executed))
                        :leave (fn [ctx]
                                 (swap! shared-log conj [:leave :int3])
                                 ctx)}
          pipeline [interceptor1 interceptor2 interceptor3]
          initial-ctx {:test true}
          result (ic/run-pipeline initial-ctx pipeline)]

      ;; Check that execution halted
      (is (:halt? result))
      (is (= :executed (:int1 result)))
      (is (= :executed (:int2 result)))
      (is (nil? (:int3 result))) ; Never executed

      ;; Check execution order: enter until halt, then leave in reverse
      (is (= [[:enter :int1] [:enter :int2] [:leave :int2] [:leave :int1]]
             @shared-log)))))

(deftest ^:unit run-pipeline-halt-on-response-test
  (testing "pipeline stops the enter chain when an interceptor sets :response
            without :halt? (ZZP-117) — a short-circuiting guard is not bypassed"
    (let [shared-log (atom [])
          the-response {:status 401 :body "nope"}
          interceptor1 {:name :int1
                        :enter (fn [ctx]
                                 (swap! shared-log conj [:enter :int1])
                                 (assoc ctx :int1 :executed))
                        :leave (fn [ctx]
                                 (swap! shared-log conj [:leave :int1])
                                 ctx)}
          ;; A guard that rejects by setting ONLY :response (the ZZP-117 pattern).
          guard        {:name :guard
                        :enter (fn [ctx]
                                 (swap! shared-log conj [:enter :guard])
                                 (assoc ctx :response the-response))
                        :leave (fn [ctx]
                                 (swap! shared-log conj [:leave :guard])
                                 ctx)}
          handler      {:name :handler
                        :enter (fn [ctx]
                                 (swap! shared-log conj [:enter :handler])
                                 (assoc ctx :handler :executed))}
          pipeline [interceptor1 guard handler]
          result (ic/run-pipeline {:test true} pipeline)]

      ;; The guard's response survives; the downstream handler never runs.
      (is (= the-response (:response result)))
      (is (nil? (:handler result)) "handler after a response must not execute")

      ;; enter runs up to and including the guard, then leave in reverse for the
      ;; interceptors that executed. The handler's :enter never fires.
      (is (= [[:enter :int1] [:enter :guard] [:leave :guard] [:leave :int1]]
             @shared-log)))))

(deftest ^:unit run-pipeline-exception-test
  (testing "pipeline handles exceptions with error phase"
    (let [interceptor1 (create-test-interceptor :int1 :enter true :leave true :error true)
          interceptor2 (create-test-interceptor :int2 :enter true :leave true :error true :fail-on :enter)
          interceptor3 (create-test-interceptor :int3 :enter true :leave true :error true)
          pipeline [interceptor1 interceptor2 interceptor3]
          initial-ctx {:test true}
          result (ic/run-pipeline initial-ctx pipeline)]

      ;; Check error handling
      (is (instance? Throwable (:exception result)))
      (is (:error-handled result))
      (is (= :executed (:int1 result)))
      (is (nil? (:int2 result))) ; Failed before setting
      (is (nil? (:int3 result))) ; Never executed

      ;; Check execution order: enter until failure, then error in reverse
      (is (= [[:enter :int1] [:error :int1]]
             (get-execution-log interceptor1)))
      (is (empty? (get-execution-log interceptor3))))))

;; Validation Tests

(deftest ^:unit validate-interceptor-test
  (testing "valid interceptor passes validation"
    (let [valid-interceptor {:name :test
                             :enter (fn [ctx] ctx)
                             :leave (fn [ctx] ctx)
                             :error (fn [ctx] ctx)}]
      (is (= valid-interceptor (ic/validate-interceptor valid-interceptor)))))

  (testing "interceptor missing :name fails validation"
    (let [invalid-interceptor {:enter (fn [ctx] ctx)}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Interceptor must have a :name"
                            (ic/validate-interceptor invalid-interceptor)))))

  (testing "interceptor with non-function :enter fails validation"
    (let [invalid-interceptor {:name :test :enter "not-a-function"}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Interceptor :enter must be a function"
                            (ic/validate-interceptor invalid-interceptor)))))

  (testing "interceptor with invalid keys fails validation"
    (let [invalid-interceptor {:name :test :invalid-key "value"}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Interceptor contains invalid keys"
                            (ic/validate-interceptor invalid-interceptor))))))

(deftest ^:unit validate-pipeline-test
  (testing "valid pipeline passes validation"
    (let [valid-pipeline [{:name :int1 :enter (fn [ctx] ctx)}
                          {:name :int2 :leave (fn [ctx] ctx)}]]
      (is (= valid-pipeline (ic/validate-pipeline valid-pipeline)))))

  (testing "non-vector pipeline fails validation"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Pipeline must be a vector"
                          (ic/validate-pipeline '({:name :test})))))

  (testing "pipeline with duplicate names fails validation"
    (let [invalid-pipeline [{:name :test :enter (fn [ctx] ctx)}
                            {:name :test :leave (fn [ctx] ctx)}]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Pipeline contains duplicate interceptor names"
                            (ic/validate-pipeline invalid-pipeline))))))

;; Utility Function Tests

(deftest ^:unit halt-pipeline-test
  (testing "halt-pipeline sets :halt? flag"
    (let [ctx {:test true}
          halted-ctx (ic/halt-pipeline ctx)]
      (is (:halt? halted-ctx))
      (is (= true (:test halted-ctx)))))

  (testing "halt-pipeline with response data"
    (let [ctx {:test true}
          response-data {:status 400 :body "Error"}
          halted-ctx (ic/halt-pipeline ctx response-data)]
      (is (:halt? halted-ctx))
      (is (= response-data (:response halted-ctx))))))

(deftest ^:unit update-context-test
  (testing "update-context with simple key"
    (let [ctx {:existing true}
          updated-ctx (ic/update-context ctx :new-key "value")]
      (is (= "value" (:new-key updated-ctx)))
      (is (= true (:existing updated-ctx)))))

  (testing "update-context with key path"
    (let [ctx {:existing {:nested true}}
          updated-ctx (ic/update-context ctx [:existing :new-nested] "value")]
      (is (= "value" (get-in updated-ctx [:existing :new-nested])))
      (is (= true (get-in updated-ctx [:existing :nested]))))))

(deftest ^:unit get-from-context-test
  (testing "get-from-context with simple key"
    (let [ctx {:test-key "test-value"}]
      (is (= "test-value" (ic/get-from-context ctx :test-key)))
      (is (nil? (ic/get-from-context ctx :missing-key)))
      (is (= "default" (ic/get-from-context ctx :missing-key "default")))))

  (testing "get-from-context with key path"
    (let [ctx {:nested {:deep {:value "found"}}}]
      (is (= "found" (ic/get-from-context ctx [:nested :deep :value])))
      (is (nil? (ic/get-from-context ctx [:nested :missing :value])))
      (is (= "default" (ic/get-from-context ctx [:nested :missing :value] "default"))))))

;; Error Handling Tests

(deftest ^:unit empty-pipeline-test
  (testing "empty pipeline returns original context"
    (let [ctx {:test true}
          result (ic/run-pipeline ctx [])]
      (is (= ctx result)))))

(deftest ^:unit single-interceptor-pipeline-test
  (testing "single interceptor pipeline works correctly"
    (let [interceptor (create-test-interceptor :single :enter true :leave true)
          ctx {:test true}
          result (ic/run-pipeline ctx [interceptor])]

      (is (= :executed (:single result)))
      (is (= [[:enter :single] [:leave :single]] (get-execution-log interceptor))))))