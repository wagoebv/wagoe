(ns wagoe.realtime.shell.adapters.websocket-adapter
  "WebSocket connection adapter for Ring/Jetty.
  
  Wraps Ring WebSocket implementation to provide IWebSocketConnection protocol.
  Handles JSON encoding/decoding and WebSocket frame transmission.
  
  Responsibilities (Shell/I/O):
  - Send messages over WebSocket (I/O operation)
  - Close WebSocket connections (I/O operation)
  - JSON encoding (external format transformation)
  - Error handling and logging"
  (:require [wagoe.realtime.ports :as ports]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]))

;; =============================================================================
;; Ring WebSocket Adapter
;; =============================================================================

(defrecord RingWebSocketAdapter [connection-id ws-channel]
  ports/IWebSocketConnection

  (send-message [_this message]
    ;; Encode message to JSON and send as text frame
    (try
      (let [json-message (json/generate-string message)]
        ;; Ring WebSocket: send! function takes channel and message
        ;; Assumes ws-channel has :send! function (Ring 2.0 WebSocket spec)
        (when-let [send-fn (:send! ws-channel)]
          (send-fn json-message))
        nil)
      (catch Exception e
        ;; Log error but don't throw - connection may be closed
        (log/warn e "Failed to send WebSocket message"
                  {:connection-id connection-id
                   :message-type (:type message)})
        nil)))

  (close [_this]
    ;; Close WebSocket connection gracefully
    (try
      (when-let [close-fn (:close! ws-channel)]
        (close-fn))
      nil
      (catch Exception e
        (log/warn e "Error closing WebSocket connection"
                  {:connection-id connection-id})
        nil)))

  (connection-id [_this]
    connection-id)

  (open? [_this]
    ;; Check if WebSocket is open
    ;; Ring WebSocket channels have :open? function
    (if-let [open-fn (:open? ws-channel)]
      (open-fn)
      false)))

;; =============================================================================
;; Test Adapter (In-Memory for Testing)
;; =============================================================================

(defrecord TestWebSocketAdapter [connection-id sent-messages open-state]
  ;; sent-messages is an atom of vector
  ;; open-state is an atom of boolean
  ports/IWebSocketConnection

  (send-message [_this message]
    (when @open-state
      (swap! sent-messages conj message))
    nil)

  (close [_this]
    (reset! open-state false)
    nil)

  (connection-id [_this]
    connection-id)

  (open? [_this]
    @open-state))

;; =============================================================================
;; Factory Functions
;; =============================================================================

(defn create-ring-websocket-adapter
  "Create WebSocket adapter for Ring WebSocket channel.
  
  Args:
    connection-id - UUID for connection
    ws-channel - Ring WebSocket channel map with:
                 :send! - Function to send message
                 :close! - Function to close connection
                 :open? - Function to check if open
  
  Returns:
    RingWebSocketAdapter instance implementing IWebSocketConnection"
  [connection-id ws-channel]
  (->RingWebSocketAdapter connection-id ws-channel))

(defn create-test-websocket-adapter
  "Create test WebSocket adapter for testing (in-memory).
  
  Args:
    connection-id - UUID for connection
  
  Returns:
    TestWebSocketAdapter instance with:
      - sent-messages atom (vector of sent messages)
      - open-state atom (boolean)"
  [connection-id]
  (->TestWebSocketAdapter connection-id (atom []) (atom true)))
