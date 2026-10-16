(ns wagoe.realtime.core.sse
  "Pure pieces of the server-sent events bridge: the wire format, the identity
   a session user maps to, and the decision on which topics a stream may open."
  (:require [clojure.string :as str]))

(def heartbeat
  "An SSE comment line. Browsers ignore it; it keeps proxies from timing the
   stream out and makes a dead client surface as a failed write."
  ": heartbeat\n\n")

(defn format-event
  "One SSE event. Each line of `data` gets its own `data:` field, as the spec
   requires; the browser joins them back with newlines."
  [event data]
  (str "event: " event "\n"
       (->> (str/split-lines (str data))
            (map #(str "data: " % "\n"))
            (apply str))
       "\n"))

(defn- ->uuid [x]
  (cond (uuid? x)   x
        (string? x) (parse-uuid x)
        :else       nil))

(defn user-identity
  "The connection identity for a signed-in user (`:user` on the request), or
   nil when there is none or its id is not a UUID."
  [user]
  (when-let [id (->uuid (:id user))]
    {:user-id id
     :roles   (into #{}
                    (comp (remove nil?) (map keyword))
                    (or (seq (:roles user)) [(:role user)]))
     :email   (:email user)}))

(defn request-topics
  "The `topic` query parameter as a vector of topic names; repeated params
   arrive as a vector."
  [query-params]
  (let [v (get query-params "topic")]
    (cond (string? v)     [v]
          (sequential? v) (vec v)
          :else           [])))

(defn subscription-decision
  "Decide whether `topics` may be streamed. `allowed?` answers per topic;
   `valid?` is the topic schema.

   Returns {:ok topics} or {:error {:type ... :message ...}}."
  [topics valid? allowed?]
  (cond
    (empty? topics)
    {:error {:type :validation-error :message "At least one topic is required"}}

    (not-every? valid? topics)
    {:error {:type :validation-error :message "Invalid topic name"}}

    (not-every? allowed? topics)
    {:error {:type :forbidden :message "Topic not allowed"}}

    :else {:ok (vec (distinct topics))}))
