(ns wagoe.payments.schema-property-test
  "Property test: every value malli generates from a payments schema validates
   against that same schema. Catches schemas that cannot generate cleanly
   (e.g. a regex/enum whose generator drifts from the validator) — a real
   regression guard, not a tautology, for the constrained schemas below."
  (:require [wagoe.payments.schema :as schema]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [malli.core :as m]
            [malli.generator :as mg]))

(defn- generates-valid
  [s]
  (prop/for-all [v (mg/generator s)]
                (m/validate s v)))

(defspec ^:property checkout-request-generates-valid 100
  (generates-valid schema/CheckoutRequest))

(defspec ^:property checkout-result-generates-valid 100
  (generates-valid schema/CheckoutResult))

(defspec ^:property webhook-result-generates-valid 100
  (generates-valid schema/WebhookResult))

(defspec ^:property off-session-request-generates-valid 100
  (generates-valid schema/OffSessionPaymentRequest))
