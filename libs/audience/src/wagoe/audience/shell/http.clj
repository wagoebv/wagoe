(ns wagoe.audience.shell.http
  "HTTP handlers and route definitions for audience management.

   Web Pages (mounted under /web/audiences):
     GET    /audiences               — list all segments
     GET    /audiences/builder       — new segment form
     GET    /audiences/builder/:id   — edit existing segment
     POST   /audiences               — create from the builder form
     POST   /audiences/builder/:id   — update from the builder form

   API Endpoints (mounted under /api/audiences):
     POST   /audiences               — create audience
     PUT    /audiences/:id           — update audience
     DELETE /audiences/:id           — delete audience
     POST   /audiences/preview       — returns count + sample
     POST   /audiences/:id/evaluate  — trigger evaluation
     GET    /audiences/:id/members   — list members

   JSON keys are camelCase (`cacheConfig`), converted to kebab-case here."
  (:require [wagoe.audience.core.composition :as composition]
            [wagoe.audience.core.filter :as filter]
            [wagoe.audience.core.ui :as ui]
            [wagoe.audience.ports :as ports]
            [wagoe.audience.schema :as schema]
            [cheshire.core :as json]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [hiccup2.core :as h]
            [malli.core :as m]
            [malli.transform :as mt]
            [malli.util :as mu]))

;; =============================================================================
;; Helpers
;; =============================================================================

(def ^:private create-body
  "A definition as JSON carries it: camelCase keys."
  (-> schema/DynamicAudienceDefinition
      (mu/dissoc :cache-config)
      (mu/assoc :cacheConfig [:map
                              [:ttlMinutes {:optional true} :int]
                              [:refreshSchedule {:optional true} :string]])
      (mu/optional-keys [:cacheConfig])))

(def ^:private update-body
  "An update takes its id from the path."
  (mu/dissoc create-body :id))

(defn- body->definition
  "The kebab-case definition for a coerced camelCase body."
  [body]
  (cond-> (set/rename-keys body {:cacheConfig :cache-config})
    (:cacheConfig body) (update :cache-config set/rename-keys {:ttlMinutes      :ttl-minutes
                                                               :refreshSchedule :refresh-schedule})))

(defn- parse-audience-id
  "Parse a path-param string into a keyword, or nil if it fails validation.
   Guards against keyword interning DoS by rejecting invalid patterns."
  [s]
  (when (and (string? s) (re-matches #"[a-z0-9][a-z0-9\-]{0,63}" s))
    (keyword s)))

(defn- error-response
  "The framework's one error body (BOU-586). Audience does not depend on
   platform, so the shape of `wagoe.platform.core.http.errors` is written out.
   No Content-Type: muuntaja encodes a map body only when none is set."
  [status type message & [details]]
  {:status status
   :body   {:error (cond-> {:type type :message message}
                     (seq details) (assoc :details details))}})

(defn- bad-request-response [message]
  (error-response 400 "validation-error" message))

(defn- not-found-response [message]
  (error-response 404 "not-found" message))

(defn- unprocessable-response [message & [details]]
  (error-response 422 "unprocessable" message details))

(defn- internal-error-response [message]
  (error-response 500 "internal-error" message))

(defn- invalid-definition-response
  "A 422 when `definition` cannot be saved, or nil. The route's body schema has
   already checked its shape."
  [definition filter-fields]
  (when-let [err (:error (or (composition/explain-filters-beside-compose definition)
                             (filter/explain-filter-fields (:filters definition)
                                                           (or filter-fields filter/default-filter-fields))))]
    (error-response 422 "validation-error" (:message err))))

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
   ;; No Content-Type: muuntaja encodes a map body only when none is set.
   {:status status
    :body   body}))

