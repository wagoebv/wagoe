(ns wagoe.storage.shell.module-wiring
  "Integrant lifecycle for the storage module.

   Config key:

   :wagoe/storage
     {:provider :local :root \"uploads\"}                ; local filesystem
     {:provider :s3  :bucket \"b\" :region \"eu-west-1\"}  ; AWS S3 / compatible
     {:provider :gcs :bucket \"b\" :project-id \"p\"}      ; Google Cloud Storage

     Returns {:provider <kw> :storage <IFileStorage> :service <IStorageService>}.
     Consumers (e.g. :wagoe/storage-routes) use :service.

   The `:local` provider accepts the catalogue's `:root` as an alias for the
   local adapter's `:base-path`."
  (:require [integrant.core :as ig]
            [wagoe.storage.shell.adapters.local :as local]
            [wagoe.storage.shell.adapters.s3 :as s3]
            [wagoe.storage.shell.adapters.gcs :as gcs]
            [wagoe.storage.shell.adapters.image-processor :as image-processor]
            [wagoe.storage.shell.service :as service]
            [wagoe.storage.shell.http-handlers :as http-handlers]
            [clojure.tools.logging :as log]))

(defn- build-file-storage
  "Construct the IFileStorage adapter for the configured provider."
  [{:keys [provider] :as config} logger]
  (case provider
    :local (local/create-local-storage
            {:base-path      (or (:root config) (:base-path config))
             :url-base       (:url-base config)
             :signing-secret (:signing-secret config)
             :logger         logger})
    :s3    (s3/create-s3-storage (assoc config :logger logger))
    :gcs   (gcs/create-gcs-storage (assoc config :logger logger))
    (throw (ex-info "Unknown storage provider"
                    {:type :unknown-provider :provider provider}))))

(defmethod ig/init-key :wagoe/storage
  [_ {:keys [provider logger] :or {provider :local} :as config}]
  (log/info "Initializing storage component" {:provider provider})
  (let [file-storage (build-file-storage (assoc config :provider provider) logger)
        processor    (image-processor/create-image-processor {:logger logger})
        svc          (service/create-storage-service
                      {:storage file-storage :image-processor processor :logger logger})]
    (log/info "Storage component initialized" {:provider provider})
    {:provider provider :storage file-storage :service svc}))

(defmethod ig/halt-key! :wagoe/storage
  [_ {:keys [provider storage]}]
  (log/info "Halting storage component" {:provider provider})
  ;; Release cloud SDK clients; local has nothing to close.
  (case provider
    :s3  (s3/close-s3-storage storage)
    :gcs (gcs/close-gcs-storage storage)
    nil)
  nil)

;; =============================================================================
;; Storage Routes Component
;; =============================================================================

(defmethod ig/init-key :wagoe/storage-routes
  [_ {:keys [storage]}]
  (log/info "Initializing storage routes")
  {:api    (http-handlers/storage-routes (:service storage))
   :web    []
   :static []})

(defmethod ig/halt-key! :wagoe/storage-routes
  [_ _routes]
  ;; Routes are pure data — no cleanup.
  nil)
