(ns wagoe.devtools.shell.dashboard.server
  "Integrant component for the dev dashboard HTTP server on port 9999."
  (:require [wagoe.devtools.shell.dashboard.pages.overview :as overview]
            [wagoe.devtools.shell.dashboard.pages.routes :as routes-page]
            [wagoe.devtools.shell.dashboard.pages.requests :as requests-page]
            [wagoe.devtools.shell.dashboard.pages.schemas :as schemas-page]
            [wagoe.devtools.shell.dashboard.pages.database :as database-page]
            [wagoe.devtools.shell.dashboard.pages.errors :as errors-page]
            [wagoe.devtools.shell.dashboard.pages.docs :as docs-page]
            [wagoe.devtools.shell.dashboard.pages.jobs :as jobs-page]
            [wagoe.devtools.shell.dashboard.pages.config :as config-page]
            [wagoe.devtools.shell.dashboard.pages.security :as security-page]
            [wagoe.jobs.ports :as job-ports]
            [wagoe.user.ports :as user-ports]
            [clojure.edn :as edn]
            [integrant.core :as ig]
            [reitit.core]
            [reitit.ring :as ring]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.params :refer [wrap-params]]
            [clojure.tools.logging :as log]))

(defn- html-response [body]
  {:status  200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body    body})

(defn- jetty-port
  "Extract the actual listening port from a Jetty Server instance."
  [server]
  (try
    (when server
      (let [connector (first (.getConnectors server))]
        (when connector
          (.getLocalPort connector))))
    (catch Exception _ nil)))

