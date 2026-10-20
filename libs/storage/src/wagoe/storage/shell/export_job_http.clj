(ns wagoe.storage.shell.export-job-http
  "Data export as a background job: POST /me/export-jobs for a signed-in
   user's own, or `ExportJobs` for another module's admin export; then
   GET /me/export-jobs/:id until it is done, then
   GET /me/export-jobs/:id/download. A job is visible only to who asked, and
   an export of someone else's data only while they are a platform admin."
  (:require [wagoe.platform.core.http.access :as access]
            [wagoe.platform.ports.privacy :as privacy]
            [wagoe.storage.core.export-jobs :as core]
            [wagoe.storage.ports :as ports]
            [wagoe.storage.shell.export-jobs :as export-jobs])
  (:import [java.time Instant]))

(defn- user-id [request]
  (or (get-in request [:user :id])
      (throw (ex-info "User not authenticated" {:type :unauthorized}))))

(defn- job-path [id]
  (str "/api/v1/me/export-jobs/" id))

(defn- view [job]
  (core/status-view job (str (job-path (:id job)) "/download") (Instant/now)))

(defn- own-job
  "The path's job, when the signed-in user asked for it. Anyone else's is
   not found, rather than forbidden: its id is no business of theirs. An
   admin's export of another subject needs them to be one still."
  [{:keys [store]} request]
  (let [job (ports/find-export-job store (get-in request [:path-params :id]))]
    (if (and job
             (core/requested-by? job (user-id request))
             (or (core/own-data? job) (access/platform-admin? (:user request))))
      job
      (throw (ex-info "Export not found" {:type :not-found})))))

(defn- accepted [job]
  {:status  202
   :headers {"Location" (job-path (:id job))}
   :body    (view job)})

(defrecord ExportJobs [ctx]
  privacy/IExportJobs
  (request-export-job! [_ subject requested-by]
    (accepted (export-jobs/request-export! ctx subject requested-by))))

(defn request-handler [ctx]
  (fn [request]
    (let [id (user-id request)]
      (accepted (export-jobs/request-export! ctx {:type :user :id id} id)))))

(defn status-handler [ctx]
  (fn [request]
    {:status 200 :body (view (own-job ctx request))}))

(defn download-handler [ctx]
  (fn [request]
    (let [job (own-job ctx request)]
      (when-not (core/downloadable? job (Instant/now))
        (throw (ex-info "No archive to download: the export is not done, or has expired"
                        {:type :not-found :export-id (:id job)})))
      (export-jobs/download-response ctx job))))

(def ^:private id-parameter
  {:swagger {:parameters [{:name "id" :in "path" :required true :type "string"}]}})

(defn routes
  "This feature's `:api` routes. Every one needs a signed-in user."
  [ctx]
  [["/me/export-jobs"
    {:authorization :signed-in
     :post {:handler (request-handler ctx)
            :summary "Ask for your data export as a background job"
            :tags    ["users"]}}]
   ["/me/export-jobs/:id"
    (merge id-parameter
           {:authorization :signed-in
            :get {:handler (status-handler ctx)
                  :summary "Your export job's status"
                  :tags    ["users"]}})]
   ["/me/export-jobs/:id/download"
    (merge id-parameter
           {:authorization :signed-in
            :get {:handler (download-handler ctx)
                  :summary "Download your finished export"
                  :tags    ["users"]}})]])
