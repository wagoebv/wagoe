(ns wagoe.push.shell.service
  (:require [wagoe.push.ports :as ports]
            [wagoe.push.core.callback :as callback]
            [wagoe.push.core.delivery :as delivery]
            [wagoe.jobs.core.job :as job]
            [wagoe.jobs.ports :as job-ports]
            [clojure.tools.logging :as log])
  (:import [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]
           [java.nio.charset StandardCharsets]
           [java.time Instant]))

(defn- hmac-sha256 [secret data]
  (let [mac (Mac/getInstance "HmacSHA256")
        key (SecretKeySpec. (.getBytes ^String secret StandardCharsets/UTF_8) "HmacSHA256")]
    (.init mac key)
    (apply str (map #(format "%02x" (bit-and % 0xff))
                    (.doFinal mac (.getBytes ^String data StandardCharsets/UTF_8))))))

(defn- same?
  "Constant-time string equality."
  [^String a ^String b]
  (java.security.MessageDigest/isEqual (.getBytes a StandardCharsets/UTF_8)
                                       (.getBytes b StandardCharsets/UTF_8)))

(defn- legacy-token-valid?
  "A token over the id alone, as 1.0 minted them."
  [callback-secret provider-message-id callback-token]
  (boolean (and (seq callback-secret)
                (same? (hmac-sha256 callback-secret provider-message-id) callback-token))))

(defn- bound-mac
  "Keyed apart from the legacy token, so no legacy MAC passes for a bound one
   or the other way round."
  [callback-secret message]
  (hmac-sha256 (hmac-sha256 callback-secret "wagoe.push/callback-token-v2") message))

(defn generate-callback-token
  "Deprecated, goes in 2.0: the token covers the id alone, so it verifies for
   any notification and device and never expires. Use `callback-token`."
  {:deprecated "1.0.2"}
  [callback-secret provider-message-id]
  (hmac-sha256 callback-secret provider-message-id))

(defn verify-callback-token
  "Deprecated, goes in 2.0: checks only a `generate-callback-token` token.
   Use `callback-verdict`."
  {:deprecated "1.0.2"}
  [callback-secret provider-message-id callback-token]
  (legacy-token-valid? callback-secret provider-message-id callback-token))

(defn callback-token
  "A token valid for one push to one device until `:expires-at`, in epoch
   seconds: `fields` are `:callback-id`, `:notification-id`, `:device-token`,
   `:platform` and `:expires-at` (BOU-849)."
  [callback-secret fields]
  (callback/token (:expires-at fields)
                  (bound-mac callback-secret (callback/signed-message fields))))

(defn callback-verdict
  "`:bound` for a callback whose token names its notification, device and
   platform and has not expired at `now` (epoch seconds), `:legacy` for a
   `generate-callback-token` token, nil for anything else, and for everything
   without a secret: an empty HMAC key throws."
  [callback-secret {:keys [provider-message-id callback-token] :as payload} now]
  (when (seq callback-secret)
    (if-let [{:keys [expires-at mac]} (callback/parse-token callback-token)]
      (when (and (< now expires-at)
                 (same? mac (bound-mac callback-secret
                                       (callback/signed-message
                                        (assoc payload
                                               :callback-id provider-message-id
                                               :expires-at expires-at)))))
        :bound)
      (when (legacy-token-valid? callback-secret provider-message-id callback-token)
        :legacy))))

(defn- attach-callback
  "`notification` with a minted id and `(mint-token id)` in its data; unchanged
   without a secret, as no token could verify."
  [notification notification-id callback-secret mint-token]
  (if (empty? callback-secret)
    notification
    (let [callback-id (str (random-uuid))]
      (update notification :data merge
              (callback/callback-data notification-id callback-id (mint-token callback-id))))))

(defn with-callback
  "The notification with what `device` posts back to `/push/callback`: a
   minted id, and a token for this notification on this device that expires.
   The provider's message id arrives only after the send, so the id is minted
   here. Unchanged without a secret: no token could verify.

   Without `device`, deprecated: one id and a token over it alone, which goes
   in 2.0."
  ([notification notification-id callback-secret]
   (attach-callback notification notification-id callback-secret
                    #(hmac-sha256 callback-secret %)))
  ([notification notification-id callback-secret {:keys [token platform]}]
   (let [expires-at (+ (.getEpochSecond (Instant/now)) callback/token-ttl-seconds)]
     (attach-callback notification notification-id callback-secret
                      #(callback-token callback-secret {:callback-id     %
                                                        :notification-id notification-id
                                                        :device-token    token
                                                        :platform        platform
                                                        :expires-at      expires-at})))))

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

(defn deliver-each!
  "Internal: each `[device-token notification]` sent on `platform`, concurrently
   where the provider implements `IPerDeviceSend`. Per-token results, in order."
  [{:keys [fcm-provider apns-provider]} platform sends]
  (let [[provider build send-one]
        (case platform
          :fcm  [fcm-provider #(delivery/build-fcm-payload %2 %1) #(ports/fcm-send! fcm-provider %2)]
          :apns [apns-provider #(delivery/build-apns-payload %2) #(ports/apns-send! apns-provider %2 %1)])
        payloads (mapv (fn [[token notification]] [token (build token notification)]) sends)]
    (if (satisfies? ports/IPerDeviceSend provider)
      (ports/send-each! provider payloads)
      (mapv (fn [[token payload]] (send-one token payload)) payloads))))

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
