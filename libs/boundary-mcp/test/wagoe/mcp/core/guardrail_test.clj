(ns wagoe.mcp.core.guardrail-test
  (:require [wagoe.mcp.core.guardrail :as gr]
            [wagoe.mcp.core.protocol :as proto]
            [wagoe.mcp.core.security :as sec]
            [clojure.test :refer [deftest is testing]]))

(def ^:private prod (sec/resolve-context {"BND_ENV" "prod"}))

(deftest ^:unit from-denial-maps-violation-to-code
  (testing "tier-exceeded denial → BND-803, hard (not overridable)"
    (let [denial (sec/authorize prod {:name "eval" :capability :execute})
          desc   (gr/from-denial denial)]
      (is (= "BND-803" (:code desc)))
      (is (= :tier-exceeded (:rule desc)))
      (is (false? (:overridable? desc)))
      (is (= {:tool "eval" :capability :execute :mode :no-execute} (:context desc)))))
  (testing "unmapped violation → BND-800 fallback"
    (is (= "BND-800" (:code (gr/from-denial {:violation :mystery :reason "x"}))))))

(deftest ^:unit build-uses-catalog-text-when-present
  (let [desc  (gr/from-denial (sec/authorize prod {:name "eval" :capability :execute}))
        entry {:code "BND-803" :title "Capability Tier Exceeded"
               :description "Tool tier exceeds the ceiling." :fix "Run in dev."}
        p     (gr/build desc entry)]
    (is (= "Capability Tier Exceeded" (:rule p)))
    (is (= "Tool tier exceeds the ceiling." (:principle p)))
    (is (= "Run in dev." (:fix p)))
    (is (false? (:overridable? p)))
    (is (not (contains? p :override)))
    (is (= {:tool "eval" :capability :execute :mode :no-execute} (:details p)))))

(deftest ^:unit build-falls-back-without-catalog-entry
  (let [desc (gr/from-denial (sec/authorize prod {:name "eval" :capability :execute}))
        p    (gr/build desc nil)]
    (is (= "tier-exceeded" (:rule p)))          ;; rule name, not catalog title
    (is (string? (:principle p)))               ;; falls back to denial reason
    (is (nil? (:fix p)))))

(deftest ^:unit overridable-guardrail-carries-override-descriptor
  (testing "BND-806 (FC/IS codegen) is overridable and describes the bypass"
    (is (gr/overridable? "BND-806"))
    (let [p (gr/build {:code "BND-806" :overridable? true} nil)]
      (is (true? (:overridable? p)))
      (is (= :allow (get-in p [:override :flag])))
      (is (re-find #"audited" (get-in p [:override :how])))))
  (testing "security codes are not overridable"
    (is (not (gr/overridable? "BND-803")))))

(deftest ^:unit jsonrpc-error-uses-forbidden-code-and-carries-payload
  (let [p    (gr/build (gr/from-denial (sec/authorize prod {:name "eval" :capability :execute})) nil)
        resp (gr/->jsonrpc-error 9 p)]
    (is (= 9 (:id resp)))
    (is (= (:forbidden proto/error-codes) (get-in resp [:error :code])))
    (is (= -32001 (get-in resp [:error :code])))
    (is (= p (get-in resp [:error :data])))))

(deftest ^:unit override-request-detection-and-event
  (is (gr/override-requested? {:allow true}))
  (is (not (gr/override-requested? {:allow false})))
  (is (not (gr/override-requested? {})))
  (let [ev (gr/override-event {:code "BND-806" :rule "FC/IS Boundary Guardrail"
                               :principle "..."}
                              {:tool "scaffold-module"})]
    (is (= :guardrail-override (:event ev)))
    (is (= "BND-806" (:code ev)))
    (is (= "scaffold-module" (:tool ev)))))
