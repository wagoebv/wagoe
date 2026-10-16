(ns wagoe.realtime.shell.handlers.sse
  "Server-sent events for htmx: `sse-handler` streams, `push-fragment!` pushes.
   See the realtime guide for the route, page and push example."
  (:require [wagoe.realtime.ports :as ports]
            [wagoe.realtime.core.sse :as sse]
            [wagoe.realtime.shell.adapters.sse-adapter :as sse-adapter]
            [wagoe.i18n.shell.middleware :as i18n-middleware]
            [clojure.tools.logging :as log]
            [ring.core.protocols :as ring-protocols])
  (:import (java.io IOException OutputStream)
           (java.nio.charset StandardCharsets)
           (java.util UUID)
           (java.util.concurrent.atomic AtomicInteger)))

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

(defn- stream!
  "Register `connection`, then write its events until the client goes away or
   the connection closes. Always deregisters and calls `release`."
  [realtime-service connection identity topics heartbeat-ms release ^OutputStream out]
  (try
    (let [cid (ports/connect-identified realtime-service connection identity topics)]
      (try
        (write! out sse/heartbeat)
        (loop []
          (let [item (sse-adapter/next-event connection heartbeat-ms)]
            (when-not (sse-adapter/closed? item)
              (write! out (or item sse/heartbeat))
              (recur))))
        (catch IOException _
          (log/debug "SSE client went away" {:connection-id cid}))
        (finally
          (ports/close connection)
          (ports/disconnect realtime-service cid)
          (try (.close out) (catch IOException _ nil)))))
    (finally (release))))

(defn- stream-body [realtime-service connection identity topics heartbeat-ms release]
  (reify ring-protocols/StreamableResponseBody
    (write-body-to-stream [_ _response out]
      (stream! realtime-service connection identity topics heartbeat-ms release out))))

(def ^:private error-status
  {:unauthorized 401 :forbidden 403 :validation-error 400})

(defn sse-handler
  "Ring handler that opens an SSE stream on the topics named by `?topic=`
   (repeatable). Needs a signed-in `:user`; every topic must pass
   `:authorize-topic`, and without one every topic is refused.

   Each open stream holds a server thread, so past `:max-streams` the handler
   answers 503 rather than starve the pool (Jetty's default is 50).

   Options:
     :authorize-topic (fn [request topic]) -> truthy to allow
     :heartbeat-ms    comment line interval, default 15000
     :queue-size      events buffered per client before it is dropped, default 256
     :max-streams     concurrent streams for this handler, default 32"
  [realtime-service & {:keys [authorize-topic heartbeat-ms queue-size max-streams]
                       :or   {authorize-topic (constantly false)
                              heartbeat-ms    15000
                              max-streams     32}}]
  (let [open (AtomicInteger.)]
    (fn [request]
      (let [identity (sse/user-identity (:user request))
            decision (if identity
                       (sse/subscription-decision (sse/request-topics (:query-params request))
                                                  #(authorize-topic request %))
                       {:error {:type :unauthorized :message "Sign in required"}})]
        (cond
          (:error decision)
          (let [{:keys [type message]} (:error decision)]
            {:status  (error-status type 400)
             :headers {"Content-Type" "text/plain; charset=utf-8"}
             :body    message})

          (> (.incrementAndGet open) max-streams)
          (do (.decrementAndGet open)
              (log/warn "SSE stream limit reached" {:max-streams max-streams})
              {:status  503
               :headers {"Content-Type" "text/plain; charset=utf-8" "Retry-After" "10"}
               :body    "Too many open streams"})

          :else
          (let [connection (sse-adapter/create-sse-connection
                            (UUID/randomUUID)
                            (i18n-middleware/resolve-t-fn request)
                            :queue-size queue-size)]
            {:status  200
             :headers {"Content-Type"      "text/event-stream; charset=utf-8"
                       "Cache-Control"     "no-cache"
                       "X-Accel-Buffering" "no"}
             :body    (stream-body realtime-service connection identity (:ok decision)
                                   heartbeat-ms #(.decrementAndGet open))}))))))
