(ns wagoe.mcp.shell.dispatch-test
  (:require [wagoe.mcp.core.registry :as registry]
            [wagoe.mcp.core.resources :as resources]
            [wagoe.mcp.core.security :as security]
            [wagoe.mcp.core.tools :as tools]
            [wagoe.mcp.shell.audit :as audit]
            [wagoe.mcp.shell.codec :as codec]
            [wagoe.mcp.shell.dispatch :as dispatch]
            [wagoe.mcp.shell.system-source :as system-source]
            [clojure.test :refer [deftest is testing]]))

(def ^:private snapshot
  {:conventions {:fc-is {:rules ["core pure"]}}})

(defn- deps
  ([] (deps (security/resolve-context {"BND_ENV" "dev"})))
  ([ctx]
   {:registry      (as-> registry/empty-registry r
                     (reduce registry/register-resource r resources/catalog)
                     (reduce registry/register-tool r tools/catalog))
    :security      ctx
    :audit         (audit/in-memory-audit-log)
    :system-source (system-source/static-system-source snapshot)
    :ai-provider   nil}))

(deftest ^:unit resources-list-advertises-catalog
  (let [resp (dispatch/dispatch (deps) {:jsonrpc "2.0" :id 1 :method "resources/list"})]
    (is (= 7 (count (get-in resp [:result :resources]))))
    (is (some #(= "boundary://conventions" (:uri %)) (get-in resp [:result :resources])))))

(deftest ^:unit resources-read-returns-json-content
  (let [d    (deps)
        resp (dispatch/dispatch d {:jsonrpc "2.0" :id 2 :method "resources/read"
                                   :params {:uri "boundary://conventions"}})
        content (first (get-in resp [:result :contents]))]
    (is (= "boundary://conventions" (:uri content)))
    (is (= "application/json" (:mimeType content)))
    ;; the content text is the JSON-encoded resource data (the conventions value)
    (is (= (:conventions snapshot) (codec/decode (:text content))))
    (testing "a successful read is audited"
      (is (some #(= :resource-read (:event %)) (audit/events (:audit d)))))))

(deftest ^:unit resources-read-unknown-uri-is-invalid-params
  (let [resp (dispatch/dispatch (deps) {:jsonrpc "2.0" :id 3 :method "resources/read"
                                        :params {:uri "boundary://nope"}})]
    (is (= -32602 (get-in resp [:error :code])))))

(deftest ^:unit resources-read-denied-in-disabled-context-yields-guardrail
  (let [d    (deps (security/resolve-context {"MCP_CAPABILITY_MODE" "disabled"}))
        resp (dispatch/dispatch d {:jsonrpc "2.0" :id 4 :method "resources/read"
                                   :params {:uri "boundary://conventions"}})]
    (is (= -32001 (get-in resp [:error :code])))            ;; :forbidden
    (is (= "BND-801" (get-in resp [:error :data :code])))   ;; capabilities disabled
    (testing "the denial is audited"
      (is (some #(= :resource-read-denied (:event %)) (audit/events (:audit d)))))))

(deftest ^:unit dispatch-delegates-pure-methods-to-core
  (let [resp (dispatch/dispatch (deps) {:jsonrpc "2.0" :id 5 :method "ping"})]
    (is (= {} (:result resp)))))

(deftest ^:unit generate-tool-denied-in-read-only-context
  ;; Tier 1 (:generate) tools are a hard capability gate: a read-only context
  ;; (CI / fail-closed) caps the ceiling at :read, so :generate is denied
  ;; before any codegen runs (BND-803, capability tier exceeded).
  (let [d    (deps (security/resolve-context {"CI" "true"}))
        resp (dispatch/dispatch d {:jsonrpc "2.0" :id 7 :method "tools/call"
                                   :params {:name "scaffold-module" :arguments {:module "x" :entities []}}})]
    (is (= -32001 (get-in resp [:error :code])))
    (is (= "BND-803" (get-in resp [:error :data :code])))
    (is (some #(= :tool-call-denied (:event %)) (audit/events (:audit d))))))

(deftest ^:unit tools-list-advertises-catalog
  (let [resp (dispatch/dispatch (deps) {:jsonrpc "2.0" :id 1 :method "tools/list"})]
    (is (= 13 (count (get-in resp [:result :tools]))))
    (is (some #(= "validate-schema" (:name %)) (get-in resp [:result :tools])))
    (is (some #(= "scaffold-module" (:name %)) (get-in resp [:result :tools])))
    (is (some #(= "query-db" (:name %)) (get-in resp [:result :tools])))))

(deftest ^:unit tools-call-returns-tool-result
  (let [d    (deps)
        resp (dispatch/dispatch d {:jsonrpc "2.0" :id 2 :method "tools/call"
                                   :params {:name "validate-schema"
                                            :arguments {:schema "[:map [:name :string]]"
                                                        :value {:name "Ada"}}}})
        content (first (get-in resp [:result :content]))]
    (is (false? (get-in resp [:result :isError])))
    (is (= "text" (:type content)))
    (is (= {:valid? true} (codec/decode (:text content))))
    (testing "the call is audited"
      (is (some #(= :tool-call (:event %)) (audit/events (:audit d)))))))

(deftest ^:unit tools-call-unknown-tool-is-invalid-params
  (let [resp (dispatch/dispatch (deps) {:jsonrpc "2.0" :id 3 :method "tools/call"
                                        :params {:name "nope" :arguments {}}})]
    (is (= -32602 (get-in resp [:error :code])))))

(deftest ^:unit tools-call-denied-in-disabled-context-yields-guardrail
  (let [d    (deps (security/resolve-context {"MCP_CAPABILITY_MODE" "disabled"}))
        resp (dispatch/dispatch d {:jsonrpc "2.0" :id 4 :method "tools/call"
                                   :params {:name "validate-schema" :arguments {}}})]
    (is (= -32001 (get-in resp [:error :code])))
    (is (= "BND-801" (get-in resp [:error :data :code])))
    (is (some #(= :tool-call-denied (:event %)) (audit/events (:audit d))))))

(deftest ^:unit tools-call-executor-error-is-iserror-result
  (let [d    (deps)
        resp (dispatch/dispatch d {:jsonrpc "2.0" :id 6 :method "tools/call"
                                   :params {:name "validate-schema"
                                            :arguments {:schema "this is not edn [[[" :value 1}}})]
    (is (true? (get-in resp [:result :isError])))
    (is (contains? (codec/decode (get-in resp [:result :content 0 :text])) :error))
    (is (some #(= :tool-call-error (:event %)) (audit/events (:audit d))))))
