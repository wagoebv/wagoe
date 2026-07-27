(ns wagoe.mcp.shell.guardrail-test
  (:require [wagoe.mcp.core.security :as sec]
            [wagoe.mcp.shell.guardrail :as guardrail]
            [clojure.test :refer [deftest is testing]]))

(def ^:private prod (sec/resolve-context {"WAG_ENV" "prod"}))
(def ^:private ci   (sec/resolve-context {"CI" "1"}))

(deftest ^:unit payload-enriched-from-real-catalog
  (testing "tier-exceeded denial is enriched with the BND-803 catalog entry"
    (let [p (guardrail/payload-for-denial
             (sec/authorize prod {:name "eval" :capability :execute}))]
      (is (= "BND-803" (:code p)))
      (is (= "Capability Tier Exceeded" (:rule p)))
      (is (string? (:principle p)))   ;; from catalog :description
      (is (string? (:fix p)))         ;; from catalog :fix
      (is (false? (:overridable? p)))))
  (testing "CI blocks a generate tool via the tier ceiling → BND-803"
    (let [p (guardrail/payload-for-denial
             (sec/authorize ci {:name "scaffold" :capability :generate}))]
      (is (= "BND-803" (:code p)))))
  (testing "a read-only-clamped context (ceiling above :read) → BND-804"
    ;; read-only? clamp fires only when the tier would otherwise be allowed.
    (let [clamped (assoc (sec/resolve-context {"WAG_ENV" "prod"}) :read-only? true)
          p       (guardrail/payload-for-denial
                   (sec/authorize clamped {:name "scaffold" :capability :generate}))]
      (is (= "BND-804" (:code p))))))

(deftest ^:unit error-for-denial-is-forbidden-jsonrpc
  (let [resp (guardrail/error-for-denial 5 (sec/authorize prod {:name "eval" :capability :execute}))]
    (is (= 5 (:id resp)))
    (is (= -32001 (get-in resp [:error :code])))
    (is (= "BND-803" (get-in resp [:error :data :code])))))

(deftest ^:unit unmapped-violation-resolves-to-bnd-800-entry
  (let [p (guardrail/payload-for-denial {:allow? false :violation :mystery
                                         :tool "x" :reason "weird"})]
    (is (= "BND-800" (:code p)))
    (is (= "Guardrail Triggered" (:rule p)))  ;; from real catalog entry
    (is (string? (:fix p)))))

(deftest ^:unit codegen-guardrail-payload-is-overridable
  (let [p (guardrail/payload-for-code "BND-806" {:context {:tool "scaffold-module"}})]
    (is (= "BND-806" (:code p)))
    (is (= "FC/IS Boundary Guardrail" (:rule p)))
    (is (true? (:overridable? p)))
    (is (= :allow (get-in p [:override :flag])))
    (is (= {:tool "scaffold-module"} (:details p)))))
