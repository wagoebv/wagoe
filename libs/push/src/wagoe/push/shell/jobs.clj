(ns wagoe.push.shell.jobs
  (:require [wagoe.push.core.callback :as callback]
            [wagoe.push.core.notification :as notif]
            [wagoe.push.core.delivery :as delivery]
            [wagoe.push.core.device :as device]
            [wagoe.push.shell.registry :as registry]
            [wagoe.push.shell.service :as service]
            [wagoe.push.ports :as ports]
            [clojure.tools.logging :as log]))

(defn- render
  "The notification as rendered for `locale`. With a secret, a data key the
   callback needs is the callback's: the app's value never reaches the device."
  [push-def notification-id data locale callback-secret]
  (when-let [shadowed (and (seq callback-secret) (seq (callback/shadowed-keys data)))]
    (log/warnf "Push %s: data keys %s are replaced by the callback's; rename them"
               notification-id (vec shadowed)))
  (notif/build-notification push-def data (or locale :en)))

(defn- deliver!
  "Results of sending `rendered` to `devices` on `platform`, each with the
   `:callback-id` its device posts back. Each device gets its own token, so
   without a secret one payload goes to them all."
  [{:keys [callback-secret callback-token-ttl-seconds] :as deps} platform notification-id rendered devices]
  (if (empty? callback-secret)
    (service/deliver-to-platform! deps platform rendered devices)
    (let [ttl   (or callback-token-ttl-seconds callback/token-ttl-seconds)
          sends (mapv (fn [device]
                        [(:token device)
                         (service/with-callback rendered notification-id callback-secret device ttl)])
                      devices)]
      (mapv (fn [[_ sent] result]
              (assoc result :callback-id (get-in sent [:data :provider-message-id])))
            sends
            (service/deliver-each! deps platform sends)))))

(defn- sent-event
  [notification-id result]
  (-> (delivery/result->analytics-event notification-id result (java.util.Date.))
      (assoc :id (random-uuid) :callback-id (:callback-id result))))

(defn handle-send-push
  "Job handler for :push/send. Resolves notification, fans out to devices, delivers."
  [{:keys [device-store analytics-store callback-secret] :as deps}
   {:keys [notification-id data user-id locale]}]
  (let [push-def (registry/get-push notification-id)]
    (when-not push-def
      (throw (ex-info "Push notification not found in registry"
                      {:type :not-found :notification-id notification-id})))
    (let [devices   (ports/get-user-devices device-store user-id)
          active    (filter :active? devices)
          rendered  (render push-def notification-id data locale callback-secret)
          grouped   (delivery/group-devices-by-platform active)]
      (log/infof "Push: delivering %s to %d devices for user %s"
                 notification-id (count active) user-id)
      (doseq [[platform platform-devices] grouped]
        (let [results (deliver! deps platform notification-id rendered platform-devices)]
          (doseq [result results]
            (ports/record-send! analytics-store
                                (assoc (sent-event notification-id result) :user-id user-id))
            (when (:token-invalid? result)
              (log/infof "Push: marking invalid token %s" (device/token-preview (:device-token result)))
              (ports/mark-token-invalid! device-store (:device-token result)))))))))

(def broadcast-page-size 500)

(defn handle-broadcast
  "Job handler for :push/broadcast. Paginated send to all devices on platform."
  [{:keys [device-store callback-secret] :as deps}
   {:keys [notification-id data platform _app-id locale]}]
  (let [push-def  (registry/get-push notification-id)
        rendered  (render push-def notification-id data locale callback-secret)]
    (log/infof "Push: broadcasting %s to platform %s" notification-id platform)
    (loop [after-id nil]
      (let [devices (ports/get-devices-by-platform device-store platform
                                                   {:limit broadcast-page-size :after-id after-id})]
        (when (seq devices)
          (let [results (deliver! deps platform notification-id rendered devices)]
            (doseq [result results]
              (ports/record-send! (:analytics-store deps) (sent-event notification-id result))
              (when (:token-invalid? result)
                (ports/mark-token-invalid! device-store (:device-token result)))))
          (when (= broadcast-page-size (count devices))
            (recur (:id (peek devices)))))))))
