(ns wagoe.webhooks.ports
  "Outbound webhooks: where subscriptions live, where attempts are recorded,
   how a request leaves the process, and what the rest of an application calls.")

(defprotocol ISubscriptionStore
  "Webhook subscriptions. `:secret` is stored as given; the service encrypts it
   before it gets here."

  (save-subscription! [this subscription]
    "Insert `subscription`, a map with `:id`. Returns it.")

  (update-subscription! [this id changes]
    "Apply `changes` to subscription `id`. Returns the updated subscription, or
     nil when there is none.")

  (delete-subscription! [this id]
    "Delete subscription `id`. True when one was deleted.")

  (find-subscription [this id]
    "Subscription `id`, or nil.")

  (list-subscriptions [this opts]
    "Subscriptions, newest first. `opts` may carry `:tenant-id` (only that
     tenant's), `:active?`, `:limit` and `:offset`.")

  (candidate-subscriptions [this tenant-id]
    "Active subscriptions that may receive an event of `tenant-id`: the tenant's
     own and those with no tenant. nil `tenant-id` gives only the latter."))

(defprotocol IDeliveryLog
  "One row per delivery attempt."

  (record-attempt! [this attempt]
    "Insert `attempt`. Returns it.")

  (attempt-count [this subscription-id event-id]
    "How many attempts were recorded for this subscription and event.")

  (list-attempts [this opts]
    "Attempts, newest first. `opts` may carry `:subscription-id`, `:event-id`,
     `:limit` and `:offset`."))

(defprotocol IWebhookTransport
  "Send one HTTP request."

  (send-request! [this request]
    "POST `request` — `{:url :headers :body}` — and return
     `{:status int :body string :latency-ms long}`, or
     `{:error {:type kw :message string} :latency-ms long}` when no response
     arrived. Never follows a redirect."))

(defprotocol IWebhooks
  "The module's API."

  (create-subscription! [this input]
    "Validate and store a subscription. `input`: `:url`, `:event-pattern`, and
     optionally `:tenant-id`, `:description`, `:secret`. Returns
     `{:subscription s :secret plaintext}` — the only time the secret is
     returned — or `{:error {:type :validation-error ...}}`.")

  (change-subscription! [this id changes]
    "Change `:url`, `:event-pattern`, `:active?` or `:description`. Returns
     the subscription, nil when absent, or `{:error ...}`.")

  (remove-subscription! [this id]
    "Delete a subscription. True when one was deleted.")

  (subscriptions [this opts]
    "As `list-subscriptions`, without secrets.")

  (dispatch! [this topic event]
    "Enqueue one delivery job per subscription that matches `topic` and the
     event's tenant. Returns the number enqueued.")

  (deliver! [this args]
    "Make one delivery attempt from a job's args. Returns a jobs handler
     result: `{:success? true ...}` or `{:success? false :error ...}`, which
     the queue retries."))
