(ns wagoe.realtime.shell.module-wiring-test
  "Verifies the :boundary/realtime Integrant key is registered and boots a
   working service. Guards against the regression where the defmethod was never
   loaded by the platform bootstrap. Uses the :in-memory provider — no Redis."
  {:kaocha.testable/meta {:integration true :realtime true}}
  (:require [clojure.test :refer [deftest testing is]]
            [integrant.core :as ig]
            [wagoe.realtime.ports :as ports]
            [wagoe.realtime.shell.adapters.jwt-adapter :as jwt]
            ;; Loading this namespace must register the init/halt defmethods.
            [wagoe.realtime.shell.module-wiring]))

(deftest ^:unit init-key-registered-test
  (testing ":boundary/realtime has registered Integrant init and halt methods"
    (is (contains? (methods ig/init-key) :boundary/realtime)
        "init-key defmethod must be registered (else system startup fails with 'no method for key')")
    (is (contains? (methods ig/halt-key!) :boundary/realtime)
        "halt-key! defmethod must be registered")))

(deftest ^:integration in-memory-init-halt-roundtrip-test
  (testing "in-memory provider boots a working service and halts cleanly"
    (let [jwt-verifier (jwt/create-test-jwt-adapter
                        {:expected-token "t"
                         :user-id (java.util.UUID/randomUUID)
                         :email "a@example.com"
                         :roles #{:user}})
          component (ig/init-key :boundary/realtime
                                 {:provider :in-memory
                                  :jwt-verifier jwt-verifier})]
      (try
        (is (some? (:service component)) "component exposes :service")
        (is (some? (:bus component)) "component exposes :bus")
        (testing "broadcast with no connections returns 0 (synchronous in-memory)"
          (is (= 0 (ports/broadcast (:service component) {:type :x :payload {}}))))
        (finally
          (ig/halt-key! :boundary/realtime component))))))
