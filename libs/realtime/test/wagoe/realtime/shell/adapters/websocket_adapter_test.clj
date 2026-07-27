(ns wagoe.realtime.shell.adapters.websocket-adapter-test
  "Integration tests for WebSocket adapters (shell layer).
   
   Tests WebSocket I/O operations - verifies message sending,
   connection closing, and state tracking."
  {:kaocha.testable/meta {:integration true :realtime true}}
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.realtime.shell.adapters.websocket-adapter :as ws]
            [wagoe.realtime.ports :as ports]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def test-connection-id #uuid "550e8400-e29b-41d4-a716-446655440000")

;; =============================================================================
;; TestWebSocketAdapter Tests
;; =============================================================================

(deftest ^:unit test-adapter-send-message-test
  (testing "sending message via test adapter"
    (let [adapter (ws/create-test-websocket-adapter test-connection-id)
          message {:type "notification" :content "Hello"}]
      
      (ports/send-message adapter message)
      
      (testing "message added to sent-messages atom"
        (is (= 1 (count @(:sent-messages adapter))))
        (is (= message (first @(:sent-messages adapter))))))))

(deftest ^:unit test-adapter-send-multiple-messages-test
  (testing "sending multiple messages via test adapter"
    (let [adapter (ws/create-test-websocket-adapter test-connection-id)
          message-1 {:type "notification" :content "First"}
          message-2 {:type "notification" :content "Second"}
          message-3 {:type "notification" :content "Third"}]
      
      (ports/send-message adapter message-1)
      (ports/send-message adapter message-2)
      (ports/send-message adapter message-3)
      
      (testing "all messages added in order"
        (is (= 3 (count @(:sent-messages adapter))))
        (is (= message-1 (nth @(:sent-messages adapter) 0)))
        (is (= message-2 (nth @(:sent-messages adapter) 1)))
        (is (= message-3 (nth @(:sent-messages adapter) 2)))))))

(deftest ^:unit test-adapter-send-to-closed-connection-test
  (testing "sending message to closed test adapter"
    (let [adapter (ws/create-test-websocket-adapter test-connection-id)
          message {:type "notification" :content "Hello"}]
      
      ;; Close the adapter
      (ports/close adapter)
      
      ;; Try to send message
      (ports/send-message adapter message)
      
      (testing "message not added when closed"
        (is (= 0 (count @(:sent-messages adapter))))))))

(deftest ^:unit test-adapter-close-test
  (testing "closing test adapter"
    (let [adapter (ws/create-test-websocket-adapter test-connection-id)]
      
      (testing "initially open"
        (is (true? (ports/open? adapter))))
      
      (ports/close adapter)
      
      (testing "closed after close call"
        (is (false? (ports/open? adapter)))))))

(deftest ^:unit test-adapter-close-multiple-times-test
  (testing "closing test adapter multiple times"
    (let [adapter (ws/create-test-websocket-adapter test-connection-id)]
      
      (ports/close adapter)
      (is (false? (ports/open? adapter)))
      
      ;; Close again - should not throw
      (ports/close adapter)
      (is (false? (ports/open? adapter))))))

(deftest ^:unit test-adapter-connection-id-test
  (testing "getting connection ID from test adapter"
    (let [adapter (ws/create-test-websocket-adapter test-connection-id)]
      
      (testing "returns correct connection ID"
        (is (= test-connection-id (ports/connection-id adapter)))))))

(deftest ^:unit test-adapter-open-state-test
  (testing "test adapter open state tracking"
    (let [adapter (ws/create-test-websocket-adapter test-connection-id)]
      
      (testing "starts in open state"
        (is (true? (ports/open? adapter))))
      
      (testing "can send messages when open"
        (ports/send-message adapter {:type "test"})
        (is (= 1 (count @(:sent-messages adapter)))))
      
      (ports/close adapter)
      
      (testing "changes to closed state"
        (is (false? (ports/open? adapter))))
      
      (testing "cannot send messages when closed"
        (ports/send-message adapter {:type "test2"})
        ;; Still only 1 message from before close
        (is (= 1 (count @(:sent-messages adapter))))))))

;; =============================================================================
;; RingWebSocketAdapter Tests
;; =============================================================================

(deftest ^:unit ring-adapter-send-message-test
  (testing "sending message via ring adapter"
    (let [sent-messages (atom [])
          mock-channel {:send! (fn [msg] (swap! sent-messages conj msg))
                        :close! (fn [])
                        :open? (fn [] true)}
          adapter (ws/create-ring-websocket-adapter test-connection-id mock-channel)
          message {:type "notification" :content "Hello"}]
      
      (ports/send-message adapter message)
      
      (testing "message sent as JSON string"
        (is (= 1 (count @sent-messages)))
        (let [sent-json (first @sent-messages)]
          (is (string? sent-json))
          (is (.contains sent-json "\"type\":\"notification\""))
          (is (.contains sent-json "\"content\":\"Hello\"")))))))

