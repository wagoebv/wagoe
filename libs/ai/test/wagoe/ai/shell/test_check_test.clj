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

(def ^:private lint-ctx
  {:root "." :filename "libs/ai/test/wagoe/ai/generated_good_test.clj"
   :context-files ["libs/ai/src/wagoe/ai/ports.clj"]})

(deftest ^:integration lint-errors-uses-the-projects-kondo
  ;; Runs `clojure -M:clj-kondo` from the repository root, as `bb check` does.
  (let [file (:filename lint-ctx)]
    (testing "a clean namespace has no findings"
      (is (nil? (sut/lint-errors good-ns lint-ctx))))
    (testing "a partial reify is a finding, as it is in bb check"
      (let [partial (str/replace good-ns "            (complete-json [_ _ _ _] nil)\n" "")
            errors  (sut/lint-errors partial lint-ctx)]
        (is (some #(str/includes? % "Missing protocol method") errors) (pr-str errors))
        (is (every? #(str/starts-with? % file) errors))))))

(deftest ^:integration an-invented-name-is-refused
  (testing "a protocol the required namespace does not define"
    (let [errors (sut/check-errors (str/replace good-ns "ports/IAIProvider" "ports/AIProviderStore")
                                   lint-ctx)]
      (is (some #(re-find #"error: Unresolved var: ports/AIProviderStore" %) errors) (pr-str errors))))
  (testing "an alias nothing binds"
    (let [errors (sut/check-errors (str/replace good-ns "(ports/provider-name p)" "(prov/provider-name p)")
                                   lint-ctx)]
      (is (some #(re-find #"error: Unresolved namespace prov" %) errors) (pr-str errors))))
  (testing "a namespace that is not on the classpath"
    (let [errors (sut/check-errors (str/replace good-ns "[wagoe.ai.ports :as ports]"
                                                "[wagoe.ai.ports :as ports]\n            [wagoe.ai.invented-store :as store]")
                                   lint-ctx)]
      (is (some #(str/includes? % "Requires wagoe.ai.invented-store, which is not on the classpath") errors)
          (pr-str errors)))))

(deftest ^:integration checking-runs-nothing-the-model-wrote
  ;; BOU-572 review: the check used to `load-string` the answer, so a
  ;; top-level form ran with the developer's environment before anyone read it.
  (let [marker (java.io.File/createTempFile "bou572" ".txt")
        path   (.getPath marker)
        hostile (str good-ns "\n(def pwned (spit " (pr-str path) " \"ran\"))\n")]
    (.delete marker)
    (sut/check-errors hostile lint-ctx)
    (is (not (.exists marker)))
    (is (nil? (find-ns 'wagoe.ai.generated-good-test)))))
