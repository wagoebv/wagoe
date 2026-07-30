(ns wagoe.test-support.wiring-test
  "Profile-level assertions for the :test/reset-endpoint-enabled? flag.

   These tests guard two things:
     1. The :test profile opts into the reset endpoint (so Playwright works).
     2. The :prod profile does NOT opt in — this is the production safety
        net that backs the startup assertion in wiring.clj."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.config :as config]))

(deftest ^:unit reset-endpoint-enabled-in-test-profile
  (testing ":test profile has :test/reset-endpoint-enabled? true"
    (let [cfg (config/load-config {:profile :test})]
      (is (true? (:test/reset-endpoint-enabled? cfg)))
      (is (= :test (:wagoe/profile cfg))))))

(deftest ^:unit reset-endpoint-disabled-in-prod
  (testing ":prod profile does not enable the reset flag"
    (let [cfg (config/load-config {:profile :prod})]
      (is (not (true? (:test/reset-endpoint-enabled? cfg))))
      (is (= :prod (:wagoe/profile cfg))))))
