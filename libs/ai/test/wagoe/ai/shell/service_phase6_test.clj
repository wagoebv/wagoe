(ns wagoe.ai.shell.service-phase6-test
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.ai.shell.service :as svc]
            [wagoe.ai.ports :as ports]))

(defrecord TestProvider []
  ports/IAIProvider
  (complete [_ _messages _opts]
    {:text "Test review output" :tokens 10 :provider :test :model "test"})
  (complete-json [_ _messages _schema _opts]
    {:data {} :tokens 10 :provider :test :model "test"})
  (provider-name [_] :test))

(def test-service {:provider (->TestProvider) :fallback nil})

(deftest ^:unit review-code-returns-text
  (testing "review-code calls provider and returns result"
    (let [result (svc/review-code test-service
                                  "wagoe.user.core.validation"
                                  "(ns wagoe.user.core.validation)\n(defn validate [x] x)")]
      (is (:text result))
      (is (= :test (:provider result))))))

(deftest ^:unit suggest-tests-returns-text
  (testing "suggest-tests calls provider and returns result"
    (let [result (svc/suggest-tests test-service
                                    "wagoe.user.core.validation"
                                    "(ns wagoe.user.core.validation)\n(defn validate [x] x)"
                                    nil)]
      (is (:text result))
      (is (= :test (:provider result))))))

(deftest ^:unit refactor-fcis-returns-text
  (testing "refactor-fcis calls provider and returns result"
    (let [result (svc/refactor-fcis test-service
                                    "wagoe.product.core.validation"
                                    "(ns wagoe.product.core.validation)"
                                    [{:from "core.validation" :to "shell.persistence"}])]
      (is (:text result))
      (is (= :test (:provider result))))))
