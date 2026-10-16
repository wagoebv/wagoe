(ns wagoe.realtime.shell.adapters.sse-adapter
  "A server-sent events connection, registered like a WebSocket so the bus
   delivers to it. `send-message` formats the event and queues it; the
   streaming response body drains the queue."
  (:require [wagoe.realtime.ports :as ports]
            [wagoe.realtime.core.sse :as sse]
            [wagoe.i18n.shell.render :as i18n]
            [cheshire.core :as json]
            [clojure.tools.logging :as log])
  (:import (java.util.concurrent BlockingQueue LinkedBlockingQueue TimeUnit)))

(def ^:private closed ::closed)

(defn- event-text
  "Fragments render here, per subscriber, so markers resolve in that
   subscriber's locale. Anything else goes out as JSON, named by its :type."
  [t-fn {:keys [type event fragment] :as message}]
  (if (= :fragment type)
    (sse/format-event (or event "message") (i18n/render fragment t-fn))
    (sse/format-event (if type (name type) "message") (json/generate-string message))))

(defrecord SseConnection [connection-id ^BlockingQueue queue open-state t-fn]
  ports/IWebSocketConnection

  (send-message [_this message]
    (when @open-state
      (try
        (when-not (.offer queue (event-text t-fn message))
          ;; A client that stops reading must not grow the heap.
          (log/warn "SSE client too slow; closing" {:connection-id connection-id})
          (reset! open-state false)
          (.offer queue closed))
        (catch Exception e
          (log/warn e "Failed to render SSE event" {:connection-id connection-id}))))
    nil)

  (close [_this]
    (when (compare-and-set! open-state true false)
      (.offer queue closed))
    nil)

  (connection-id [_this] connection-id)

  (open? [_this] @open-state))

(defn create-sse-connection
  "`t-fn` translates [:t ...] markers in pushed fragments; nil renders keys."
  [connection-id t-fn & {:keys [queue-size] :or {queue-size 256}}]
  (->SseConnection connection-id (LinkedBlockingQueue. (int queue-size)) (atom true) t-fn))

(defn next-event
  "The next queued event text, nil after `timeout-ms` with nothing queued, or
   ::closed once the connection is closed."
  [{:keys [^BlockingQueue queue open-state]} timeout-ms]
  (if @open-state
    (.poll queue (long timeout-ms) TimeUnit/MILLISECONDS)
    closed))

(defn closed? [x] (= closed x))
