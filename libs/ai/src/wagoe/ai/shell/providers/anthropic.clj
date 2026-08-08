(ns wagoe.ai.shell.providers.anthropic
  "Anthropic API provider adapter.

   Implements IAIProvider against the Anthropic Messages API.
   Requires an API key in the configuration.

   API docs: https://docs.anthropic.com/en/api/messages"
  (:require [wagoe.ai.core.parsing :as parsing]
            [wagoe.ai.ports :as ports]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.tools.logging :as log]))

;; =============================================================================
;; HTTP helper
;; =============================================================================

(defn- messages-request!
  "POST to Anthropic /v1/messages and return parsed JSON response.

   Args:
     api-key  - Anthropic API key string
     model    - model ID string
     messages - vector of {:role :content} maps
     opts     - map with :temperature :max-tokens

   Returns:
     Parsed JSON map or throws."
  [api-key model messages opts]
  (let [timeout     (or (:timeout opts) 60000)
        max-tokens  (or (:max-tokens opts) 4096)
        ;; Anthropic requires system messages separate from the messages array
        sys-msg     (first (filter #(= :system (:role %)) messages))
        user-msgs   (remove #(= :system (:role %)) messages)
        body        (cond-> {:model      model
                             :max_tokens max-tokens
                             :messages   (mapv (fn [{:keys [role content]}]
                                                 {:role (name role) :content content})
                                               user-msgs)}
                      (:temperature opts) (assoc :temperature (:temperature opts))
                      sys-msg             (assoc :system (:content sys-msg)))
        response    (http/post "https://api.anthropic.com/v1/messages"
                               {:body               (json/generate-string body)
                                :content-type       :json
                                :as                 :json
                                :headers            {"x-api-key"         api-key
                                                     "anthropic-version" "2023-06-01"}
                                :socket-timeout     timeout
                                :connection-timeout 10000
                                :throw-exceptions   true})]
    (:body response)))

;; =============================================================================
;; AnthropicProvider record
;; =============================================================================

(defrecord AnthropicProvider [api-key model]
  ports/IAIProvider

  (complete [_ messages opts]
    (let [effective-model (or (:model opts) model "claude-haiku-4-5-20251001")]
      (try
        (log/debug "anthropic complete" {:model effective-model :messages (count messages)})
        (let [resp   (messages-request! api-key effective-model messages opts)
              text   (get-in resp [:content 0 :text])
              tokens (get-in resp [:usage :output_tokens] 0)]
          {:text     text
           :tokens   tokens
           :provider :anthropic
           :model    effective-model})
        (catch Exception e
          (log/warn (str "anthropic complete failed: " (.getMessage e))
                    {:model effective-model})
          {:error    (.getMessage e)
           ;; Status and body come from ex-data, not the message: a 429 for
           ;; rate-limiting and a 429 for an exhausted balance need opposite
           ;; advice, and (.getMessage e) is "clj-http: status 429" for both.
           ;; Truncated — the caller needs the error type, not the payload.
           :status   (:status (ex-data e))
           :body     (some-> (ex-data e) :body str (->> (take 300) (apply str)))
           :provider :anthropic
           :model    effective-model}))))

  (complete-json [this messages _schema opts]
    (let [json-hint {:role :user
                     :content "\n\nRespond with ONLY valid JSON. No markdown fences, no explanation."}
          msgs-with-hint (conj (vec messages) json-hint)
          result (ports/complete this msgs-with-hint opts)]
      (if (:error result)
        result
        ;; The shared core parser, rather than a copy of it.
        (let [parsed (parsing/parse-json-response (:text result))]
          (if (or (nil? parsed) (:error parsed))
            (assoc result :error "Anthropic response was not valid JSON" :raw (:text result))
            (assoc result :data parsed))))))

  (provider-name [_] :anthropic))

;; =============================================================================
;; Factory
;; =============================================================================

(defn create-anthropic-provider
  "Create an AnthropicProvider.

   Args:
     config - map with:
       :api-key - Anthropic API key string (required)
       :model   - model ID (default \"claude-haiku-4-5-20251001\")

   Returns:
     AnthropicProvider record."
  [{:keys [api-key model]}]
  (->AnthropicProvider
   api-key
   (or model "claude-haiku-4-5-20251001")))
