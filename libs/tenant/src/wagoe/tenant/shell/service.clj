(ns wagoe.tenant.shell.service
  (:require [wagoe.tenant.shell.time :as time]
            [wagoe.tenant.core.tenant :as tenant-core]
            [wagoe.tenant.ports :as ports]
            [wagoe.platform.shell.service-interceptors :as service-interceptors]
            [malli.core :as m]
            [wagoe.tenant.schema :as tenant-schema])
  (:import (java.util UUID)))

(defn generate-tenant-id
  []
  (UUID/randomUUID))

(def ^:private create-tenant-request-validator (m/validator tenant-schema/CreateTenantRequest))
(def ^:private create-tenant-request-explainer (m/explainer tenant-schema/CreateTenantRequest))

(defrecord TenantService [tenant-repository validation-config logger metrics-emitter error-reporter]

  ports/ITenantService

  (get-tenant [_ tenant-id]
    (service-interceptors/execute-service-operation
     :get-tenant
     {:tenant-id tenant-id}
     (fn [{:keys [params]}]
       (let [tenant (.find-tenant-by-id tenant-repository (:tenant-id params))]
         (when-not tenant
           (throw (ex-info "Tenant not found"
                           {:type :not-found
                            :tenant-id tenant-id})))
         tenant))
     {:logger logger
      :metrics-emitter metrics-emitter
      :error-reporter error-reporter}))

  (get-tenant-by-slug [_ slug]
    (service-interceptors/execute-service-operation
     :get-tenant-by-slug
     {:slug slug}
     (fn [{:keys [params]}]
       (let [tenant (.find-tenant-by-slug tenant-repository (:slug params))]
         (when-not tenant
           (throw (ex-info "Tenant not found"
                           {:type :not-found
                            :slug slug})))
         tenant))
     {:logger logger
      :metrics-emitter metrics-emitter
      :error-reporter error-reporter}))

  (list-tenants [_ options]
    (service-interceptors/execute-service-operation
     :list-tenants
     {:options options}
     (fn [{:keys [params]}]
       (.find-all-tenants tenant-repository (:options params)))
     {:logger logger
      :metrics-emitter metrics-emitter
      :error-reporter error-reporter}))

  (create-new-tenant [_ tenant-input]
    (service-interceptors/execute-service-operation
     :create-new-tenant
     {:tenant-input tenant-input}
     (fn [{:keys [params]}]
       (let [tenant-input (:tenant-input params)]
         (when-not (create-tenant-request-validator tenant-input)
           (throw (ex-info "Invalid tenant data"
                           {:type :validation-error
                            :errors (create-tenant-request-explainer tenant-input)})))

         (let [existing-slugs (set (map :slug (.find-all-tenants tenant-repository {:limit 10000})))
               decision (tenant-core/create-tenant-decision (:slug tenant-input) existing-slugs)]

           (when-not (:valid? decision)
             (throw (ex-info (:error decision)
                             {:type :validation-error
                              :message (:error decision)})))

           (let [tenant-id (generate-tenant-id)
                 now (time/now)
                 prepared-tenant (tenant-core/prepare-tenant tenant-input tenant-id now)
                 created-tenant (.create-tenant tenant-repository prepared-tenant)]

             (try
               (.create-tenant-schema tenant-repository (:schema-name created-tenant))
               (catch clojure.lang.ExceptionInfo e
                 (if (= :not-supported (:type (ex-data e)))
                   created-tenant
                   (do
                     (.delete-tenant tenant-repository tenant-id)
                     (throw (ex-info "Failed to create tenant schema"
                                     {:type :internal-error
                                      :cause (.getMessage e)})))))
               (catch Exception e
                 (.delete-tenant tenant-repository tenant-id)
                 (throw (ex-info "Failed to create tenant schema"
                                 {:type :internal-error
                                  :cause (.getMessage e)}))))

             created-tenant))))
     {:logger logger
      :metrics-emitter metrics-emitter
      :error-reporter error-reporter}))

  (update-existing-tenant [_ tenant-id update-data]
    (service-interceptors/execute-service-operation
     :update-existing-tenant
     {:tenant-id tenant-id :update-data update-data}
     (fn [{:keys [params]}]
       (let [tenant-id (:tenant-id params)
             update-data (:update-data params)
             existing-tenant (.find-tenant-by-id tenant-repository tenant-id)
             decision (tenant-core/update-tenant-decision existing-tenant update-data)]

         (when-not (:valid? decision)
           (throw (ex-info (:error decision)
                           {:type (if (nil? existing-tenant) :not-found :validation-error)
                            :message (:error decision)})))

         (let [now (time/now)
               updated-tenant (tenant-core/prepare-tenant-update existing-tenant update-data now)]
           (.update-tenant tenant-repository updated-tenant)
           updated-tenant)))
     {:logger logger
      :metrics-emitter metrics-emitter
      :error-reporter error-reporter}))

  (delete-existing-tenant [_ tenant-id]
    (service-interceptors/execute-service-operation
     :delete-existing-tenant
     {:tenant-id tenant-id}
     (fn [{:keys [params]}]
       (let [tenant-id (:tenant-id params)
             existing-tenant (.find-tenant-by-id tenant-repository tenant-id)]

         (when-not existing-tenant
           (throw (ex-info "Tenant not found"
                           {:type :not-found
                            :tenant-id tenant-id})))

         (when-not (tenant-core/can-delete-tenant? existing-tenant)
           (throw (ex-info "Tenant is already deleted"
                           {:type :validation-error
                            :tenant-id tenant-id})))

         (let [now (time/now)
               deleted-tenant (tenant-core/prepare-tenant-deletion existing-tenant now)]
           (.update-tenant tenant-repository deleted-tenant)
           nil)))
     {:logger logger
      :metrics-emitter metrics-emitter
      :error-reporter error-reporter}))

  (suspend-tenant [_ tenant-id]
    (service-interceptors/execute-service-operation
     :suspend-tenant
     {:tenant-id tenant-id}
     (fn [{:keys [params]}]
       (let [tenant-id (:tenant-id params)
             existing-tenant (.find-tenant-by-id tenant-repository tenant-id)]

         (when-not existing-tenant
           (throw (ex-info "Tenant not found"
                           {:type :not-found
                            :tenant-id tenant-id})))

         (let [now (time/now)
               suspended-tenant (tenant-core/prepare-tenant-update
                                 existing-tenant
                                 {:status :suspended}
                                 now)]
           (.update-tenant tenant-repository suspended-tenant)
           suspended-tenant)))
     {:logger logger
      :metrics-emitter metrics-emitter
      :error-reporter error-reporter}))

  (activate-tenant [_ tenant-id]
    (service-interceptors/execute-service-operation
     :activate-tenant
     {:tenant-id tenant-id}
     (fn [{:keys [params]}]
       (let [tenant-id (:tenant-id params)
             existing-tenant (.find-tenant-by-id tenant-repository tenant-id)]

         (when-not existing-tenant
           (throw (ex-info "Tenant not found"
                           {:type :not-found
                            :tenant-id tenant-id})))

         (let [now (time/now)
               activated-tenant (tenant-core/prepare-tenant-update
                                 existing-tenant
                                 {:status :active}
                                 now)]
           (.update-tenant tenant-repository activated-tenant)
           activated-tenant)))
     {:logger logger
      :metrics-emitter metrics-emitter
      :error-reporter error-reporter})))

(defn create-tenant-service
  [tenant-repository validation-config logger metrics-emitter error-reporter]
  (->TenantService tenant-repository validation-config logger metrics-emitter error-reporter))
