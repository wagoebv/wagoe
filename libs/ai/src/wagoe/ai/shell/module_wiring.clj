(ns wagoe.ai.shell.module-wiring
  "Integrant wiring for the AI module.

   Config key: :wagoe/ai-service

   Example (Ollama, offline-first):
     :wagoe/ai-service
     {:provider :ollama
      :model    \"qwen2.5-coder:7b\"
      :base-url \"http://localhost:11434\"}

   Example (Anthropic):
     :wagoe/ai-service
     {:provider :anthropic
      :model    \"claude-haiku-4-5-20251001\"
      :api-key  #env ANTHROPIC_API_KEY}

   Example (Ollama with Anthropic fallback):
     :wagoe/ai-service
     {:provider :ollama
      :model    \"qwen2.5-coder:7b\"
      :fallback {:provider :anthropic
                 :model    \"claude-haiku-4-5-20251001\"
                 :api-key  #env ANTHROPIC_API_KEY}}

   Example (no-op, for tests):
     :wagoe/ai-service
     {:provider :no-op}"
  (:require [wagoe.ai.shell.providers.anthropic :as anthropic]
            [wagoe.ai.shell.providers.no-op :as no-op]
            [wagoe.ai.shell.providers.ollama :as ollama]
            [wagoe.ai.shell.providers.openai :as openai]
            [wagoe.ai.shell.providers.replicate :as replicate-provider]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

;; =============================================================================
;; Provider construction
;; =============================================================================

(defn- build-provider
  "Construct an IAIProvider from a configuration map.

   Args:
     config - map with at least :provider keyword

   Returns:
     IAIProvider implementation."
  [{:keys [provider] :as config}]
  (case provider
    :ollama    (ollama/create-ollama-provider config)
    :anthropic (anthropic/create-anthropic-provider config)
    :openai    (openai/create-openai-provider config)
    :no-op     (no-op/create-no-op-provider config)
    :replicate (replicate-provider/create-replicate-provider config)
    (throw (ex-info "Unknown AI provider" {:type :configuration-error :provider provider}))))

;; =============================================================================
;; Integrant lifecycle
;; =============================================================================

(defmethod ig/init-key :wagoe/ai-service
  [_ {:keys [provider fallback] :as config}]
  (log/info "Initializing AI service" {:provider provider})
  (let [primary-provider  (build-provider config)
        fallback-provider (when fallback (build-provider fallback))]
    (log/info "AI service initialized"
              {:provider provider :fallback? (boolean fallback-provider)})
    {:provider primary-provider
     :fallback fallback-provider}))

(defmethod ig/halt-key! :wagoe/ai-service
  [_ _]
  (log/info "Halting AI service")
  nil)
