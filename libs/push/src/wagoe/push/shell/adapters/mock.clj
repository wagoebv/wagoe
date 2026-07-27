(ns wagoe.push.shell.adapters.mock
  (:require [wagoe.push.ports :as ports]
            [clojure.tools.logging :as log])
  (:import [java.util UUID]))

(defrecord MockFCMProvider []
  ports/IFCMProvider

  (fcm-send! [_ payload]
    (let [token (get-in payload [:message :token])
          msg-id (str "mock-fcm-" (UUID/randomUUID))]
      (log/infof "Mock FCM: sent to %s → %s" token msg-id)
      {:success?   true
       :message-id msg-id
       :device-token token
       :platform   :fcm}))

  (fcm-send-multicast! [_ _payload tokens]
    (log/infof "Mock FCM: multicast to %d tokens" (count tokens))
    (mapv (fn [token]
            {:success?     true
             :message-id   (str "mock-fcm-" (UUID/randomUUID))
             :device-token token
             :platform     :fcm})
          tokens))

  (fcm-validate-token [_ token]
    (log/infof "Mock FCM: validate token %s → valid" token)
    {:valid? true :token token}))

(defrecord MockAPNsProvider []
  ports/IAPNsProvider

  (apns-send! [_ _payload device-token]
    (let [apns-id (str "mock-apns-" (UUID/randomUUID))]
      (log/infof "Mock APNs: sent to %s → %s" device-token apns-id)
      {:success?     true
       :apns-id      apns-id
       :message-id   apns-id
       :device-token device-token
       :platform     :apns}))

  (apns-send-batch! [_ _payload device-tokens]
    (log/infof "Mock APNs: batch to %d tokens" (count device-tokens))
    (mapv (fn [token]
            {:success?     true
             :apns-id      (str "mock-apns-" (UUID/randomUUID))
             :message-id   (str "mock-apns-" (UUID/randomUUID))
             :device-token token
             :platform     :apns})
          device-tokens)))
