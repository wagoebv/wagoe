(ns wagoe.ai.shell.providers.ollama
  "Ollama AI provider adapter.

   Connects to a local Ollama instance (default: http://localhost:11434).
   No API key required — offline-first by design.

   Implements IAIProvider via the Ollama REST API:
   POST /api/chat   — used for all completions
   POST /api/generate — alternative for simple text generation"
  (:require [wagoe.ai.core.parsing :as parsing]
            [wagoe.ai.ports :as ports]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.tools.logging :as log]))

;; =============================================================================
;; HTTP helper
;; =============================================================================

(defn- chat-request!
  "POST to /api/chat and return the parsed JSON response.

   Args:
     base-url - Ollama base URL string
     model    - model name string
     messages - vector of {:role :content} maps
     opts     - map with :temperature :max-tokens

   Returns:
     Parsed JSON map or throws."
  [base-url model messages opts]
  (let [timeout  (or (:timeout opts) 120000)
        body     {:model    model
                  :messages (mapv (fn [{:keys [role content]}]
                                    {:role (name role) :content content})
                                  messages)
                  :stream   false
                  :options  (cond-> {}
                              (:temperature opts) (assoc :temperature (:temperature opts))
                              (:max-tokens opts)  (assoc :num_predict (:max-tokens opts)))}
        response (http/post (str base-url "/api/chat")
                            {:body             (json/generate-string body)
                             :content-type     :json
                             :as               :json
                             :socket-timeout   timeout
                             :connection-timeout 10000
                             :throw-exceptions true})]
    (:body response)))

;; =============================================================================
;; OllamaProvider record
;; =============================================================================

(defrecord OllamaProvider [base-url model timeout]
  ports/IAIProvider

  (complete [_ messages opts]
    (let [effective-model (or (:model opts) model "qwen2.5-coder:7b")]
      (try
        (log/debug "ollama complete" {:model effective-model :messages (count messages)})
        (let [resp  (chat-request! base-url effective-model messages
                             (update opts :timeout #(or % timeout)))
              text  (get-in resp [:message :content])
              tokens (get-in resp [:usage :total_tokens] 0)]
          {:text     text
           :tokens   tokens
           :base-url base-url
           :provider :ollama
           :model    effective-model})
        (catch Exception e
          (log/warn (str "ollama complete failed: " (.getMessage e))
                    {:model effective-model})
          {:error    (.getMessage e)
           ;; Status and body come from ex-data, not the message: a 429 for
           ;; rate-limiting and a 429 for an exhausted balance need opposite
           ;; advice, and (.getMessage e) is "clj-http: status 429" for both.
           ;; Truncated — the caller needs the error type, not the payload.
           :status   (:status (ex-data e))
           :body     (some-> (ex-data e) :body str (->> (take 300) (apply str)))
           :base-url base-url
           :provider :ollama
           :model    effective-model}))))

  (complete-json [this messages _schema opts]
    (let [json-hint {:role :system
                     :content "Respond with ONLY valid JSON. No explanation, no markdown fences."}
          msgs-with-hint (into [json-hint] messages)
          result (ports/complete this msgs-with-hint opts)]
      (if (:error result)
        result
        ;; The shared core parser, rather than a copy of it.
        (let [parsed (parsing/parse-json-response (:text result))]
          (if (or (nil? parsed) (:error parsed))
            (assoc result :error "Ollama response was not valid JSON" :raw (:text result))
            (assoc result :data parsed))))))

  (provider-name [_] :ollama))

;; =============================================================================
;; Factory
;; =============================================================================

(defn create-ollama-provider
  "Create an OllamaProvider.

   Args:
     config - map with:
       :base-url - Ollama base URL (default \"http://localhost:11434\")
       :model    - model name (default \"qwen2.5-coder:7b\")

   Returns:
     OllamaProvider record."
  [{:keys [base-url model timeout]}]
  (->OllamaProvider
   (or base-url "http://localhost:11434")
   (or model "qwen2.5-coder:7b")
   timeout))
