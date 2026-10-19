(ns wagoe.ai.shell.test-check-test
  "BOU-572: gen-tests wrote a namespace that did not load and did not lint."
  (:require [wagoe.ai.shell.cli-entry :as cli]
            [wagoe.ai.shell.test-check :as sut]
            [wagoe.ai.shell.test-runner :as runner]
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
  (testing "a schema var carries its form, and is not listed again as a var"
    (let [{:keys [schemas vars]} (sut/namespace-api 'wagoe.ai.schema)]
      (is (= [:map [:role [:enum :system :user :assistant]] [:content :string]]
             (:form (first (filter #(= 'Message (:name %)) schemas)))))
      (is (not-any? #(= 'Message (:name %)) vars))
      (is (some #(= 'valid-message? (:name %)) vars) "functions stay vars")))
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

(deftest ^:integration the-answer-runs-only-in-a-child-without-the-environment
  ;; BOU-572 review: the check used to `load-string` the answer, so a
  ;; top-level form ran in this process, API key and all. BOU-511 runs it, but
  ;; in a child JVM with an empty environment.
  (let [marker  (java.io.File/createTempFile "bou572" ".txt")
        hostile (str good-ns "\n(def path (spit " (pr-str (.getPath marker)) " (pr-str (System/getenv \"PATH\"))))\n")]
    (try
      (sut/check-errors hostile lint-ctx)
      (is (some? (System/getenv "PATH")) "precondition")
      (is (= "nil" (slurp marker)))
      (is (nil? (find-ns 'wagoe.ai.generated-good-test)))
      (finally (.delete marker)))))

;; BOU-511 -------------------------------------------------------------------

(defn- generated [fixture assertion]
  (str "(ns wagoe.ai.generated-run-test
  (:require [clojure.test :refer [deftest is]]
            [wagoe.ai.schema :as schema]))

(def ^{:schema schema/Message} message " fixture ")

(deftest ^:unit a-test
  " assertion ")
"))

(defn- run-in-process
  "The child's checks, run here. Only for answers that never reach the tests:
   a nested run-tests would report into kaocha's counters."
  [text ns-sym]
  (let [f (java.io.File/createTempFile "gen" ".clj")]
    (try (spit f text) (runner/errors (.getPath f) ns-sym)
         (finally (.delete f)))))

(deftest ^:integration a-passing-namespace-runs-clean
  (is (nil? (sut/run-errors (generated "{:role :user :content \"hi\"}" "(is (= :user (:role message)))") {}))))

(deftest ^:integration fixture-data-that-breaks-its-schema-is-refused
  (let [errors (run-in-process (generated "{:role :user :content 42}" "(is (= :user (:role message)))")
                               'wagoe.ai.generated-run-test)]
    (is (some #(and (str/includes? % "Fixture message does not match its :schema")
                    (str/includes? % ":content"))
              errors)
        (pr-str errors))))

(deftest ^:integration a-failing-generated-test-is-not-written
  (let [errors (sut/check-errors (generated "{:role :user :content \"hi\"}" "(is (= :admin (:role message)))")
                                 (assoc lint-ctx :filename "libs/ai/test/wagoe/ai/generated_run_test.clj"))]
    (is (some #(str/includes? % "Running it: 1 failures, 0 errors") errors) (pr-str errors))
    (is (= :refuse (cli/gen-tests-outcome {:text "" :check-errors errors} "test/x_test.clj" false)))))

(deftest ^:integration a-namespace-that-does-not-load-is-reported
  (is (some #(str/starts-with? % "It does not load")
            (run-in-process "(ns wagoe.ai.generated-broken-test)\n(deftest a (is (= 1 (nope))))"
                            'wagoe.ai.generated-broken-test))))
