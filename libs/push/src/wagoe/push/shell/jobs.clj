(ns wagoe.push.shell.jobs
  (:require [wagoe.push.core.notification :as notif]
            [wagoe.push.core.delivery :as delivery]
            [wagoe.push.shell.registry :as registry]
            [wagoe.push.shell.service :as service]
            [wagoe.push.ports :as ports]
            [clojure.tools.logging :as log]))

(defn handle-send-push
  "Job handler for :push/send. Resolves notification, fans out to devices, delivers."
  [{:keys [device-store fcm-provider apns-provider
           analytics-store]}
   {:keys [notification-id data user-id locale]}]
  (let [push-def (registry/get-push notification-id)]
    (when-not push-def
      (throw (ex-info "Push notification not found in registry"
                      {:type :not-found :notification-id notification-id})))
    (let [devices   (ports/get-user-devices device-store user-id)
          active    (filter :active? devices)
          rendered  (notif/build-notification push-def data (or locale :en))
          grouped   (delivery/group-devices-by-platform active)]
      (log/infof "Push: delivering %s to %d devices for user %s"
                 notification-id (count active) user-id)
      (doseq [[platform platform-devices] grouped]
        (let [results (service/deliver-to-platform!
                       {:fcm-provider fcm-provider :apns-provider apns-provider}
                       platform rendered platform-devices)]
          (doseq [result results]
            (ports/record-send! analytics-store
                                (merge (delivery/result->analytics-event notification-id result (java.util.Date.))
                                       {:id (random-uuid) :user-id user-id}))
            (when (:token-invalid? result)
              (log/infof "Push: marking invalid token %s" (:device-token result))
              (ports/mark-token-invalid! device-store (:device-token result)))))))))

(defn handle-broadcast
  "Job handler for :push/broadcast. Paginated send to all devices on platform."
  [{:keys [device-store] :as deps}
   {:keys [notification-id data platform _app-id locale]}]
  (let [push-def  (registry/get-push notification-id)
        rendered  (notif/build-notification push-def data (or locale :en))
        page-size 500]
    (log/infof "Push: broadcasting %s to platform %s" notification-id platform)
    (loop [offset 0]
      (let [devices (ports/get-devices-by-platform device-store platform
                                                   {:limit page-size :offset offset})]
        (when (seq devices)
          (let [results (service/deliver-to-platform!
                         deps platform rendered devices)]
            (doseq [result results]
              (ports/record-send! (:analytics-store deps)
                                  (merge (delivery/result->analytics-event notification-id result (java.util.Date.))
                                         {:id (random-uuid)}))
              (when (:token-invalid? result)
                (ports/mark-token-invalid! device-store (:device-token result)))))
          (when (= page-size (count devices))
            (recur (+ offset page-size))))))))
