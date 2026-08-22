(ns wagoe.search.shell.http
  "HTTP API routes and admin web UI handlers for wagoe-search.

   API Endpoints (mounted under /api/v1):
     POST   /search/:index-id              — search
     POST   /search/:index-id/suggest      — trigram suggestions
     POST   /search/documents              — index a document
     DELETE /search/documents/:type/:id    — remove a document

   Admin Web UI (mounted under /web/admin):
     GET    /search                        — list all indices
     GET    /search/:index-id              — index detail + live search form
     POST   /search/:index-id/search       — HTMX search results fragment"
  (:require [wagoe.i18n.shell.middleware :as i18n-middleware]
            [wagoe.i18n.shell.render :as i18n]
            [wagoe.search.ports :as ports]
            [wagoe.search.core.ui :as search-ui]
            [clojure.tools.logging :as log])
  (:import [java.util UUID]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- parse-uuid-param
  [s param-name]
  (try
    (UUID/fromString s)
    (catch IllegalArgumentException _
      (throw (ex-info (str "Invalid UUID for " param-name)
                      {:type    :validation-error
                       :field   param-name
                       :value   s
                       :message (str param-name " must be a valid UUID")})))))

(defn- resolve-t
  "Resolve an i18n translation function at render time.

   Prefers `resolve-t-fn`, which reads `:user` from the fully-enriched
   request (after auth middleware has run), so it respects the authenticated
   user's language preference. Falls back to the eager `:i18n/t` from
   `wrap-i18n` (tenant/default only), then to a safe identity fallback that
   renders the key name for `[:t ...]` markers."
  [request]
  (or (i18n-middleware/resolve-t-fn request)
      (get request :i18n/t)
      (fn
        ([k] (if (keyword? k) (name k) (str k)))
        ([k _params] (if (keyword? k) (name k) (str k)))
        ([k _params _n] (if (keyword? k) (name k) (str k))))))

(defn- html-response
  ([request hiccup]
   (html-response request hiccup 200))
  ([request hiccup status]
   {:status  status
    :headers {"Content-Type" "text/html; charset=utf-8"}
    :body    (i18n/render hiccup (resolve-t request))}))

;; =============================================================================
;; API handlers
;; =============================================================================

(defn handle-search
  "POST /api/v1/search/:index-id"
  [engine request]
  (let [index-id   (keyword (get-in request [:path-params :index-id]))
        body       (get-in request [:parameters :body] {})
        query      (get body :query "")
        limit      (get body :limit 20)
        offset     (get body :offset 0)
        highlight? (get body :highlight? false)
        filters    (when-let [f (get body :filters)] (not-empty f))]
    (log/info "Search request" {:index-id index-id :query query})
    (let [response (ports/search engine index-id query
                                 (cond-> {:limit      limit
                                          :offset     offset
                                          :highlight? highlight?}
                                   filters (assoc :filters filters)))]
      {:status 200
       :body   {:results  (mapv (fn [r]
                                  (cond-> {:entityType (name (:entity-type r))
                                           :entityId   (str (:entity-id r))
                                           :rank       (:rank r)}
                                    (:snippet r)   (assoc :snippet (:snippet r))
                                    (:metadata r)  (assoc :metadata (:metadata r))))
                                (:results response))
                :total    (:total response)
                :query    (:query response)
                :tookMs   (:took-ms response)}})))

(defn handle-suggest
  "POST /api/v1/search/:index-id/suggest"
  [engine request]
  (let [index-id     (keyword (get-in request [:path-params :index-id]))
        body         (get-in request [:parameters :body] {})
        query        (get body :query "")
        limit        (get body :limit 5)
        suggestions  (ports/suggest engine index-id query {:limit limit})]
    {:status 200
     :body   {:suggestions (mapv (fn [r]
                                   {:entityType (name (:entity-type r))
                                    :entityId   (str (:entity-id r))
                                    :rank       (:rank r)})
                                 suggestions)
              :query       query}}))

(defn handle-index-document
  "POST /api/v1/search/documents"
  [engine request]
  (let [body          (get-in request [:parameters :body] {})
        index-id      (keyword (:indexId body))
        entity-id     (parse-uuid-param (:entityId body) "entityId")
        fields        (into {} (map (fn [[k v]] [(keyword k) v]) (:fields body {})))
        metadata      (:metadata body)
        filter-values (when-let [fv (:filterValues body)]
                        (into {} (map (fn [[k v]] [(keyword k) v]) fv)))]
    (log/info "Indexing document via HTTP"
              {:index-id index-id :entity-id entity-id})
    (ports/index-document! engine index-id entity-id fields
                           (cond-> {}
                             metadata      (assoc :metadata metadata)
                             filter-values (assoc :filter-values filter-values)))
    {:status 200
     :body   {:indexed  true
              :entityId (str entity-id)}}))

(defn handle-remove-document
  "DELETE /api/v1/search/documents/:entity-type/:entity-id"
  [engine request]
  (let [entity-type (keyword (get-in request [:path-params :entity-type]))
        entity-id   (parse-uuid-param
                     (get-in request [:path-params :entity-id]) "entity-id")
        index-id    (keyword (get-in request [:query-params :index-id]
                                     (name entity-type)))]
    (log/info "Removing document via HTTP"
              {:index-id index-id :entity-id entity-id})
    (ports/remove-document! engine index-id entity-id)
    {:status 200
     :body   {:removed  true
              :entityId (str entity-id)}}))

;; =============================================================================
;; Admin web handlers
;; =============================================================================

(defn handle-list-indices-web
  "GET /web/admin/search"
  [engine request]
  (let [indices   (ports/list-indices engine)
        page-opts {:user  (:user request)
                   :flash (:flash request)}]
    (html-response request (search-ui/indices-page indices page-opts))))

(defn handle-get-index-web
  "GET /web/admin/search/:index-id"
  [engine request]
  (let [index-id   (keyword (get-in request [:path-params :index-id]))
        indices    (ports/list-indices engine)
        index-info (first (filter #(= index-id (:id %)) indices))
        page-opts  {:user  (:user request)
                    :flash (:flash request)}]
    (if (nil? index-info)
      {:status  404
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body    (i18n/render
                 [:div [:p "Search index " [:code (name index-id)] " not found."]]
                 (resolve-t request))}
      (html-response request
                     (search-ui/index-detail-page index-info nil nil page-opts)))))

(defn handle-search-fragment
  "POST /web/admin/search/:index-id/search — HTMX fragment"
  [engine request]
  (let [index-id    (keyword (get-in request [:path-params :index-id]))
        form-params (get-in request [:form-params] {})
        query       (get form-params "query" "")
        results     (ports/search engine index-id query
                                  {:limit 20 :highlight? true})]
    {:status  200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body    (i18n/render
               (search-ui/search-results-fragment
                (:results results)
                (:query results)
                (:total results)
                (:took-ms results))
               (resolve-t request))}))

;; =============================================================================
;; Route definitions
;; =============================================================================

(defn search-routes
  "Reitit route data for the search API. Mounted under /api/v1.

   Args:
     engine - SearchService (ISearchEngine)"
  [engine]
  [["/search/documents"
    {:post {:handler (fn [req] (handle-index-document engine req))
            :summary "Index a search document"}}]
   ["/search/documents/:entity-type/:entity-id"
    {:delete {:handler (fn [req] (handle-remove-document engine req))
              :summary "Remove a search document"}}]
   ;; After /search/documents: reitit matches literal segments before
   ;; parameters, but declaring the specific ones first says so to a reader.
   ["/search/:index-id"
    {:post {:handler (fn [req] (handle-search engine req))
            :summary "Full-text search"}}]
   ["/search/:index-id/suggest"
    {:post {:handler (fn [req] (handle-suggest engine req))
            :summary "Trigram suggestions"}}]])

(defn search-web-routes
  "Reitit route data for the search Admin web UI.

   Mounted under /web/admin by the HTTP handler — see this module's
   `:web-prefix`.

   Args:
     engine - SearchService (ISearchEngine)"
  [engine]
  [["/search"
    {:get {:handler (fn [req] (handle-list-indices-web engine req))
           :summary "Search indices admin page"}}]
   ["/search/:index-id"
    {:get {:handler (fn [req] (handle-get-index-web engine req))
           :summary "Search index detail page"}}]
   ["/search/:index-id/search"
    {:post {:handler (fn [req] (handle-search-fragment engine req))
            :summary "HTMX search results fragment"}}]])
