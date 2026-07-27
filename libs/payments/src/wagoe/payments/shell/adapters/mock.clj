(ns wagoe.payments.shell.adapters.mock
  "Mock payment provider — auto-approves all payments. For development and tests."
  (:require [wagoe.payments.ports :as ports]
            [cheshire.core :as json]
            [clojure.tools.logging :as log])
  (:import [java.util UUID]))

(defrecord MockPaymentProvider [idempotency-cache]
  ports/IPaymentProvider

  (provider-name [_] :mock)

  (create-checkout-session [_ {:keys [amount-cents currency description metadata]}]
    (let [checkout-id (str (UUID/randomUUID))
          payment-id  (:payment-id metadata)]
      (log/infof "Mock PSP: created checkout session %s for %d %s — %s"
                 checkout-id amount-cents currency description)
      {:checkout-url         (cond-> (str "/web/payment/mock-return?checkout-id=" checkout-id)
                               payment-id (str "&payment-id=" payment-id))
       :provider-checkout-id checkout-id
       :correlation-id       checkout-id
       :provider-payment-id  (str "mock-payment-" checkout-id)}))

  (create-off-session-payment [_ {:keys [amount-cents currency description
                                         provider-customer-id
                                         provider-payment-method-id metadata
                                         idempotency-key]}]
    ;; Mirror the providers' idempotency contract: a repeated key returns the
    ;; original charge so consumers can exercise their retry logic in tests.
    (if-let [cached (and idempotency-key idempotency-cache
                         (get @idempotency-cache idempotency-key))]
      (do (log/infof "Mock PSP: off-session payment idempotency-key %s → cached %s"
                     idempotency-key (:provider-payment-id cached))
          cached)
      (let [payment-id (str "mock-payment-" (UUID/randomUUID))
            ;; Keep in sync with schema/OffSessionPaymentStatus.
            status     (get #{:pending :paid :failed} (:mock-status metadata) :paid)
            result     {:provider-payment-id payment-id
                        :status              status}]
        (log/infof "Mock PSP: off-session payment %s for customer %s (method %s) — %d %s (%s) → %s"
                   payment-id provider-customer-id (or provider-payment-method-id "default mandate")
                   amount-cents currency description status)
        (when (and idempotency-key idempotency-cache)
          (swap! idempotency-cache assoc idempotency-key result))
        result)))

  (get-payment-status [_ provider-checkout-id]
    (log/infof "Mock PSP: get-payment-status for %s → :paid" provider-checkout-id)
    {:status                     :paid
     :provider-payment-id        (str "mock-payment-" provider-checkout-id)
     :provider-customer-id       (str "mock-customer-" provider-checkout-id)
     :provider-payment-method-id (str "mock-pm-" provider-checkout-id)})

  (expire-checkout-session [_ provider-checkout-id]
    (log/infof "Mock PSP: expired checkout session %s" provider-checkout-id)
    {:provider-checkout-id provider-checkout-id
     :status               :expired})

  (verify-webhook-signature [_ _raw-body _headers]
    true)

  (process-webhook [_ raw-body _headers]
    (let [body (if (string? raw-body)
                 (try (json/parse-string raw-body true)
                      (catch Exception _ {}))
                 (or raw-body {}))]
      (log/infof "Mock PSP: processing webhook %s" body)
      (let [correlation-id (or (:checkout-id body) (str (UUID/randomUUID)))]
        {:event-type          :payment.paid
         :provider-payment-id (str "mock-payment-" correlation-id)
         :correlation-id      correlation-id
         :payload             body}))))

(defn make-mock-provider
  "Construct a MockPaymentProvider with a fresh idempotency cache. Prefer this
   over the positional ->MockPaymentProvider ctor so off-session idempotency
   keys are remembered per instance."
  []
  (->MockPaymentProvider (atom {})))
