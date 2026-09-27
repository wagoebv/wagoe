(ns wagoe.payments.shell.module-wiring
  "Integrant wiring for the :wagoe/payment-provider component."
  (:require [wagoe.payments.shell.adapters.mock :as mock]
            [wagoe.payments.shell.adapters.mollie :as mollie]
            [wagoe.payments.shell.adapters.stripe :as stripe]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

;; :wagoe/payment-provider
;; config: {:provider :mock|:mollie|:stripe   ; required; :mock in dev/test only
;;           :api-key "..."            ; mollie or stripe secret key
;;           :webhook-secret "..."     ; stripe only
;;           :webhook-base-url "..."}  ; mollie: base URL for webhook registration

(defn- blank? [v]
  (or (nil? v) (and (string? v) (str/blank? v))))

(defn- validate-credentials!
  "Fail boot when a required credential resolves to nil/blank — typically a
   forgotten env var (Aero `#env` yields nil), which would otherwise ship a
   payment system that silently takes no money (Stripe 401 on first charge,
   HMAC verification against a nil secret on first webhook).

   `required` is an ordered seq of [config-key ENV_VAR] pairs; `config` is the
   component config map. Throws ex-info {:type :configuration-error} naming every
   missing key and its env var."
  [provider required config]
  (when-let [missing (seq (filter (fn [[k _env]] (blank? (get config k))) required))]
    (throw (ex-info (format "%s payment provider configured but %s nil/blank — set %s (see resources/conf/<env>/config.edn)"
                            (str/capitalize (name provider))
                            (str/join ", " (map (comp str first) missing))
                            (str/join ", " (map second missing)))
                    {:type         :configuration-error
                     :provider     provider
                     :missing-keys (mapv first missing)
                     :env-vars     (mapv second missing)}))))

(defn- no-provider! []
  (throw (ex-info (str ":wagoe/payment-provider has no :provider. Set :stripe or :mollie;"
                       " :mock only in the dev and test profiles.")
                  {:type :configuration-error :key :wagoe/payment-provider})))

(defn ig-config
  "The module's graph: its settings as they are, once they pass. There is no
   default provider, and the mock, which accepts any webhook as paid, runs only
   under the dev and test profiles (BOU-564)."
  [settings ctx]
  (let [provider (:provider settings)
        profile  (get-in ctx [:config :wagoe/profile])]
    (cond
      (nil? provider) (no-provider!)

      (and (= :mock provider) (not (#{:dev :test} profile)))
      (throw (ex-info (str ":wagoe/payment-provider is :mock under the " (pr-str profile)
                           " profile. The mock accepts any webhook as paid, so it runs"
                           " only in dev and test; set :stripe or :mollie.")
                      {:type :configuration-error :key :wagoe/payment-provider
                       :provider :mock :profile profile}))

      :else {:components {:wagoe/payment-provider settings}})))

(defmethod ig/init-key :wagoe/payment-provider
  [_ {:keys [provider api-key webhook-secret webhook-base-url] :as config}]
  (log/infof "Initializing payment provider: %s" provider)
  (when (nil? provider) (no-provider!))
  (case provider
    :mock   (do (log/info "Using Mock payment provider (development mode)")
                (mock/make-mock-provider))
    :mollie (do (validate-credentials! :mollie [[:api-key "MOLLIE_API_KEY"]] config)
                (log/info "Using Mollie payment provider")
                (mollie/->MolliePaymentProvider api-key webhook-base-url))
    :stripe (do (validate-credentials! :stripe [[:api-key "STRIPE_API_KEY"]
                                                [:webhook-secret "STRIPE_WEBHOOK_SECRET"]] config)
                (log/info "Using Stripe payment provider")
                (stripe/->StripePaymentProvider api-key webhook-secret))
    (throw (ex-info "Unknown payment provider"
                    {:type     :unknown-provider
                     :provider provider
                     :valid    #{:mock :mollie :stripe}}))))

(defmethod ig/halt-key! :wagoe/payment-provider
  [_ _]
  (log/info "Payment provider halted"))
