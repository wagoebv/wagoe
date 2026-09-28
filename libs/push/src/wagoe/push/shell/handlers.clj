(ns wagoe.push.shell.handlers
  (:require [wagoe.push.ports :as ports]
            [wagoe.push.schema :as schema]
            [wagoe.push.shell.service :as service]
            [wagoe.push.core.analytics :as analytics]
            [malli.core :as m]
            [malli.error :as me]
            [ring.util.response :as resp]))

(def ^:private device-info-explainer (m/explainer schema/DeviceInfo))
(def ^:private callback-payload-explainer (m/explainer schema/CallbackPayload))

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

(defn register-device-handler
  [{:keys [device-store]} request]
  (let [user-id (get-in request [:user :id])
        body    (:body-params request)]
    (if-not (schema/valid-device-info? body)
      (invalid-body (device-info-explainer body))
      (let [device (ports/register-device! device-store user-id body)]
        (-> (resp/created (str "/api/push/devices/" (:id device)) device)
            (resp/content-type "application/json"))))))

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
  (let [body (:body-params request)]
    (cond
      (not (schema/valid-callback? body))
      (invalid-body (callback-payload-explainer body))

      (not (service/verify-callback-token
            callback-secret
            (:provider-message-id body)
            (:callback-token body)))
      (error-response 403 "forbidden" "Invalid callback token")

      :else
      (do
        (let [event {:id                  (random-uuid)
                     :notification-id     (:notification-id body)
                     :device-token        (:device-token body)
                     :platform            (:platform body)
                     :provider-message-id (:provider-message-id body)
                     :timestamp           (or (:timestamp body) (java.util.Date.))}]
          (case (:event-type body)
            :delivered (ports/record-delivery! analytics-store event)
            :opened    (ports/record-open! analytics-store event)))
        {:status 204 :headers {} :body nil}))))

(defn push-stats-handler
  [{:keys [analytics-store]} request]
  (let [notif-id (keyword (get-in request [:path-params :notification-id]))
        stats    (ports/get-push-stats analytics-store notif-id {})]
    (resp/response (analytics/calculate-rates stats))))

(defn push-routes
  "Every route but the callback requires a signed-in user, which the platform
   enforces. The callback is public: a device posts it, and the HMAC in its body
   is the credential (BOU-568)."
  [deps]
  ["/api/push"
   ["/devices"      {:post   {:handler (partial register-device-handler deps)}
                     :get    {:handler (partial list-devices-handler deps)}}]
   ["/devices/:token" {:delete {:handler (partial unregister-device-handler deps)}}]
   ["/callback"      {:post   {:handler (partial analytics-callback-handler deps)
                               :public  true}}]
   ["/stats/:notification-id" {:get {:handler (partial push-stats-handler deps)}}]])
