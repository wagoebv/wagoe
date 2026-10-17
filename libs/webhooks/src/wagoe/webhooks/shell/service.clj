(ns wagoe.webhooks.shell.service
  "`IWebhooks`: subscriptions in, one job per delivery out, one log row per
   attempt."
  (:require [clojure.tools.logging :as log]
            [malli.core :as m]
            [malli.error :as me]
            [wagoe.jobs.core.job :as job]
            [wagoe.jobs.ports :as jobs]
            [wagoe.webhooks.core.delivery :as delivery]
            [wagoe.webhooks.core.signature :as signature]
            [wagoe.webhooks.ports :as ports]
            [wagoe.webhooks.schema :as schema]
            [wagoe.webhooks.shell.crypto :as crypto]
            [wagoe.webhooks.shell.ssrf :as ssrf])
  (:import (java.time Instant)))

(def job-type
  "The job type a delivery runs as."
  :webhooks/deliver)

(def ^:private claim-ttl-ms
  "How long a (subscription, event) pair is remembered, so a redelivered event
   does not enqueue a second delivery."
  (* 7 24 60 60 1000))

(defn- validation-error [message details]
  {:error {:type :validation-error :message message :details details}})

(defn- target-problem
  "Why `url` may not be a target under `settings`, as an error map, or nil."
  [url settings]
  (if-let [problem (delivery/url-problem url settings)]
    {:type :validation-error :message problem}
    (when-let [{:keys [message]} (ssrf/host-problem url settings)]
      {:type :validation-error :message message})))

(defn- public-view [subscription] (dissoc subscription :secret))

(defn- enqueue! [{:keys [job-queue settings]} args]
  (let [now (Instant/now)
        j   (job/create-job {:job-type    job-type
                             :queue       (:queue settings)
                             :args        args
                             :max-retries (:max-attempts settings)}
                            (random-uuid) now)]
    (jobs/enqueue-job! job-queue (:queue settings) j)))

(defn- claim-key [subscription-id event-id] (str subscription-id "/" event-id))

(defn- first-claim? [idempotency subscription-id event-id]
  (or (nil? idempotency)
      (jobs/claim-key! idempotency "webhooks" (claim-key subscription-id event-id) claim-ttl-ms)))

(defn- attempt! [{:keys [store transport settings key]} subscription args attempt]
  (let [{:keys [event-id topic body]} args
        record! (fn [fields]
                  (ports/record-attempt! store (merge {:id              (random-uuid)
                                                       :subscription-id (:id subscription)
                                                       :tenant-id       (:tenant-id subscription)
                                                       :event-id        event-id
                                                       :topic           topic
                                                       :attempt         attempt
                                                       :created-at      (Instant/now)}
                                                      fields)))
        problem (or (some->> (delivery/url-problem (:url subscription) settings)
                             (hash-map :type :refused :message))
                    (ssrf/host-problem (:url subscription) settings))]
    (if (= :refused (:type problem))
      ;; Retrying cannot make a refused target acceptable.
      (do (record! {:status :refused :error (:message problem)})
          (log/warn "Webhook target refused" {:subscription-id (:id subscription) :reason (:message problem)})
          {:success? true :result {:status :refused}})
      (let [response (if problem
                       {:error problem :latency-ms 0}
                       (ports/send-request!
                        transport
                        {:url     (:url subscription)
                         :body    body
                         :headers (delivery/headers
                                   args
                                   (signature/signature-header
                                    (crypto/decrypt key (:secret subscription))
                                    (.getEpochSecond (Instant/now))
                                    body))}))
            status   (delivery/attempt-status response attempt (:max-attempts settings))]
        (record! {:status      status
                  :status-code (:status response)
                  :latency-ms  (:latency-ms response)
                  :response    (delivery/truncate (:body response) (:max-response-bytes settings))
                  :error       (when-not (= :delivered status) (delivery/failure-message response))})
        (if (= :delivered status)
          {:success? true :result {:status :delivered :status-code (:status response)}}
          {:success? false
           :error    {:type    :webhooks/delivery-failed
                      :message (delivery/failure-message response)}})))))

(defrecord WebhookService [store transport job-queue idempotency settings key]
  ports/IWebhooks
  (create-subscription! [_ input]
    (if-not (m/validate schema/SubscriptionInput input)
      (validation-error "Invalid subscription" (me/humanize (m/explain schema/SubscriptionInput input)))
      (if-let [problem (target-problem (:url input) settings)]
        {:error problem}
        (let [secret (or (:secret input) (crypto/generate-secret))
              now    (Instant/now)
              s      {:id            (str (random-uuid))
                      :tenant-id     (:tenant-id input)
                      :event-pattern (:event-pattern input)
                      :url           (:url input)
                      :secret        (crypto/encrypt key secret)
                      :active?       true
                      :description   (:description input)
                      :created-at    now
                      :updated-at    now}]
          (ports/save-subscription! store s)
          {:subscription (public-view s) :secret secret}))))

  (change-subscription! [_ id changes]
    (cond
      (not (m/validate schema/SubscriptionChanges changes))
      (validation-error "Invalid changes" (me/humanize (m/explain schema/SubscriptionChanges changes)))

      (and (:url changes) (target-problem (:url changes) settings))
      {:error (target-problem (:url changes) settings)}

      :else
      (some-> (ports/update-subscription! store id (assoc changes :updated-at (Instant/now)))
              public-view)))

  (remove-subscription! [_ id]
    (ports/delete-subscription! store id))

  (subscriptions [_ opts]
    (mapv public-view (ports/list-subscriptions store opts)))

  (dispatch! [this topic event]
    (let [matches (delivery/matching-subscriptions
                   (ports/candidate-subscriptions store (:tenant-id event)) topic event)
          body    (delivery/payload-body topic event)]
      (reduce (fn [n s]
                (if (first-claim? idempotency (:id s) (:id event))
                  (do (try
                        (enqueue! this {:subscription-id (:id s)
                                        :event-id        (str (:id event))
                                        :topic           (delivery/topic-name topic)
                                        :body            body})
                        (catch Exception e
                          ;; Unclaimed, so the bus's redelivery can try again.
                          (some-> idempotency
                                  (jobs/release-key! "webhooks" (claim-key (:id s) (:id event))))
                          (throw e)))
                      (inc n))
                  n))
              0
              matches)))

  (deliver! [this {:keys [subscription-id event-id] :as args}]
    (let [s (ports/find-subscription store subscription-id)]
      (if-not (:active? s)
        {:success? true :result {:status :skipped}}
        (attempt! this s args (inc (ports/attempt-count store subscription-id event-id)))))))

(def defaults
  {:queue              :default
   :max-attempts       8
   :timeout-ms         10000
   :connect-timeout-ms 5000
   :max-response-bytes 2000
   :allowed-hosts      #{}
   :require-https?     true})

(defn create-service
  "`deps`: `:store` (both store protocols), `:transport`, `:job-queue`, and
   optionally `:idempotency`. `settings`: see `defaults`, plus
   `:require-https?` and `:encryption-key`."
  [{:keys [store transport job-queue idempotency]} settings]
  (let [settings (merge defaults settings)]
    (->WebhookService store transport job-queue idempotency
                      (dissoc settings :encryption-key)
                      (crypto/derive-key (:encryption-key settings)))))
