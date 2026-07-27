(ns wagoe.i18n.core.translate
  "Pure translation functions for the i18n module.

   FC/IS rule: PURE functions only — no I/O, no logging, no exceptions.

   Translation key resolution order (locale chain):
     1. User locale (e.g. :nl)
     2. Tenant locale (e.g. :nl)
     3. Default locale (:en)
     4. Fallback: (str key) for graceful degradation

   Marker syntax:
     [:t :key]                   — simple lookup
     [:t :key {:param val}]      — with interpolation params
     [:t :key {:n 3} 3]          — plural form (count as 4th arg)"
  (:require [wagoe.i18n.ports :as ports]
            [clojure.string :as str]))

;; =============================================================================
;; String interpolation
;; =============================================================================

(defn- interpolate
  "Replace {param} placeholders in a string with values from params map.

   Args:
     s      - string template with {param} placeholders
     params - map of param keyword/string keys to replacement values

   Returns:
     String with placeholders replaced"
  [s params]
  (if (empty? params)
    s
    (reduce-kv (fn [acc k v]
                 (str/replace acc
                              (str "{" (name k) "}")
                              (str v)))
               s
               params)))

;; =============================================================================
;; Plural resolution
;; =============================================================================

(defn- resolve-plural
  "Select the appropriate plural form from an entry map.

   Supports English-style two-form plurals:
     {:one \"item\" :many \"items\"}

   Falls back to :many if :one not present, then to raw string.

   Args:
     entry - string or map with plural keys (:one, :many, :zero)
     n     - count for plural selection

   Returns:
     String (the selected plural form)"
  [entry n]
  (cond
    (string? entry) entry
    (map? entry)    (or (cond
                          (zero? n) (:zero entry)
                          (= 1 n)   (:one  entry)
                          :else     (:many entry))
                        (:many entry)
                        (:one  entry)
                        (first (vals entry)))
    :else           (str entry)))

;; =============================================================================
;; Catalogue lookup with locale chain
;; =============================================================================

(defn t
  "Translate a key using a locale chain and optional catalogue.

   Args:
     catalogue    - map of {locale {key string-or-plural-map}} or ICatalogue
     locale-chain - ordered vector of locale keywords to try, e.g. [:nl :en]
     key          - keyword translation key, e.g. :user/sign-in
     params       - (optional) map of interpolation params, e.g. {:name \"Alice\"}
     n            - (optional) count for plural selection

   Returns:
     Translated and interpolated string, or (str key) on missing entry"
  ([catalogue locale-chain key]
   (t catalogue locale-chain key {} nil))
  ([catalogue locale-chain key params]
   (t catalogue locale-chain key params nil))
  ([catalogue locale-chain key params n]
   (let [;; satisfies? is not cheap — hoist it out of the per-locale fn so it
         ;; runs once per t call instead of once per locale in the chain.
         ;; defrecord catalogue implementations also satisfy map?, so prefer
         ;; the protocol when available.
         catalogue? (satisfies? ports/ICatalogue catalogue)
         plain-map? (and (not catalogue?) (map? catalogue))
         entry (some (fn [locale]
                       (cond
                         catalogue? (ports/lookup catalogue locale key)
                         plain-map? (get-in catalogue [locale key])
                         :else nil))
                     locale-chain)
         resolved (if entry
                    (if n
                      (resolve-plural entry n)
                      (if (map? entry)
                        (or (:one entry) (:many entry) (first (vals entry)))
                        entry))
                    (str (name (or (namespace key) "")) (when (namespace key) "/") (name key)))]
     (interpolate resolved params))))
