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

(defn- fetch-payment [api-key payment-id]
  (let [url      (str mollie-api-base "/payments/" payment-id)
        response (http/get url {:headers          (mollie-headers api-key)
                                :as               :string
                                :throw-exceptions false})]
    (when (= 200 (:status response))
      (json/parse-string (:body response) true))))

(defrecord MolliePaymentProvider [api-key webhook-base-url]
  ports/IPaymentProvider

  (provider-name [_] :mollie)

  (create-checkout-session [_ {:keys [amount-cents currency description redirect-url webhook-url metadata
                                      setup-future-usage]}]
    ;; Mandate-aware checkout (sequenceType=first + customer creation) is not
    ;; implemented yet — fail loudly rather than silently ignoring the option,
    ;; which would leave the caller believing a mandate was stored.
    (when setup-future-usage
      (throw (ex-info "Mollie create-checkout-session does not support :setup-future-usage yet"
                      {:type               :not-implemented
                       :provider           :mollie
                       :method             :create-checkout-session
                       :setup-future-usage setup-future-usage})))
    (let [checkout-id  (str (UUID/randomUUID))
          webhook      (or webhook-url (str webhook-base-url "/api/v1/payments/webhook"))
          payload      {:amount      {:currency (or currency "EUR")
                                      :value    (provider/cents->euro amount-cents)}
                        :description description
                        :redirectUrl redirect-url
                        :webhookUrl  webhook
                        :metadata    (merge {:checkout-id checkout-id} metadata)}
          response     (http/post (str mollie-api-base "/payments")
                                  {:headers          (mollie-headers api-key)
                                   :body             (json/generate-string payload)
                                   :as               :string
                                   :throw-exceptions false})
          body         (json/parse-string (:body response) true)]
      (log/infof "Mollie create-checkout: status=%d id=%s" (:status response) (:id body))
      (when-not (#{200 201} (:status response))
        (throw (ex-info "Mollie checkout creation failed"
                        {:type   :internal-error
                         :status (:status response)
                         :body   body})))
      {:checkout-url         (get-in body [:_links :checkout :href])
       :provider-checkout-id (:id body)
       ;; Internal correlation id, also stored in Mollie payment metadata
       ;; (metadata.checkout-id) so the webhook can recover it.
       :correlation-id       checkout-id}))

  (create-off-session-payment [_ _opts]
    ;; Mollie recurring payments (sequenceType=recurring) are not implemented yet.
    (throw (ex-info "Mollie create-off-session-payment is not implemented yet"
                    {:type     :not-implemented
                     :provider :mollie
                     :method   :create-off-session-payment})))

  (expire-checkout-session [_ provider-checkout-id]
    ;; Mollie payments expire automatically; explicit expiry is not implemented yet.
    (throw (ex-info "Mollie expire-checkout-session is not implemented yet"
                    {:type                 :not-implemented
                     :provider             :mollie
                     :method               :expire-checkout-session
                     :provider-checkout-id provider-checkout-id})))

  (get-payment-status [_ provider-checkout-id]
    (let [payment (fetch-payment api-key provider-checkout-id)]
      (if payment
        {:status              (provider/mollie-status->payment-status (:status payment))
         :provider-payment-id (:id payment)}
        ;; :provider-payment-id deliberately absent (not nil) so callers
        ;; merging the result cannot clobber a stored payment id.
        {:status :pending})))

  (verify-webhook-signature [_ _raw-body _headers]
    ;; Mollie does not use HMAC signing. Verification happens by fetching
    ;; the payment and checking its status.
    true)

  (process-webhook [_ raw-body _headers]
    ;; Mollie sends a form-POST with only the payment id as `id`
    (let [payment-id (if (string? raw-body)
                       (second (re-find #"id=([^&]+)" raw-body))
                       (or (:id raw-body) (str raw-body)))
          payment    (when payment-id (fetch-payment api-key payment-id))
          status     (:status payment)
          event-type (provider/mollie-status->event-type status)]
      (log/infof "Mollie webhook: payment-id=%s status=%s → event=%s"
                 payment-id status event-type)
      (when-not event-type
        (throw (ex-info "Unhandled Mollie payment status"
                        {:type   :internal-error
                         :status status})))
      ;; :provider-payment-id is the genuine Mollie payment id (tr_…) — the same
      ;; value returned as :provider-checkout-id at creation. :correlation-id is
      ;; the internal UUID recovered from metadata, round-tripping CheckoutResult.
      {:event-type            event-type
       :provider-payment-id   payment-id
       :correlation-id        (get-in payment [:metadata :checkoutId]
                                      (get-in payment [:metadata :checkout-id]))
       :payload               (or payment {})})))
