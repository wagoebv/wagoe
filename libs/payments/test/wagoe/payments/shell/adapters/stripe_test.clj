(ns wagoe.payments.shell.adapters.stripe-test
  "Integration tests for the Stripe payment provider adapter.
   All tests run without network access — clj-http is stubbed with with-redefs,
   only local HMAC, request shaping and JSON parsing are exercised."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [wagoe.payments.ports :as ports]
            [wagoe.payments.shell.adapters.stripe :as stripe])
  (:import [java.net URLDecoder]
           [java.nio.charset StandardCharsets]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

;; =============================================================================
;; Test helpers
;; =============================================================================

(def ^:private test-secret "whsec_test_signing_secret_32chars!")

(def ^:private provider
  (stripe/->StripePaymentProvider "sk_test_key" test-secret))

(defn- compute-signature
  "Replicate the Stripe HMAC-SHA256 signature computation for use in tests."
  [raw-body timestamp secret]
  (let [signed-payload (str timestamp "." raw-body)
        mac            (Mac/getInstance "HmacSHA256")
        key            (SecretKeySpec. (.getBytes ^String secret StandardCharsets/UTF_8)
                                       "HmacSHA256")]
    (.init mac key)
    (apply str (map #(format "%02x" (bit-and % 0xff))
                    (.doFinal mac (.getBytes ^String signed-payload StandardCharsets/UTF_8))))))

(defn- stripe-sig-header [body timestamp secret]
  (str "t=" timestamp ",v1=" (compute-signature body timestamp secret)))

(defn- fresh-timestamp
  "Seconds since the epoch, read now.

   Was a `def`, so the value was fixed when the namespace loaded. Stripe
   rejects a signature more than 300 seconds old — its replay window, and the
   adapter is right to enforce it — and in a full suite this namespace loads
   minutes before these tests run. Past five minutes the fixture is genuinely
   expired and the test fails on its own staleness, which reads exactly like a
   broken adapter (BOU-355)."
  []
  (str (quot (System/currentTimeMillis) 1000)))

(def ^:private paid-event
  {:type "payment_intent.succeeded"
   :data {:object {:id       "pi_test_123"
                   ;; checkout_id is the adapter-internal correlation UUID set at
                   ;; session creation — NOT the cs_… session id.
                   :metadata {:checkout_id "corr_test_abc"}}}})

(defn- form-decode
  "Decode an application/x-www-form-urlencoded body into a string->string map."
  [body]
  (into {}
        (for [pair (str/split (str body) #"&")
              :let [[k v] (str/split pair #"=" 2)]]
          [(URLDecoder/decode ^String k "UTF-8")
           (URLDecoder/decode (str v) "UTF-8")])))

(defn- json-response [status body-map]
  {:status status
   :body   (json/generate-string body-map)})

;; =============================================================================
;; verify-webhook-signature
;; =============================================================================

(deftest ^:integration verify-webhook-signature-test
  (let [body (json/generate-string paid-event)]

    (testing "returns true for a valid signature — lowercase header key"
      (let [sig (stripe-sig-header body (fresh-timestamp) test-secret)]
        (is (true? (ports/verify-webhook-signature
                    provider body {"stripe-signature" sig})))))

    (testing "returns true for a valid signature — titlecase header key"
      (let [sig (stripe-sig-header body (fresh-timestamp) test-secret)]
        (is (true? (ports/verify-webhook-signature
                    provider body {"Stripe-Signature" sig})))))

    (testing "returns false when signature is wrong"
      (let [sig (stripe-sig-header body (fresh-timestamp) "wrong-secret-00000000000000000")]
        (is (false? (ports/verify-webhook-signature
                     provider body {"stripe-signature" sig})))))

    (testing "returns false when Stripe-Signature header is absent"
      (is (false? (ports/verify-webhook-signature provider body {}))))

    (testing "returns false when header is nil"
      (is (false? (ports/verify-webhook-signature provider body {"stripe-signature" nil}))))

    (testing "returns false when body is empty and signature does not match"
      (let [sig (stripe-sig-header body (fresh-timestamp) test-secret)]
        (is (false? (ports/verify-webhook-signature
                     provider "" {"stripe-signature" sig})))))

    (testing "returns false when timestamp is older than 300 seconds"
      (let [old-ts (str (- (quot (System/currentTimeMillis) 1000) 301))
            sig    (stripe-sig-header body old-ts test-secret)]
        (is (false? (ports/verify-webhook-signature
                     provider body {"stripe-signature" sig})))))

    (testing "returns false on malformed (non-numeric) timestamp — no NumberFormatException"
      (is (false? (ports/verify-webhook-signature
                   provider body {"stripe-signature" "t=not-a-number,v1=deadbeef"}))))

    (testing "returns false on empty timestamp string"
      (is (false? (ports/verify-webhook-signature
                   provider body {"stripe-signature" "t=,v1=deadbeef"}))))))

;; =============================================================================
;; process-webhook — event type mapping
;; =============================================================================

(deftest ^:integration process-webhook-event-types-test
  (testing "payment_intent.succeeded → :payment.paid"
    (let [body (json/generate-string {:type "payment_intent.succeeded"
                                      :data {:object {:id "pi_1" :metadata {}}}})
          result (ports/process-webhook provider body {})]
      (is (= :payment.paid (:event-type result)))))

  (testing "payment_intent.payment_failed → :payment.failed"
    (let [body (json/generate-string {:type "payment_intent.payment_failed"
                                      :data {:object {:id "pi_2" :metadata {}}}})
          result (ports/process-webhook provider body {})]
      (is (= :payment.failed (:event-type result)))))

  (testing "payment_intent.canceled → :payment.cancelled"
    (let [body (json/generate-string {:type "payment_intent.canceled"
                                      :data {:object {:id "pi_3" :metadata {}}}})
          result (ports/process-webhook provider body {})]
      (is (= :payment.cancelled (:event-type result)))))

  (testing "payment_intent.amount_capturable_updated → :payment.authorized"
    (let [body (json/generate-string {:type "payment_intent.amount_capturable_updated"
                                      :data {:object {:id "pi_4" :metadata {}}}})
          result (ports/process-webhook provider body {})]
      (is (= :payment.authorized (:event-type result)))))

  ;; BOU-147: an unmapped-but-valid Stripe event must NOT throw. A connected
  ;; endpoint receives many event types by default; a throw here becomes an
  ;; HTTP 500 in the billing webhook handler and Stripe retries for days. The
  ;; event is acknowledged with :event-type nil and the payload preserved so the
  ;; billing layer's event-action can route/ignore it by payload type.
  (testing "unmapped event type → :event-type nil, no throw, payload preserved"
    (let [body   (json/generate-string {:type "charge.succeeded"
                                        :data {:object {:id "ch_1"}}})
          result (ports/process-webhook provider body {})]
      (is (nil? (:event-type result)))
      (is (= "charge.succeeded" (get-in result [:payload :type])))))

  (testing "checkout.session.completed → :event-type nil (billing routes by payload)"
    ;; checkout.session.completed is the primary paid-flow event but is not in
    ;; the generic payment.* map; it must reach the handler (not 500) so
    ;; event-action can flip it to :paid.
    (let [body   (json/generate-string {:type "checkout.session.completed"
                                        :data {:object {:id "cs_1" :payment_status "paid"}}})
          result (ports/process-webhook provider body {})]
      (is (nil? (:event-type result)))
      (is (= "checkout.session.completed" (get-in result [:payload :type])))))

  (testing "checkout.session.expired → :event-type nil (the exact BOU-147 reproducer)"
    ;; evt_…m1yaatdM in the sandbox: every delivery failed with a 500 because the
    ;; old code threw on this type. It must now come back as a nil-event-type
    ;; result so the billing handler acknowledges it and event-action → :expired.
    (let [body   (json/generate-string {:type "checkout.session.expired"
                                        :data {:object {:id "cs_2"}}})
          result (ports/process-webhook provider body {})]
      (is (nil? (:event-type result)))
      (is (= "checkout.session.expired" (get-in result [:payload :type]))))))

;; =============================================================================
;; process-webhook — field extraction
;; =============================================================================

(deftest ^:integration process-webhook-field-extraction-test
  (let [body   (json/generate-string paid-event)
        result (ports/process-webhook provider body {})]

    (testing "extracts provider-payment-id from data.object.id"
      (is (= "pi_test_123" (:provider-payment-id result))))

    (testing "extracts correlation-id from data.object.metadata.checkout_id"
      (is (= "corr_test_abc" (:correlation-id result))))

    (testing "does not alias the internal correlation id to provider-checkout-id"
      ;; payment_intent.* events carry no cs_… session id — the field is absent
      ;; rather than misleadingly set to the internal UUID (BOU-78).
      (is (not (contains? result :provider-checkout-id))))

    (testing "payload contains the full parsed event"
      (is (= "payment_intent.succeeded" (get-in result [:payload :type]))))

    (testing "accepts a pre-parsed map body"
      (let [result (ports/process-webhook provider paid-event {})]
        (is (= :payment.paid (:event-type result)))
        (is (= "pi_test_123" (:provider-payment-id result)))))))

;; =============================================================================
;; process-webhook — metadata round-trip
;; =============================================================================

(deftest ^:integration process-webhook-metadata-round-trip-test
  (testing "checkout_id from PaymentIntent metadata is recovered as correlation-id"
    (let [event {:type "payment_intent.succeeded"
                 :data {:object {:id       "pi_round_trip"
                                 :metadata {:checkout_id "corr_original"}}}}
          result (ports/process-webhook provider (json/generate-string event) {})]
      (is (= "corr_original" (:correlation-id result)))))

  (testing "correlation-id is nil when metadata has no checkout_id"
    (let [event {:type "payment_intent.succeeded"
                 :data {:object {:id "pi_no_meta" :metadata {}}}}
          result (ports/process-webhook provider (json/generate-string event) {})]
      (is (nil? (:correlation-id result))))))

;; =============================================================================
;; correlation round-trip — create → webhook (BOU-78 acceptance)
;; =============================================================================

(deftest ^:contract create-to-webhook-correlation-round-trip-test
  (testing "the :correlation-id from CheckoutResult is recoverable from the webhook"
    (let [captured (atom nil)]
      (with-redefs [http/post (fn [_url req]
                                (reset! captured req)
                                (json-response 200 {:id  "cs_live_1"
                                                    :url "https://checkout.stripe.com/c/pay/cs_live_1"}))]
        (let [created        (ports/create-checkout-session
                              provider
                              {:amount-cents 4900
                               :currency     "EUR"
                               :description  "Premium plan"
                               :redirect-url "https://app.example.com/return"})
              correlation-id (:correlation-id created)
              ;; the adapter embedded the same id in PI metadata at creation
              sent-checkout-id (-> (form-decode (:body @captured))
                                   (get "payment_intent_data[metadata][checkout_id]"))
              ;; Stripe later delivers a payment_intent.* webhook echoing it back
              webhook-event  {:type "payment_intent.succeeded"
                              :data {:object {:id       "pi_live_1"
                                              :metadata {:checkout_id sent-checkout-id}}}}
              processed      (ports/process-webhook provider (json/generate-string webhook-event) {})]
          (is (string? correlation-id))
          (is (= correlation-id sent-checkout-id)
              "creation echoes the internal id into PI metadata")
          (is (= correlation-id (:correlation-id processed))
              "consumer can match the webhook to the stored checkout via :correlation-id"))))))

;; =============================================================================
;; create-checkout-session
;; =============================================================================

(deftest ^:integration create-checkout-session-test
  (testing "posts a payment-mode session with mandate options and returns ids"
    (let [captured (atom nil)]
      (with-redefs [http/post (fn [url req]
                                (reset! captured {:url url :req req})
                                (json-response 200 {:id  "cs_test_123"
                                                    :url "https://checkout.stripe.com/c/pay/cs_test_123"}))]
        (let [result (ports/create-checkout-session
                      provider
                      {:amount-cents       11900
                       :currency           "EUR"
                       :description        "Premium plan"
                       :redirect-url       "https://app.example.com/return"
                       :setup-future-usage :off-session
                       :customer-email     "jane@example.com"
                       :metadata           {:order-id "ord-1"}})
              params (form-decode (get-in @captured [:req :body]))]
          (is (= "https://api.stripe.com/v1/checkout/sessions" (:url @captured)))
          (is (= "Bearer sk_test_key" (get-in @captured [:req :headers "Authorization"])))
          (is (= "payment"     (get params "mode")))
          (is (= "eur"         (get params "line_items[0][price_data][currency]")))
          (is (= "11900"       (get params "line_items[0][price_data][unit_amount]")))
          (is (= "off_session" (get params "payment_intent_data[setup_future_usage]")))
          (is (= "always"      (get params "customer_creation")))
          (is (= "jane@example.com" (get params "customer_email")))
          (is (= "https://app.example.com/return" (get params "success_url")))
          (is (= "https://app.example.com/return" (get params "cancel_url")))
          (is (= "ord-1" (get params "metadata[order-id]")))
          (is (= "ord-1" (get params "payment_intent_data[metadata][order-id]")))
          (is (= "https://checkout.stripe.com/c/pay/cs_test_123" (:checkout-url result)))
          (is (= "cs_test_123" (:provider-checkout-id result)))
          ;; correlation-id is the internal UUID also written to PI metadata
          (is (string? (:correlation-id result)))
          (is (= (:correlation-id result)
                 (get params "payment_intent_data[metadata][checkout_id]")))
          (is (nil? (:provider-payment-id result)))))))

  (testing "explicit success/cancel URLs override redirect-url"
    (let [captured (atom nil)]
      (with-redefs [http/post (fn [url req]
                                (reset! captured {:url url :req req})
                                (json-response 200 {:id "cs_1" :url "https://stripe/pay"}))]
        (ports/create-checkout-session
         provider
         {:amount-cents 100
          :currency     "EUR"
          :description  "Test"
          :redirect-url "https://app.example.com/return"
          :success-url  "https://app.example.com/ok"
          :cancel-url   "https://app.example.com/cancelled"})
        (let [params (form-decode (get-in @captured [:req :body]))]
          (is (= "https://app.example.com/ok"        (get params "success_url")))
          (is (= "https://app.example.com/cancelled" (get params "cancel_url")))))))

  ;; The resolved success_url/cancel_url must be an ABSOLUTE http(s) URL. A broken
  ;; upstream config (unset PUBLIC_BASE_URL) reaches Stripe as either an empty
  ;; string (parameter_invalid_empty, BOU-148) or a scheme-less relative path
  ;; (url_invalid, BOU-149). The guard rejects both, named, and never POSTs.
  (testing "blank resolved return URL → :configuration-error, Stripe is never called (BOU-148)"
    (let [posted? (atom false)]
      (with-redefs [http/post (fn [_ _] (reset! posted? true)
                                (json-response 200 {:id "cs" :url "u"}))]
        (try
          (ports/create-checkout-session
           provider
           {:amount-cents 100 :currency "EUR" :description "Test"
            :redirect-url ""})           ; blank → success_url/cancel_url blank
          (is false "should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :configuration-error (:type (ex-data e))))
            (is (= "success_url" (:param (ex-data e))))))
        (is (false? @posted?) "must not POST an empty success_url to Stripe"))))

  (testing "relative (scheme-less) return URL → :configuration-error, Stripe is never called (BOU-149)"
    (let [posted? (atom false)]
      (with-redefs [http/post (fn [_ _] (reset! posted? true)
                                (json-response 200 {:id "cs" :url "u"}))]
        (try
          (ports/create-checkout-session
           provider
           {:amount-cents 100 :currency "EUR" :description "Test"
            ;; exactly the acc shape: PUBLIC_BASE_URL empty → config left relative
            :redirect-url "/web/license/payment/return?session_id={CHECKOUT_SESSION_ID}"})
          (is false "should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :configuration-error (:type (ex-data e))))
            (is (= "success_url" (:param (ex-data e))))))
        (is (false? @posted?) "must not POST a relative success_url to Stripe"))))

  (testing "a blank success-url override still falls back to a non-blank redirect-url"
    (let [captured (atom nil)]
      (with-redefs [http/post (fn [url req]
                                (reset! captured {:url url :req req})
                                (json-response 200 {:id "cs_1" :url "https://stripe/pay"}))]
        (ports/create-checkout-session
         provider
         {:amount-cents 100 :currency "EUR" :description "Test"
          :redirect-url "https://app.example.com/return"
          :success-url  "   "
          :cancel-url   nil})
        (let [params (form-decode (get-in @captured [:req :body]))]
          (is (= "https://app.example.com/return" (get params "success_url")))
          (is (= "https://app.example.com/return" (get params "cancel_url")))))))

  (testing "existing provider-customer-id is reused as customer"
    (let [captured (atom nil)]
      (with-redefs [http/post (fn [url req]
                                (reset! captured {:url url :req req})
                                (json-response 200 {:id "cs_1" :url "https://stripe/pay"}))]
        (ports/create-checkout-session
         provider
         {:amount-cents         100
          :currency             "EUR"
          :description          "Test"
          :redirect-url         "https://app.example.com/return"
          :setup-future-usage   :off-session
          :provider-customer-id "cus_existing"})
        (let [params (form-decode (get-in @captured [:req :body]))]
          (is (= "cus_existing" (get params "customer")))
          (is (not (contains? params "customer_creation")))))))

  (testing "includes provider-payment-id when Stripe returns a payment_intent"
    (with-redefs [http/post (fn [_url _req]
                              (json-response 200 {:id             "cs_1"
                                                  :url            "https://stripe/pay"
                                                  :payment_intent "pi_early"}))]
      (let [result (ports/create-checkout-session
                    provider
                    {:amount-cents 100 :currency "EUR" :description "Test"
                     :redirect-url "https://app.example.com/return"})]
        (is (= "pi_early" (:provider-payment-id result))))))

  (testing "non-2xx response throws :internal-error"
    (with-redefs [http/post (fn [_url _req]
                              (json-response 401 {:error {:type "invalid_request_error"}}))]
      (let [ex (try
                 (ports/create-checkout-session
                  provider
                  {:amount-cents 100 :currency "EUR" :description "Test"
                   :redirect-url "https://app.example.com/return"})
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex))
        (is (= :internal-error (:type (ex-data ex))))))))

(deftest ^:unit checkout-error-fields-test
  (testing "pulls Stripe's error reason (message/param/code/type) from a 400 body"
    (is (= {:status 400
            :type "invalid_request_error"
            :code "url_invalid"
            :param "success_url"
            :message "Not a valid URL"}
           (#'stripe/checkout-error-fields
            400
            {:error {:type "invalid_request_error"
                     :code "url_invalid"
                     :param "success_url"
                     :message "Not a valid URL"}}))))
  (testing "missing :error fields degrade to nil, status always present"
    (is (= {:status 500 :type nil :code nil :param nil :message nil}
           (#'stripe/checkout-error-fields 500 {})))))

;; =============================================================================
;; create-off-session-payment
;; =============================================================================

(deftest ^:integration create-off-session-payment-test
  (testing "posts a confirmed off-session PaymentIntent and maps succeeded → :paid"
    (let [captured (atom nil)]
      (with-redefs [http/post (fn [url req]
                                (reset! captured {:url url :req req})
                                (json-response 200 {:id "pi_ok" :status "succeeded"}))]
        (let [result (ports/create-off-session-payment
                      provider
                      {:amount-cents               4900
                       :currency                   "EUR"
                       :description                "Monthly subscription"
                       :provider-customer-id       "cus_abc"
                       :provider-payment-method-id "pm_9"
                       :metadata                   {:subscription-id "sub-42"}})
              params (form-decode (get-in @captured [:req :body]))]
          (is (= "https://api.stripe.com/v1/payment_intents" (:url @captured)))
          (is (= "4900"    (get params "amount")))
          (is (= "eur"     (get params "currency")))
          (is (= "cus_abc" (get params "customer")))
          (is (= "pm_9"    (get params "payment_method")))
          (is (= "true"    (get params "off_session")))
          (is (= "true"    (get params "confirm")))
          (is (= "sub-42"  (get params "metadata[subscription-id]")))
          (is (= {:provider-payment-id "pi_ok" :status :paid} result))))))

  (testing "processing intent → :pending"
    (with-redefs [http/post (fn [_url _req]
                              (json-response 200 {:id "pi_proc" :status "processing"}))]
      (is (= {:provider-payment-id "pi_proc" :status :pending}
             (ports/create-off-session-payment
              provider
              {:amount-cents 4900 :currency "EUR" :description "Sub"
               :provider-customer-id "cus_abc"})))))

  (testing "requires_action off-session → :failed (SCA cannot complete unattended)"
    (with-redefs [http/post (fn [_url _req]
                              (json-response 200 {:id "pi_sca" :status "requires_action"}))]
      (is (= {:provider-payment-id "pi_sca" :status :failed}
             (ports/create-off-session-payment
              provider
              {:amount-cents 4900 :currency "EUR" :description "Sub"
               :provider-customer-id "cus_abc"})))))

  (testing "card_declined card error returns :failed result instead of throwing"
    (with-redefs [http/post (fn [_url _req]
                              (json-response 402 {:error {:type           "card_error"
                                                          :code           "card_declined"
                                                          :payment_intent {:id "pi_declined"}}}))]
      (is (= {:provider-payment-id "pi_declined" :status :failed}
             (ports/create-off-session-payment
              provider
              {:amount-cents 4900 :currency "EUR" :description "Sub"
               :provider-customer-id "cus_abc"})))))

  (testing "authentication_required card error returns :failed result"
    (with-redefs [http/post (fn [_url _req]
                              (json-response 402 {:error {:type           "card_error"
                                                          :code           "authentication_required"
                                                          :payment_intent {:id "pi_sca_required"}}}))]
      (is (= {:provider-payment-id "pi_sca_required" :status :failed}
             (ports/create-off-session-payment
              provider
              {:amount-cents 4900 :currency "EUR" :description "Sub"
               :provider-customer-id "cus_abc"})))))

  (testing "auth/config errors still throw :internal-error"
    (with-redefs [http/post (fn [_url _req]
                              (json-response 401 {:error {:type    "invalid_request_error"
                                                          :message "Invalid API Key"}}))]
      (let [ex (try
                 (ports/create-off-session-payment
                  provider
                  {:amount-cents 4900 :currency "EUR" :description "Sub"
                   :provider-customer-id "cus_abc"})
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex))
        (is (= :internal-error (:type (ex-data ex))))))))

(deftest ^:integration create-off-session-payment-idempotency-key-test
  (testing "sends the Idempotency-Key header when :idempotency-key is given"
    (let [captured (atom nil)]
      (with-redefs [http/post (fn [_url req]
                                (reset! captured req)
                                (json-response 200 {:id "pi_idem" :status "succeeded"}))]
        (ports/create-off-session-payment
         provider
         {:amount-cents 4900 :currency "EUR" :description "Sub"
          :provider-customer-id "cus_abc"
          :idempotency-key "incasso-sub-42-2026-06"})
        (is (= "incasso-sub-42-2026-06"
               (get-in @captured [:headers "Idempotency-Key"]))))))

  (testing "omits the Idempotency-Key header when no key is given"
    (let [captured (atom nil)]
      (with-redefs [http/post (fn [_url req]
                                (reset! captured req)
                                (json-response 200 {:id "pi_plain" :status "succeeded"}))]
        (ports/create-off-session-payment
         provider
         {:amount-cents 4900 :currency "EUR" :description "Sub"
          :provider-customer-id "cus_abc"})
        (is (not (contains? (:headers @captured) "Idempotency-Key")))))))

;; =============================================================================
;; get-payment-status — id dispatch (cs_ → Checkout Session, pi_ → PaymentIntent)
;; =============================================================================

(deftest ^:integration get-payment-status-checkout-session-test
  (testing "cs_ id polls the Checkout Session with expanded payment_intent"
    (let [captured (atom nil)]
      (with-redefs [http/get (fn [url req]
                               (reset! captured {:url url :req req})
                               (json-response 200 {:id             "cs_1"
                                                   :status         "complete"
                                                   :payment_status "paid"
                                                   :customer       "cus_9"
                                                   :payment_intent {:id             "pi_9"
                                                                    :status         "succeeded"
                                                                    :payment_method "pm_9"}}))]
        (let [result (ports/get-payment-status provider "cs_1")]
          (is (= "https://api.stripe.com/v1/checkout/sessions/cs_1" (:url @captured)))
          (is (= "payment_intent" (get-in @captured [:req :query-params "expand[]"])))
          (is (= {:status                      :paid
                  :provider-payment-id         "pi_9"
                  :provider-customer-id        "cus_9"
                  :provider-payment-method-id  "pm_9"}
                 result))))))

  (testing "expired session → :expired"
    (with-redefs [http/get (fn [_url _req]
                             (json-response 200 {:id             "cs_2"
                                                 :status         "expired"
                                                 :payment_status "unpaid"}))]
      (is (= :expired (:status (ports/get-payment-status provider "cs_2"))))))

  (testing "open unpaid session → :pending"
    (with-redefs [http/get (fn [_url _req]
                             (json-response 200 {:id             "cs_3"
                                                 :status         "open"
                                                 :payment_status "unpaid"}))]
      (is (= :pending (:status (ports/get-payment-status provider "cs_3"))))))

  (testing "non-expanded string payment_intent still yields provider-payment-id"
    (with-redefs [http/get (fn [_url _req]
                             (json-response 200 {:id             "cs_4"
                                                 :status         "complete"
                                                 :payment_status "paid"
                                                 :customer       "cus_4"
                                                 :payment_intent "pi_4"}))]
      (let [result (ports/get-payment-status provider "cs_4")]
        (is (= "pi_4" (:provider-payment-id result)))
        (is (nil? (:provider-payment-method-id result)))))))

(deftest ^:integration get-payment-status-payment-intent-test
  (testing "pi_ id polls the PaymentIntent endpoint directly"
    (let [captured (atom nil)]
      (with-redefs [http/get (fn [url req]
                               (reset! captured {:url url :req req})
                               (json-response 200 {:id             "pi_7"
                                                   :status         "succeeded"
                                                   :customer       "cus_7"
                                                   :payment_method "pm_7"}))]
        (let [result (ports/get-payment-status provider "pi_7")]
          (is (= "https://api.stripe.com/v1/payment_intents/pi_7" (:url @captured)))
          (is (= {:status                      :paid
                  :provider-payment-id         "pi_7"
                  :provider-customer-id        "cus_7"
                  :provider-payment-method-id  "pm_7"}
                 result))))))

  (testing "canceled intent → :cancelled"
    (with-redefs [http/get (fn [_url _req]
                             (json-response 200 {:id "pi_8" :status "canceled"}))]
      (is (= :cancelled (:status (ports/get-payment-status provider "pi_8"))))))

  (testing "requires_action intent polls as :pending"
    (with-redefs [http/get (fn [_url _req]
                             (json-response 200 {:id "pi_9" :status "requires_action"}))]
      (is (= :pending (:status (ports/get-payment-status provider "pi_9")))))))

(deftest ^:integration get-payment-status-error-handling-test
  (testing "401 (config error) throws :internal-error"
    (with-redefs [http/get (fn [_url _req]
                             (json-response 401 {:error {:message "Invalid API Key"}}))]
      (let [ex (try (ports/get-payment-status provider "cs_1")
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex))
        (is (= :internal-error (:type (ex-data ex)))))))

  (testing "404 degrades to :pending without a provider-payment-id key (Mollie symmetry)"
    (with-redefs [http/get (fn [_url _req]
                             (json-response 404 {:error {:message "No such session"}}))]
      (let [result (ports/get-payment-status provider "cs_missing")]
        (is (= {:status :pending} result))
        ;; key absent, not nil — merging callers must not clobber a stored id
        (is (not (contains? result :provider-payment-id)))))))

;; =============================================================================
;; expire-checkout-session
;; =============================================================================

(deftest ^:integration expire-checkout-session-test
  (testing "posts to /checkout/sessions/{id}/expire and returns :expired"
    (let [captured (atom nil)]
      (with-redefs [http/post (fn [url req]
                                (reset! captured {:url url :req req})
                                (json-response 200 {:id "cs_1" :status "expired"}))]
        (let [result (ports/expire-checkout-session provider "cs_1")]
          (is (= "https://api.stripe.com/v1/checkout/sessions/cs_1/expire" (:url @captured)))
          (is (= {:provider-checkout-id "cs_1" :status :expired} result))))))

  (testing "non-200 (e.g. session already complete) throws :internal-error"
    (with-redefs [http/post (fn [_url _req]
                              (json-response 400 {:error {:message "Session is already complete"}}))]
      (let [ex (try (ports/expire-checkout-session provider "cs_done")
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex))
        (is (= :internal-error (:type (ex-data ex))))
        (is (= "cs_done" (:provider-checkout-id (ex-data ex))))))))