(defn- handle-audience-error
  "Map ex-info :type to appropriate HTTP response."
  [e audience-id]
  (let [data (ex-data e)]
    (case (:type data)
      :audience-not-found
      (not-found-response (str "Audience not found: " (name audience-id)))

      :audience-exists
      (error-response 409 "conflict" (str "Audience already exists: " (name audience-id)))

      :circular-reference
      (unprocessable-response "Circular segment reference detected" {:id (:id data)})

      (:unknown-segment-ref :invalid-segment :composition-error)
      (unprocessable-response (ex-message e) (select-keys data [:id]))

      :unsupported-filter-op
      (unprocessable-response (ex-message e) (select-keys data [:op :filter-type]))

      :unknown-filter-type
      (unprocessable-response (ex-message e) (select-keys data [:filter-type]))

      :validation-error
      (error-response 422 "validation-error" (ex-message e))

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
  [_resolver _store request]
  (log/debug "Rendering new audience builder page")
  (html-response (ui/builder-layout {:csrf-token (:anti-forgery-token request)})))

(defn builder-edit-handler
  "GET /web/audiences/builder/:id — edit existing segment."
  [_resolver store request]
  (let [id (parse-audience-id (get-in request [:path-params :id]))]
    (if-not id
      (bad-request-response "Invalid audience id")
      (try
        (let [segment (ports/find-audience store id)]
          (if segment
            (html-response (ui/builder-layout {:segment    segment
                                               :edit?      true
                                               :csrf-token (:anti-forgery-token request)}))
            {:status  404
             :headers {"Content-Type" "text/html; charset=utf-8"}
             :body    (str (h/html [:div [:p "Audience not found."]]))}))
        (catch Exception e
          (log/error e "Failed to load audience for builder" {:id id})
          (html-response [:div [:p "An error occurred."]] 500))))))

;; =============================================================================
;; Saving, shared by the API and the builder form
;; =============================================================================

(defn- invalid-new-id-response
  "A 400 unless `id` is one every /audiences/:id path accepts, else nil.
   /audiences/preview matches the literal route, not :id."
  [id]
  (let [id (some-> id symbol str)]
    (when (or (= "preview" id) (not (parse-audience-id id)))
      (bad-request-response "Invalid audience id"))))

(defn- save-response
  "Check `definition`, then save it with `save!`: `status` and the saved id and
   label, or the error response."
  [save! definition filter-fields status]
  (or
   (invalid-definition-response definition filter-fields)
   (try
     (log/info "Saving audience" {:id (:id definition)})
     (let [saved (save! definition)]
       (json-response {:id    (name (:id saved))
                       :label (:label saved)}
                      status))
     (catch clojure.lang.ExceptionInfo e
       (handle-audience-error e (:id definition)))
     (catch Exception e
       (log/error e "Failed to save audience" {:id (:id definition)})
       (internal-error-response "Internal error")))))

;; =============================================================================
;; Builder form handlers
;; =============================================================================

(def ^:private decode-form-definition
  (m/decoder schema/DynamicAudienceDefinition
             (mt/transformer mt/strip-extra-keys-transformer mt/json-transformer)))

(defn- form->definition
  "The definition a builder form posts, or nil when it is not one."
  [{:strs [id label description filters-data compose-data]}]
  (let [parse      #(when-not (str/blank? %) (json/parse-string % true))
        definition (try
                     (decode-form-definition
                      (cond-> {:id id :label label :filters (or (parse filters-data) [])}
                        (not (str/blank? description)) (assoc :description description)
                        (not (str/blank? compose-data)) (assoc :compose (parse compose-data))))
                     (catch Exception _ nil))]
    (when (m/validate schema/DynamicAudienceDefinition definition)
      definition)))

(defn- form-response
  "Back to the list when `response` saved; otherwise the builder again, showing
   `segment` and the error."
  [response request segment edit?]
  (if (< (:status response) 300)
    {:status 303 :headers {"Location" "/web/audiences"} :body ""}
    (html-response (ui/builder-layout {:segment    segment
                                       :edit?      edit?
                                       :error      (get-in response [:body :error :message])
                                       :csrf-token (:anti-forgery-token request)})
                   (:status response))))

(defn create-audience-form-handler
  "POST /web/audiences — create from the builder form."
  [_resolver store request & [filter-fields]]
  (let [params     (:form-params request)
        definition (form->definition params)]
    (form-response (or (when-not definition (bad-request-response "Invalid audience definition"))
                       (invalid-new-id-response (:id definition))
                       (save-response #(ports/create-audience store %) definition filter-fields 201))
                   request
                   (or definition {:label (get params "label") :description (get params "description")})
                   false)))

(defn update-audience-form-handler
  "POST /web/audiences/builder/:id — update from the builder form. What the
   form has no field for — cache config and tags — is kept."
  [_resolver store request & [filter-fields]]
  (if-let [id (parse-audience-id (get-in request [:path-params :id]))]
    (let [params     (:form-params request)
          definition (form->definition (assoc params "id" (name id)))
          keep-rest  (fn [d] (merge (select-keys (ports/find-audience store id) [:cache-config :tags]) d))]
      (form-response (if definition
                       (save-response #(ports/update-audience store (keep-rest %)) definition filter-fields 200)
                       (bad-request-response "Invalid audience definition"))
                     request
                     (or definition {:id id :label (get params "label") :description (get params "description")})
                     true))
    (bad-request-response "Invalid audience id")))

;; =============================================================================
;; API handlers
;; =============================================================================

(defn create-audience-handler
  "POST /api/audiences — create a new audience definition; 409 when its id is
   taken. `filter-fields` are the fields a filter may name,
   `filter/default-filter-fields` when nil."
  [_resolver store request & [filter-fields]]
  (let [definition (body->definition (get-in request [:parameters :body]))]
    (or (invalid-new-id-response (:id definition))
        (save-response #(ports/create-audience store %) definition filter-fields 201))))

(defn update-audience-handler
  "PUT /api/audiences/:id — replace an audience definition; 404 when none is
   stored. See `create-audience-handler` for `filter-fields`."
  [_resolver store request & [filter-fields]]
  (if-let [id (parse-audience-id (get-in request [:path-params :id]))]
    (save-response #(ports/update-audience store %)
                   (assoc (body->definition (get-in request [:parameters :body])) :id id)
                   filter-fields 200)
    (bad-request-response "Invalid audience id")))

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
  "POST /api/audiences/preview — resolve the audience named by `audienceId` and
   return count + sample."
  [resolver _store request]
  (if-let [audience-id (parse-audience-id (get-in request [:parameters :body :audienceId]))]
    (try
      (log/debug "Previewing audience" {:audience-id audience-id})
      (let [result (ports/resolve-audience resolver audience-id {:force-refresh? true})]
        (json-response {:count  (:count result)
                        :sample (take 10 (:user-ids result))}))
      (catch clojure.lang.ExceptionInfo e
        (handle-audience-error e audience-id))
      (catch Exception e
        (log/error e "Failed to preview audience" {:audience-id audience-id})
        (internal-error-response "Internal error")))
    (bad-request-response "audienceId must name a saved audience: lowercase letters, digits and dashes")))

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

(defn- guarded
  "Attach `middleware` to every route in `routes`.

   Every route in this namespace manages segments or reads their membership, so
   the guard is applied to all of them rather than route by route — a new route
   added below is protected by existing, which is the opposite of the default
   that let these ship reachable by anyone (BOU-419 review)."
  [middleware routes]
  (let [mw (vec middleware)]
    (if (empty? mw)
      routes
      (mapv (fn [[path data]] [path (update data :middleware (fnil into []) mw)]) routes))))

(defn audience-web-routes
  "Reitit route data for the audience web pages. Mounted under /web.

   Args:
     resolver   - IAudienceResolver
     store      - IAudienceRepository
     middleware - route middleware every page is wrapped in (authorization)
     opts       - {:filter-fields #{...}}, the fields a filter may name"
  ([resolver store] (audience-web-routes resolver store []))
  ([resolver store middleware] (audience-web-routes resolver store middleware {}))
  ([resolver store middleware {:keys [filter-fields]}]
   (guarded middleware
            [["/audiences"
              {:get  {:handler (fn [req] (list-audiences-handler resolver store req))
                      :summary "List audience segments"}
               :post {:handler (fn [req] (create-audience-form-handler resolver store req filter-fields))
                      :summary "Create audience from the builder form"}}]
             ["/audiences/builder"
              {:get {:handler (fn [req] (builder-page-handler resolver store req))
                     :summary "New audience builder page"}}]
             ["/audiences/builder/:id"
              {:get  {:handler (fn [req] (builder-edit-handler resolver store req))
                      :summary "Edit audience builder page"}
               :post {:handler (fn [req] (update-audience-form-handler resolver store req filter-fields))
                      :summary "Update audience from the builder form"}}]])))

(defn audience-api-routes
  "Reitit route data for the audience API. Mounted under /api/v1.

   Args:
     resolver   - IAudienceResolver
     store      - IAudienceRepository
     middleware - route middleware every endpoint is wrapped in (authorization)
     opts       - {:filter-fields #{...}}, the fields a filter may name"
  ([resolver store] (audience-api-routes resolver store []))
  ([resolver store middleware] (audience-api-routes resolver store middleware {}))
  ([resolver store middleware {:keys [filter-fields]}]
   (guarded middleware
            [["/audiences"
              {:post {:handler    (fn [req] (create-audience-handler resolver store req filter-fields))
                      :summary    "Create audience segment"
                      :parameters {:body create-body}}}]
             ["/audiences/preview"
              {:post {:handler    (fn [req] (preview-audience-handler resolver store req))
                      :summary    "Preview audience filter results"
                      :parameters {:body [:map [:audienceId :string]]}}}]
             ["/audiences/:id"
              {:put    {:handler    (fn [req] (update-audience-handler resolver store req filter-fields))
                        :summary    "Update audience segment"
                        :parameters {:body update-body}}
               :delete {:handler (fn [req] (delete-audience-handler resolver store req))
                        :summary "Delete audience segment"}}]
             ["/audiences/:id/evaluate"
              {:post {:handler (fn [req] (evaluate-audience-handler resolver store req))
                      :summary "Evaluate and cache audience"}}]
             ["/audiences/:id/members"
              {:get {:handler (fn [req] (list-members-handler resolver store req))
                     :summary "List audience member user-ids"}}]])))