(deftest ^:unit ring-adapter-send-complex-message-test
  (testing "sending complex message structure via ring adapter"
    (let [sent-messages (atom [])
          mock-channel {:send! (fn [msg] (swap! sent-messages conj msg))
                        :close! (fn [])
                        :open? (fn [] true)}
          adapter (ws/create-ring-websocket-adapter test-connection-id mock-channel)
          message {:type "notification"
                   :content "Hello"
                   :metadata {:user-id "123"
                              :timestamp "2024-01-01T12:00:00Z"}
                   :nested-array [1 2 3]}]
      
      (ports/send-message adapter message)
      
      (testing "complex structure serialized to JSON"
        (is (= 1 (count @sent-messages)))
        (let [sent-json (first @sent-messages)]
          (is (string? sent-json))
          (is (.contains sent-json "\"type\":\"notification\""))
          (is (.contains sent-json "\"metadata\""))
          (is (.contains sent-json "\"user-id\":\"123\""))
          (is (.contains sent-json "\"nested-array\":[1,2,3]")))))))

(deftest ^:unit ring-adapter-close-test
  (testing "closing ring adapter"
    (let [close-called (atom false)
          mock-channel {:send! (fn [_msg])
                        :close! (fn [] (reset! close-called true))
                        :open? (fn [] (not @close-called))}
          adapter (ws/create-ring-websocket-adapter test-connection-id mock-channel)]
      
      (testing "initially not closed"
        (is (false? @close-called)))
      
      (ports/close adapter)
      
      (testing "close function called"
        (is (true? @close-called))))))

(deftest ^:unit ring-adapter-connection-id-test
  (testing "getting connection ID from ring adapter"
    (let [mock-channel {:send! (fn [_msg])
                        :close! (fn [])
                        :open? (fn [] true)}
          adapter (ws/create-ring-websocket-adapter test-connection-id mock-channel)]
      
      (testing "returns correct connection ID"
        (is (= test-connection-id (ports/connection-id adapter)))))))

(deftest ^:unit ring-adapter-open-state-test
  (testing "ring adapter open state tracking"
    (let [is-open (atom true)
          mock-channel {:send! (fn [_msg])
                        :close! (fn [] (reset! is-open false))
                        :open? (fn [] @is-open)}
          adapter (ws/create-ring-websocket-adapter test-connection-id mock-channel)]
      
      (testing "initially open"
        (is (true? (ports/open? adapter))))
      
      (ports/close adapter)
      
      (testing "closed after close call"
        (is (false? (ports/open? adapter)))))))

(deftest ^:unit ring-adapter-missing-send-fn-test
  (testing "ring adapter with missing send function"
    (let [mock-channel {:close! (fn [])
                        :open? (fn [] true)}
          ;; Note: no :send! function
          adapter (ws/create-ring-websocket-adapter test-connection-id mock-channel)
          message {:type "test"}]
      
      (testing "does not throw when send function missing"
        (is (nil? (ports/send-message adapter message)))))))

(deftest ^:unit ring-adapter-missing-close-fn-test
  (testing "ring adapter with missing close function"
    (let [mock-channel {:send! (fn [_msg])
                        :open? (fn [] true)}
          ;; Note: no :close! function
          adapter (ws/create-ring-websocket-adapter test-connection-id mock-channel)]
      
      (testing "does not throw when close function missing"
        (is (nil? (ports/close adapter)))))))

(deftest ^:unit ring-adapter-missing-open-fn-test
  (testing "ring adapter with missing open function"
    (let [mock-channel {:send! (fn [_msg])
                        :close! (fn [])}
          ;; Note: no :open? function
          adapter (ws/create-ring-websocket-adapter test-connection-id mock-channel)]
      
      (testing "returns false when open function missing"
        (is (false? (ports/open? adapter)))))))

(deftest ^:unit ring-adapter-send-error-handling-test
  (testing "ring adapter handles send errors gracefully"
    (let [mock-channel {:send! (fn [_msg] (throw (Exception. "Network error")))
                        :close! (fn [])
                        :open? (fn [] true)}
          adapter (ws/create-ring-websocket-adapter test-connection-id mock-channel)
          message {:type "test"}]
      
      (testing "does not throw when send fails"
        (is (nil? (ports/send-message adapter message)))))))

(deftest ^:unit ring-adapter-close-error-handling-test
  (testing "ring adapter handles close errors gracefully"
    (let [mock-channel {:send! (fn [_msg])
                        :close! (fn [] (throw (Exception. "Close error")))
                        :open? (fn [] true)}
          adapter (ws/create-ring-websocket-adapter test-connection-id mock-channel)]
      
      (testing "does not throw when close fails"
        (is (nil? (ports/close adapter)))))))

;; =============================================================================
;; Factory Function Tests
;; =============================================================================

(deftest ^:unit create-test-websocket-adapter-test
  (testing "creating test WebSocket adapter"
    (let [adapter (ws/create-test-websocket-adapter test-connection-id)]
      
      (testing "returns adapter implementing protocol"
        (is (satisfies? ports/IWebSocketConnection adapter)))
      
      (testing "has correct initial state"
        (is (true? (ports/open? adapter)))
        (is (= test-connection-id (ports/connection-id adapter)))
        (is (= 0 (count @(:sent-messages adapter))))))))

(deftest ^:unit create-ring-websocket-adapter-test
  (testing "creating ring WebSocket adapter"
    (let [mock-channel {:send! (fn [_msg])
                        :close! (fn [])
                        :open? (fn [] true)}
          adapter (ws/create-ring-websocket-adapter test-connection-id mock-channel)]
      
      (testing "returns adapter implementing protocol"
        (is (satisfies? ports/IWebSocketConnection adapter)))
      
      (testing "has correct connection ID"
        (is (= test-connection-id (ports/connection-id adapter)))))))
