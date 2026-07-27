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

(defn- wrap-route-with-version
  "Wrap a single route with version prefix.
   
   Args:
     route - Normalized route map {:path \"/users\" :methods {...}}
     version - Version keyword (:v1, :v2, etc.)
     
   Returns:
     Route map with version prefix in path
     
   Example:
     (wrap-route-with-version
       {:path \"/users\" :methods {:get {...}}}
       :v1)
     ;;=> {:path \"/api/v1/users\" :methods {:get {...}}}"
  [route version]
  (let [version-str (name version)
        prefix (str "/api/" version-str)]
    (update route :path #(str prefix %))))

(defn wrap-routes-with-version
  "Wrap all routes with version prefix.
   
   Args:
     routes - Vector of normalized route maps
     version - Version keyword (:v1, :v2, etc.)
     
   Returns:
     Vector of routes with version prefix
     
   Side Effects:
     - Logging
     
   Example:
     (wrap-routes-with-version
       [{:path \"/users\" :methods {:get {...}}}
        {:path \"/items\" :methods {:get {...}}}]
       :v1)
     ;;=> [{:path \"/api/v1/users\" ...}
     ;;    {:path \"/api/v1/items\" ...}]"
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
  "Create redirect route from unversioned path to versioned path.
   
   Creates a route that redirects /api/users → /api/v1/users (307 Temporary Redirect)
   
   Args:
     path - Unversioned path (e.g., \"/users\")
     target-version - Version to redirect to (e.g., :v1)
     
   Returns:
     Normalized route map with redirect handler
     
   Example:
     (create-redirect-route \"/users\" :v1)
     ;;=> {:path \"/api/users\"
     ;;    :methods {:get {:handler (fn [req]
     ;;                               {:status 307
     ;;                                :headers {\"Location\" \"/api/v1/users\"}})}
     ;;              :post {...}
     ;;              :put {...}
     ;;              :delete {...}}}"
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
    {:path (str "/api" path)
     :methods {:get {:handler redirect-handler
                     :summary (str "Redirect to " target-path)}
               :post {:handler redirect-handler
                      :summary (str "Redirect to " target-path)}
               :put {:handler redirect-handler
                     :summary (str "Redirect to " target-path)}
               :delete {:handler redirect-handler
                        :summary (str "Redirect to " target-path)}
               :patch {:handler redirect-handler
                       :summary (str "Redirect to " target-path)}}}))

(defn create-backward-compatibility-routes
  "Create redirect routes for backward compatibility.
   
   Generates /api/* routes that redirect to /api/v1/* for all existing routes.
   
   Args:
     routes - Vector of normalized route maps (versioned)
     target-version - Version to redirect to (default :v1)
     
   Returns:
     Vector of redirect route maps
     
   Side Effects:
     - Logging
     
   Example:
     (create-backward-compatibility-routes
       [{:path \"/api/v1/users\" ...}
        {:path \"/api/v1/items\" ...}]
       :v1)
     ;;=> [{:path \"/api/users\" :methods {:get redirect-handler ...}}
     ;;    {:path \"/api/items\" :methods {:get redirect-handler ...}}]"
  ([routes]
   (create-backward-compatibility-routes routes :v1))
  ([routes target-version]
   (let [;; Extract paths and remove version prefix
         version-str (name target-version)
         version-prefix (str "/api/" version-str)
         unversioned-paths (->> routes
                                (map :path)
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
  "Apply API versioning to routes.
   
   Takes unversioned API routes and returns versioned routes with:
   - Version prefix (/api/v1/users)
   - Version headers middleware
   - Backward compatibility redirects
   
   Args:
     api-routes - Vector of normalized API route maps (unversioned)
     config - Application config map
     
   Returns:
     Vector of versioned and redirect routes
     
   Side Effects:
     - Route transformation
     - Logging
     
   Example:
     (apply-versioning
       [{:path \"/users\" :methods {:get {...}}}
        {:path \"/items\" :methods {:get {...}}}]
       {:active {:wagoe/api-versioning
                 {:default-version :v1
                  :latest-stable :v1
                  :supported-versions #{:v1}}}})
     ;;=> [{:path \"/api/v1/users\" ...}
     ;;    {:path \"/api/v1/items\" ...}
     ;;    {:path \"/api/users\" :methods {:get redirect-handler ...}}
     ;;    {:path \"/api/items\" :methods {:get redirect-handler ...}}]"
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
  "Example usage:
   
   ;; Define unversioned routes
   (def user-routes
     [{:path \"/users\"
       :methods {:get {:handler 'user/list-users}
                 :post {:handler 'user/create-user}}}
      {:path \"/users/:id\"
       :methods {:get {:handler 'user/get-user}
                 :put {:handler 'user/update-user}
                 :delete {:handler 'user/delete-user}}}])
   
   ;; Apply versioning
   (def versioned-routes
     (apply-versioning
       user-routes
       {:active {:wagoe/api-versioning
                 {:default-version :v1
                  :latest-stable :v1
                  :deprecated-versions #{}
                  :supported-versions #{:v1}}}}))
   
   ;; Result:
   ;; [{:path \"/api/v1/users\" :methods {...}}     ; Versioned route
   ;;  {:path \"/api/v1/users/:id\" :methods {...}} ; Versioned route
   ;;  {:path \"/api/users\" :methods {:get redirect-handler ...}} ; Redirect
   ;;  {:path \"/api/users/:id\" :methods {:get redirect-handler ...}}] ; Redirect
   
   ;; Compile routes with router
   (def handler (compile-routes router versioned-routes config))
   
   ;; Wrap handler with version headers
   (def final-handler
     (wrap-handler-with-version-headers handler config))
   
   ;; Now requests to /api/users redirect to /api/v1/users
   ;; And all responses include version headers")
