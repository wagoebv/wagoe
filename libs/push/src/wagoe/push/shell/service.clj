(ns wagoe.push.shell.service
  (:require [wagoe.push.ports :as ports]
            [wagoe.push.core.delivery :as delivery]
            [wagoe.jobs.ports :as job-ports]
            [clojure.tools.logging :as log])
  (:import [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]
           [java.nio.charset StandardCharsets]))

(defn- hmac-sha256 [secret data]
  (let [mac (Mac/getInstance "HmacSHA256")
        key (SecretKeySpec. (.getBytes ^String secret StandardCharsets/UTF_8) "HmacSHA256")]
    (.init mac key)
    (apply str (map #(format "%02x" (bit-and % 0xff))
                    (.doFinal mac (.getBytes ^String data StandardCharsets/UTF_8))))))

(defn generate-callback-token
  "Generate HMAC callback token for a provider-message-id."
  [callback-secret provider-message-id]
  (hmac-sha256 callback-secret provider-message-id))

(defn verify-callback-token
  "Verify HMAC callback token. Constant-time comparison."
  [callback-secret provider-message-id callback-token]
  (let [expected (hmac-sha256 callback-secret provider-message-id)]
    (java.security.MessageDigest/isEqual
     (.getBytes ^String expected StandardCharsets/UTF_8)
     (.getBytes ^String callback-token StandardCharsets/UTF_8))))

(defn deliver-to-platform!
  "Internal: send notification to devices on a specific platform.
   Returns vector of results, or empty vector if no devices."
  [{:keys [fcm-provider apns-provider]} platform notification devices]
  (if (empty? devices)
    []
    (case platform
      :fcm  (let [tokens (mapv :token devices)]
              (ports/fcm-send-multicast!
               fcm-provider
               (delivery/build-fcm-payload notification (first tokens))
               tokens))
      :apns (ports/apns-send-batch!
             apns-provider
             (delivery/build-apns-payload notification)
             (mapv :token devices)))))

(defrecord PushService [device-store analytics-store
                        fcm-provider apns-provider
                        job-queue callback-secret]
  ports/IPushService

  (send-push! [_ notification-id data opts]
    (let [job {:id       (random-uuid)
               :job-type :push/send
               :args     {:notification-id notification-id
                          :data            data
                          :user-id         (:user-id opts)
                          :locale          (:locale opts)}}]
      (log/infof "Push: enqueueing %s for user %s" notification-id (:user-id opts))
      (job-ports/enqueue-job! job-queue :push job)
      (:id job)))

  (schedule-push! [_ notification-id data opts scheduled-at]
    (let [job {:id           (random-uuid)
               :job-type     :push/send
               :args         {:notification-id notification-id
                              :data            data
                              :user-id         (:user-id opts)
                              :locale          (:locale opts)}
               :scheduled-at scheduled-at}]
      (log/infof "Push: scheduling %s for %s" notification-id scheduled-at)
      (job-ports/enqueue-job! job-queue :push job)
      (:id job)))

  (broadcast! [_ notification-id data opts]
    (let [job {:id       (random-uuid)
               :job-type :push/broadcast
               :args     {:notification-id notification-id
                          :data            data
                          :platform        (:platform opts)
                          :app-id          (:app-id opts)
                          :locale          (:locale opts)}}]
      (log/infof "Push: enqueueing broadcast %s" notification-id)
      (job-ports/enqueue-job! job-queue :push job)
      (:id job))))
