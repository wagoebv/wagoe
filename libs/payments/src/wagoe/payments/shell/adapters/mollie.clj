(ns wagoe.payments.shell.adapters.mollie
  "Mollie payment provider adapter."
  (:require [wagoe.payments.core.provider :as provider]
            [wagoe.payments.ports :as ports]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.tools.logging :as log])
  (:import [java.util UUID]))

(def ^:private mollie-api-base "https://api.mollie.com/v2")

(defn- mollie-headers [api-key]
  {"Authorization"  (str "Bearer " api-key)
   "Content-Type"   "application/json"
   "Accept"         "application/json"
   "User-Agent"     "wagoe-payments/1.0"})

(defn- parse-body [response]
  (try (json/parse-string (:body response) true)
       (catch Exception _ nil)))

(defn- request!
  "Call the Mollie API. Returns {:status int :body parsed-map-or-nil}."
  ([api-key method path] (request! api-key method path nil nil))
  ([api-key method path payload extra-headers]
   (let [send!    (case method :get http/get :post http/post :delete http/delete)
         response (send! (str mollie-api-base path)
                         (cond-> {:headers          (merge (mollie-headers api-key) extra-headers)
                                  :as               :string
                                  :throw-exceptions false}
                           payload (assoc :body (json/generate-string payload))))]
     {:status (:status response)
      :body   (parse-body response)})))

(defn- fail! [message method {:keys [status body]}]
  (throw (ex-info message {:type     :internal-error
                           :provider :mollie
                           :method   method
                           :status   status
                           :body     body})))

(defn- fetch-payment [api-key payment-id]
  (let [{:keys [status body]} (request! api-key :get (str "/payments/" payment-id))]
    (when (= 200 status) body)))

(defn- create-customer!
  "A first payment needs a customer to hang the mandate on."
  [api-key customer-email]
  (let [{:keys [status body] :as response}
        (request! api-key :post "/customers"
                  (cond-> {} customer-email (assoc :email customer-email)) nil)]
    (when-not (#{200 201} status)
      (fail! "Mollie customer creation failed" :create-checkout-session response))
    (:id body)))

(defn- default-webhook-url [webhook-base-url]
  (str webhook-base-url "/api/v1/payments/webhook"))

(defrecord MolliePaymentProvider [api-key webhook-base-url]
  ports/IPaymentProvider

  (provider-name [_] :mollie)

  (create-checkout-session [_ {:keys [webhook-url setup-future-usage customer-email
                                      provider-customer-id] :as opts}]
    ;; Any :setup-future-usage makes this the first payment of a mandate; Mollie
    ;; has no on-session/off-session distinction, a mandate serves both.
    (let [checkout-id (str (UUID/randomUUID))
          customer-id (when setup-future-usage
                        (or provider-customer-id (create-customer! api-key customer-email)))
          payload     (provider/mollie-checkout-payload
                       opts
                       {:checkout-id checkout-id
                        :webhook-url (or webhook-url (default-webhook-url webhook-base-url))
                        :customer-id customer-id})
          {:keys [status body] :as response} (request! api-key :post "/payments" payload nil)]
      (log/infof "Mollie create-checkout: status=%d id=%s" status (:id body))
      (when-not (#{200 201} status)
        (fail! "Mollie checkout creation failed" :create-checkout-session response))
      {:checkout-url         (get-in body [:_links :checkout :href])
       :provider-checkout-id (:id body)
       ;; Internal correlation id, also stored in Mollie payment metadata
       ;; (metadata.checkout-id) so the webhook can recover it.
       :correlation-id       checkout-id}))

  (create-off-session-payment [_ {:keys [idempotency-key] :as opts}]
    ;; A 422 (no valid mandate) creates no payment, so there is no id to
    ;; report as :failed — it throws, like Stripe's declined-without-intent case.
    (let [payload (provider/mollie-recurring-payload opts (default-webhook-url webhook-base-url))
          headers (when idempotency-key {"Idempotency-Key" idempotency-key})
          {:keys [status body] :as response} (request! api-key :post "/payments" payload headers)]
      (when-not (#{200 201} status)
        (fail! "Mollie off-session payment failed" :create-off-session-payment response))
      (log/infof "Mollie off-session payment: id=%s status=%s" (:id body) (:status body))
      {:provider-payment-id (:id body)
       :status              (provider/mollie-recurring-status (:status body))}))

  (expire-checkout-session [_ provider-checkout-id]
    ;; Mollie has no expiry call; cancelling the open payment is what stops
    ;; the checkout. Its webhook then reports :payment.cancelled.
    (let [{:keys [status body] :as response}
          (request! api-key :delete (str "/payments/" provider-checkout-id))]
      (log/infof "Mollie expire-checkout: status=%s id=%s" status provider-checkout-id)
      (when-not (= 200 status)
        (fail! "Mollie expire-checkout-session failed" :expire-checkout-session response))
      {:provider-checkout-id (or (:id body) provider-checkout-id)
       :status               :expired}))

  (get-payment-status [_ provider-checkout-id]
    (if-let [payment (fetch-payment api-key provider-checkout-id)]
      (cond-> {:status              (provider/mollie-status->payment-status (:status payment))
               :provider-payment-id (:id payment)}
        (:customerId payment) (assoc :provider-customer-id (:customerId payment))
        (:mandateId payment)  (assoc :provider-payment-method-id (:mandateId payment)))
      ;; :provider-payment-id deliberately absent (not nil) so callers
      ;; merging the result cannot clobber a stored payment id.
      {:status :pending}))

  (verify-webhook-signature [_ _raw-body _headers]
    ;; Mollie does not use HMAC signing. Verification happens by fetching
    ;; the payment and checking its status.
    true)

  (process-webhook [_ raw-body _headers]
    ;; Mollie sends a form-POST with only the payment id as `id`
    (let [payment-id (if (string? raw-body)
                       (second (re-find #"id=([^&]+)" raw-body))
                       (:id raw-body))
          payment    (when payment-id (fetch-payment api-key payment-id))]
      ;; A failed fetch throws so the endpoint answers 5xx and Mollie retries;
      ;; acknowledging it would lose the payment's outcome for good.
      (when-not payment
        (throw (ex-info "Mollie webhook: payment could not be fetched"
                        {:type       :internal-error
                         :provider   :mollie
                         :payment-id payment-id})))
      (let [status     (:status payment)
            event-type (provider/mollie-status->event-type status)]
        ;; event-type is nil for a status with no payment.* outcome (open,
        ;; pending) — acknowledged, not thrown, as the port requires.
        (log/infof "Mollie webhook: payment-id=%s status=%s → event=%s"
                   payment-id status event-type)
        ;; :provider-payment-id is the genuine Mollie payment id (tr_…) — the same
        ;; value returned as :provider-checkout-id at creation. :correlation-id is
        ;; the internal UUID recovered from metadata, round-tripping CheckoutResult.
        {:event-type          event-type
         :provider-payment-id payment-id
         :correlation-id      (get-in payment [:metadata :checkout-id])
         :payload             payment}))))
