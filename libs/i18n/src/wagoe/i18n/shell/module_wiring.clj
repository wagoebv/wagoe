(ns wagoe.i18n.shell.module-wiring
  "Integrant wiring for the i18n module.

   Config key: :wagoe/i18n

   Example (development):
     :wagoe/i18n {:catalogue-paths [\"wagoe/i18n/translations\"
                                       \"my_app/i18n/translations\"]
                     :default-locale :en
                     :dev? true}

   Example (production):
     :wagoe/i18n {:catalogue-path \"wagoe/i18n/translations\"
                     :default-locale :en}"
  (:require [wagoe.i18n.shell.catalogue :as catalogue]
            [wagoe.i18n.shell.middleware :as middleware]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

;; =============================================================================
;; Integrant lifecycle
;; =============================================================================

(defmethod ig/init-key :wagoe/i18n
  [_ {:keys [catalogue-path catalogue-paths default-locale dev?]
      :or   {default-locale :en}}]
  (let [resolved-paths (or catalogue-paths
                           (when catalogue-path [catalogue-path]))]
    (log/info "Initializing i18n service" {:catalogue-paths resolved-paths
                                           :default-locale default-locale})
    (let [data    (catalogue/load-catalogue resolved-paths)
          cat     (catalogue/create-map-catalogue data)
          locales (set (keys data))]
      (log/info "i18n service initialized" {:locales locales})
      {:catalogue       cat
       :default-locale  default-locale
       :catalogue-paths resolved-paths
       :dev?            (boolean dev?)})))

(defmethod ig/halt-key! :wagoe/i18n
  [_ _]
  (log/info "Halting i18n service")
  nil)

;; =============================================================================
;; HTTP middleware
;; =============================================================================

(defmethod ig/init-key :wagoe/i18n-http-middleware
  [_ {:keys [i18n]}]
  ;; Built here, in the i18n lib, and injected into platform's http-handler —
  ;; the shape BOU-200 established for tenant. Platform used to require
  ;; wagoe.i18n.shell.middleware directly, which made wagoe-i18n a mandatory
  ;; dependency of every consumer of platform whether or not they translated
  ;; anything (BOU-131).
  ;;
  ;; A (fn [handler] ...) applied lazily when the pipeline compiles, so an
  ;; absent i18n component simply contributes no middleware.
  (when i18n
    (log/info "Adding i18n middleware to HTTP pipeline")
    (fn [handler] (middleware/wrap-i18n handler i18n))))

(defmethod ig/halt-key! :wagoe/i18n-http-middleware
  [_ _mw]
  (log/info "i18n HTTP middleware halted (no cleanup needed)"))

;; =============================================================================
;; Module graph
;; =============================================================================

(defn ig-config
  "This module's Integrant entries, for `wagoe.platform.shell.system.config`.

   Assembled in every application whether or not its config names `:wagoe/i18n`:
   the defaults below are a working English-only setup, and the HTTP handler
   takes a ref to the middleware unconditionally.

   The middleware is built here and injected rather than constructed by the
   HTTP handler, so platform does not require `wagoe.i18n.shell.middleware`."
  [settings _ctx]
  {:components
   {:wagoe/i18n                 (or settings {:catalogue-path "wagoe/i18n/translations"
                                              :default-locale :en})
    :wagoe/i18n-http-middleware {:i18n (ig/ref :wagoe/i18n)}}})
