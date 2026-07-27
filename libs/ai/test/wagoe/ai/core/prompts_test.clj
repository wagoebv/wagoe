(ns wagoe.ai.core.prompts-test
  (:require [wagoe.ai.core.prompts :as prompts]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest ^:unit build-scaffolding-system-prompt-test
  (testing "system prompt contains FC/IS framework context"
    (let [prompt (prompts/build-scaffolding-system-prompt)]
      (is (string? prompt))
      (is (str/includes? prompt "FC/IS"))
      (is (str/includes? prompt "kebab-case"))
      (is (str/includes? prompt "JSON")))))

(deftest ^:unit build-scaffolding-user-prompt-test
  (testing "user prompt includes description"
    (let [prompt (prompts/build-scaffolding-user-prompt "a product module" [])]
      (is (str/includes? prompt "product module"))))

  (testing "user prompt includes existing modules when provided"
    (let [prompt (prompts/build-scaffolding-user-prompt "product" ["user" "order"])]
      (is (str/includes? prompt "user"))
      (is (str/includes? prompt "order"))))

  (testing "user prompt omits module context when empty"
    (let [prompt (prompts/build-scaffolding-user-prompt "product" [])]
      (is (not (str/includes? prompt "Existing modules"))))))

(deftest ^:unit scaffolding-messages-test
  (testing "returns two messages with correct roles"
    (let [msgs (prompts/scaffolding-messages "product module" ["user"])]
      (is (= 2 (count msgs)))
      (is (= :system (:role (first msgs))))
      (is (= :user   (:role (second msgs)))))))

(deftest ^:unit error-explainer-messages-test
  (testing "returns system + user messages"
    (let [msgs (prompts/error-explainer-messages "ExceptionInfo: ..." {})]
      (is (= 2 (count msgs)))
      (is (= :system (:role (first msgs))))))

  (testing "user message contains the stack trace"
    (let [trace "clojure.lang.ExceptionInfo: validation failed"
          msgs  (prompts/error-explainer-messages trace {})]
      (is (str/includes? (:content (second msgs)) trace)))))

(deftest ^:unit test-generator-messages-test
  (testing "includes source file path and type in user message"
    (let [msgs (prompts/test-generator-messages "libs/user/src/core/v.clj" "(ns foo)" :unit)]
      (is (str/includes? (:content (second msgs)) "libs/user/src/core/v.clj"))
      (is (str/includes? (:content (second msgs)) "unit")))))

(deftest ^:unit sql-copilot-messages-test
  (testing "description is included in user message"
    (let [msgs (prompts/sql-copilot-messages "find active users" nil)]
      (is (str/includes? (:content (second msgs)) "active users"))))

  (testing "schema context is appended when provided"
    (let [msgs (prompts/sql-copilot-messages "find users" "users: id, email")]
      (is (str/includes? (:content (second msgs)) "users: id, email")))))

(deftest ^:unit docs-messages-test
  (testing "agents type uses AGENTS.md prompt"
    (let [msgs (prompts/docs-messages "libs/user" {} :agents)]
      (is (str/includes? (:content (first msgs)) "AGENTS.md"))))

  (testing "openapi type uses OpenAPI prompt"
    (let [msgs (prompts/docs-messages "libs/user" {} :openapi)]
      (is (str/includes? (:content (first msgs)) "OpenAPI"))))

  (testing "readme type uses README prompt"
    (let [msgs (prompts/docs-messages "libs/user" {} :readme)]
      (is (str/includes? (:content (first msgs)) "README")))))

(deftest ^:unit build-docs-system-prompt-agents-ports-test
  (testing "agents prompt instructs documenting the ports/protocols convention (BOU-80)"
    (let [prompt (prompts/build-docs-system-prompt :agents)]
      (is (str/includes? prompt "Ports & Protocols"))
      (is (str/includes? prompt "ports.clj")))))
