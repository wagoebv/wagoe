(ns wagoe.payments.shell.adapters.mollie-test
  "What the Mollie adapter sends, and how it treats Mollie's errors. The port
   contract shared with the other adapters is in adapter-surface-test.
   clj-http is stubbed; no test reaches the network."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.test :refer [deftest testing is]]
            [wagoe.payments.ports :as ports]
            [wagoe.payments.shell.adapters.mollie :as mollie]
            [wagoe.payments.test-support :refer [json-response]]))

(def ^:private provider
  (mollie/->MolliePaymentProvider "test_api_key" "https://example.com"))

(defn- recording
  "A clj-http stand-in that records [method url req] and answers with `f`."
  [calls method f]
  (fn [url & [req]]
    (swap! calls conj [method url req])
    (f url req)))

(defn- sent-json [[_ _ req]]
  (json/parse-string (:body req) true))

(defn- ex-data-of [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest ^:integration mandate-checkout-test
  (testing "with no customer, one is created and the payment is sequenceType=first"
    (let [calls (atom [])]
      (with-redefs [http/post (recording calls :post
                                         (fn [url _]
                                           (if (re-find #"/customers$" url)
                                             (json-response 201 {:id "cst_new"})
                                             (json-response 201 {:id "tr_1" :_links {:checkout {:href "https://mollie/c"}}}))))]
        (ports/create-checkout-session provider {:amount-cents       100
                                                 :currency           "eur"
                                                 :description        "First"
                                                 :redirect-url       "https://example.com/done"
                                                 :setup-future-usage :off-session
                                                 :customer-email     "jo@example.com"}))
      (let [[customer payment] @calls]
        (is (= {:email "jo@example.com"} (sent-json customer)))
        (is (= "cst_new" (:customerId (sent-json payment))))
        (is (= "first" (:sequenceType (sent-json payment))))
        (is (= {:currency "EUR" :value "1.00"} (:amount (sent-json payment)))
            "Mollie wants the currency upper-case"))))

  (testing "an existing customer is reused"
    (let [calls (atom [])]
      (with-redefs [http/post (recording calls :post
                                         (fn [_ _] (json-response 201 {:id "tr_1" :_links {:checkout {:href "x"}}})))]
        (ports/create-checkout-session provider {:amount-cents         100
                                                 :currency             "EUR"
                                                 :description          "First"
                                                 :redirect-url         "https://example.com/done"
                                                 :setup-future-usage   :off-session
                                                 :provider-customer-id "cst_known"}))
      (is (= 1 (count @calls)))
      (is (= "cst_known" (:customerId (sent-json (first @calls)))))))

  (testing "a plain checkout creates no customer and no mandate"
    (let [calls (atom [])]
      (with-redefs [http/post (recording calls :post
                                         (fn [_ _] (json-response 201 {:id "tr_1" :_links {:checkout {:href "x"}}})))]
        (ports/create-checkout-session provider {:amount-cents 100 :currency "EUR" :description "One-off"
                                                 :redirect-url "https://example.com/done"}))
      (is (= 1 (count @calls)))
      (is (not (contains? (sent-json (first @calls)) :sequenceType))))))

(deftest ^:integration off-session-payment-test
  (testing "charges the mandate as sequenceType=recurring, with the idempotency key as a header"
    (let [calls (atom [])]
      (with-redefs [http/post (recording calls :post (fn [_ _] (json-response 201 {:id "tr_r" :status "pending"})))]
        (is (= {:provider-payment-id "tr_r" :status :pending}
               (ports/create-off-session-payment provider {:amount-cents               995
                                                           :currency                   "EUR"
                                                           :description                "October"
                                                           :provider-customer-id       "cst_1"
                                                           :provider-payment-method-id "mdt_1"
                                                           :idempotency-key            "incasso-1-10"}))))
      (let [[_ url req :as call] (first @calls)]
        (is (= "https://api.mollie.com/v2/payments" url))
        (is (= "incasso-1-10" (get-in req [:headers "Idempotency-Key"])))
        (is (= {:customerId "cst_1" :mandateId "mdt_1" :sequenceType "recurring"
                :webhookUrl "https://example.com/api/v1/payments/webhook"}
               (select-keys (sent-json call) [:customerId :mandateId :sequenceType :webhookUrl]))))))

  (testing "a payment that ended without money is :failed"
    (with-redefs [http/post (fn [& _] (json-response 201 {:id "tr_r" :status "failed"}))]
      (is (= :failed (:status (ports/create-off-session-payment
                               provider {:amount-cents 1 :currency "EUR" :description "x"
                                         :provider-customer-id "cst_1"}))))))

  (testing "no valid mandate (422) throws a typed error"
    (with-redefs [http/post (fn [& _] (json-response 422 {:detail "The customer has no valid mandate"}))]
      (let [data (ex-data-of #(ports/create-off-session-payment
                               provider {:amount-cents 1 :currency "EUR" :description "x"
                                         :provider-customer-id "cst_1"}))]
        (is (= :internal-error (:type data)))
        (is (= 422 (:status data)))))))

(deftest ^:integration expire-checkout-session-test
  (testing "cancels the open payment"
    (let [calls (atom [])]
      (with-redefs [http/delete (recording calls :delete (fn [_ _] (json-response 200 {:id "tr_1" :status "canceled"})))]
        (is (= {:provider-checkout-id "tr_1" :status :expired}
               (ports/expire-checkout-session provider "tr_1"))))
      (is (= "https://api.mollie.com/v2/payments/tr_1" (second (first @calls))))))

  (testing "a payment Mollie will not cancel throws"
    (with-redefs [http/delete (fn [& _] (json-response 422 {:detail "The payment cannot be canceled"}))]
      (is (= :internal-error (:type (ex-data-of #(ports/expire-checkout-session provider "tr_1"))))))))

(deftest ^:integration process-webhook-test
  (testing "a payment Mollie cannot be asked about throws, so the webhook is retried"
    (with-redefs [http/get (fn [& _] (json-response 503 {}))]
      (is (= :internal-error (:type (ex-data-of #(ports/process-webhook provider "id=tr_1" {})))))))

  (testing "a body without an id is a validation error"
    (is (= :validation-error (:type (ex-data-of #(ports/process-webhook provider "foo=bar" {}))))))

  (testing "an id Mollie does not know is acknowledged, not retried"
    (with-redefs [http/get (fn [& _] (json-response 404 {:title "Not Found"}))]
      (let [r (ports/process-webhook provider "id=tr_forged" {})]
        (is (nil? (:event-type r)))
        (is (= "tr_forged" (:provider-payment-id r))))))

  (testing "Ring's string-keyed :form-params work as the body"
    (with-redefs [http/get (fn [& _] (json-response 200 {:id "tr_1" :status "paid"}))]
      (is (= :payment.paid (:event-type (ports/process-webhook provider {"id" "tr_1"} {})))))))
