(ns wagoe.ai.shell.providers.no-op-test
  (:require [wagoe.ai.ports :as ports]
            [wagoe.ai.shell.providers.no-op :as no-op]
            [clojure.test :refer [deftest is testing]]))

(deftest ^:integration no-op-provider-complete-json-test
  (testing "complete-json satisfies protocol arity and returns deterministic payload"
    (let [provider (no-op/create-no-op-provider {:model "no-op-model"})
          result   (ports/complete-json provider [{:role :user :content "hello"}] "ModuleSpec" {})]
      (is (= :no-op (:provider result)))
      (is (= "no-op-model" (:model result)))
      (is (= {:no-op true :message-count 1} (:data result))))))
