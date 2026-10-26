(ns wagoe.push.core.callback
  "What a push's callback token signs and how it is written (BOU-849). The
   HMAC itself is the shell's.")

(def token-ttl-seconds
  "How long a callback token verifies: a device reports an open days later."
  (* 7 24 60 60))

(defn- id-str
  "A notification id as the device posts it back: `:orders/shipped` is
   \"orders/shipped\", which a JSON body decodes to the same keyword."
  [notification-id]
  (str (symbol notification-id)))

(defn signed-message
  "The fields a token binds, each prefixed by its length so no field can
   borrow characters from its neighbour."
  [{:keys [callback-id notification-id device-token platform expires-at]}]
  (apply str (map #(str (count %) ":" %)
                  ["v2" (str callback-id) (id-str notification-id) (str device-token)
                   (name platform) (str expires-at)])))

(defn token
  "A token as a device echoes it: version, expiry in epoch seconds, MAC."
  [expires-at mac]
  (str "v2." expires-at "." mac))

(defn parse-token
  "`{:expires-at n :mac hex}` for a token `token` wrote, nil for anything else."
  [token]
  (when-let [[_ expires-at mac] (and (string? token)
                                     (re-matches #"v2\.(\d{1,12})\.([0-9a-f]{64})" token))]
    {:expires-at (parse-long expires-at) :mac mac}))

(defn live?
  "Whether a token expiring at `expires-at` still verifies at `now`, both epoch seconds."
  [expires-at now]
  (< now expires-at))

(defn callback-data
  "The keys a push carries for its callback, as the device posts them back."
  [notification-id callback-id callback-token]
  {:notification-id     (id-str notification-id)
   :provider-message-id callback-id
   :callback-token      callback-token})

(defn shadowed-keys
  "The app's data keys a push's callback keys replace, keyword or string."
  [data]
  (filter #(or (contains? data %) (contains? data (name %)))
          [:notification-id :provider-message-id :callback-token]))
