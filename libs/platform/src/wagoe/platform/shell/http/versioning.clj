(ns wagoe.platform.shell.http.versioning
  "HTTP API versioning support - wraps routes with version prefixes and headers.
   
   SIDE EFFECTS:
   - Route transformation
   - Response header modification
   - Logging
   
   Provides URL-based versioning (/api/v1/users, /api/v2/users) with:
   - Automatic version prefix wrapping
   - Version header injection (X-API-Version, X-API-Latest, X-API-Deprecated)
   - Backward compatibility (/api/users → /api/v1/users redirect)
   - Multiple version support concurrently"
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]))

;; =============================================================================
;; Version Configuration
;; =============================================================================

(def default-version-config
  "Default API versioning configuration.
   
   Override in config.edn under :wagoe/api-versioning"
  {:default-version :v1           ; Version to use when not specified
   :latest-stable :v1              ; Latest stable version
   :deprecated-versions #{}        ; Set of deprecated version keywords
   :sunset-dates {}                ; Map of version -> ISO date string
   :supported-versions #{:v1}})    ; Set of all supported versions

(defn version-config
  "Get versioning configuration with defaults.
   
   Args:
     config - Application config map
     
   Returns:
     Version config map with defaults applied"
  [config]
  (merge default-version-config
         (get-in config [:active :wagoe/api-versioning])))

;; =============================================================================
;; Route Transformation
;; =============================================================================

(defn route-path
  "The path of a Reitit route — `[path data & children]`."
  [route]
  (first route))

(defn with-route-path
  "`route` with its path replaced."
  [route path]
  (assoc route 0 path))

(defn- wrap-route-with-version
  "Put `/api/<version>` in front of a route's path.

     (wrap-route-with-version [\"/users\" {:get {...}}] :v1)
     ;;=> [\"/api/v1/users\" {:get {...}}]"
  [route version]
  (let [prefix (str "/api/" (name version))]
    (with-route-path route (str prefix (route-path route)))))

(defn wrap-routes-with-version
  "`wrap-route-with-version` over a vector of routes. Logs the count."
  [routes version]
  (log/debug "Wrapping routes with version"
             {:route-count (count routes)
              :version version})
  (mapv #(wrap-route-with-version % version) routes))

;; =============================================================================
;; Version Header Middleware
;; =============================================================================

(defn version-headers-middleware
  "Middleware to add version headers to responses.
   
   Adds headers:
   - X-API-Version: Current version (e.g., \"v1\")
   - X-API-Version-Latest: Latest stable version
   - X-API-Deprecated: \"true\" if version is deprecated
   - X-API-Sunset: ISO 8601 date if sunset date exists
   
   Args:
     handler - Ring handler function
     version - Version keyword (:v1, :v2, etc.)
     config - Version configuration map
     
   Returns:
     Wrapped Ring handler
     
   Side Effects:
     - Response header modification
     
   Example:
     (def handler
       (version-headers-middleware
         my-handler
         :v1
         {:latest-stable :v2
          :deprecated-versions #{:v1}
          :sunset-dates {:v1 \"2026-06-01\"}}))
     
     (handler request)
     ;;=> {:status 200
     ;;    :headers {\"X-API-Version\" \"v1\"
     ;;              \"X-API-Version-Latest\" \"v2\"
     ;;              \"X-API-Deprecated\" \"true\"
     ;;              \"X-API-Sunset\" \"2026-06-01\"}
     ;;    :body ...}"
  [handler version config]
  (let [;; Version and config are fixed at wrap time — build the headers once.
        deprecated? (contains? (:deprecated-versions config) version)
        sunset-date (get (:sunset-dates config) version)
        version-headers (cond-> {"X-API-Version" (name version)
                                 "X-API-Version-Latest" (name (:latest-stable config))}
                          deprecated?
                          (assoc "X-API-Deprecated" "true")

                          sunset-date
                          (assoc "X-API-Sunset" sunset-date))
        wrapper
        (fn [request]
          (update (handler request) :headers merge version-headers))]
    (with-meta wrapper (meta handler))))

;; =============================================================================
;; Backward Compatibility Redirect
;; =============================================================================

(defn create-redirect-route
  "A route at `/api<path>` that 307s to `/api/<version><path>`.

   307 rather than 301: it preserves the method, so a POST stays a POST.

     (create-redirect-route \"/users\" :v1)
     ;;=> [\"/api/users\" {:get {:handler …} :post {…} …}]"
  [path target-version]
  (let [version-str (name target-version)
        target-path (str "/api/" version-str path)
        redirect-handler (fn [request]
                           (log/debug "Redirecting unversioned request"
                                      {:from (:uri request)
                                       :to target-path})
                           {:status 307  ; Temporary Redirect (preserves method)
                            :headers {"Location" target-path
                                      "X-API-Deprecated-Path" "true"}
                            :body {:message "Please use versioned API endpoint"
                                   :location target-path
                                   :version (name target-version)}})]
    ;; Reitit data: the redirect is platform's own route, so it is written in
    ;; the target format rather than migrated later (ADR-037).
    [(str "/api" path)
     (into {} (map (fn [method]
                     [method {:handler redirect-handler
                              :summary (str "Redirect to " target-path)}]))
           [:get :post :put :delete :patch])]))

(defn create-backward-compatibility-routes
  "One `/api/…` redirect for every `/api/<version>/…` route given.

     [\"/api/v1/users\" …] --> [\"/api/users\" {:get redirect …}]"
  ([routes]
   (create-backward-compatibility-routes routes :v1))
  ([routes target-version]
   (let [;; Extract paths and remove version prefix
         version-str (name target-version)
         version-prefix (str "/api/" version-str)
         unversioned-paths (->> routes
                                (map route-path)
                                (filter #(str/starts-with? % version-prefix))
                                (map #(subs % (count version-prefix)))
                                (into #{}))

         ;; Create redirect routes
         redirect-routes (mapv #(create-redirect-route % target-version)
                               unversioned-paths)]

     (log/info "Created backward compatibility redirects"
               {:redirect-count (count redirect-routes)
                :target-version target-version})
     redirect-routes)))

;; =============================================================================
;; High-Level API
;; =============================================================================

(defn apply-versioning
  "The whole versioning step: what modules contribute as `:api` goes in
   unversioned, and comes out prefixed with the default version plus a
   backward-compatibility redirect for each path.

     [\"/users\" {:get …}]
     ;;=> [\"/api/v1/users\" {:get …}]   and   [\"/api/users\" {:get redirect}]

   The version is read from `[:active :wagoe/api-versioning :default-version]`."
  [api-routes config]
  (let [version-cfg (version-config config)
        default-version (:default-version version-cfg)

        ;; Wrap routes with version prefix
        versioned-routes (wrap-routes-with-version api-routes default-version)

        ;; Create backward compatibility redirects
        redirect-routes (create-backward-compatibility-routes
                         versioned-routes
                         default-version)

        ;; Combine versioned and redirect routes
        all-routes (concat versioned-routes redirect-routes)]

    (log/info "Applied API versioning"
              {:versioned-routes (count versioned-routes)
               :redirect-routes (count redirect-routes)
               :total-routes (count all-routes)
               :default-version default-version})

    (vec all-routes)))

(defn wrap-handler-with-version-headers
  "Wrap Ring handler with version headers middleware.
   
   Adds version headers to all responses.
   
   Args:
     handler - Ring handler function
     config - Application config map
     
   Returns:
     Wrapped Ring handler with version headers
     
   Example:
     (def versioned-handler
       (wrap-handler-with-version-headers
         my-handler
         {:active {:wagoe/api-versioning
                   {:default-version :v1
                    :latest-stable :v1}}}))
     
     (versioned-handler request)
     ;;=> {:status 200
     ;;    :headers {\"X-API-Version\" \"v1\" ...}
     ;;    :body ...}"
  [handler config]
  (let [version-cfg (version-config config)
        default-version (:default-version version-cfg)]
    (version-headers-middleware handler default-version version-cfg)))

(comment
  ;; What the versioning step does to a module's :api contribution.
  (apply-versioning
   [["/users" {:get {:handler identity} :post {:handler identity}}]]
   {:active {:wagoe/api-versioning {:default-version :v1}}})
  ;;=> [["/api/v1/users" {:get … :post …}]      ; versioned
  ;;    ["/api/users"    {:get redirect …}]]    ; 307 to the versioned path
  )
