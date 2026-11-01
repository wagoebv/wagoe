(ns wagoe.platform.core.http.errors
  "The one JSON error body (BOU-586):

     {\"error\": {\"type\":           \"not-found\",
                \"message\":        \"User not found\",
                \"details\":        {...},    ; optional
                \"correlation-id\": \"...\",  ; optional
                \"dev\":            {...}}}   ; optional, dev only (WGE code)

   Everything about the failure sits inside `error`, so a client reads one
   key. `type` is a string: the `:type` keyword of the ex-info, without its
   colon. The pure part lives in `wagoe.core.http.errors` (BOU-930); this
   namespace adds what needs JSON."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [wagoe.core.http.errors :as core]))

(defn type-name
  "See `wagoe.core.http.errors/type-name`."
  [t]
  (core/type-name t))

(defn status->type
  "See `wagoe.core.http.errors/status->type`."
  [status]
  (core/status->type status))

(defn body
  "See `wagoe.core.http.errors/body`."
  ([type message] (core/body type message))
  ([type message opts] (core/body type message opts)))

(defn response
  "See `wagoe.core.http.errors/response`."
  ([status type message] (core/response status type message))
  ([status type message opts] (core/response status type message opts)))

(defn error-body?
  "See `wagoe.core.http.errors/error-body?`."
  [b]
  (core/error-body? b))

(defn- json-string-body
  "The decoded body of a response a handler encoded itself, or nil."
  [{:keys [headers body]}]
  (when (and (string? body)
             (some-> (or (get headers "Content-Type") (get headers "content-type"))
                     (str/includes? "json")))
    (try (json/parse-string body true) (catch Exception _ nil))))

(defn error-of
  "The `error` map of `response`'s body, a map or JSON a handler encoded
   itself; nil when it carries none."
  [response]
  (let [b (:body response)
        e (:error (if (map? b) b (json-string-body response)))]
    (when (map? e) e)))

(defn with-correlation-id
  "`response` with `correlation-id` in its error body when it has none: for
   the bodies handlers build themselves, encoded or not (BOU-592)."
  [{:keys [status] :as response} correlation-id]
  (let [error?  (and (number? status) (>= status 400))
        b       (:body response)
        decoded (when (and error? (not (map? b))) (json-string-body response))
        target  (if (map? b) b decoded)]
    ;; Not `error-body?`: a body still holding a keyword :type is encoded later.
    (if (and error?
             (some? correlation-id)
             (map? (:error target))
             (nil? (get-in target [:error :correlation-id])))
      (let [b' (assoc-in target [:error :correlation-id] (str correlation-id))]
        (assoc response :body (if decoded (json/generate-string b') b')))
      response)))
