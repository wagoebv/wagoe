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

(deftest ^:unit the-advertised-interfaces-are-the-ones-scaffold-module-forwards
  ;; The schema is the contract a client reads before it calls. It advertised
  ;; `cli`, which the scaffolder had no generator for and BOU-479 removed, and
  ;; said all interfaces "default false" while the generator defaults both to
  ;; true — so a client that trusted it expected a module with neither
  ;; interface and got both (BOU-484 review).
  (let [schema (->> tools/catalog
                    (filter #(= "scaffold-module" (:name %)))
                    first
                    :inputSchema)
        advertised (set (keys (get-in schema [:properties "interfaces" :properties])))]
    (is (= #{"http" "web"} advertised)
        "the schema advertises an interface the scaffolder does not generate")

    (testing "and the description does not claim a default the generator contradicts"
      (let [described (get-in schema [:properties "interfaces" :description])]
        (is (not (re-find #"(?i)default\s+false" described))
            "absent means every interface, not none")))))

(deftest ^:unit capability-lookup
  (is (= :read (tools/capability "lint")))
  (is (= :generate (tools/capability "scaffold-module")))
  (is (= :execute (tools/capability "eval")))
  (is (nil? (tools/capability "nope"))))
