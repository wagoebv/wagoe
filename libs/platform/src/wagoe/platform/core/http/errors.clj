(ns wagoe.platform.core.http.errors
  "The one JSON error body (BOU-586):

     {\"error\": {\"type\":           \"not-found\",
                \"message\":        \"User not found\",
                \"details\":        {...},    ; optional
                \"correlation-id\": \"...\",  ; optional
                \"dev\":            {...}}}   ; optional, dev only (BND code)

   Everything about the failure sits inside `error`, so a client reads one
   key. `type` is a string: the `:type` keyword of the ex-info, without its
   colon.")

(defn type-name
  "`:not-found` -> \"not-found\"; a qualified keyword keeps its namespace."
  [t]
  (cond
    (keyword? t) (if-let [n (namespace t)] (str n "/" (name t)) (name t))
    (nil? t)     "internal-error"
    :else        (str t)))

(def ^:private status-types
  {400 :validation-error 401 :unauthorized 403 :forbidden 404 :not-found
   405 :method-not-allowed 409 :conflict 422 :unprocessable 429 :rate-limit-exceeded
   501 :not-supported 503 :unavailable})

(defn status->type
  "The `:type` a handler that knows only its status answers with: ADR-022's
   vocabulary where it has a word, `:validation-error` for any other 4xx and
   `:internal-error` for any other 5xx."
  [status]
  (get status-types status (if (>= status 500) :internal-error :validation-error)))

(defn body
  "The error body. `details` is left out when empty."
  ([type message] (body type message nil))
  ([type message {:keys [details correlation-id dev]}]
   {:error (cond-> {:type    (type-name type)
                    :message (if (some? message) (str message) "An error occurred")}
             (and (coll? details) (seq details)) (assoc :details details)
             (some? correlation-id)              (assoc :correlation-id (str correlation-id))
             (some? dev)                         (assoc :dev dev))}))

(defn response
  "A Ring response carrying `body`. No Content-Type: muuntaja encodes a map
   body only when none is set (ZZP-120). The correlation id, when given, is
   echoed in the X-Correlation-ID header too."
  ([status type message] (response status type message nil))
  ([status type message {:keys [correlation-id] :as opts}]
   (cond-> {:status status
            :body   (body type message opts)}
     correlation-id (assoc :headers {"X-Correlation-ID" (str correlation-id)}))))

(defn error-body?
  "Whether `b` is in the one shape — with keyword or string keys, so it can
   judge a decoded JSON body as well as a map."
  [b]
  (let [e (when (map? b) (or (get b :error) (get b "error")))]
    (and (map? e)
         (string? (or (get e :type) (get e "type")))
         (string? (or (get e :message) (get e "message"))))))
