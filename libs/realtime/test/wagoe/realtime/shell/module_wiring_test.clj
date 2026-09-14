(ns wagoe.realtime.shell.module-wiring-test
  "Verifies the :wagoe/realtime Integrant key is registered and boots a
   working service. Guards against the regression where the defmethod was never
   loaded by the platform bootstrap. Uses the :in-memory provider — no Redis."
  {:kaocha.testable/meta {:integration true :realtime true}}
  (:require [clojure.test :refer [deftest testing is]]
            [integrant.core :as ig]
            [wagoe.realtime.ports :as ports]
            [wagoe.realtime.shell.adapters.jwt-adapter :as jwt]
            ;; Loading this namespace must register the init/halt defmethods.
            [wagoe.realtime.shell.module-wiring :as wiring]))

(deftest ^:unit init-key-registered-test
  (testing ":wagoe/realtime has registered Integrant init and halt methods"
    (is (contains? (methods ig/init-key) :wagoe/realtime)
        "init-key defmethod must be registered (else system startup fails with 'no method for key')")
    (is (contains? (methods ig/halt-key!) :wagoe/realtime)
        "halt-key! defmethod must be registered")))

(deftest ^:integration in-memory-init-halt-roundtrip-test
  (testing "in-memory provider boots a working service and halts cleanly"
    (let [jwt-verifier (jwt/create-test-jwt-adapter
                        {:expected-token "t"
                         :user-id (java.util.UUID/randomUUID)
                         :email "a@example.com"
                         :roles #{:user}})
          component (ig/init-key :wagoe/realtime
                                 {:provider :in-memory
                                  :jwt-verifier jwt-verifier})]
      (try
        (is (some? (:service component)) "component exposes :service")
        (is (some? (:bus component)) "component exposes :bus")
        (testing "broadcast with no connections returns 0 (synchronous in-memory)"
          (is (= 0 (ports/broadcast (:service component) {:type :x :payload {}}))))
        (finally
          (ig/halt-key! :wagoe/realtime component))))))

(deftest ^:unit the-provider-vocabulary-is-shared
  ;; `:in-memory` here, `:memory` in jobs, `:redis-streams` in events — the same
  ;; choice spelled three ways (BOU-436). The test above still passes
  ;; `:in-memory`, which is how the old spelling stays exercised.
  (testing "the canonical spellings, the old one, and the default"
    (is (= :memory (wiring/normalize-provider :memory)))
    (is (= :redis (wiring/normalize-provider :redis)))
    (is (= :memory (wiring/normalize-provider :in-memory)))
    (is (= :memory (wiring/normalize-provider nil))))

  (testing "an unrecognised provider throws instead of building a node-local bus"
    ;; It used to fall through to the in-memory arm, so a replica configured for
    ;; Redis ran a bus its siblings could not see.
    (let [e (try (ig/init-key :wagoe/realtime {:provider :rediss})
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo e))
      (is (= :unknown-provider (:type (ex-data e))))
      (is (= [:memory :redis] (:known (ex-data e)))))))
