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

(defn- run
  ([provider body] (run provider body {}))
  ([provider body opts]
   (let [sent (atom nil)]
     (with-redefs [http/post (fn [_ opts]
                               (reset! sent (json/parse-string (:body opts) true))
                               {:body body})]
       (assoc (ports/complete provider msgs opts) ::sent @sent)))))

(deftest ^:unit anthropic-reports-max-tokens
  (let [p (anthropic/create-anthropic-provider {:api-key "k"})]
    (is (true? (:truncated? (run p {:content [{:text "{:a"}] :stop_reason "max_tokens"}))))
    (is (not (:truncated? (run p {:content [{:text "{}"}] :stop_reason "end_turn"}))))
    (testing "the general default is unchanged"
      (is (= 4096 (:max_tokens (::sent (run p {:content [{:text "{}"}]}))))))))

(deftest ^:unit anthropic-limits-fit-the-model
  ;; BOU-567 review: admin-entity asks for 8192, which Claude 3 models refuse.
  (let [sent (fn [model] (:max_tokens (::sent (run (anthropic/create-anthropic-provider
                                                    {:api-key "k" :model model})
                                                   {:content [{:text "{}"}]}
                                                   {:max-tokens 8192}))))]
    (is (= 4096 (sent "claude-3-haiku-20240307")))
    (is (= 4096 (sent "claude-3-opus-20240229")))
    (is (= 8192 (sent "claude-haiku-4-5-20251001")))))

(deftest ^:unit openai-sends-the-limit-each-model-reads
  ;; o-series and gpt-5 models reject max_tokens and require
  ;; max_completion_tokens; OpenAI-compatible servers only know max_tokens.
  (let [sent (fn [model] (::sent (run (openai/create-openai-provider {:api-key "k" :model model})
                                      {:choices [{:message {:content "{}"}}]}
                                      {:max-tokens 8192})))]
    (doseq [model ["o3-mini" "o1" "gpt-5" "gpt-5.2"]]
      (testing model
        (is (= 8192 (:max_completion_tokens (sent model))))
        (is (not (contains? (sent model) :max_tokens)))))
    (testing "chat models keep max_tokens, capped where the model caps it"
      (is (= 8192 (:max_tokens (sent "gpt-4o-mini"))))
      (is (= 4096 (:max_tokens (sent "gpt-3.5-turbo"))))
      (is (= 4096 (:max_tokens (sent "gpt-4-turbo")))))))

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
