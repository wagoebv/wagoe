(ns wagoe.push.core.notification
  "Pure push-notification logic: locale resolution and template rendering.

   The definition registry and the `defpush` macro live in the shell
   (wagoe.push.shell.registry) — this namespace holds no mutable state."
  (:require [clojure.string :as str]))

;; ===== Template Rendering =====

(defn render-template
  "Interpolate {{var}} placeholders with data map values."
  [template data]
  (reduce-kv
   (fn [s k v]
     (str/replace s (str "{{" (name k) "}}") (str v)))
   template
   data))

(defn resolve-content
  "Resolve localized content. Fallback: requested -> :en -> first available."
  [content locale]
  (cond
    (string? content) content
    (map? content)    (or (get content locale)
                          (get content :en)
                          (first (vals content)))))

(defn build-notification
  "Pure: resolve locale + render templates into ready-to-send map."
  [push-def data locale]
  {:title        (render-template (resolve-content (:title push-def) locale) data)
   :body         (render-template (resolve-content (:body push-def) locale) data)
   :deep-link    (some-> (:deep-link push-def) (render-template data))
   :priority     (:priority push-def :normal)
   :ttl          (:ttl push-def 86400)
   :silent?      (:silent? push-def false)
   :collapse-key (:collapse-key push-def)
   :data         data})
