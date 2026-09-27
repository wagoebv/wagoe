(ns wagoe.ai.core.context-test
  (:require [wagoe.ai.core.context :as ctx]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest ^:unit extract-module-names-test
  (testing "extracts basenames from paths"
    (is (= ["core" "user"] (ctx/extract-module-names ["libs/core" "libs/user"]))))

  (testing "excludes the ai library itself"
    (is (= ["core" "user"] (ctx/extract-module-names ["libs/ai" "libs/core" "libs/user"]))))

  (testing "returns sorted result"
    (is (= ["a" "b" "c"] (ctx/extract-module-names ["libs/c" "libs/a" "libs/b"]))))

  (testing "returns empty seq for empty input"
    (is (= [] (ctx/extract-module-names [])))))

(deftest ^:unit extract-file-references-test
  (testing "extracts file:line refs from stack traces"
    (let [trace "at wagoe.user.core.validation/validate (validation.clj:42)\n   at wagoe.user.shell.service/create-user! (service.clj:15)"
          refs  (ctx/extract-file-references trace)]
      (is (= ["service.clj:15" "validation.clj:42"] refs))))

  (testing "returns empty for traces with no references"
    (is (= [] (ctx/extract-file-references "Exception in thread main java.lang.NPE"))))

  (testing "deduplicates repeated references"
    (let [trace "(validation.clj:10) (validation.clj:10)"
          refs  (ctx/extract-file-references trace)]
      (is (= 1 (count refs))))))

(deftest ^:unit summarise-stacktrace-test
  (testing "returns full trace when under limit"
    (let [trace "line1\nline2\nline3"]
      (is (= trace (ctx/summarise-stacktrace trace 10)))))

  (testing "truncates to n lines"
    (let [trace (str/join "\n" (map str (range 100)))]
      (is (= 60 (count (str/split-lines (ctx/summarise-stacktrace trace)))))))

  (testing "uses default of 60 lines"
    (let [trace (str/join "\n" (repeat 200 "x"))]
      (is (= 60 (count (str/split-lines (ctx/summarise-stacktrace trace))))))))

(deftest ^:unit extract-public-function-names-test
  (testing "extracts defn names"
    (let [src "(ns foo) (defn my-func [x] x) (defn other-fn [] nil)"]
      (is (= ["my-func" "other-fn"] (ctx/extract-public-function-names src)))))

  (testing "does not extract private defn-"
    (let [src "(defn- private-fn [] nil) (defn public-fn [] nil)"]
      (is (= ["public-fn"] (ctx/extract-public-function-names src))))))

(deftest ^:unit determine-test-type-test
  (testing "core/ paths become :unit"
    (is (= :unit (ctx/determine-test-type "libs/user/src/wagoe/user/core/validation.clj"))))

  (testing "adapters/ paths become :contract"
    (is (= :contract (ctx/determine-test-type "libs/geo/src/wagoe/geo/shell/adapters/osm.clj"))))

  (testing "shell/ paths become :integration"
    (is (= :integration (ctx/determine-test-type "libs/user/src/wagoe/user/shell/service.clj")))))

(deftest ^:unit derive-test-ns-test
  (testing "correctly derives test namespace"
    (is (= "wagoe.user.core.validation-test"
           (ctx/derive-test-ns "libs/user/src/wagoe/user/core/validation.clj"))))

  (testing "handles shell namespaces"
    (is (= "wagoe.user.shell.service-test"
           (ctx/derive-test-ns "libs/user/src/wagoe/user/shell/service.clj")))))

(deftest ^:unit derive-test-path-test
  (testing "a monorepo library source file lands in that library's test tree"
    (is (= "libs/user/test/wagoe/user/core/validation_test.clj"
           (ctx/derive-test-path "libs/user/src/wagoe/user/core/validation.clj"))))

  (testing "a generated project's top-level src/ maps to its top-level test/"
    (is (= "test/wagoe/order/core/order_test.clj"
           (ctx/derive-test-path "src/wagoe/order/core/order.clj"))))

  (testing "underscores in the source filename are preserved"
    (is (= "libs/core/test/wagoe/core/utils/case_conversion_test.clj"
           (ctx/derive-test-path "libs/core/src/wagoe/core/utils/case_conversion.clj"))))

  (testing "only the first src segment is rewritten"
    ;; A namespace segment named src further down the path is part of the
    ;; namespace, not the source root, and moving it would change the ns name.
    (is (= "libs/a/test/wagoe/a/src/handler_test.clj"
           (ctx/derive-test-path "libs/a/src/wagoe/a/src/handler.clj"))))

  (testing "a src substring that is not a path segment is left alone"
    (is (nil? (ctx/derive-test-path "libs/a/srcgen/wagoe/a/core/thing.clj"))))

  (testing "no src segment means no convention to apply"
    (is (nil? (ctx/derive-test-path "dev/wagoe/test/reporter.clj")))
    (is (nil? (ctx/derive-test-path nil)))))

(deftest ^:unit extract-schema-context-test
  (testing "extracts def names from schema files"
    (let [files {"schema.clj" "(def UserRecord ...) (def UserConfig ...)"}
          result (ctx/extract-schema-context files)]
      (is (str/includes? result "UserRecord"))
      (is (str/includes? result "UserConfig"))))

  (testing "returns nil for empty input"
    (is (nil? (ctx/extract-schema-context {})))))

(deftest ^:unit truncate-source-test
  (testing "does not truncate short sources"
    (let [src "line1\nline2"]
      (is (= src (ctx/truncate-source src 150)))))

  (testing "truncates long sources"
    (let [src (str/join "\n" (repeat 200 "x"))
          result (ctx/truncate-source src 10)]
      (is (str/includes? result "truncated"))
      (is (= 11 (count (str/split-lines result)))))))

;; BOU-572 -------------------------------------------------------------------

(deftest ^:unit ns-form-requires-test
  (let [form '(ns wagoe.demo.shell.service
               "doc"
               (:require [wagoe.workflow.ports :as wf]
                         [clojure.string :as str]
                         wagoe.events.ports
                         [wagoe.demo [schema :as schema] core]
                         [clojure.test :refer [deftest]])
               (:import (java.util UUID)))]
    (is (= '[wagoe.workflow.ports clojure.string wagoe.events.ports
             wagoe.demo.schema wagoe.demo.core clojure.test]
           (ctx/ns-form-requires form)))
    (is (= '{wagoe.workflow.ports wf clojure.string str wagoe.demo.schema schema}
           (ctx/ns-form-aliases form)))))

(deftest ^:unit render-namespace-apis-test
  (let [text (ctx/render-namespace-apis
              '{wagoe.workflow.ports wf}
              '[{:ns wagoe.workflow.ports
                 :protocols [{:name IWorkflowStore
                              :methods [{:name save-instance! :arglists ([this instance])}
                                        {:name find-instance :arglists ([this id])}]}]
                 :vars [{:name workflow-id :arglists ([instance])}]}
                {:ns wagoe.events.ports
                 :protocols [{:name IEventBus :methods [{:name publish! :arglists ([this event])}]}]}
                {:ns wagoe.gone :error "Could not locate wagoe/gone.clj"}])]
    (testing "protocols and vars are named the way the source refers to them"
      (is (str/includes? text "protocol wf/IWorkflowStore"))
      (is (str/includes? text "(save-instance! [this instance])"))
      (is (str/includes? text "(wf/workflow-id [instance])")))
    (testing "without an alias the name is fully qualified"
      (is (str/includes? text "protocol wagoe.events.ports/IEventBus")))
    (testing "a namespace that would not load is named as such"
      (is (str/includes? text "wagoe.gone"))
      (is (str/includes? text "Could not locate")))))
