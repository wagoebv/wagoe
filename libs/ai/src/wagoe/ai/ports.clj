(ns wagoe.ai.ports
  "Protocol definitions for the AI module.

   FC/IS rule: protocols are interfaces — no implementation here.
   Adapters (shell/providers) implement these protocols.")

;; =============================================================================
;; IAIProvider — the single AI provider contract
;; =============================================================================

(defprotocol IAIProvider
  "Contract for AI provider adapters.

   Implementations live in shell/providers/:
   - OllamaProvider     (Ollama, local/offline, no API key)
   - AnthropicProvider  (Anthropic API, requires api-key)
   - OpenAIProvider     (OpenAI API, requires api-key)
   - NoOpProvider       (test stub — returns deterministic canned responses)"

  (complete [this messages opts]
    "Text completion.

     Args:
       messages - vector of {:role :user/:system/:assistant :content str}
       opts     - map with optional :model :temperature :max-tokens

     Returns:
       {:text str :tokens int :provider kw :model str}
       or {:error str :provider kw :model str} on failure.")

  (complete-json [this messages schema opts]
    "Structured JSON completion.

     Args:
       messages - vector of {:role :user/:system/:assistant :content str}
       schema   - description string hinting the expected JSON shape (informational)
       opts     - map with optional :model :temperature :max-tokens

     Returns:
       {:data map :tokens int :provider kw :model str}
       or {:error str :provider kw :model str} on failure.")

  (provider-name [this]
    "Return the provider keyword (:ollama, :anthropic, :openai, :no-op)."))
