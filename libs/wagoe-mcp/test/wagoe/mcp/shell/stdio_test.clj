(ns wagoe.mcp.shell.stdio-test
  (:require [wagoe.mcp.core.handlers :as handlers]
            [wagoe.mcp.core.registry :as registry]
            [wagoe.mcp.shell.codec :as codec]
            [wagoe.mcp.shell.stdio :as stdio]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.io StringReader StringWriter)))

(defn- run
  "Feed `lines` (a seq of JSON-RPC strings) through the stdio server with an
   empty registry and return the parsed response messages."
  [lines]
  (let [in  (StringReader. (str (str/join "\n" lines) "\n"))
        out (StringWriter.)
        t   (stdio/transport in out)]
    (stdio/serve t #(handlers/handle registry/empty-registry %))
    (->> (str/split-lines (str out))
         (remove str/blank?)
         (mapv codec/decode))))

(deftest ^:unit stdio-handshake-round-trip
  (testing "initialize → initialized notif (no reply) → tools/list → resources/list"
    (let [responses (run [(codec/encode {:jsonrpc "2.0" :id 1 :method "initialize"})
                          (codec/encode {:jsonrpc "2.0" :method "notifications/initialized"})
                          (codec/encode {:jsonrpc "2.0" :id 2 :method "tools/list"})
                          (codec/encode {:jsonrpc "2.0" :id 3 :method "resources/list"})])]
      ;; the notification produces no response line
      (is (= 3 (count responses)))
      (is (= "2025-06-18" (get-in (first responses) [:result :protocolVersion])))
      (is (= {:tools []} (:result (nth responses 1))))
      (is (= {:resources []} (:result (nth responses 2)))))))

(deftest ^:unit stdio-blank-lines-do-not-stop-the-server
  (testing "a blank line is skipped, not treated as EOF"
    (let [responses (run [(codec/encode {:jsonrpc "2.0" :id 1 :method "ping"})
                          "" ;; stray blank line mid-stream
                          (codec/encode {:jsonrpc "2.0" :id 2 :method "ping"})])]
      (is (= 2 (count responses)))
      (is (= [1 2] (mapv :id responses))))))

(deftest ^:unit stdio-malformed-input-yields-parse-error-and-continues
  (let [responses (run ["{ this is not json"
                        (codec/encode {:jsonrpc "2.0" :id 5 :method "ping"})])]
    (is (= -32700 (get-in (first responses) [:error :code])))
    (is (= 5 (:id (second responses))))
    (is (= {} (:result (second responses))))))
