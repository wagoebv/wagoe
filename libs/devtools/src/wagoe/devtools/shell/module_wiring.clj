(ns wagoe.devtools.shell.module-wiring
  "Integrant wiring for devtools components an application opts into.

   Loaded by the app, not by platform: platform must not know the name of an
   optional library (BOU-131). A config that asks for `:wagoe/dev-error-enricher`
   is a config that has devtools on its classpath."
  (:require [integrant.core :as ig]
            [wagoe.devtools.core.error-classifier :as classifier]
            [wagoe.devtools.core.error-enricher :as enricher]
            [wagoe.devtools.shell.dashboard.pages.errors :as dashboard-errors]))

(defn error-enricher
  "A function from exception to `{:code :category :fix :docs-url}`, or nil when
   the pipeline has nothing to say about it.

   This is what turns a 400 that reads \"Validation failed\" into one that names
   BND-201 and the field to add. It also records the error for the dashboard,
   so the page shows what the API returned rather than a separate story.

   Returned as a plain fn: platform calls `(enrich ex)` and knows nothing about
   this namespace."
  []
  (fn [exception]
    (let [enriched (enricher/enrich (classifier/classify exception))]
      (when (:code enriched)
        (dashboard-errors/record-error!
         {:code         (:code enriched)
          :message      (or (:message enriched) (ex-message exception))
          :category     (:category enriched)
          :timestamp-ms (System/currentTimeMillis)
          :source       :http})
        ;; Nils dropped: this map goes into a response body, and `:fix null`
        ;; reads as "there is a fix and we lost it" rather than "there is none".
        (into {} (remove (comp nil? val))
              {:code     (:code enriched)
               :category (:category enriched)
               :fix      (get-in enriched [:fix :label])
               :docs-url (:docs-url enriched)})))))

(defmethod ig/init-key :wagoe/dev-error-enricher
  [_ _config]
  (error-enricher))

(defmethod ig/halt-key! :wagoe/dev-error-enricher
  [_ _enricher]
  nil)
