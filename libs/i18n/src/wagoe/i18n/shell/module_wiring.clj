(ns wagoe.i18n.shell.module-wiring
  "Integrant wiring for the i18n module.

   Config key: :boundary/i18n

   Example (development):
     :boundary/i18n {:catalogue-paths [\"wagoe/i18n/translations\"
                                       \"my_app/i18n/translations\"]
                     :default-locale :en
                     :dev? true}

   Example (production):
     :boundary/i18n {:catalogue-path \"wagoe/i18n/translations\"
                     :default-locale :en}"
  (:require [wagoe.i18n.shell.catalogue :as catalogue]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

;; =============================================================================
;; Integrant lifecycle
;; =============================================================================

(defmethod ig/init-key :boundary/i18n
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

(defmethod ig/halt-key! :boundary/i18n
  [_ _]
  (log/info "Halting i18n service")
  nil)
