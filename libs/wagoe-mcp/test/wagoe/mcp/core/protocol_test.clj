(ns wagoe.mcp.core.protocol-test
  (:require [wagoe.mcp.core.protocol :as proto]
            [clojure.test :refer [deftest is testing]]))

(deftest ^:unit success-shape
  (is (= {:jsonrpc "2.0" :id 1 :result {:ok true}}
         (proto/success 1 {:ok true}))))

(deftest ^:unit error-maps-code-keyword
  (testing "named code resolves to its JSON-RPC integer"
    (let [e (proto/error 1 :method-not-found "nope")]
      (is (= -32601 (get-in e [:error :code])))
      (is (= "nope" (get-in e [:error :message])))
      (is (not (contains? (:error e) :data)))))
  (testing "data included only when provided"
    (is (= {:m 1} (get-in (proto/error 1 :internal-error "x" {:m 1}) [:error :data]))))
  (testing "raw integer code passes through"
    (is (= 123 (get-in (proto/error 1 123 "x") [:error :code])))))

(deftest ^:unit request-vs-notification
  (is (proto/request? {:id 1 :method "m"}))
  (is (proto/notification? {:method "m"}))
  (is (not (proto/notification? {:id 1 :method "m"}))))

(deftest ^:unit version-negotiation
  (testing "supported request is echoed"
    (is (= "2025-06-18" (proto/negotiate-version "2025-06-18"))))
  (testing "unsupported or absent request falls back to preferred"
    (is (= proto/mcp-protocol-version (proto/negotiate-version "1999-01-01")))
    (is (= proto/mcp-protocol-version (proto/negotiate-version nil))))
  (testing "preferred is the newest supported"
    (is (= (first proto/supported-protocol-versions) proto/mcp-protocol-version))))
