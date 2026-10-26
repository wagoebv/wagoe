(ns wagoe.push.shell.handlers
  (:require [clojure.string :as str]
            [wagoe.push.ports :as ports]
            [wagoe.push.schema :as schema]
            [wagoe.push.shell.service :as service]
            [wagoe.push.core.analytics :as analytics]
            [malli.core :as m]
            [malli.error :as me]
            [malli.transform :as mt]
            [ring.util.codec :as codec]
            [ring.util.response :as resp]))

(def ^:private device-info-explainer (m/explainer schema/DeviceInfo))
(def ^:private callback-payload-explainer (m/explainer schema/CallbackPayload))

;; A JSON body carries its enums as strings; the schemas want keywords.
(def ^:private decode-device-info (m/decoder schema/DeviceInfo mt/json-transformer))
(def ^:private decode-callback-payload (m/decoder schema/CallbackPayload mt/json-transformer))

(defn- error-response
  "The framework's one error body (BOU-586), written out: push does not
   depend on platform."
  [status type message & [details]]
  {:status  status
   :headers {}
   :body    {:error (cond-> {:type type :message message}
                      (seq details) (assoc :details details))}})

(defn- invalid-body [explanation]
  (error-response 400 "validation-error" "Request validation failed"
                  (me/humanize explanation)))

(defn- request-body
  "[body explanation]: the body the router coerced against the route's
   `:parameters`, or, where nothing did (the deprecated vector under a router
   without coercion, a handler called directly), decoded and explained here.
   The explanation is nil for a valid body."
  [request decode explain]
  (if-let [body (get-in request [:parameters :body])]
    [body nil]
    (let [body (decode (:body-params request))]
      [body (explain body)])))

(defn- device-location
  "The device's own path, under whichever prefix this was posted to, with its
   app-id: one token may be registered under several. nil for a token with a
   `/`, whose encoded form Jetty refuses in a path."
  [request {:keys [token app-id]}]
  (when-not (str/includes? token "/")
    (str (:uri request) "/" (codec/url-encode token) "?app-id=" (codec/url-encode app-id))))

(defn register-device-handler
  [{:keys [device-store]} request]
  (let [user-id        (get-in request [:user :id])
        [body invalid] (request-body request decode-device-info device-info-explainer)]
    (if invalid
      (invalid-body invalid)
      (let [device   (ports/register-device! device-store user-id body)
            location (device-location request device)]
        (cond-> (-> (resp/response device)
                    (resp/status 201)
                    (resp/content-type "application/json"))
          location (resp/header "Location" location))))))

(defn- user-devices-with-token [store user-id token app-id]
  (if (satisfies? ports/IDeviceLookup store)
    (ports/find-user-devices store user-id token app-id)
    (filter #(and (= token (:token %)) (or (nil? app-id) (= app-id (:app-id %))))
            (ports/get-user-devices store user-id))))

(defn get-device-handler
  [{:keys [device-store]} request]
  (let [app-id  (get-in request [:query-params "app-id"])
        ;; A repeated ?app-id= arrives as a vector, which SQL cannot compare to.
        devices (user-devices-with-token device-store
                                         (get-in request [:user :id])
                                         (get-in request [:path-params :token])
                                         (when (string? app-id) (not-empty app-id)))]
    (case (count devices)
      0 (error-response 404 "not-found" "Device not found")
      1 (resp/response (first devices))
      (error-response 409 "conflict" "The token is registered under several app-ids; pass ?app-id="))))

(defn unregister-device-handler
  [{:keys [device-store]} request]
  (let [user-id (get-in request [:user :id])
        token   (get-in request [:path-params :token])]
    (ports/unregister-device! device-store user-id token)
    {:status 204 :headers {} :body nil}))

(defn list-devices-handler
  [{:keys [device-store]} request]
  (let [user-id (get-in request [:user :id])
        devices (ports/get-user-devices device-store user-id)]
    (resp/response {:devices devices})))

(defn analytics-callback-handler
  [{:keys [analytics-store callback-secret]} request]
  (let [[body invalid] (request-body request decode-callback-payload callback-payload-explainer)
        verdict        (when-not invalid
                         (service/callback-verdict callback-secret body
                                                   (.getEpochSecond (java.time.Instant/now))))]
    (cond
      invalid
      (invalid-body invalid)

      (nil? verdict)
      (error-response 403 "forbidden" "Invalid callback token")

      :else
      ;; A bound token's id is the one minted for the push, which its sent row
      ;; carries as :callback-id. A legacy id is kept where 1.0 kept it.
      (let [id    (:provider-message-id body)
            event (cond-> {:id              (random-uuid)
                           :notification-id (:notification-id body)
                           :device-token    (:device-token body)
                           :platform        (:platform body)
                           :timestamp       (or (:timestamp body) (java.util.Date.))}
                    (= :bound verdict) (assoc :callback-id id)
                    (= :legacy verdict) (assoc :provider-message-id id))]
        (case (:event-type body)
          :delivered (ports/record-delivery! analytics-store event)
          :opened    (ports/record-open! analytics-store event))
        {:status 204 :headers {} :body nil}))))

(defn push-stats-handler
  [{:keys [analytics-store]} request]
  (let [notif-id (keyword (get-in request [:path-params :notification-id]))
        stats    (ports/get-push-stats analytics-store notif-id {})]
    (resp/response (analytics/calculate-rates stats))))

(defn api-routes
  "Every route but the callback requires a signed-in user, which the platform
   enforces. The callback is public: a device posts it, and the HMAC in its body
   is the credential (BOU-568). The device routes act on the caller's own
   devices; the stats ask `:push/read`. Contributed as `:api`, so served under
   `/api/v1`; flat, so each path gets its unversioned redirect."
  [deps]
  [["/push/devices" {:authorization :self
                     :post {:handler    (partial register-device-handler deps)
                            :parameters {:body schema/DeviceInfo}}
                     :get  {:handler (partial list-devices-handler deps)}}]
   ["/push/devices/:token" {:authorization :self
                            :openapi {:parameters [{:name "token" :in "path" :required true
                                                    :schema {:type "string"}}]}
                            :get    {:handler (partial get-device-handler deps)
                                     :openapi {:parameters [{:name "app-id" :in "query" :required false
                                                             :schema {:type "string"}}]}}
                            :delete {:handler (partial unregister-device-handler deps)}}]
   ["/push/callback" {:post {:handler    (partial analytics-callback-handler deps)
                             :parameters {:body schema/CallbackPayload}
                             :public     true}}]
   ["/push/stats/:notification-id" {:permission :push/read
                                    :openapi {:parameters [{:name "notification-id" :in "path" :required true
                                                            :schema {:type "string"}}]}
                                    :get {:handler (partial push-stats-handler deps)}}]])

(defn- without-body-parameters
  "Route data with no `:parameters` on its methods: an app's own router may
   coerce with something else, and the handlers check the body themselves."
  [data]
  (reduce (fn [d method] (cond-> d (map? (get d method)) (update method dissoc :parameters)))
          data
          [:get :post :delete]))

(defn push-routes
  "Deprecated: the module mounts `api-routes` itself. The same routes as one
   `/api/push` vector, for apps that mounted it by hand."
  [deps]
  (into ["/api/push"]
        (map (fn [[path data]] [(subs path (count "/push")) (without-body-parameters data)]))
        (api-routes deps)))
