(ns wagoe.mcp.shell.tools-tier2-test
  "Tier 2 execute tools (BOU-102): run-tests, eval, run-migration, query-db.
   The real work is injected, so these tests stub the runner/evaluator/migrator/
   db-query and assert the result shape, the read-only/limit policy, and that
   every invocation is audited. The end-to-end gate test proves the catalog
   capability denies the tool in prod and allows it in dev."
  (:require [wagoe.mcp.core.security :as security]
            [wagoe.mcp.shell.audit :as audit]
            [wagoe.mcp.shell.dispatch :as dispatch]
            [wagoe.mcp.shell.evaluator :as evaluator]
            [wagoe.mcp.shell.tools :as tools]
            [clojure.test :refer [deftest is testing]]))

(defn- deps [extra]
  (merge {:audit (audit/in-memory-audit-log)} extra))

(defn- execute-events [d tool]
  (filter #(and (= :execute (:event %)) (= tool (:tool %)))
          (audit/events (:audit d))))

;; --- run-tests --------------------------------------------------------------

(deftest ^:unit run-tests-uses-injected-runner-and-audits
  (let [d (deps {:test-runner (fn [m] {:status :passed :passed 3 :failed 0 :ran m})})
        r (tools/run d "run-tests" {:module "user"})]
    (is (= :passed (:status r)))
    (is (= "user" (:module r)) "the module is echoed onto the report")
    (is (= "user" (:ran r)) "the injected runner received the module")
    (is (seq (execute-events d "run-tests")) "the execution is audited")))

(deftest ^:unit run-tests-without-runner-is-unavailable
  (is (= :unavailable (:status (tools/run (deps {}) "run-tests" {:module "user"})))))

;; --- eval -------------------------------------------------------------------

(deftest ^:unit eval-evaluates-and-audits
  (let [d (deps {:evaluator evaluator/default-evaluator})
        r (tools/run d "eval" {:code "(println \"hi\") (+ 1 2)"})]
    (is (= :ok (:status r)))
    (is (= "3" (:value r)) "the last form's value is returned")
    (is (= "hi" (:out r)) "stdout is captured")
    (is (seq (execute-events d "eval")))
    (is (some #(= "(println \"hi\") (+ 1 2)" (:code %)) (execute-events d "eval"))
        "the audited payload records the exact code")))

(deftest ^:unit eval-reports-errors-not-throws
  (let [r (tools/run (deps {:evaluator evaluator/default-evaluator}) "eval" {:code "(/ 1 0)"})]
    (is (= :error (:status r)))
    (is (string? (:error r)))))

(deftest ^:unit eval-without-evaluator-is-unavailable
  (is (= :unavailable (:status (tools/run (deps {}) "eval" {:code "(+ 1 1)"})))))

;; --- run-migration ----------------------------------------------------------

(deftest ^:unit run-migration-defaults-to-up-and-audits
  (let [d (deps {:migrator (fn [dir] {:status :ok :exit 0 :ran dir})})
        r (tools/run d "run-migration" {})]
    (is (= :ok (:status r)))
    (is (= "up" (:direction r)) "direction defaults to up")
    (is (= "up" (:ran r)))
    (is (some #(= "up" (:direction %)) (execute-events d "run-migration")))))

(deftest ^:unit run-migration-status-passes-through
  (let [r (tools/run (deps {:migrator (fn [dir] {:status :ok :ran dir})}) "run-migration" {:direction "status"})]
    (is (= "status" (:direction r)))))

(deftest ^:unit run-migration-rejects-unknown-direction-without-running-it
  (let [called (atom false)
        d      (deps {:migrator (fn [_] (reset! called true) {:status :ok})})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported migration direction"
                          (tools/run d "run-migration" {:direction "down"})))
    (is (false? @called) "an unknown direction is rejected before the migrator runs")))

(deftest ^:unit run-migration-without-migrator-is-unavailable
  (is (= :unavailable (:status (tools/run (deps {}) "run-migration" {:direction "up"})))))

;; --- query-db ---------------------------------------------------------------

(deftest ^:unit query-db-runs-read-only-query-with-limit
  (let [calls (atom [])
        d     (deps {:db-query (fn [sql n] (swap! calls conj [sql n]) (repeat 500 {:x 1}))})
        r     (tools/run d "query-db" {:sql "SELECT * FROM users" :limit 10})]
    (is (= :ok (:status r)))
    (is (= 10 (:limit r)))
    (is (= 10 (:row-count r)) "the result is truncated to the clamped limit")
    (is (= 10 (count (:rows r))))
    (is (= [["SELECT * FROM users" 10]] @calls) "the limit is passed to the datasource")
    (is (seq (execute-events d "query-db")))))

(deftest ^:unit query-db-defaults-limit
  (let [r (tools/run (deps {:db-query (fn [_ _] (repeat 1000 {:x 1}))}) "query-db" {:sql "SELECT 1"})]
    (is (= 100 (:limit r)) "no limit → default 100")
    (is (= 100 (:row-count r)))))

(deftest ^:unit query-db-refuses-writes-and-multiple-statements
  (let [d (deps {:db-query (fn [_ _] (throw (ex-info "should not run" {})))})]
    (testing "a write is refused before touching the datasource"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Refused query"
                            (tools/run d "query-db" {:sql "DELETE FROM users"}))))
    (testing "multiple statements are refused"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Refused query"
                            (tools/run d "query-db" {:sql "SELECT 1; DROP TABLE users"}))))
    (testing "a data-modifying CTE is refused even though it leads with WITH"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Refused query"
                            (tools/run d "query-db" {:sql "WITH x AS (DELETE FROM users RETURNING *) SELECT * FROM x"}))))))

(deftest ^:unit query-db-without-datasource-is-unavailable
  (let [r (tools/run (deps {}) "query-db" {:sql "SELECT 1"})]
    (is (= :unavailable (:status r)))
    (is (= 100 (:limit r)) "the clamped limit is still reported")))

;; --- end-to-end capability gate (acceptance: refuse in prod, run in dev) -----

(def ^:private dev  (security/resolve-context {"WAG_ENV" "dev"}))
(def ^:private prod (security/resolve-context {"WAG_ENV" "prod"}))

(defn- call [ctx]
  (dispatch/dispatch
   {:security ctx
    :audit    (audit/in-memory-audit-log)
    :test-runner (fn [m] {:status :passed :passed 1 :failed 0 :ran m})}
   {:jsonrpc "2.0" :id 1 :method "tools/call"
    :params  {:name "run-tests" :arguments {:module "user"}}}))

(deftest ^:unit execute-tool-denied-in-prod-allowed-in-dev
  (testing "prod (no-execute) refuses the Tier 2 tool with the tier-exceeded guardrail"
    (let [resp (call prod)]
      (is (= -32001 (get-in resp [:error :code])) "capability denial → app forbidden code")
      (is (= "BND-803" (get-in resp [:error :data :code])) "tier-exceeded")))
  (testing "dev (full) runs the Tier 2 tool"
    (let [resp (call dev)]
      (is (false? (get-in resp [:result :isError])))
      (is (nil? (:error resp))))))
