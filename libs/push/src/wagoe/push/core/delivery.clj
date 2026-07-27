(ns wagoe.push.core.delivery)

(defn stringify-values
  "Convert map keys to strings, values to strings. FCM data field requires string values."
  [m]
  (reduce-kv (fn [acc k v] (assoc acc (name k) (str v))) {} m))

(defn group-devices-by-platform
  "Group devices by :platform key."
  [devices]
  (group-by :platform devices))

(defn build-fcm-payload
  "Pure: transform rendered notification into FCM v1 API payload."
  [notification device-token]
  {:message
   {:token        device-token
    :notification {:title (:title notification)
                   :body  (:body notification)}
    :data         (stringify-values (:data notification))
    :android      {:priority     (name (:priority notification :normal))
                   :ttl          (str (:ttl notification 86400) "s")
                   :collapse_key (some-> (:collapse-key notification) name)
                   :notification {:click_action "OPEN_ACTIVITY"}}}})

(defn build-apns-payload
  "Pure: transform rendered notification into APNs payload."
  [notification]
  {:aps       {:alert             {:title (:title notification)
                                   :body  (:body notification)}
               :sound             (when-not (:silent? notification) "default")
               :badge             1
               :content-available (if (:silent? notification) 1 0)
               :mutable-content   1}
   :deep-link (:deep-link notification)
   :data      (:data notification)})

(def ^:private fcm-error-classification
  {"UNAVAILABLE"        :retryable
   "INTERNAL"           :retryable
   "UNREGISTERED"       :token-invalid
   "INVALID_ARGUMENT"   :token-invalid
   "QUOTA_EXCEEDED"     :rate-limited
   "PERMISSION_DENIED"  :permanent
   "SENDER_ID_MISMATCH" :permanent})

(def ^:private apns-error-classification
  {"ServiceUnavailable" :retryable
   "BadDeviceToken"     :token-invalid
   "Unregistered"       :token-invalid
   "TooManyRequests"    :rate-limited
   "BadCertificate"     :permanent
   "Forbidden"          :permanent})

(defn classify-error
  "Pure: classify provider error code into action category."
  [platform error-code]
  (let [table (case platform
                :fcm  fcm-error-classification
                :apns apns-error-classification)]
    (get table error-code :retryable)))

(defn retry-delay-ms
  "Pure: calculate backoff delay in ms for attempt n."
  [retry-config attempt]
  (case (:backoff retry-config :exponential)
    :exponential (* 1000 (long (Math/pow 2 attempt)))
    :linear      (* 1000 attempt)
    :fixed       2000))

(defn result->analytics-event
  "Pure: transform provider send result into analytics event map. Caller supplies timestamp."
  [notification-id {:keys [device-token platform success? message-id error]} timestamp]
  {:notification-id     notification-id
   :device-token        device-token
   :platform            platform
   :event-type          (if success? :sent :failed)
   :provider-message-id message-id
   :error               error
   :timestamp           timestamp})
