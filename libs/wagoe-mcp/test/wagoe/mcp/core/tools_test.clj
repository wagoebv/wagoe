(ns wagoe.mcp.core.tools-test
  (:require [wagoe.mcp.core.tools :as tools]
            [clojure.test :refer [deftest is testing]]))

(deftest ^:unit catalog-has-tier0-tier1-and-tier2-tools
  (is (= 13 (count tools/catalog)))
  (is (= #{"explain-error" "lint" "validate-schema" "describe-module" "sql-preview"
           "scaffold-module" "add-field" "gen-tests" "gen-migration"
           "run-tests" "eval" "run-migration" "query-db"}
         tools/tool-names))
  (testing "Tier 0 :read, Tier 1 :generate, Tier 2 :execute"
    (is (= #{"explain-error" "lint" "validate-schema" "describe-module" "sql-preview"}
           (set (keep #(when (= :read (:capability %)) (:name %)) tools/catalog))))
    (is (= #{"scaffold-module" "add-field" "gen-tests" "gen-migration"}
           (set (keep #(when (= :generate (:capability %)) (:name %)) tools/catalog))))
    (is (= #{"run-tests" "eval" "run-migration" "query-db"}
           (set (keep #(when (= :execute (:capability %)) (:name %)) tools/catalog)))))
  (testing "every tool has wire fields and a known capability"
    (is (every? #(and (:name %) (:description %) (:inputSchema %)) tools/catalog))
    (is (every? #(#{:read :generate :execute} (:capability %)) tools/catalog)))
  (testing "inputSchema is JSON-Schema-shaped; :required is a (possibly empty) vector"
    (is (every? #(= "object" (get-in % [:inputSchema :type])) tools/catalog))
    (is (every? #(vector? (get-in % [:inputSchema :required])) tools/catalog))
    ;; run-migration is the only tool with no required args (direction defaults).
    (is (= #{"run-migration"}
           (set (keep #(when (empty? (get-in % [:inputSchema :required])) (:name %)) tools/catalog))))))

(deftest ^:unit capability-lookup
  (is (= :read (tools/capability "lint")))
  (is (= :generate (tools/capability "scaffold-module")))
  (is (= :execute (tools/capability "eval")))
  (is (nil? (tools/capability "nope"))))
