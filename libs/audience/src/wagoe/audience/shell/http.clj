(ns wagoe.audience.shell.http
  "HTTP handlers and route definitions for audience management.

   Web Pages (mounted under /web/audiences):
     GET    /audiences               — list all segments
     GET    /audiences/builder       — new segment form
     GET    /audiences/builder/:id   — edit existing segment

   API Endpoints (mounted under /api/audiences):
     POST   /audiences               — create audience
     PUT    /audiences/:id           — update audience
     DELETE /audiences/:id           — delete audience
     POST   /audiences/preview       — returns count + sample
     POST   /audiences/:id/evaluate  — trigger evaluation
     GET    /audiences/:id/members   — list members"
  (:require [wagoe.audience.core.ui :as ui]
            [wagoe.audience.ports :as ports]
            [wagoe.audience.schema :as schema]
            [clojure.tools.logging :as log]
            [hiccup2.core :as h]
            [malli.core :as m]))

;; =============================================================================
;; Helpers
;; =============================================================================

(def ^:private dynamic-audience-definition-validator
  (m/validator schema/DynamicAudienceDefinition))

(def ^:private dynamic-audience-definition-explainer
  (m/explainer schema/DynamicAudienceDefinition))

(defn- parse-audience-id
  "Parse a path-param string into a keyword, or nil if it fails validation.
   Guards against keyword interning DoS by rejecting invalid patterns."
  [s]
  (when (and (string? s) (re-matches #"[a-z0-9][a-z0-9\-]{0,63}" s))
    (keyword s)))

(defn- bad-request-response
  "Return a 400 Bad Request response."
  [message]
  {:status  400
   :headers {"Content-Type" "application/json"}
   :body    {:error message}})

(defn- not-found-response
  "Return a 404 Not Found response."
  [message]
  {:status  404
   :headers {"Content-Type" "application/json"}
   :body    {:error message}})

(defn- unprocessable-response
  "Return a 422 Unprocessable Entity response."
  [message & [details]]
  {:status  422
   :headers {"Content-Type" "application/json"}
   :body    (cond-> {:error message}
              details (assoc :details details))})

(defn- internal-error-response
  "Return a 500 Internal Server Error response."
  [message]
  {:status  500
   :headers {"Content-Type" "application/json"}
   :body    {:error message}})

(defn- html-response
  ([hiccup]
   (html-response hiccup 200))
  ([hiccup status]
   {:status  status
    :headers {"Content-Type" "text/html; charset=utf-8"}
    :body    (str (h/html hiccup))}))

(defn- json-response
  ([body]
   (json-response body 200))
  ([body status]
   {:status  status
    :headers {"Content-Type" "application/json"}
    :body    body}))

(defn- handle-audience-error
  "Map ex-info :type to appropriate HTTP response."
  [e audience-id]
  (let [data (ex-data e)]
    (case (:type data)
      :audience-not-found
      (not-found-response (str "Audience not found: " (name audience-id)))

      :circular-reference
      (unprocessable-response "Circular segment reference detected" {:id (:id data)})

      :unsupported-filter-op
      (unprocessable-response (ex-message e) (select-keys data [:op :filter-type]))

      :unknown-filter-type
      (unprocessable-response (ex-message e) (select-keys data [:filter-type]))

      ;; Unrecognized ex-info type — log and return 500
      (do (log/error e "Unexpected error in audience handler" {:audience-id audience-id})
          (internal-error-response "Internal error")))))

;; =============================================================================
;; Web handlers
;; =============================================================================

(defn list-audiences-handler
  "GET /web/audiences — lists all segments."
  [_resolver store _request]
  (log/debug "Listing audiences")
  (try
    (let [segments (ports/list-audiences store)]
      (html-response
       [:div.min-h-screen.bg-gray-50.p-6
        [:div.max-w-4xl.mx-auto
         [:div.flex.items-center.justify-between.mb-6
          [:h1.text-2xl.font-bold "Audiences"]
          [:a.rounded-md.bg-blue-600.px-4.py-2.text-white.text-sm
           {:href "/web/audiences/builder"} "New Audience"]]
         (if (seq segments)
           (ui/segment-list segments)
           [:p.text-gray-500 "No audience segments defined yet."])]]))
    (catch Exception e
      (log/error e "Failed to list audiences")
      (html-response [:div [:p "An error occurred loading audiences."]] 500))))

(defn builder-page-handler
  "GET /web/audiences/builder — new segment form."
  [_resolver _store _request]
  (log/debug "Rendering new audience builder page")
  (html-response (ui/builder-layout {})))

(defn builder-edit-handler
  "GET /web/audiences/builder/:id — edit existing segment."
  [_resolver store request]
  (let [id (parse-audience-id (get-in request [:path-params :id]))]
    (if-not id
      (bad-request-response "Invalid audience id")
      (try
        (let [segment (ports/find-audience store id)]
          (if segment
            (html-response (ui/builder-layout {:segment segment}))
            {:status  404
             :headers {"Content-Type" "text/html; charset=utf-8"}
             :body    (str (h/html [:div [:p "Audience not found."]]))}))
        (catch Exception e
          (log/error e "Failed to load audience for builder" {:id id})
          (html-response [:div [:p "An error occurred."]] 500))))))

;; =============================================================================
;; API handlers
;; =============================================================================

(defn create-audience-handler
  "POST /api/audiences — create a new audience definition."
  [_resolver store request]
  (let [body       (get-in request [:parameters :body] {})
        definition (cond-> body
                     (:filtersData body) (assoc :filters (:filtersData body)))]
    (if-not (dynamic-audience-definition-validator definition)
      (let [explanation (dynamic-audience-definition-explainer definition)]
        (log/warn "Invalid audience definition submitted" {:errors explanation})
        (unprocessable-response "Invalid audience definition"
                                {:errors (str explanation)}))
      (try
        (log/info "Creating audience" {:label (:label definition)})
        (let [saved (ports/save-audience store definition)]
          (json-response {:id    (str (:id saved))
                          :label (:label saved)}
                         201))
        (catch clojure.lang.ExceptionInfo e
          (handle-audience-error e (:id definition)))
        (catch Exception e
          (log/error e "Failed to create audience" {:label (:label definition)})
          (internal-error-response "Internal error"))))))

(defn update-audience-handler
  "PUT /api/audiences/:id — update an existing audience definition."
  [_resolver store request]
  (let [id (parse-audience-id (get-in request [:path-params :id]))]
    (if-not id
      (bad-request-response "Invalid audience id")
      (let [body       (get-in request [:parameters :body] {})
            definition (assoc body :id id)]
        (if-not (dynamic-audience-definition-validator definition)
          (unprocessable-response "Invalid audience definition"
                                  {:errors (str (dynamic-audience-definition-explainer definition))})
          (try
            (log/info "Updating audience" {:id id})
            (let [updated (ports/save-audience store definition)]
              (json-response {:id    (str (:id updated))
                              :label (:label updated)}))
            (catch clojure.lang.ExceptionInfo e
              (handle-audience-error e id))
            (catch Exception e
              (log/error e "Failed to update audience" {:id id})
              (internal-error-response "Internal error"))))))))

(defn delete-audience-handler
  "DELETE /api/audiences/:id — delete an audience definition."
  [_resolver store request]
  (let [id (parse-audience-id (get-in request [:path-params :id]))]
    (if-not id
      (bad-request-response "Invalid audience id")
      (try
        (log/info "Deleting audience" {:id id})
        (ports/delete-audience store id)
        {:status 204 :body nil}
        (catch clojure.lang.ExceptionInfo e
          (handle-audience-error e id))
        (catch Exception e
          (log/error e "Failed to delete audience" {:id id})
          (internal-error-response "Internal error"))))))

(defn preview-audience-handler
  "POST /api/audiences/preview — evaluate filters and return count + sample."
  [resolver _store request]
  (let [body        (get-in request [:parameters :body] {})
        raw-id      (get body :audienceId)
        audience-id (when raw-id (parse-audience-id raw-id))]
    (if (and raw-id (nil? audience-id))
      (bad-request-response "Invalid audienceId format")
      (let [resolved-id (or audience-id :preview)]
        (try
          (log/debug "Previewing audience" {:audience-id resolved-id})
          (let [result (ports/resolve-audience resolver resolved-id {:force-refresh? true})]
            (json-response {:count  (:count result)
                            :sample (take 10 (:user-ids result))}))
          (catch clojure.lang.ExceptionInfo e
            (handle-audience-error e resolved-id))
          (catch Exception e
            (log/error e "Failed to preview audience" {:audience-id resolved-id})
            (internal-error-response "Internal error")))))))

(defn evaluate-audience-handler
  "POST /api/audiences/:id/evaluate — trigger full evaluation and cache."
  [resolver _store request]
  (let [audience-id (parse-audience-id (get-in request [:path-params :id]))]
    (if-not audience-id
      (bad-request-response "Invalid audience id")
      (try
        (log/info "Evaluating audience" {:id audience-id})
        (let [result (ports/resolve-audience resolver audience-id {:force-refresh? true})]
          (json-response {:count       (:count result)
                          :cachedAt    (str (:evaluated-at result))
                          :cached      (:cached? result)}))
        (catch clojure.lang.ExceptionInfo e
          (handle-audience-error e audience-id))
        (catch Exception e
          (log/error e "Failed to evaluate audience" {:id audience-id})
          (internal-error-response "Internal error"))))))

(defn list-members-handler
  "GET /api/audiences/:id/members — list member user-ids for an audience."
  [resolver _store request]
  (let [audience-id (parse-audience-id (get-in request [:path-params :id]))]
    (if-not audience-id
      (bad-request-response "Invalid audience id")
      (try
        (log/debug "Listing members" {:id audience-id})
        (let [result (ports/resolve-audience resolver audience-id)]
          (json-response {:count   (:count result)
                          :userIds (mapv str (:user-ids result))}))
        (catch clojure.lang.ExceptionInfo e
          (handle-audience-error e audience-id))
        (catch Exception e
          (log/error e "Failed to list members" {:id audience-id})
          (internal-error-response "Internal error"))))))

;; =============================================================================
;; Route definitions
;; =============================================================================

(defn audience-web-routes
  "Return normalized route definitions for the audience web pages.

   Routes will be mounted under /web by the HTTP handler.

   Args:
     resolver - IAudienceResolver
     store    - IAudienceRepository

   Returns:
     Vector of normalized route maps"
  [resolver store]
  [{:path    "/audiences"
    :methods {:get {:handler (fn [req] (list-audiences-handler resolver store req))
                    :summary "List audience segments"}}}
   {:path    "/audiences/builder"
    :methods {:get {:handler (fn [req] (builder-page-handler resolver store req))
                    :summary "New audience builder page"}}}
   {:path    "/audiences/builder/:id"
    :methods {:get {:handler (fn [req] (builder-edit-handler resolver store req))
                    :summary "Edit audience builder page"}}}])

(defn audience-api-routes
  "Return normalized route definitions for the audience API.

   Routes will be mounted under /api by the HTTP handler.

   Args:
     resolver - IAudienceResolver
     store    - IAudienceRepository

   Returns:
     Vector of normalized route maps"
  [resolver store]
  [{:path    "/audiences"
    :methods {:post {:handler (fn [req] (create-audience-handler resolver store req))
                     :summary "Create audience segment"}}}
   {:path    "/audiences/preview"
    :methods {:post {:handler (fn [req] (preview-audience-handler resolver store req))
                     :summary "Preview audience filter results"}}}
   {:path    "/audiences/:id"
    :methods {:put    {:handler (fn [req] (update-audience-handler resolver store req))
                       :summary "Update audience segment"}
              :delete {:handler (fn [req] (delete-audience-handler resolver store req))
                       :summary "Delete audience segment"}}}
   {:path    "/audiences/:id/evaluate"
    :methods {:post {:handler (fn [req] (evaluate-audience-handler resolver store req))
                     :summary "Evaluate and cache audience"}}}
   {:path    "/audiences/:id/members"
    :methods {:get {:handler (fn [req] (list-members-handler resolver store req))
                    :summary "List audience member user-ids"}}}])
