(ns wagoe.push.core.device
  (:import [java.time Instant Duration]))

(defn detect-platform
  "Heuristic platform detection from token format. Returns :fcm, :apns, or nil."
  [token]
  (cond
    (and (string? token) (re-find #":" token) (> (count token) 10)) :fcm
    (and (string? token) (= 64 (count token)) (re-matches #"[a-fA-F0-9]+" token)) :apns
    :else nil))

(defn stale-token?
  "Check if device token hasn't been used within max-age-days. Caller supplies current instant."
  [{:keys [last-used-at]} max-age-days now]
  (let [max-age  (Duration/ofDays max-age-days)
        used-at  (if (instance? Instant last-used-at)
                   last-used-at
                   (.toInstant ^java.util.Date last-used-at))
        elapsed  (Duration/between used-at now)]
    (> (.toMillis elapsed) (.toMillis max-age))))

(defn prepare-device-record
  "Pure: build device record from user-id and device-info. Caller supplies id and now."
  [user-id device-info id now]
  {:id           id
   :user-id      user-id
   :token        (:token device-info)
   :platform     (:platform device-info)
   :app-id       (:app-id device-info)
   :device-name  (:device-name device-info)
   :os-version   (:os-version device-info)
   :active?      true
   :created-at   now
   :last-used-at now})
