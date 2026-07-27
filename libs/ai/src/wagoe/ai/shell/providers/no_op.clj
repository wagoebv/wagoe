(ns wagoe.ai.shell.providers.no-op
  "No-op AI provider for tests and environments without AI.

   Returns deterministic canned responses — no network calls,
   no API keys needed. Used in test configs."
  (:require [wagoe.ai.ports :as ports]))

;; =============================================================================
;; NoOpProvider record
;; =============================================================================

(defrecord NoOpProvider [model]
  ports/IAIProvider

  (complete [_ messages opts]
    (let [effective-model (or (:model opts) model "no-op")]
      {:text     (str "no-op response for " (count messages) " message(s)")
       :tokens   0
       :provider :no-op
       :model    effective-model}))

  (complete-json [_ messages _schema opts]
    (let [effective-model (or (:model opts) model "no-op")]
      {:data     {:no-op true :message-count (count messages)}
       :tokens   0
       :provider :no-op
       :model    effective-model}))

  (provider-name [_] :no-op))

;; =============================================================================
;; Factory
;; =============================================================================

(defn create-no-op-provider
  "Create a NoOpProvider for use in tests.

   Returns:
     NoOpProvider record."
  ([] (create-no-op-provider {}))
  ([{:keys [model]}]
   (->NoOpProvider (or model "no-op"))))
