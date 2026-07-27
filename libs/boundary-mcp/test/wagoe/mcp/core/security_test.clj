(ns wagoe.mcp.core.security-test
  (:require [wagoe.mcp.core.security :as sec]
            [clojure.test :refer [deftest is testing]]))

;; --- context resolution + precedence ---------------------------------------

(deftest ^:unit resolve-from-bnd-env
  (testing "dev → full"
    (let [c (sec/resolve-context {"BND_ENV" "dev"})]
      (is (= :full (:mode c)))
      (is (= :execute (:max-tier c)))
      (is (false? (:read-only? c)))
      (is (= :bnd-env (:source c)))))
  (testing "prod → no-execute (Tier 2 denied, writes still allowed)"
    (let [c (sec/resolve-context {"BND_ENV" "production"})]
      (is (= :no-execute (:mode c)))
      (is (= :generate (:max-tier c)))))
  (testing "test → full"
    (is (= :full (:mode (sec/resolve-context {"BND_ENV" "test"}))))))

(deftest ^:unit ci-overrides-bnd-env
  (testing "CI forces read-only even when BND_ENV=dev"
    (let [c (sec/resolve-context {"BND_ENV" "dev" "CI" "true"})]
      (is (= :read-only (:mode c)))
      (is (true? (:read-only? c)))
      (is (= :ci (:source c)))))
  (testing "CI=false is not truthy"
    (is (= :full (:mode (sec/resolve-context {"BND_ENV" "dev" "CI" "false"}))))))

(deftest ^:unit explicit-override-wins
  (testing "MCP_CAPABILITY_MODE beats CI and BND_ENV"
    (let [c (sec/resolve-context {"BND_ENV" "prod" "CI" "true"
                                  "MCP_CAPABILITY_MODE" "full"})]
      (is (= :full (:mode c)))
      (is (= :override (:source c)))))
  (testing "disabled override"
    (is (= :disabled (:mode (sec/resolve-context {"MCP_CAPABILITY_MODE" "off"}))))))

(deftest ^:unit malformed-override-is-ignored-with-warning
  (testing "garbage override falls through to inferred mode, not full"
    (let [c (sec/resolve-context {"MCP_CAPABILITY_MODE" "yolo" "BND_ENV" "prod"})]
      (is (= :no-execute (:mode c)))
      (is (= :bnd-env (:source c)))
      (is (seq (:warnings c)))
      (is (re-find #"yolo" (first (:warnings c))))))
  (testing "valid override produces no warnings"
    (is (empty? (:warnings (sec/resolve-context {"MCP_CAPABILITY_MODE" "full"}))))))

(deftest ^:unit fail-closed-default
  (testing "no env signal → read-only, not full"
    (let [c (sec/resolve-context {})]
      (is (= :read-only (:mode c)))
      (is (= :fail-closed (:source c))))))

;; --- authorization ----------------------------------------------------------

(def ^:private dev   (sec/resolve-context {"BND_ENV" "dev"}))
(def ^:private prod  (sec/resolve-context {"BND_ENV" "prod"}))
(def ^:private ci    (sec/resolve-context {"CI" "1"}))

(defn- tool [name capability] {:name name :capability capability})

(deftest ^:unit tier-ceiling
  (testing "dev allows every tier"
    (is (sec/permit? dev (tool "lint" :read)))
    (is (sec/permit? dev (tool "scaffold" :generate)))
    (is (sec/permit? dev (tool "eval" :execute))))
  (testing "prod denies execute, allows read + generate"
    (is (sec/permit? prod (tool "lint" :read)))
    (is (sec/permit? prod (tool "scaffold" :generate)))
    (is (not (sec/permit? prod (tool "eval" :execute)))))
  (testing "ci (read-only) allows only read"
    (is (sec/permit? ci (tool "lint" :read)))
    (is (not (sec/permit? ci (tool "scaffold" :generate))))
    (is (not (sec/permit? ci (tool "eval" :execute))))))

(deftest ^:unit denial-carries-reason
  (let [d (sec/authorize prod (tool "eval" :execute))]
    (is (false? (:allow? d)))
    (is (= "eval" (:tool d)))
    (is (string? (:reason d)))))

(deftest ^:unit unknown-capability-fails-closed
  (is (not (sec/permit? dev (tool "weird" :delete-everything))))
  (is (re-find #"Unknown capability" (:reason (sec/authorize dev (tool "weird" :nope))))))

(deftest ^:unit disabled-context-denies-all
  (let [off (sec/resolve-context {"MCP_CAPABILITY_MODE" "disabled"})]
    (is (not (sec/permit? off (tool "lint" :read))))))

(deftest ^:unit allowlist-restricts
  (let [c (sec/with-allowlist dev #{"lint"})]
    (is (sec/permit? c (tool "lint" :read)))
    (is (not (sec/permit? c (tool "describe-module" :read))))
    (is (re-find #"allowlist" (:reason (sec/authorize c (tool "describe-module" :read)))))))

(deftest ^:unit empty-allowlist-denies-all
  (let [c (sec/with-allowlist dev #{})]
    (is (not (sec/permit? c (tool "lint" :read))))
    (is (not (sec/permit? c (tool "anything" :read))))))

(deftest ^:unit describe-is-loggable-and-secret-free
  (let [d (sec/describe dev)]
    (is (= :full (:mode d)))
    (is (= :all (:allowlist d)))
    (is (= #{:mode :source :env :ci? :max-tier :read-only? :disabled? :allowlist}
           (set (keys d))))))