(defn- build-context
  "Build a context map from the injected Integrant components.
   Falls back to integrant.repl.state/system for component count (full system view),
   but uses the injected refs for actual data access."
  [config]
  (let [sys          (try @(resolve 'integrant.repl.state/system) (catch Exception _ nil))
        http-handler (:http-handler config)
        http-server  (:http-server config)
        db-context   (:db-context config)
        app-port     (or (jetty-port http-server) (:http-port config) 3000)
        ;; Jobs: standard setup uses :wagoe/jobs as a composite map
        ;; with {:queue :store :stats} keys. Also check for separate keys.
        jobs-composite (when sys (get sys :wagoe/jobs))
        job-queue     (when sys (or (when (map? jobs-composite) (:queue jobs-composite))
                                    (get sys :wagoe/job-queue)))
        job-store     (when sys (or (when (map? jobs-composite) (:store jobs-composite))
                                    (get sys :wagoe/job-store)
                                    (when (satisfies? job-ports/IJobStore job-queue) job-queue)))
        job-stats-svc (when sys (or (when (map? jobs-composite) (:stats jobs-composite))
                                    (get sys :wagoe/job-stats)
                                    (when (satisfies? job-ports/IJobStats job-queue) job-queue)))]
    {:system-status   (if (or sys http-handler) :running :stopped)
     :component-count (when sys (count sys))
     :error-count     (:total (errors-page/error-stats))
     :http-port       app-port
     :http-handler    http-handler
     :db-context      db-context
     :job-queue       job-queue
     :job-store       job-store
     :job-stats       (when job-stats-svc
                        (try (job-ports/job-stats job-stats-svc) (catch Exception _ nil)))
     :failed-jobs     (when job-store
                        (try (job-ports/failed-jobs job-store 20) (catch Exception _ nil)))
     :config          (when sys (try @(resolve 'integrant.repl.state/config) (catch Exception _ nil)))
     :active-sessions (when-let [session-repo (when sys (get sys :wagoe/session-repository))]
                        (try (let [now (java.time.Instant/now)]
                               (count (filter (fn [s]
                                                (and (nil? (:revoked-at s))
                                                     (or (nil? (:expires-at s))
                                                         (.isAfter (:expires-at s) now))))
                                              (user-ports/find-all-sessions session-repo))))
                             (catch Exception _ 0)))
     :recent-auth-failures
     (when-let [audit-repo (when sys (get sys :wagoe/audit-repository))]
       (try (let [logs (:audit-logs
                        (user-ports/find-audit-logs
                         audit-repo
                         {:filter-action :login
                          :filter-result :failure
                          :limit 20
                          :sort-by :created-at
                          :sort-direction :desc}))]
              (mapv (fn [log]
                      {:timestamp (str (:created-at log))
                       :type      :failed-login
                       :detail    (or (:details log)
                                      (str (:actor-email log)))})
                    logs))
            (catch Exception _ [])))
     :rate-limiting?  (try
                        (when-let [router (some-> http-handler meta :reitit/router)]
                          (some (fn [[_path data]]
                                  (some (fn [interceptor]
                                          (= :http-rate-limit (:name interceptor)))
                                        (concat (:interceptors data)
                                                (mapcat (fn [method]
                                                          (:interceptors (get data method)))
                                                        [:get :post :put :patch :delete]))))
                                (reitit.core/routes router)))
                        (catch Exception _ false))}))

;; Accumulates config overrides applied from the dashboard so successive
;; edits are not lost. Each apply merges into this atom; the prep function
;; applies all accumulated overrides on top of the disk config.
;; Cleared on halt/go/reset so dashboard changes don't leak across restarts.
(defonce config-overrides* (atom {}))

(defn clear-config-overrides!
  "Reset dashboard config overrides so the next restart loads only on-disk config."
  []
  (reset! config-overrides* {}))

(defn- make-handler [config]
  (-> (ring/router
       [["/dashboard"
         {:get (fn [_req]
                 (html-response (overview/render (build-context config))))}]
        ["/dashboard/routes"
         {:get (fn [_req]
                 (html-response (routes-page/render (build-context config))))}]
        ["/dashboard/requests"
         {:get (fn [_req]
                 (html-response (requests-page/render (build-context config))))}]
        ["/dashboard/schemas"
         {:get (fn [req]
                 (html-response (schemas-page/render (build-context config) req)))}]
        ["/dashboard/db"
         {:get (fn [_req]
                 (html-response (database-page/render (build-context config))))}]
        ["/dashboard/errors"
         {:get (fn [_req]
                 (html-response (errors-page/render (build-context config))))}]
        ["/dashboard/jobs"
         {:get (fn [_req]
                 (html-response (jobs-page/render (build-context config))))}]
        ["/dashboard/config"
         {:get (fn [_req]
                 (html-response (config-page/render (build-context config))))}]
        ["/dashboard/security"
         {:get (fn [_req]
                 (html-response (security-page/render (build-context config))))}]
        ["/dashboard/fragments/config-preview"
         {:post (fn [req]
                  (let [ctx    (build-context config)
                        params (:params req)
                        ;; Find the config-:key param sent by hx-include
                        [section-key section-val new-val]
                        (or (some (fn [[k v]]
                                    (when (and (string? k) (.startsWith k "config-"))
                                      (let [cfg-key (try (edn/read-string (subs k 7))
                                                         (catch Exception _ nil))]
                                        (when cfg-key
                                          [cfg-key (get (:config ctx) cfg-key) v]))))
                                  params)
                            [:unknown nil ""])]
                    {:status  200
                     :headers {"Content-Type" "text/html; charset=utf-8"}
                     :body    (config-page/render-preview-fragment
                               section-key section-val (or new-val ""))}))}]
        ["/dashboard/fragments/config-apply"
         {:post (fn [req]
                  (let [params (:params req)
                        ;; Extract section key and new value from form params
                        [section-key new-val-str]
                        (or (some (fn [[k v]]
                                    (when (and (string? k) (.startsWith k "config-"))
                                      (let [cfg-key (try (edn/read-string (subs k 7))
                                                         (catch Exception _ nil))]
                                        (when cfg-key [cfg-key v]))))
                                  params)
                            [nil nil])
                        result
                        (if (and section-key new-val-str)
                          (try
                            (let [new-val      (config-page/parse-edited-value new-val-str ::parse-failed)
                                  _            (when (= new-val ::parse-failed)
                                                 (throw (ex-info "Failed to parse edited config value as EDN" {})))
                                  set-prep-fn  (resolve 'integrant.repl/set-prep!)
                                  load-cfg-fn  (resolve 'wagoe.config/load-config)
                                  ig-cfg-fn    (resolve 'wagoe.config/ig-config)
                                  sys-var      (resolve 'integrant.repl.state/system)
                                  cfg-var      (resolve 'integrant.repl.state/config)
                                  restart-fn   (resolve 'wagoe.devtools.shell.repl/restart-component)]
                              (if (and set-prep-fn sys-var cfg-var restart-fn load-cfg-fn ig-cfg-fn)
                                (let [;; Snapshot previous state so we can roll back on failure
                                      prev-override (get @config-overrides* section-key ::absent)
                                      prev-cfg-val  (get @cfg-var section-key)]
                                  ;; Accumulate this override so successive edits aren't lost
                                  (swap! config-overrides* assoc section-key new-val)
                                  ;; Patch the prep function to apply ALL accumulated overrides
                                  ;; after loading from disk, so reset preserves all edits
                                  (set-prep-fn
                                   (fn []
                                     (let [cfg (load-cfg-fn)]
                                       (merge (ig-cfg-fn cfg) @config-overrides*))))
                                  ;; Update the live config var, restart the component,
                                  ;; then cascade to all dependents so they pick up
                                  ;; the new instance (restart-component alone leaves
                                  ;; dependents holding stale refs)
                                  (let [old-cfg       @cfg-var
                                        find-deps-fn  (resolve 'wagoe.devtools.shell.repl/find-dependents)
                                        ;; Compute dependents from BOTH old and new config so that
                                        ;; consumers that dropped the ig/ref are still restarted
                                        old-deps      (when find-deps-fn (set (find-deps-fn old-cfg section-key)))
                                        _             (alter-var-root cfg-var assoc section-key new-val)
                                        live-cfg      @cfg-var
                                        new-deps      (when find-deps-fn (set (find-deps-fn live-cfg section-key)))
                                        all-dep-keys  (into (or old-deps #{}) (or new-deps #{}))
                                        ;; Topologically sort: new-config deps first (already sorted),
                                        ;; then old-only deps sorted by the OLD config so removed
                                        ;; chains like A <- B <- C restart in the right order
                                        dependents    (if find-deps-fn
                                                        (let [sorted-new  (find-deps-fn live-cfg section-key)
                                                              new-set     (set sorted-new)
                                                              old-only    (remove new-set all-dep-keys)
                                                              sorted-old  (find-deps-fn old-cfg section-key)
                                                              ;; Keep old-only keys in their old topological order
                                                              old-ordered (filterv (set old-only) sorted-old)
                                                              ;; Any old-only keys not in sorted-old (shouldn't happen,
                                                              ;; but safe fallback)
                                                              remainder   (remove (set old-ordered) old-only)]
                                                          (into (vec sorted-new) (concat old-ordered remainder)))
                                                        [])
                                        all-to-restart (into [section-key] dependents)
                                        ;; Restart components one by one; stop on first failure
                                        {:keys [succeeded failed-key failed-error]}
                                        (reduce (fn [acc k]
                                                  (if (:failed-key acc)
                                                    acc ;; skip remaining after first failure
                                                    (try (restart-fn sys-var @cfg-var k)
                                                         (update acc :succeeded conj k)
                                                         (catch Exception e
                                                           (log/warn "Failed to restart" {:key k :error (.getMessage e)})
                                                           (assoc acc :failed-key k :failed-error (.getMessage e))))))
                                                {:succeeded []}
                                                all-to-restart)]
                                    (if failed-key
                                      ;; Roll back: restore config state and re-restart
                                      ;; already-succeeded components with the old config
                                      (do (if (= prev-override ::absent)
                                            (swap! config-overrides* dissoc section-key)
                                            (swap! config-overrides* assoc section-key prev-override))
                                          (alter-var-root cfg-var assoc section-key prev-cfg-val)
                                          (set-prep-fn
                                           (fn []
                                             (let [cfg (load-cfg-fn)]
                                               (merge (ig-cfg-fn cfg) @config-overrides*))))
                                          ;; Re-restart succeeded components with restored config
                                          (doseq [k succeeded]
                                            (try (restart-fn sys-var @cfg-var k)
                                                 (catch Exception e
                                                   (log/warn "Rollback restart failed" {:key k :error (.getMessage e)}))))
                                          ;; The failed component was already halted by restart-component
                                          ;; before init-key threw — the system map still holds the old
                                          ;; (halted) instance. Set it to nil so restart-component's
                                          ;; halt-key! is a no-op, then re-init with old config.
                                          (alter-var-root sys-var assoc failed-key nil)
                                          (try (restart-fn sys-var @cfg-var failed-key)
                                               (catch Exception e
                                                 (log/warn "Rollback re-init failed" {:key failed-key :error (.getMessage e)})))
                                          {:success? false
                                           :error (str "Failed to restart " failed-key " (" failed-error
                                                       "). All changes rolled back.")})
                                      {:success? true :restarted all-to-restart})))
                                {:success? false
                                 :error "Cannot resolve Integrant REPL state. Is the system running?"}))
                            (catch Exception e
                              {:success? false :error (.getMessage e)}))
                          {:success? false :error "No config section identified in request."})]
                    {:status  200
                     :headers {"Content-Type" "text/html; charset=utf-8"}
                     :body    (config-page/render-apply-result result)}))}]
        ["/dashboard/docs"
         {:get (fn [_req]
                 (html-response (docs-page/render-index (build-context config))))}]
        ["/dashboard/docs/:module/:file"
         {:get (fn [req]
                 (let [module (get-in req [:path-params :module])
                       file   (get-in req [:path-params :file])]
                   (html-response (docs-page/render (build-context config) module file))))}]
        ["/dashboard/fragments/jobs-content"
         {:get (fn [_req]
                 {:status  200
                  :headers {"Content-Type" "text/html; charset=utf-8"}
                  :body    (jobs-page/render-fragment (build-context config))})}]
        ["/dashboard/fragments/retry-job"
         {:post (fn [req]
                  (let [ctx (build-context config)]
                    (when-let [job-store (:job-store ctx)]
                      (when-let [job-id-str (get-in req [:params "job-id"])]
                        (try
                          (let [job-id (try (java.util.UUID/fromString job-id-str)
                                            (catch Exception _ job-id-str))]
                            (job-ports/retry-job! job-store job-id))
                          (catch Exception e
                            (log/warn "Failed to retry job" {:job-id job-id-str :error (.getMessage e)})))))
                    ;; Rebuild context AFTER retry so the response reflects the new state
                    (let [fresh-ctx (build-context config)]
                      {:status  200
                       :headers {"Content-Type" "text/html; charset=utf-8"}
                       :body    (jobs-page/render-failed-jobs-fragment fresh-ctx)})))}]
        ["/dashboard/fragments/request-list"
         {:get (fn [req]
                 {:status  200
                  :headers {"Content-Type" "text/html; charset=utf-8"}
                  :body    (requests-page/render-fragment req)})}]
        ["/dashboard/fragments/request-detail"
         {:get (fn [req]
                 {:status  200
                  :headers {"Content-Type" "text/html; charset=utf-8"}
                  :body    (requests-page/render-detail-fragment req)})}]
        ["/dashboard/fragments/error-list"
         {:get (fn [_req]
                 {:status  200
                  :headers {"Content-Type" "text/html; charset=utf-8"}
                  :body    (errors-page/render-fragment)})}]
        ["/dashboard/fragments/pool-status"
         {:get (fn [_req]
                 {:status  200
                  :headers {"Content-Type" "text/html; charset=utf-8"}
                  :body    (database-page/render-pool-fragment (build-context config))})}]
        ["/dashboard/fragments/query-result"
         {:post (fn [req]
                  {:status  200
                   :headers {"Content-Type" "text/html; charset=utf-8"}
                   :body    (database-page/render-query-result (merge req (select-keys (build-context config) [:db-context])))})}]
        ["/dashboard/fragments/try-route"
         {:post (fn [req]
                  {:status  200
                   :headers {"Content-Type" "text/html; charset=utf-8"}
                   :body    (routes-page/render-try-result (merge req (select-keys (build-context config) [:http-handler])))})}]
        ["/dashboard/fragments/routes-table"
         {:get (fn [req]
                 {:status  200
                  :headers {"Content-Type" "text/html; charset=utf-8"}
                  :body    (routes-page/render-table-fragment (merge req (select-keys (build-context config) [:http-handler])))})}]
        ["/dashboard/fragments/route-inspect"
         {:get (fn [req]
                 {:status  200
                  :headers {"Content-Type" "text/html; charset=utf-8"}
                  :body    (routes-page/render-inspect-fragment req)})}]])
      ring/ring-handler
      (wrap-resource "dashboard")
      (wrap-resource "public")
      wrap-content-type
      wrap-params))

(defn- try-start-jetty
  "Attempt to start Jetty on the given port. On BindException, try up to
   max-port before giving up. Returns {:server s :port p} or nil."
  [handler host port max-port]
  (loop [p port]
    (when (<= p max-port)
      (let [result (try
                     {:server (jetty/run-jetty handler {:port p :host host :join? false})
                      :port   p}
                     (catch java.net.BindException _
                       (log/debugf "Dashboard port %d in use, trying %d" p (inc p))
                       nil))]
        (or result (recur (inc p)))))))

(defmethod ig/init-key :wagoe/dashboard [_ {:keys [port host] :as config}]
  (let [port   (or port 9999)
        host   (or host "127.0.0.1")
        result (try-start-jetty (make-handler config) host port (+ port 10))]
    (if result
      (do
        (log/infof "Dev dashboard started on http://%s:%d/dashboard" host (:port result))
        (when (not= port (:port result))
          (log/warnf "Dashboard port %d was busy, using %d instead" port (:port result)))
        result)
      (do
        (log/warnf "Could not start dev dashboard — ports %d–%d all in use" port (+ port 10))
        nil))))

(defmethod ig/halt-key! :wagoe/dashboard [_ {:keys [server]}]
  (when server
    (.stop server)
    (schemas-page/reset-schemas!)
    (log/info "Dev dashboard stopped")))
