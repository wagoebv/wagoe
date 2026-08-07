(ns wagoe.ai.shell.providers.openai
  "OpenAI API provider adapter.

   Implements IAIProvider against the OpenAI Chat Completions API.
   Requires an API key in the configuration."
  (:require [wagoe.ai.ports :as ports]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

;; =============================================================================
;; HTTP helper
;; =============================================================================

(defn- chat-completion-request!
  "POST to /v1/chat/completions and return parsed JSON response.

   Args:
     base-url - API base URL string. Accepted forms:
                  \"https://api.openai.com\"      (bare host)
                  \"https://api.openai.com/\"     (trailing slash)
                  \"https://api.openai.com/v1\"   (with /v1)
                  \"https://api.openai.com/v1/\"  (with /v1/)
                Trailing /v1 and /v1/ are stripped before appending
                /v1/chat/completions. base-url must not contain /v1
                in a non-terminal position (e.g. http://host/v1/openai
                would produce an incorrect double-segment URL).
     api-key  - API key string
     model    - model ID string
     messages - vector of {:role :content} maps
     opts     - map with :temperature :max-tokens

   Returns:
     Parsed JSON map or throws."
  [base-url api-key model messages opts]
  (let [timeout    (or (:timeout opts) 60000)
        url        (-> base-url
                       (str/replace #"/v1/?$" "")
                       (str/replace #"/$" "")
                       (str "/v1/chat/completions"))
        body       (cond-> {:model    model
                            :messages (mapv (fn [{:keys [role content]}]
                                              {:role (name role) :content content})
                                            messages)}
                     (:temperature opts) (assoc :temperature (:temperature opts))
                     (:response-format opts) (assoc :response_format (:response-format opts))
                     (:max-tokens opts)  (assoc :max_tokens (:max-tokens opts)))
        response   (http/post url
                              {:body               (json/generate-string body)
                               :content-type       :json
                               :as                 :json
                               :headers            {"Authorization" (str "Bearer " api-key)}
                               :socket-timeout     timeout
                               :connection-timeout 10000
                               :throw-exceptions   true})]
    (:body response)))

;; =============================================================================
;; OpenAIProvider record
;; =============================================================================

(defrecord OpenAIProvider [base-url api-key model]
  ports/IAIProvider

  (complete [_ messages opts]
    (let [effective-model (or (:model opts) model "gpt-4o-mini")]
      (try
        (log/debug "openai complete" {:model effective-model :messages (count messages)})
        (let [resp   (chat-completion-request! base-url api-key effective-model messages opts)
              text   (get-in resp [:choices 0 :message :content])
              tokens (get-in resp [:usage :total_tokens] 0)]
          {:text     text
           :tokens   tokens
           :provider :openai
           :model    effective-model})
        (catch Exception e
          (log/warn (str "openai complete failed: " (.getMessage e))
                    {:model effective-model})
          {:error    (.getMessage e)
           :provider :openai
           :model    effective-model}))))

  (complete-json [this messages _schema opts]
    (let [json-opts (assoc opts :response-format {:type "json_object"})
          result    (ports/complete this messages json-opts)]
      (if (:error result)
        result
        (let [parsed (try
                       (json/parse-string (:text result) true)
                       (catch Exception _
                         (let [json-str (re-find #"(?s)\{.*\}" (:text result))]
                           (when json-str
                             (try (json/parse-string json-str true)
                                  (catch Exception _ nil))))))]
          (if parsed
            (assoc result :data parsed)
            (assoc result :error "OpenAI response was not valid JSON" :raw (:text result)))))))

  (provider-name [_] :openai))

;; =============================================================================
;; Factory
;; =============================================================================

(defn create-openai-provider
  "Create an OpenAIProvider.

   Args:
     config - map with:
       :base-url - API base URL (default \"https://api.openai.com\")
       :api-key  - API key string (required)
       :model    - model ID (default \"gpt-4o-mini\")

   Returns:
     OpenAIProvider record."
  [{:keys [base-url api-key model]}]
  (->OpenAIProvider
   (or base-url "https://api.openai.com")
   api-key
   (or model "gpt-4o-mini")))
