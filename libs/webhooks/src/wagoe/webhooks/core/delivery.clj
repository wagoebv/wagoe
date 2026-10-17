(ns wagoe.webhooks.core.delivery
  "Which subscriptions an event goes to, what is sent, and what an attempt's
   outcome means. Pure: the clock, ids and I/O come from the shell."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [wagoe.events.core.event :as event])
  (:import (java.net URI URISyntaxException)))

;; =============================================================================
;; Matching
;; =============================================================================

(def topic-name
  "`topic` as a string, namespace included, as the bus names it."
  event/topic-name)

(defn- glob->regex [pattern]
  (re-pattern (str/join ".*" (map #(java.util.regex.Pattern/quote %)
                                  (str/split pattern #"\*" -1)))))

(defn pattern-matches?
  "Whether topic glob `pattern` matches `topic`. `*` matches any run of
   characters, `/` included: `orders/*`, `*.created`, `*`."
  [pattern topic]
  (boolean (and (string? pattern)
                (re-matches (glob->regex pattern) (topic-name topic)))))

(defn tenant-allows?
  "A subscription with a tenant receives only that tenant's events. One without
   a tenant is the operator's own and receives every event."
  [subscription event]
  (let [own (:tenant-id subscription)]
    (or (nil? own) (= (str own) (some-> (:tenant-id event) str)))))

(defn matching-subscriptions
  "The active subscriptions in `subscriptions` that `event` on `topic` goes to."
  [subscriptions topic event]
  (filterv #(and (:active? %)
                 (tenant-allows? % event)
                 (pattern-matches? (:event-pattern %) topic))
           subscriptions))

;; =============================================================================
;; Payload
;; =============================================================================

(defn payload-body
  "The JSON body for `event` published on `topic`. Built once per event, so
   every attempt sends, and signs, the same bytes."
  [topic event]
  (json/generate-string
   (cond-> {:id           (str (:id event))
            :topic        (topic-name topic)
            :type         (some-> (:type event) topic-name)
            :source       (some-> (:source event) topic-name)
            :publishedAt  (some-> (:published-at event) str)
            :payload      (:payload event)}
     (:tenant-id event) (assoc :tenantId (str (:tenant-id event))))))

(defn headers
  "Request headers for one attempt."
  [{:keys [event-id topic]} signature]
  {"Content-Type"    "application/json"
   "User-Agent"      "Wagoe-Webhooks/1"
   "Wagoe-Event-Id"  (str event-id)
   "Wagoe-Topic"     (topic-name topic)
   "Wagoe-Signature" signature})

;; =============================================================================
;; URLs
;; =============================================================================

(defn url-problem
  "Why `url` cannot be a webhook target, or nil. Checks the shape only; whether
   the host is private is the shell's question, since it needs DNS."
  [url {:keys [require-https?]}]
  (let [uri (try (URI. (str url)) (catch URISyntaxException _ nil))
        scheme (some-> uri .getScheme str/lower-case)]
    (cond
      (str/blank? (str url))                 "URL is required"
      (nil? uri)                             "URL is not a valid URI"
      (not (#{"http" "https"} scheme))       "URL must use http or https"
      (and require-https? (not= "https" scheme)) "URL must use https"
      (str/blank? (.getHost uri))            "URL has no host"
      (.getRawUserInfo uri)                  "URL must not carry credentials")))

(defn url-host
  "The host of `url`, lower case, without IPv6 brackets."
  [url]
  (some-> (URI. (str url)) .getHost str/lower-case (str/replace #"^\[|\]$" "")))

;; =============================================================================
;; Outcome
;; =============================================================================

(defn attempt-status
  "`:delivered` for a 2xx, otherwise `:failed`, or `:dead` when this was the
   last attempt the queue will make. A redirect is a failure: it is not
   followed, since its target was never checked."
  [{:keys [status]} attempt max-attempts]
  (cond
    (and status (<= 200 status 299)) :delivered
    (>= attempt max-attempts)        :dead
    :else                            :failed))

(defn failure-message
  "What a failed attempt says about itself."
  [{:keys [status error]}]
  (if status
    (str "Receiver answered HTTP " status)
    (str "No response: " (:message error))))
