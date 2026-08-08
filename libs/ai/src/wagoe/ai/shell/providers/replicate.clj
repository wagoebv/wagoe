(ns wagoe.ai.shell.providers.replicate
  "Replicate provider adapter.

   Implements IAIProvider against Replicate's predictions API, which hosts many
   models — including Anthropic and Google ones — behind a single token.

   Replicate has no OpenAI-compatible endpoint (`/v1/chat/completions` is a
   404), so it cannot be driven by the openai adapter. Two further differences
   shape this namespace:

   - The documented flow is asynchronous: create a prediction, poll until it
     succeeds. The `Prefer: wait` header makes it synchronous, which is all
     Wagoe needs and avoids a polling loop entirely.
   - `output` comes back as a list of string chunks rather than a string.

   Input constraints are per-model rather than per-API. `anthropic/claude-4.5-haiku`
   rejects `max_tokens` below 1024 with a 422 naming the field, so the failure
   is reported with that detail instead of a bare status."
  (:require [wagoe.ai.core.parsing :as parsing]
            [wagoe.ai.ports :as ports]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

(def ^:private default-base-url "https://api.replicate.com")

(def default-model
  "Small, fast, and good at instruction-following — the qualities that matter
   for scaffolding and SQL generation."
  "anthropic/claude-4.5-haiku")

;; =============================================================================
;; Request/response shaping
;; =============================================================================

(defn messages->input
  "Wagoe's message vector as Replicate's flat input map.

   Replicate takes a single `prompt` plus an optional `system_prompt`, not a
   conversation. System messages are joined into `system_prompt` and the rest
   into `prompt`, preserving order within each — flattening both into one
   string would lose the distinction the models are tuned on.

   Pure, so the shaping is testable without a token."
  [messages]
  (let [system? (comp #{:system "system"} :role)
        text    (fn [ms] (->> ms (map :content) (remove nil?) (str/join "\n\n")))
        systems (filter system? messages)
        rest'   (remove system? messages)]
    (cond-> {:prompt (text rest')}
      (seq systems) (assoc :system_prompt (text systems)))))

(defn output->text
  "Replicate's `output` as a string.

   It is a list of chunks — `[\"\\n\\n\" \"OK\"]` — and printing that verbatim
   would show a vector to the user. A string is passed through so a model that
   returns one still works."
  [output]
  (cond
    (string? output)     output
    (sequential? output) (str/join output)
    (nil? output)        nil
    :else                (str output)))

(defn failure-detail
  "The useful part of a Replicate error body, or nil.

   A 422 names the field it rejected — `input.max_tokens: Must be greater than
   or equal to 1024` — which is worth surfacing, because the constraint varies
   per model and is not something the caller could have known from the API.

   Takes a map as readily as a string. The request uses `:as :json`, and while
   clj-http's default `:coerce :unexceptional` leaves error bodies as strings —
   measured against a real 422 — that is a default, not a guarantee. Running
   `(str m)` over a map and parsing the result as JSON yields nil, which would
   silently drop the detail this exists to surface."
  [body]
  (let [parsed (cond
                 (map? body) body
                 (nil? body) nil
                 :else       (try (json/parse-string (str body) true)
                                  (catch Exception _ nil)))]
    (some-> (or (:detail parsed) (:title parsed)) str str/trim not-empty)))

;; =============================================================================
;; HTTP
;; =============================================================================

(defn- prediction-request!
  "POST a prediction and return the parsed body, waiting for it to finish."
  [base-url api-token model input timeout]
  (let [url  (str (str/replace (or base-url default-base-url) #"/+$" "")
                  "/v1/models/" model "/predictions")
        resp (http/post url
                        {:body               (json/generate-string {:input input})
                         :content-type       :json
                         :as                 :json
                         :headers            {"Authorization" (str "Bearer " api-token)
                                              ;; Synchronous, so no polling loop.
                                              "Prefer"        "wait"}
                         :socket-timeout     timeout
                         :connection-timeout 10000
                         :throw-exceptions   true})]
    (:body resp)))

;; =============================================================================
;; Provider
;; =============================================================================

(defrecord ReplicateProvider [base-url api-key model]
  ports/IAIProvider

  (complete [_ messages opts]
    (let [effective-model (or (:model opts) model default-model)
          input (cond-> (messages->input messages)
                  (:max-tokens opts)  (assoc :max_tokens (:max-tokens opts))
                  (:temperature opts) (assoc :temperature (:temperature opts)))]
      (try
        (log/debug "replicate complete" {:model effective-model :messages (count messages)})
        (let [resp   (prediction-request! base-url api-key effective-model input
                                          (or (:timeout opts) 60000))
              status (:status resp)]
          (if (= "succeeded" status)
            {:text     (output->text (:output resp))
             ;; Replicate reports no token usage on this endpoint.
             :tokens   0
             :provider :replicate
             :model    effective-model}
            ;; A prediction can fail after a 2xx — the HTTP call succeeded, the
            ;; run did not. Without this the caller would read :text as nil and
            ;; report a parse failure for what is a provider error.
            {:error    (or (some-> (:error resp) str)
                           (str "Replicate prediction " (or status "did not succeed")))
             :provider :replicate
             :model    effective-model}))
        (catch Exception e
          (log/warn (str "replicate complete failed: " (.getMessage e))
                    {:model effective-model})
          (let [data (ex-data e)]
            {:error    (or (failure-detail (:body data)) (.getMessage e))
             ;; Status and body come from ex-data, not the message, so the
             ;; caller can tell a rejected token from an exhausted balance.
             :status   (:status data)
             :body     (some-> (:body data) str (->> (take 300) (apply str)))
             :provider :replicate
             :model    effective-model})))))

  (complete-json [this messages _schema opts]
    ;; Replicate has no JSON mode, so the instruction is the only lever and
    ;; models wrap the object in prose or fences anyway. Extraction is the
    ;; shared core parser rather than a fourth inline copy — the three existing
    ;; providers each reimplement it, and none strip ```json fences the way the
    ;; shared one does.
    (let [hint   {:role :system
                  :content "Respond with ONLY valid JSON. No explanation, no markdown fences."}
          result (ports/complete this (into [hint] messages) opts)]
      (if (:error result)
        result
        (let [parsed (parsing/parse-json-response (:text result))]
          (if (or (nil? parsed) (:error parsed))
            (assoc result :error "Replicate response was not valid JSON"
                   :raw (:text result))
            (assoc result :data parsed))))))

  (provider-name [_] :replicate))

(defn create-replicate-provider
  "Build a ReplicateProvider.

   Config:
     :api-key  - Replicate API token (required)
     :model    - owner/name, e.g. anthropic/claude-4.5-haiku
     :base-url - override, for a proxy or a test double"
  [{:keys [base-url api-key model]}]
  (->ReplicateProvider (or base-url default-base-url)
                       api-key
                       (or model default-model)))
