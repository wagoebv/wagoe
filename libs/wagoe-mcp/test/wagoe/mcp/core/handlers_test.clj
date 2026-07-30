(ns wagoe.mcp.core.handlers-test
  (:require [wagoe.mcp.core.handlers :as handlers]
            [wagoe.mcp.core.protocol :as proto]
            [wagoe.mcp.core.registry :as registry]
            [clojure.test :refer [deftest is testing]]))

(def reg registry/empty-registry)

(deftest ^:unit initialize-handshake
  (testing "initialize returns protocol version, capabilities, and server info"
    (let [resp (handlers/handle reg {:jsonrpc "2.0" :id 1 :method "initialize"})]
      (is (= "2.0" (:jsonrpc resp)))
      (is (= 1 (:id resp)))
      (let [result (:result resp)]
        (is (= proto/mcp-protocol-version (:protocolVersion result)))
        (is (= {:tools {} :resources {}} (:capabilities result)))
        (is (= "wagoe-mcp" (get-in result [:serverInfo :name])))))))

(deftest ^:unit initialize-negotiates-client-version
  (testing "supported client version is echoed back"
    (let [resp (handlers/handle reg {:jsonrpc "2.0" :id 1 :method "initialize"
                                     :params {:protocolVersion "2025-06-18"}})]
      (is (= "2025-06-18" (get-in resp [:result :protocolVersion])))))
  (testing "unsupported client version falls back to server preferred"
    (let [resp (handlers/handle reg {:jsonrpc "2.0" :id 1 :method "initialize"
                                     :params {:protocolVersion "1999-01-01"}})]
      (is (= proto/mcp-protocol-version (get-in resp [:result :protocolVersion]))))))

(deftest ^:unit ping-responds-empty
  (let [resp (handlers/handle reg {:jsonrpc "2.0" :id 7 :method "ping"})]
    (is (= 7 (:id resp)))
    (is (= {} (:result resp)))))

(deftest ^:unit empty-tool-and-resource-sets
  (testing "tools/list on an empty registry"
    (let [resp (handlers/handle reg {:jsonrpc "2.0" :id 2 :method "tools/list"})]
      (is (= {:tools []} (:result resp)))))
  (testing "resources/list on an empty registry"
    (let [resp (handlers/handle reg {:jsonrpc "2.0" :id 3 :method "resources/list"})]
      (is (= {:resources []} (:result resp))))))

(deftest ^:unit registered-items-surface-only-wire-fields
  (testing "tools/list exposes wire fields and drops internal keys"
    (let [r    (registry/register-tool reg {:name        "lint"
                                            :description "Lint Clojure"
                                            :inputSchema {:type "object"}
                                            :handler     :should-be-dropped})
          resp (handlers/handle r {:jsonrpc "2.0" :id 4 :method "tools/list"})]
      (is (= [{:name "lint" :description "Lint Clojure" :inputSchema {:type "object"}}]
             (get-in resp [:result :tools]))))))

(deftest ^:unit unknown-method-is-method-not-found
  (let [resp (handlers/handle reg {:jsonrpc "2.0" :id 9 :method "does/not-exist"})]
    (is (= 9 (:id resp)))
    (is (= -32601 (get-in resp [:error :code])))
    (is (= "does/not-exist" (get-in resp [:error :data :method])))))

(deftest ^:unit notifications-get-no-reply
  (testing "client initialized ack"
    (is (nil? (handlers/handle reg {:jsonrpc "2.0" :method "notifications/initialized"}))))
  (testing "unknown notification (no id) is silently ignored"
    (is (nil? (handlers/handle reg {:jsonrpc "2.0" :method "something/unknown"})))))
