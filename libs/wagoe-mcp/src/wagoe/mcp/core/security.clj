(ns wagoe.mcp.core.security
  "Pure capability/context gating for the MCP server (BOU-97 threat model).

   The question this answers: an agent may be pointed at a live REPL with a
   production database. What is it allowed to do? Every tool declares a
   capability tier; the active context grants a ceiling derived from the
   environment. Authorization is a pure decision here; the shell enforces it
   and audits the outcome.

   See ADR-031 for the threat model and rationale."
  (:require [clojure.string :as str]))

;; --- Capability tiers -------------------------------------------------------
;; Ordered least → most dangerous. Every tool declares exactly one.
;;   :read     Tier 0 — read/analyze, zero mutation (lint, explain, describe)
;;   :generate Tier 1 — write to disk, reversible via git (scaffold, gen-tests)
;;   :execute  Tier 2 — RCE surface (eval, run-tests, migrations, db queries)
(def tiers [:read :generate :execute])

(def ^:private tier-rank (zipmap tiers (range)))

(defn tier-allowed?
  "Is `capability` within the `max-tier` ceiling? Fail-closed: unknown tiers,
   or a nil ceiling, deny."
  [max-tier capability]
  (let [c (tier-rank capability)
        m (tier-rank max-tier)]
    (boolean (and c m (<= c m)))))

;; --- Modes → policy ---------------------------------------------------------
;; A mode is the resolved security posture. Policy is the ceiling it grants.
(def modes
  {:full       {:max-tier :execute  :read-only? false}  ;; local dev
   :no-execute {:max-tier :generate :read-only? false}  ;; prod: Tier 2 denied
   :read-only  {:max-tier :read     :read-only? true}   ;; CI / unknown
   :disabled   {:max-tier nil       :read-only? true :disabled? true}})

;; Environment → default mode. The CI flag (detected separately) overrides this
;; to :read-only regardless of BND_ENV; an explicit MCP override beats both.
(def ^:private env->mode
  {:dev  :full
   :test :full
   :prod :no-execute
   :ci   :read-only})

;; --- Environment parsing (pure; the shell supplies the env map) -------------
(def override-var "MCP_CAPABILITY_MODE")
(def bnd-env-var  "BND_ENV")
(def ci-var       "CI")

(defn- normalise [s] (some-> s str/trim str/lower-case))

(defn- parse-mode [s]
  (get {"full"       :full
        "no-execute" :no-execute
        "read-only"  :read-only
        "readonly"   :read-only
        "disabled"   :disabled
        "off"        :disabled}
       (normalise s)))

(defn- parse-env [s]
  (get {"dev"         :dev
        "development" :dev
        "test"        :test
        "prod"        :prod
        "production"  :prod
        "ci"          :ci}
       (normalise s)))

(defn- truthy-env? [s]
  (let [v (normalise s)]
    (boolean (and v (not (#{"" "false" "0" "no"} v))))))

(defn resolve-context
  "Pure: derive the active security context from an environment map
   (String -> String). Precedence — explicit MCP override > CI detection >
   BND_ENV > fail-closed default (:read-only).

   Returns a context map: {:mode :source :env :ci? :allowlist + policy keys}.
   `:allowlist` defaults to :all (see `with-allowlist`)."
  [env-map]
  (let [raw-override (get env-map override-var)
        override     (parse-mode raw-override)
        bnd          (parse-env (get env-map bnd-env-var))
        ci?          (truthy-env? (get env-map ci-var))
        [mode source]
        (cond
          override [override :override]
          ci?      [:read-only :ci]
          bnd      [(env->mode bnd) :bnd-env]
          :else    [:read-only :fail-closed])
        ;; A present-but-unrecognized override is fail-safe (we ignore it), but
        ;; silent ignore hides operator error — surface it for the shell to log.
        warnings (cond-> []
                   (and (some? raw-override) (nil? override))
                   (conj (format "Ignored unrecognized %s=%s; resolved to %s (%s)"
                                 override-var (pr-str raw-override)
                                 (name mode) (name source))))]
    (merge {:mode mode :source source :env bnd :ci? ci? :allowlist :all
            :warnings warnings}
           (modes mode))))

(defn with-allowlist
  "Restrict `context` to an explicit set of tool names. Tools outside the set
   are denied even if their tier is within the ceiling."
  [context tool-names]
  (assoc context :allowlist (set tool-names)))

;; --- Authorization ----------------------------------------------------------
(defn authorize
  "Pure authorization decision for invoking `tool` under `context`.
   `tool` is a map with :name and :capability (:read | :generate | :execute).

   Returns {:allow? bool :reason str :tool :capability :mode}. Fail-closed:
   unknown capability or a disabled context denies."
  [context tool]
  (let [{tool-name :name capability :capability} tool
        {:keys [mode max-tier read-only? allowlist disabled?]} context
        deny (fn [violation reason]
               {:allow?     false
                :violation  violation
                :tool       tool-name
                :capability capability
                :mode       mode
                :reason     reason})]
    (cond
      disabled?
      (deny :disabled "MCP server capabilities are disabled in this context")

      (nil? (tier-rank capability))
      (deny :unknown-capability (str "Unknown capability tier: " (pr-str capability)))

      (not (tier-allowed? max-tier capability))
      (deny :tier-exceeded
            (format "Capability %s exceeds the %s ceiling (mode %s)"
                    capability max-tier mode))

      (and read-only? (not= capability :read))
      (deny :read-only
            (format "Context is read-only; tool %s requires %s" tool-name capability))

      (and (set? allowlist) (not (contains? allowlist tool-name)))
      (deny :allowlist (format "Tool %s is not in the allowlist" tool-name))

      :else
      {:allow? true :tool tool-name :capability capability :mode mode})))

(defn permit?
  "Boolean convenience over `authorize`."
  [context tool]
  (:allow? (authorize context tool)))

(defn describe
  "A compact, loggable summary of a context (no secrets) for audit events.
   Boolean flags are always present (defaulted) so the shape is stable."
  [context]
  {:mode       (:mode context)
   :source     (:source context)
   :env        (:env context)
   :ci?        (boolean (:ci? context))
   :max-tier   (:max-tier context)
   :read-only? (boolean (:read-only? context))
   :disabled?  (boolean (:disabled? context))
   :allowlist  (let [a (:allowlist context)]
                 (if (set? a) (vec (sort a)) a))})
