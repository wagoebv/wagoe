(ns wagoe.ai.shell.test-check-test
  "BOU-572: gen-tests wrote a namespace that did not load and did not lint."
  (:require [wagoe.ai.shell.test-check :as sut]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest ^:unit namespace-api-reads-the-real-protocols
  (let [{:keys [protocols vars error]} (sut/namespace-api 'wagoe.ai.ports)
        provider (first (filter #(= 'IAIProvider (:name %)) protocols))]
    (is (nil? error))
    (is (= '#{complete complete-json provider-name} (set (map :name (:methods provider)))))
    (is (= '([this messages opts]) (:arglists (first (filter #(= 'complete (:name %)) (:methods provider))))))
    (testing "protocol methods are not listed again as vars"
      (is (not-any? #(= 'complete (:name %)) vars))))
  (testing "a namespace that does not exist is reported, not thrown"
    (is (string? (:error (sut/namespace-api 'wagoe.no-such-namespace))))))

(def ^:private good-ns
  "(ns wagoe.ai.generated-good-test
  (:require [clojure.test :refer [deftest is]]
            [wagoe.ai.ports :as ports]))

(deftest ^:unit a-test
  (let [p (reify ports/IAIProvider
            (complete [_ _ _] {:text \"x\"})
            (complete-json [_ _ _ _] nil)
            (provider-name [_] :stub))]
    (is (= :stub (ports/provider-name p)))))
")

(deftest ^:unit compile-errors-loads-the-namespace
  (testing "a namespace that loads has none, and leaves nothing behind"
    (is (nil? (sut/compile-errors good-ns)))
    (is (nil? (find-ns 'wagoe.ai.generated-good-test))))

  (testing "an invented protocol is a compile error that names it"
    (let [errors (sut/compile-errors
                  (str/replace good-ns "ports/IAIProvider" "ports/AIProviderStore"))]
      (is (some #(str/includes? % "AIProviderStore") errors) (pr-str errors)))))

(deftest ^:integration lint-errors-uses-the-projects-kondo
  ;; Runs `clojure -M:clj-kondo` from the repository root, as `bb check` does.
  (let [file "libs/ai/test/wagoe/ai/generated_good_test.clj"
        ctx  {:root "." :filename file :context-files ["libs/ai/src/wagoe/ai/ports.clj"]}]
    (testing "a clean namespace has no findings"
      (is (nil? (sut/lint-errors good-ns ctx))))
    (testing "a partial reify is a finding, as it is in bb check"
      (let [partial (str/replace good-ns "            (complete-json [_ _ _ _] nil)\n" "")
            errors  (sut/lint-errors partial ctx)]
        (is (some #(str/includes? % "Missing protocol method") errors) (pr-str errors))
        (is (every? #(str/starts-with? % file) errors))))))
