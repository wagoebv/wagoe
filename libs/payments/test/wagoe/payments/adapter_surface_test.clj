(ns wagoe.payments.adapter-surface-test
  "One port contract, run against every PSP adapter.

   Each adapter had its own test file and no case in common, so nothing said
   they were interchangeable — and they were not: Mollie threw on two port
   methods and on any webhook for a payment still open. Here every adapter
   answers the same questions against a fake of its PSP, and the answers must
   conform to the port's schemas.

   The fakes replace clj-http for the whole run; a request they do not know
   throws, so no case can reach a real network. Where an adapter cannot express
   a case (the mock is always paid; only Stripe signs webhooks) its fixture says
   so with nil, and the case skips it by decision rather than by omission."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [malli.error :as me]
            [wagoe.payments.ports :as ports]
            [wagoe.payments.schema :as schema]
            [wagoe.payments.shell.adapters.mock :as mock]
            [wagoe.payments.shell.adapters.mollie :as mollie]
            [wagoe.payments.shell.adapters.stripe :as stripe])
  (:import [java.net URLDecoder]
           [java.nio.charset StandardCharsets]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

;; =============================================================================
;; Fake PSPs — just enough of each API, keyed on method + path
;; =============================================================================

(defn- respond [status body]
  {:status status :body (json/generate-string body)})

(defn- next-id [psp prefix]
  (str prefix (:n (swap! psp update :n (fnil inc 0)))))

(defn- idempotent
  "Replay the first response for a repeated Idempotency-Key, as both PSPs do."
  [psp req f]
  (let [k (get-in req [:headers "Idempotency-Key"])]
    (or (and k (get-in @psp [:idempotency k]))
        (let [resp (f)]
          (when k (swap! psp assoc-in [:idempotency k] resp))
          resp))))

(defn- form-decode [body]
  (into {}
        (for [pair (str/split (str body) #"&")
              :let [[k v] (str/split pair #"=" 2)]]
          [(URLDecoder/decode ^String k "UTF-8") (URLDecoder/decode (str v) "UTF-8")])))

(defn- fake-mollie [psp method url req]
  (let [path (str/replace url "https://api.mollie.com/v2" "")
        body (some-> (:body req) (json/parse-string true))]
    (cond
      (:down? @psp)
      (respond 503 {:title "Service Unavailable"})

      (and (= :post method) (= "/customers" path))
      (respond 201 {:id (next-id psp "cst_")})

      (and (= :post method) (= "/payments" path) (= "recurring" (:sequenceType body)))
      (idempotent psp req
                  #(let [id (next-id psp "tr_")]
                     (swap! psp assoc-in [:payments id]
                            (assoc body :id id :status "pending"
                                   :mandateId (or (:mandateId body) "mdt_default")))
                     (respond 201 (get-in @psp [:payments id]))))

      (and (= :post method) (= "/payments" path))
      (let [id (next-id psp "tr_")]
        (swap! psp assoc-in [:payments id]
               (assoc body :id id :status "open"
                      :_links {:checkout {:href (str "https://www.mollie.com/checkout/" id)}}))
        (respond 201 (get-in @psp [:payments id])))

      :else
      (let [[_ id] (re-matches #"/payments/([^/]+)" path)
            payment (get-in @psp [:payments id])]
        (cond
          (nil? payment)     (respond 404 {:title "Not Found"})
          (= :get method)    (respond 200 payment)
          (= :delete method) (do (swap! psp assoc-in [:payments id :status] "canceled")
                                 (respond 200 (get-in @psp [:payments id]))))))))

(defn- fake-stripe [psp method url req]
  (let [path (str/replace url "https://api.stripe.com/v1" "")
        form (form-decode (:body req))]
    (cond
      (:down? @psp)
      (respond 503 {:error {:type "api_error"}})

      (and (= :post method) (= "/checkout/sessions" path))
      (let [id (next-id psp "cs_")
            pi (next-id psp "pi_")]
        (swap! psp assoc-in [:sessions id]
               {:id id :url (str "https://checkout.stripe.com/c/pay/" id)
                :status "open" :payment_status "unpaid"
                :payment_intent {:id pi :status "requires_payment_method"
                                 :metadata {:checkout_id (form "payment_intent_data[metadata][checkout_id]")}}})
        (respond 200 (-> (get-in @psp [:sessions id]) (assoc :payment_intent nil))))

      (and (= :post method) (= "/payment_intents" path))
      (idempotent psp req #(respond 200 {:id (next-id psp "pi_") :status "processing"}))

      (and (= :post method) (str/ends-with? path "/expire"))
      (let [id (second (re-matches #"/checkout/sessions/([^/]+)/expire" path))]
        (swap! psp assoc-in [:sessions id :status] "expired")
        (respond 200 (get-in @psp [:sessions id])))

      (= :get method)
      (if-let [session (get-in @psp [:sessions (second (re-matches #"/checkout/sessions/([^/]+)" path))])]
        (respond 200 session)
        (respond 404 {:error {:type "invalid_request_error"}})))))

(defn- offline
  "Stands in for clj-http. Anything the fake does not answer is a test bug,
   never a request that goes out."
  [fake psp]
  (fn [method]
    (fn [url & [req]]
      (or (when fake (fake psp method url req))
          (throw (ex-info (str "unexpected " (name method) " " url) {:type :internal-error}))))))

;; =============================================================================
;; Fixtures — one per adapter
;; =============================================================================

(def ^:private stripe-secret "whsec_surface_test_secret_000000000")

(defn- stripe-sign [raw secret]
  (let [ts  (str (quot (System/currentTimeMillis) 1000))
        mac (doto (Mac/getInstance "HmacSHA256")
              (.init (SecretKeySpec. (.getBytes ^String secret StandardCharsets/UTF_8) "HmacSHA256")))
        sig (->> (.doFinal mac (.getBytes (str ts "." raw) StandardCharsets/UTF_8))
                 (map #(format "%02x" (bit-and % 0xff)))
                 (apply str))]
    {"stripe-signature" (str "t=" ts ",v1=" sig)}))

(defn- stripe-session [psp checkout]
  (get-in @psp [:sessions (:provider-checkout-id checkout)]))

(defn- stripe-set! [psp checkout status payment-status]
  (swap! psp update-in [:sessions (:provider-checkout-id checkout)]
         assoc :status status :payment_status payment-status)
  (:provider-checkout-id checkout))

(defn- stripe-event [psp checkout type]
  (let [raw (json/generate-string
             {:type type
              :data {:object (:payment_intent (stripe-session psp checkout))}})]
    [raw (stripe-sign raw stripe-secret)]))

(defn- mollie-set! [psp checkout status]
  (swap! psp assoc-in [:payments (:provider-checkout-id checkout) :status] status)
  (:provider-checkout-id checkout))

(defn- mollie-event [psp checkout status]
  (mollie-set! psp checkout status)
  [(str "id=" (:provider-checkout-id checkout)) {}])

(def ^:private fixtures
  "Each fixture's functions take the fake PSP state and a CheckoutResult.
   `:statuses` maps a port status to a fn that puts the PSP in it and returns
   the id to poll; a status the adapter cannot express is simply absent."
  [{:label    "mock"
    :make     (fn [] [(mock/make-mock-provider) nil])
    :statuses {:paid (fn [_ c] (:provider-checkout-id c))}
    :paid-webhook (fn [_ c] [(json/generate-string {:checkout-id (:correlation-id c)}) {}])}

   {:label    "stripe"
    :make     (fn [] [(stripe/->StripePaymentProvider "sk_test" stripe-secret) fake-stripe])
    :statuses {:pending (fn [psp c] (stripe-set! psp c "open" "unpaid"))
               :paid    (fn [psp c] (stripe-set! psp c "complete" "paid"))
               :expired (fn [psp c] (stripe-set! psp c "expired" "unpaid"))}
    :mandate!         (fn [psp c]
                        (swap! psp update-in [:sessions (:provider-checkout-id c)]
                               #(-> % (assoc :customer "cus_1")
                                    (assoc-in [:payment_intent :payment_method] "pm_1"))))
    :paid-webhook     (fn [psp c] (stripe-event psp c "payment_intent.succeeded"))
    :unmapped-webhook (fn [psp c] (stripe-event psp c "checkout.session.completed"))
    :forged-webhook   (fn [psp c]
                        (let [[raw _] (stripe-event psp c "payment_intent.succeeded")]
                          [raw (stripe-sign raw "whsec_someone_else_00000000000000")]))}

   {:label    "mollie"
    :make     (fn [] [(mollie/->MolliePaymentProvider "test_key" "https://shop.example") fake-mollie])
    :statuses {:pending   (fn [psp c] (mollie-set! psp c "open"))
               :paid      (fn [psp c] (mollie-set! psp c "paid"))
               :failed    (fn [psp c] (mollie-set! psp c "failed"))
               :cancelled (fn [psp c] (mollie-set! psp c "canceled"))
               :expired   (fn [psp c] (mollie-set! psp c "expired"))}
    :mandate!         (fn [psp c]
                        (swap! psp update-in [:payments (:provider-checkout-id c)]
                               assoc :customerId "cst_1" :mandateId "mdt_1"))
    :paid-webhook     (fn [psp c] (mollie-event psp c "paid"))
    :unmapped-webhook (fn [psp c] (mollie-event psp c "open"))}])

(defn- each-adapter
  "Call `(f provider psp fixture)` per adapter, with clj-http replaced."
  [f]
  (doseq [{:keys [label make] :as fixture} fixtures]
    (testing label
      (let [[provider fake] (make)
            psp             (atom {})
            stub            (offline fake psp)]
        (with-redefs [http/get    (stub :get)
                      http/post   (stub :post)
                      http/delete (stub :delete)]
          (f provider psp fixture))))))

(defn- conforms? [schema value]
  (is (m/validate schema value)
      (pr-str (me/humanize (m/explain schema value)))))

(def ^:private checkout-request
  {:amount-cents 1250
   :currency     "EUR"
   :description  "Order 42"
   :redirect-url "https://shop.example/return"
   :metadata     {:order-id "42"}})

(def ^:private off-session-request
  {:amount-cents         995
   :currency             "EUR"
   :description          "Subscription, October"
   :provider-customer-id "cus_or_cst_1"
   :idempotency-key      "incasso-sub-1-2026-10"})

;; =============================================================================
;; The sweep
;; =============================================================================

(deftest ^:integration the-sweep-covers-every-adapter
  (is (= #{"mock" "stripe" "mollie"} (set (map :label fixtures)))))

(deftest ^:integration provider-name-is-the-config-keyword
  (each-adapter
   (fn [p _ {:keys [label]}]
     (is (= (keyword label) (ports/provider-name p))))))

(deftest ^:integration create-checkout-session-contract
  (each-adapter
   (fn [p _ _]
     (testing "a plain checkout"
       (let [a (ports/create-checkout-session p checkout-request)
             b (ports/create-checkout-session p checkout-request)]
         (conforms? schema/CheckoutResult a)
         (is (not= (:correlation-id a) (:correlation-id b))
             "two checkouts must not share a correlation id")))
     (testing "a checkout that stores a mandate does not throw"
       (conforms? schema/CheckoutResult
                  (ports/create-checkout-session
                   p (assoc checkout-request
                            :setup-future-usage :off-session
                            :customer-email "jo@example.com")))))))

(deftest ^:integration create-off-session-payment-contract
  (each-adapter
   (fn [p _ _]
     (let [first-try (ports/create-off-session-payment p off-session-request)]
       (conforms? schema/OffSessionPaymentResult first-try)
       (testing "a retry with the same idempotency key returns the same charge"
         (is (= first-try (ports/create-off-session-payment p off-session-request))))
       (testing "a different key is a different charge"
         (is (not= (:provider-payment-id first-try)
                   (:provider-payment-id
                    (ports/create-off-session-payment
                     p (assoc off-session-request :idempotency-key "incasso-sub-1-2026-11")))))))
     (testing "without a key"
       (conforms? schema/OffSessionPaymentResult
                  (ports/create-off-session-payment
                   p (dissoc off-session-request :idempotency-key)))))))

(deftest ^:integration get-payment-status-contract
  (each-adapter
   (fn [p psp {:keys [statuses mandate!]}]
     (doseq [[expected put!] statuses]
       (testing (str "the PSP says " (name expected))
         (let [c      (ports/create-checkout-session p checkout-request)
               result (ports/get-payment-status p (put! psp c))]
           (conforms? schema/PaymentStatusResult result)
           (is (= expected (:status result))))))

     (when mandate!
       (testing "a paid mandate checkout exposes the customer and payment method"
         (let [c (ports/create-checkout-session
                  p (assoc checkout-request :setup-future-usage :off-session))
               _ (mandate! psp c)
               result (ports/get-payment-status p ((:paid statuses) psp c))]
           (is (string? (:provider-customer-id result)))
           (is (string? (:provider-payment-method-id result))))))

     (when (contains? statuses :pending)
       (testing "an unreachable PSP reads as :pending, with no payment id to clobber"
         (let [c (ports/create-checkout-session p checkout-request)]
           (swap! psp assoc :down? true)
           (is (= {:status :pending} (ports/get-payment-status p (:provider-checkout-id c))))
           (swap! psp dissoc :down?)))))))

(deftest ^:integration mock-mandate-fields
  ;; The mock has no PSP state to set, and reports mandate fields always.
  (let [p (mock/make-mock-provider)
        r (ports/get-payment-status p "any")]
    (is (string? (:provider-customer-id r)))
    (is (string? (:provider-payment-method-id r)))))

(deftest ^:integration expire-checkout-session-contract
  (each-adapter
   (fn [p _ _]
     (let [c      (ports/create-checkout-session p checkout-request)
           result (ports/expire-checkout-session p (:provider-checkout-id c))]
       (conforms? schema/ExpireCheckoutResult result)
       (is (= (:provider-checkout-id c) (:provider-checkout-id result)))))))

(deftest ^:integration webhook-contract
  (each-adapter
   (fn [p psp {:keys [paid-webhook unmapped-webhook forged-webhook]}]
     (testing "a paid webhook round-trips the correlation id, whatever the metadata says"
       (let [c              (ports/create-checkout-session
                             p (assoc checkout-request
                                      :metadata {:checkout-id "mine" :checkout_id "mine"}))
             [raw headers]  (paid-webhook psp c)]
         (is (true? (ports/verify-webhook-signature p raw headers)))
         (let [result (ports/process-webhook p raw headers)]
           (conforms? schema/WebhookResult result)
           (is (= :payment.paid (:event-type result)))
           (is (= (:correlation-id c) (:correlation-id result))))))

     (when unmapped-webhook
       (testing "an event with no payment.* outcome is acknowledged, not thrown"
         (let [c             (ports/create-checkout-session p checkout-request)
               [raw headers] (unmapped-webhook psp c)
               result        (ports/process-webhook p raw headers)]
           (conforms? schema/WebhookResult result)
           (is (nil? (:event-type result))))))

     (when forged-webhook
       (testing "a webhook signed with another secret fails verification"
         (let [c             (ports/create-checkout-session p checkout-request)
               [raw headers] (forged-webhook psp c)]
           (is (false? (ports/verify-webhook-signature p raw headers)))))))))
