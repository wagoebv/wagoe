(ns wagoe.realtime.shell.handlers.sse
  "Server-sent events for htmx: `sse-handler` streams, `push-fragment!` pushes.
   See the realtime guide for the route, page and push example."
  (:require [wagoe.realtime.ports :as ports]
            [wagoe.realtime.core.sse :as sse]
            [wagoe.realtime.shell.adapters.sse-adapter :as sse-adapter]
            [wagoe.realtime.shell.service :as service]
            [wagoe.i18n.shell.middleware :as i18n-middleware]
            [clojure.tools.logging :as log]
            [ring.core.protocols :as ring-protocols])
  (:import (java.io IOException OutputStream)
           (java.nio.charset StandardCharsets)
           (java.util UUID)))

(defn push-fragment!
  "Render `fragment` (Hiccup or an HTML string) for every stream subscribed to
   `topic` and send it as SSE event `:event` (default \"message\", what
   `sse-swap=\"message\"` listens for). Elements carrying `:hx-swap-oob` swap
   by id, so one push can update several places. Goes over the bus, so other
   replicas' subscribers get it too; keep the fragment plain data."
  ([realtime-service topic fragment]
   (push-fragment! realtime-service topic fragment {}))
  ([realtime-service topic fragment {:keys [event]}]
   (ports/publish-to-topic realtime-service topic
                           {:type     :fragment
                            :event    event
                            :fragment fragment})))

(defn- write! [^OutputStream out ^String s]
  (.write out (.getBytes s StandardCharsets/UTF_8))
  (.flush out))

(defn- still-valid? [revalidate request]
  (or (nil? revalidate)
      (try (boolean (revalidate request))
           (catch Exception e
             (log/warn e "SSE revalidation threw; closing")
             false))))

(defn- pump!
  "Write events until the client goes away, the connection closes, or the
   request stops passing `revalidate` (asked once per heartbeat interval)."
  [connection heartbeat-ms revalidate request out]
  (write! out sse/heartbeat)
  (loop [checked (System/currentTimeMillis)]
    (let [item (sse-adapter/next-event connection heartbeat-ms)]
      (when-not (sse-adapter/closed? item)
        (let [now  (System/currentTimeMillis)
              due? (>= (- now checked) heartbeat-ms)]
          (if (and due? (not (still-valid? revalidate request)))
            (log/info "SSE session no longer valid; closing")
            (do (write! out (or item sse/heartbeat))
                (recur (if due? now checked)))))))))

(defn- stream!
  "Take a stream slot, register `connection`, and pump. The slot is taken here,
   not in the handler, so a response that is never written holds none. Over
   the cap the client is told to retry later."
  [realtime-service connection identity topics {:keys [heartbeat-ms revalidate]}
   request ^OutputStream out]
  (try
    (if-not (service/acquire-stream! realtime-service)
      (do (log/warn "SSE stream limit reached")
          (write! out sse/retry-later))
      (try
        (let [cid (ports/connect-identified realtime-service connection identity topics)]
          (try
            (pump! connection heartbeat-ms revalidate request out)
            (catch IOException _
              (log/debug "SSE client went away" {:connection-id cid}))
            (finally
              (ports/close connection)
              (ports/disconnect realtime-service cid))))
        (finally (service/release-stream! realtime-service))))
    (catch IOException _ nil)
    (finally
      (try (.close out) (catch IOException _ nil)))))

(def ^:private error-status
  {:unauthorized 401 :forbidden 403 :validation-error 400})

(defn sse-handler
  "Ring handler that opens an SSE stream on the topics named by `?topic=`
   (repeatable). Needs a signed-in `:user`; every topic must pass
   `:authorize-topic`, and without one every topic is refused.

   Each open stream holds a server thread; the service caps them per node
   (`:max-streams`), and a stream over the cap is told to retry in 10 s.

   Options:
     :authorize-topic (fn [request topic]) -> truthy to allow
     :revalidate      (fn [request]) -> truthy while the session is still good;
                      asked every heartbeat, the stream closes when it fails
     :heartbeat-ms    comment line interval, default 15000
     :queue-size      events buffered per client before it is dropped, default 256
     :max-topics      topics per stream, default 16"
  [realtime-service & {:keys [authorize-topic heartbeat-ms queue-size max-topics revalidate]
                       :or   {authorize-topic (constantly false)
                              heartbeat-ms    15000
                              max-topics      16}}]
  (fn [request]
    (let [identity (sse/user-identity (:user request))
          decision (if identity
                     (sse/subscription-decision (sse/request-topics (:query-params request))
                                                #(authorize-topic request %)
                                                max-topics)
                     {:error {:type :unauthorized :message "Sign in required"}})]
      (if-let [{:keys [type message]} (:error decision)]
        {:status  (error-status type 400)
         :headers {"Content-Type" "text/plain; charset=utf-8"}
         :body    message}
        (let [connection (sse-adapter/create-sse-connection
                          (UUID/randomUUID)
                          (i18n-middleware/resolve-t-fn request)
                          :queue-size queue-size)
              opts       {:heartbeat-ms heartbeat-ms :revalidate revalidate}]
          {:status  200
           :headers {"Content-Type"      "text/event-stream; charset=utf-8"
                     "Cache-Control"     "no-cache"
                     "X-Accel-Buffering" "no"}
           :body    (reify ring-protocols/StreamableResponseBody
                      (write-body-to-stream [_ _response out]
                        (stream! realtime-service connection identity (:ok decision)
                                 opts request out)))})))))
