(ns wagoe.ai.shell.module-wiring-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [integrant.core :as ig]
            [wagoe.ai.shell.module-wiring]))

(deftest ^:unit a-missing-provider-names-the-key-and-the-choices
  ;; `{:enabled? true}` failed as "Unknown AI provider" with :provider nil,
  ;; which names neither the key nor what to set (BOU-564).
  (let [ex (try (ig/init-key :wagoe/ai-service {:enabled? true}) nil
                (catch clojure.lang.ExceptionInfo e e))]
    (is (= :configuration-error (:type (ex-data ex))))
    (is (str/includes? (str (ex-message ex)) ":wagoe/ai-service"))
    (is (str/includes? (str (ex-message ex)) ":ollama"))))
