(ns wagoe.webhooks.shell.service
  "`IWebhooks`: subscriptions in, one job per delivery out, one log row per
   attempt."
  (:require [clojure.tools.logging :as log]
            [clojure.string :as str]
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

(defn- check-target
  "`ssrf/check-host` for `url`, with `:problem` `:refused` also for a URL whose
   shape `settings` do not allow."
  [{:keys [settings resolve]} url]
  (if-let [problem (delivery/url-problem url settings)]
    {:problem {:type :refused :message problem}}
    (ssrf/check-host url settings resolve)))

(defn- invalid-target
  "A target problem as a validation error, for create and change."
  [svc url]
  (when-let [{:keys [message]} (:problem (check-target svc url))]
    {:error {:type :validation-error :message message}}))

(defn- public-view [subscription] (dissoc subscription :secret))

(defn- enqueue! [{:keys [job-queue settings]} args]
  (let [now (Instant/now)
        j   (job/create-job {:job-type    job-type
                             :queue       (:queue settings)
                             :args        args
                             :max-retries (:max-attempts settings)}
                            (random-uuid) now)]
    (jobs/enqueue-job! job-queue (:queue settings) j)))

(defn- first-claim? [idempotency subscription-id event-id]
  (or (nil? idempotency)
      (jobs/claim-key! idempotency "webhooks" (str subscription-id "/" event-id) claim-ttl-ms)))

(defn- attempt! [{:keys [store transport settings key] :as svc} subscription args attempt]
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
        {:keys [problem addresses]} (check-target svc (:url subscription))]
    (if (= :refused (:type problem))
      ;; Retrying cannot make a refused target acceptable.
      (do (record! {:status :refused :error (:message problem)})
          (log/warn "Webhook target refused" {:subscription-id (:id subscription) :reason (:message problem)})
          {:success? true :result {:status :refused}})
      (let [secret   (when-not problem
                       ;; Unreadable under this key (a rotated JWT_SECRET):
                       ;; logged as an attempt, not thrown past the log.
                       (try (crypto/decrypt key (:secret subscription))
                            (catch Exception e
                              (log/error e "Webhook secret cannot be decrypted" {:subscription-id (:id subscription)})
                              nil)))
            response (cond
                       problem       {:error problem :latency-ms 0}
                       (nil? secret) {:error {:message "signing secret cannot be decrypted"} :latency-ms 0}
                       :else
                       (ports/send-request!
                        transport
                        {:url       (:url subscription)
                         ;; Connect to what was checked, not to a second lookup.
                         :addresses addresses
                         :body      body
                         :headers   (delivery/headers
                                   args
                                   (signature/signature-header
                                    secret (.getEpochSecond (Instant/now)) body))}))
            status   (delivery/attempt-status response attempt (:max-attempts settings))
            failure  (when-not (= :delivered status) (delivery/failure-message response))]
        (record! {:status      status
                  :status-code (:status response)
                  :latency-ms  (:latency-ms response)
                  :response    (:body response)
                  :error       failure})
        (if failure
          {:success? false :error {:type :webhooks/delivery-failed :message failure}}
          {:success? true :result {:status :delivered :status-code (:status response)}})))))

(defrecord WebhookService [store transport job-queue idempotency resolve settings key]
  ports/IWebhooks
  (create-subscription! [this input]
    (if-not (m/validate schema/SubscriptionInput input)
      (validation-error "Invalid subscription" (me/humanize (m/explain schema/SubscriptionInput input)))
      (or (invalid-target this (:url input))
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

  (change-subscription! [this id changes]
    (cond
      (not (m/validate schema/SubscriptionChanges changes))
      (validation-error "Invalid changes" (me/humanize (m/explain schema/SubscriptionChanges changes)))

      :else
      (or (some->> (:url changes) (invalid-target this))
          (some-> (ports/update-subscription! store id (assoc changes :updated-at (Instant/now)))
                  public-view))))

  (remove-subscription! [_ id]
    (ports/delete-subscription! store id))

  (subscriptions [_ opts]
    (mapv public-view (ports/list-subscriptions store opts)))

  (dispatch! [this topic event]
    (let [matches  (delivery/matching-subscriptions
                    (ports/candidate-subscriptions store (:tenant-id event)) topic event)
          body     (when (seq matches) (delivery/payload-body topic event))
          event-id (str (:id event))
          topic    (delivery/topic-name topic)]
      ;; Enqueue, then claim: a crash in between duplicates a delivery, which
      ;; receivers dedupe on Wagoe-Event-Id. Claiming first would lose it.
      (reduce (fn [n s]
                (let [job-id (enqueue! this {:subscription-id (:id s)
                                             :event-id        event-id
                                             :topic           topic
                                             :body            body})]
                  (if (first-claim? idempotency (:id s) event-id)
                    (inc n)
                    (do (jobs/delete-job! job-queue job-id) n))))
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
   optionally `:idempotency` and `:resolve` (host -> addresses, default
   `ssrf/system-resolve`). `settings`: see `defaults`, plus
   `:require-https?` and `:encryption-key`."
  [{:keys [store transport job-queue idempotency resolve]} settings]
  (let [settings (-> (merge defaults settings)
                     ;; Lower case once, not on every check.
                     (update :allowed-hosts #(into #{} (map str/lower-case) %)))]
    (->WebhookService store transport job-queue idempotency (or resolve ssrf/system-resolve)
                      (dissoc settings :encryption-key)
                      (crypto/derive-key (:encryption-key settings)))))
