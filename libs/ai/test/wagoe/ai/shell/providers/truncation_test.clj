(ns wagoe.ai.shell.providers.truncation-test
  "BOU-567: an answer the output limit stopped was reported as invalid EDN.
   Each provider says when it hit the limit, in its own field."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.test :refer [deftest is testing]]
            [wagoe.ai.ports :as ports]
            [wagoe.ai.shell.providers.anthropic :as anthropic]
            [wagoe.ai.shell.providers.ollama :as ollama]
            [wagoe.ai.shell.providers.openai :as openai]))

(def ^:private msgs [{:role :user :content "x"}])

(defn- run [provider body]
  (let [sent (atom nil)]
    (with-redefs [http/post (fn [_ opts]
                              (reset! sent (json/parse-string (:body opts) true))
                              {:body body})]
      (assoc (ports/complete provider msgs {}) ::sent @sent))))

(deftest ^:unit anthropic-reports-max-tokens
  (let [p (anthropic/create-anthropic-provider {:api-key "k"})]
    (is (true? (:truncated? (run p {:content [{:text "{:a"}] :stop_reason "max_tokens"}))))
    (is (not (:truncated? (run p {:content [{:text "{}"}] :stop_reason "end_turn"}))))
    (testing "the default budget fits a full entity config"
      (is (<= 8192 (:max_tokens (::sent (run p {:content [{:text "{}"}]}))))))))

(deftest ^:unit openai-reports-length
  (let [p (openai/create-openai-provider {:api-key "k"})]
    (is (true? (:truncated? (run p {:choices [{:message {:content "{:a"} :finish_reason "length"}]}))))
    (is (not (:truncated? (run p {:choices [{:message {:content "{}"} :finish_reason "stop"}]}))))))

(deftest ^:unit ollama-reports-length
  (let [p (ollama/create-ollama-provider {})]
    (is (true? (:truncated? (run p {:message {:content "{:a"} :done_reason "length"}))))
    (is (not (:truncated? (run p {:message {:content "{}"} :done_reason "stop"}))))
    (testing "a budget is sent, so an old Ollama's 128-token default does not apply"
      (is (<= 8192 (get-in (::sent (run p {:message {:content "{}"}})) [:options :num_predict]))))))

(deftest ^:unit an-exception-without-a-message-is-still-an-error
  ;; {:error nil} reads as success to every caller.
  (doseq [p [(anthropic/create-anthropic-provider {:api-key "k"})
             (openai/create-openai-provider {:api-key "k"})
             (ollama/create-ollama-provider {})]]
    (with-redefs [http/post (fn [_ _] (throw (NullPointerException.)))]
      (is (= "java.lang.NullPointerException" (:error (ports/complete p msgs {})))))))
