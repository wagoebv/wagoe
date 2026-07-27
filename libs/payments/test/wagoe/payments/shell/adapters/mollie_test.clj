(ns wagoe.payments.shell.adapters.mollie-test
  "Integration tests for the Mollie payment provider adapter.
   All tests run without network access."
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.payments.ports :as ports]
            [wagoe.payments.shell.adapters.mollie :as mollie]))

(def ^:private provider
  (mollie/->MolliePaymentProvider "test_api_key" "https://example.com"))

;; =============================================================================
;; new port methods — not implemented until BOU-63
;; =============================================================================

(deftest ^:integration not-implemented-stubs-test
  (testing "create-off-session-payment throws a clear :not-implemented error"
    (let [ex (try
               (ports/create-off-session-payment
                provider
                {:amount-cents         100
                 :currency             "EUR"
                 :description          "Recurring"
                 :provider-customer-id "cst_abc"})
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (= :not-implemented (:type (ex-data ex))))
      (is (= :mollie (:provider (ex-data ex))))))

  (testing "expire-checkout-session throws a clear :not-implemented error"
    (let [ex (try
               (ports/expire-checkout-session provider "tr_abc")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (= :not-implemented (:type (ex-data ex))))
      (is (= :mollie (:provider (ex-data ex))))))

  (testing "create-checkout-session with :setup-future-usage throws instead of silently ignoring it"
    (let [ex (try
               (ports/create-checkout-session
                provider
                {:amount-cents       100
                 :currency           "EUR"
                 :description        "First installment"
                 :redirect-url       "https://example.com/done"
                 :setup-future-usage :off-session})
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (= :not-implemented (:type (ex-data ex))))
      (is (= :mollie (:provider (ex-data ex))))
      (is (= :off-session (:setup-future-usage (ex-data ex)))))))
