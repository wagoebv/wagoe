(ns wagoe.push.shell.service
  (:require [wagoe.push.ports :as ports]
            [wagoe.push.core.delivery :as delivery]
            [wagoe.jobs.core.job :as job]
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
  "Verify HMAC callback token. Constant-time comparison. False when no secret
   is configured, blank included (an empty HMAC key throws): no token can be
   valid then."
  [callback-secret provider-message-id callback-token]
  (boolean
   (when (seq callback-secret)
     (java.security.MessageDigest/isEqual
      (.getBytes ^String (hmac-sha256 callback-secret provider-message-id) StandardCharsets/UTF_8)
      (.getBytes ^String callback-token StandardCharsets/UTF_8)))))

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

(defn- push-job
  "A complete job map for the queue.

   Built through `job/create-job` rather than by hand: the hand-built maps
   carried no `:created-at`, `:retry-count` or `:max-retries`, so the DB adapter
   threw serialising one and `fail-job` compared a retry count against nil
   (BOU-418 review)."
  [queue-name job-type args]
  (job/create-job {:job-type job-type :queue queue-name :args args}
                  (random-uuid)
                  (java.time.Instant/now)))

(defrecord PushService [device-store analytics-store
                        fcm-provider apns-provider
                        job-queue queue-name callback-secret]
  ports/IPushService

  (send-push! [_ notification-id data opts]
    (let [job (push-job queue-name :push/send
                        {:notification-id notification-id
                         :data            data
                         :user-id         (:user-id opts)
                         :locale          (:locale opts)})]
      (log/infof "Push: enqueueing %s for user %s" notification-id (:user-id opts))
      (job-ports/enqueue-job! job-queue queue-name job)
      (:id job)))

  (schedule-push! [_ notification-id data opts scheduled-at]
    (let [job (push-job queue-name :push/send
                        {:notification-id notification-id
                         :data            data
                         :user-id         (:user-id opts)
                         :locale          (:locale opts)})]
      (log/infof "Push: scheduling %s for %s" notification-id scheduled-at)
      ;; `schedule-job!`, not `enqueue-job!` with a `:scheduled-at` key: the
      ;; adapters schedule on `:execute-at` and knew nothing of `:scheduled-at`,
      ;; so a push scheduled for next week went out immediately (BOU-418 review).
      (job-ports/schedule-job! job-queue queue-name job scheduled-at)
      (:id job)))

  (broadcast! [_ notification-id data opts]
    (let [job (push-job queue-name :push/broadcast
                        {:notification-id notification-id
                         :data            data
                         :platform        (:platform opts)
                         :app-id          (:app-id opts)
                         :locale          (:locale opts)})]
      (log/infof "Push: enqueueing broadcast %s" notification-id)
      (job-ports/enqueue-job! job-queue queue-name job)
      (:id job))))
