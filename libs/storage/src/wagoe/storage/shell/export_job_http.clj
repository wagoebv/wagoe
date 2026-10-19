(ns wagoe.storage.shell.export-job-http
  "A signed-in user's own data export as a background job:
   POST /me/export-jobs, then GET /me/export-jobs/:id until it is done, then
   GET /me/export-jobs/:id/download. A job is visible only to who asked."
  (:require [wagoe.storage.core.export-jobs :as core]
            [wagoe.storage.ports :as ports]
            [wagoe.storage.shell.export-jobs :as export-jobs])
  (:import [java.time Instant]))

(defn- user-id [request]
  (or (get-in request [:user :id])
      (throw (ex-info "User not authenticated" {:type :unauthorized}))))

(defn- download-path [id]
  (str "/api/v1/me/export-jobs/" id "/download"))

(defn- own-job
  "The path's job, when the signed-in user asked for it. Anyone else's is
   not found, rather than forbidden: its id is no business of theirs."
  [{:keys [store]} request]
  (let [job (ports/find-export-job store (get-in request [:path-params :id]))]
    (if (and job (core/requested-by? job (user-id request)))
      job
      (throw (ex-info "Export not found" {:type :not-found})))))

(defn request-handler [ctx]
  (fn [request]
    (let [id  (user-id request)
          job (export-jobs/request-export! ctx {:type :user :id id} id)]
      {:status  202
       :headers {"Location" (str "/api/v1/me/export-jobs/" (:id job))}
       :body    (core/status-view job (download-path (:id job)) (Instant/now))})))

(defn status-handler [ctx]
  (fn [request]
    (let [job (own-job ctx request)]
      {:status 200 :body (core/status-view job (download-path (:id job)) (Instant/now))})))

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
    {:post {:handler (request-handler ctx)
            :summary "Ask for your data export as a background job"
            :tags    ["users"]}}]
   ["/me/export-jobs/:id"
    (merge id-parameter
           {:get {:handler (status-handler ctx)
                  :summary "Your export job's status"
                  :tags    ["users"]}})]
   ["/me/export-jobs/:id/download"
    (merge id-parameter
           {:get {:handler (download-handler ctx)
                  :summary "Download your finished export"
                  :tags    ["users"]}})]])
