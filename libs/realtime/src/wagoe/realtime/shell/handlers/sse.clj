(ns wagoe.realtime.shell.handlers.sse
  "Server-sent events for htmx: a stream endpoint and `push-fragment!`.

     ;; route, under :web — the session authenticates it like any page
     [\"/realtime/events\"
      {:get {:handler (sse/sse-handler svc :authorize-topic may-see-topic?)}}]

     ;; page
     [:div {:hx-ext \"sse\" :sse-connect \"/web/realtime/events?topic=order:123\"}
      [:div#order-row {:sse-swap \"message\"}]]

     ;; anywhere, any replica
     (sse/push-fragment! svc \"order:123\" [:tr#order-row [:td \"Shipped\"]])

   The stream writes on the request thread for as long as the client stays,
   so each open stream holds one server thread."
  (:require [wagoe.realtime.ports :as ports]
            [wagoe.realtime.schema :as schema]
            [wagoe.realtime.core.sse :as sse]
            [wagoe.realtime.shell.adapters.sse-adapter :as sse-adapter]
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
   by id, so one push can update several places.

   Goes over the bus, so subscribers on other replicas receive it too. Under
   :redis the fragment is serialised: keep it plain data."
  ([realtime-service topic fragment]
   (push-fragment! realtime-service topic fragment {}))
  ([realtime-service topic fragment {:keys [event]}]
   (ports/publish-to-topic realtime-service topic
                           {:type     :fragment
                            :event    (or event "message")
                            :fragment fragment})))

(defn- write! [^OutputStream out ^String s]
  (.write out (.getBytes s StandardCharsets/UTF_8))
  (.flush out))

(defn- stream!
  "Register `connection`, then write its events until the client goes away or
   the connection closes. Always deregisters."
  [realtime-service connection identity topics heartbeat-ms ^OutputStream out]
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
        (try (.close out) (catch IOException _ nil))))))

(defn- stream-body [realtime-service connection identity topics heartbeat-ms]
  (reify ring-protocols/StreamableResponseBody
    (write-body-to-stream [_ _response out]
      (stream! realtime-service connection identity topics heartbeat-ms out))))

(def ^:private error-status
  {:unauthorized 401 :forbidden 403 :validation-error 400})

(defn sse-handler
  "Ring handler that opens an SSE stream on the topics named by `?topic=`
   (repeatable).

   Needs a signed-in user on the request (`:user`, set by the session
   middleware); mount it as an ordinary, non-public web route. Clients do not
   choose their topics on the WebSocket path, so here every requested topic
   must pass `:authorize-topic`; without one, every topic is refused.

   Options:
     :authorize-topic (fn [request topic]) -> truthy to allow
     :heartbeat-ms    comment line interval, default 15000
     :queue-size      events buffered per client before it is dropped, default 256"
  [realtime-service & {:keys [authorize-topic heartbeat-ms queue-size]
                       :or   {authorize-topic (constantly false)
                              heartbeat-ms    15000
                              queue-size      256}}]
  (fn [request]
    (let [identity (sse/user-identity (:user request))
          decision (if identity
                     (sse/subscription-decision (sse/request-topics (:query-params request))
                                                schema/valid-topic?
                                                #(authorize-topic request %))
                     {:error {:type :unauthorized :message "Sign in required"}})]
      (if-let [{:keys [type message]} (:error decision)]
        {:status  (error-status type 400)
         :headers {"Content-Type" "text/plain; charset=utf-8"}
         :body    message}
        (let [connection (sse-adapter/create-sse-connection
                          (UUID/randomUUID)
                          (i18n-middleware/resolve-t-fn request)
                          :queue-size queue-size)]
          {:status  200
           :headers {"Content-Type"      "text/event-stream; charset=utf-8"
                     "Cache-Control"     "no-cache"
                     "X-Accel-Buffering" "no"}
           :body    (stream-body realtime-service connection identity
                                 (:ok decision) heartbeat-ms)})))))
