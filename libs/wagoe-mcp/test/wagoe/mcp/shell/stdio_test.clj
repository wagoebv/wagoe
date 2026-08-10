(ns wagoe.mcp.shell.stdio-test
  (:require [wagoe.mcp.core.handlers :as handlers]
            [wagoe.mcp.core.registry :as registry]
            [wagoe.mcp.shell.codec :as codec]
            [wagoe.mcp.shell.stdio :as stdio]
            [clojure.java.io :as io]
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

;; =============================================================================
;; stdout belongs to the protocol (BOU-105)
;; =============================================================================

(deftest ^:unit server-claims-stdout-so-logging-cannot-corrupt-the-stream
  ;; stdio MCP puts JSON-RPC on stdout. Measured before this existed: running
  ;; the documented command from the monorepo produced 88 lines of logback
  ;; output before the first response, because the repository's logback.xml
  ;; console appender targets stdout.
  ;;
  ;; The library ships a logback.xml targeting stderr, but the server does not
  ;; control the classpath it runs on, so it takes stdout for itself.
  (require 'wagoe.mcp.shell.server)
  (let [claim (resolve 'wagoe.mcp.shell.server/claim-stdout!)
        real-out System/out
        real-err System/err]
    (try
      (let [returned (claim)]
        (testing "the caller is handed the original stdout, for the transport"
          (is (identical? real-out returned)))

        (testing "System/out no longer points at it"
          (is (not (identical? real-out System/out)))))
      (finally
        (System/setOut real-out)
        (System/setErr real-err)
        (alter-var-root #'*out* (constantly (java.io.PrintWriter. real-out true)))))))

(deftest ^:unit entry-namespace-requires-nothing-that-logs
  ;; The claim only works if it runs before logging initialises, and logging
  ;; initialises when a namespace that uses it is loaded. So the entry
  ;; namespace must require nothing — the composition root lives in
  ;; wagoe.mcp.shell.boot and is loaded from inside -main.
  ;;
  ;; This is the part that regresses silently: adding one convenient require to
  ;; server.clj moves logback's init back before -main and the banner returns.
  (let [src (or (some #(when (.exists (io/file %)) (slurp %))
                      ["libs/wagoe-mcp/src/wagoe/mcp/shell/server.clj"
                       "src/wagoe/mcp/shell/server.clj"])
                (throw (ex-info "server.clj not found — cannot check" {})))]

    (testing "the source was read — otherwise this passes vacuously"
      (is (str/includes? src "claim-stdout!")))

    (testing "the entry namespace has no :require form"
      (is (not (str/includes? src "(:require"))
          "server.clj requires a namespace again; loading it initialises logback before -main"))

    (testing "and loads the composition root from inside -main"
      (is (str/includes? src "(require 'wagoe.mcp.shell.boot)")))))
