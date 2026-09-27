(ns wagoe.admin.shell.http.handlers.crud
  "Create / update entity handlers."
  (:require
   [wagoe.admin.ports :as ports]
   [wagoe.admin.core.ui :as admin-ui]
   [wagoe.admin.core.forms :as forms]
   [wagoe.admin.core.permissions :as permissions]
   [wagoe.admin.shell.permissions :as shell-permissions]
   [wagoe.admin.shell.http.support :as support]
   [clojure.tools.logging :as log]
   [ring.util.response :as ring-response]))

(defn- client-safe-error-message
  "Message safe to show in an admin flash: a typed domain error whose mapped
   status is 4xx (same contract as the platform error path, BOU-161). Untyped,
   unmapped, or 5xx-mapped errors return nil — the caller shows a generic
   flash instead and the details stay in the server log (BOU-182).

   Handles both mapping shapes in combined-error-mappings: the platform's
   [status title] vectors and the admin map form {:status ...}."
  [e]
  (when-let [error-type (:type (ex-data e))]
    (let [mapping (get support/combined-error-mappings error-type)
          status  (cond
                    (vector? mapping) (first mapping)
                    (map? mapping)    (:status mapping))]
      (when (and status (< status 500))
        (ex-message e)))))

(defn- submitted-params
  "The fields the request carries, whichever decoder put them there.

   Ring leaves an empty `:form-params` on a request with a decoded body, and
   `{}` is truthy, so an `or` chain starting there never reached
   `:body-params`: a JSON write was read as no fields at all (BOU-477)."
  [request]
  (or (some #(when (seq %) %)
            [(:form-params request) (:body-params request) (:params request)])
      {}))

(defn- log-rejected!
  "Log a rejected form. At warn when an error is on a field the form does
   not show: that is a config problem the user cannot fix (BOU-570)."
  [entity-name entity-config errors]
  (let [off-form (keys (forms/off-form-errors entity-config errors))]
    (if (seq off-form)
      (log/warn "admin form rejected on fields it does not show"
                {:entity entity-name :fields (vec off-form) :errors errors})
      (log/info "admin form rejected" {:entity entity-name :fields (vec (keys errors))}))
    errors))

(defn- rejected-create-opts
  "Page opts for a create form shown again after it failed. The values go in
   as :prefill, not as the record: a record makes it an edit form that PUTs to
   an id-less URL (BOU-533). return_to keeps a child create tied to its parent."
  [admin-service config entity-configs entity-name request form-data & [nested]]
  {:display       (support/display-options config request)
   :prefill       form-data
   :return-to     (support/safe-return-to request)
   :field-options (support/foreign-key-options admin-service config entity-configs entity-name form-data)
   :nested        (:sections nested)})

;; =============================================================================
;; Child rows a parent is created with (BOU-570)
;; =============================================================================

(defn- parse-child-row
  "A filled child row as [data errors]: read with the child's config, and
   validated as a new child whose foreign key the parent will set. Only the
   row's own fields are read."
  [admin-service rel zones index raw]
  (let [entity  (:entity rel)
        raw     (select-keys raw (map name (:fields rel)))
        offsets (into {} (keep (fn [f]
                                 (when-let [o (get (:offsets zones) (keyword (forms/child-param entity index f)))]
                                   [(keyword f) o])))
                      (keys raw))
        [data parse-errors] (support/parse-form-params-checked raw (:entity-config rel) (assoc zones :offsets offsets))
        result  (ports/validate-entity-data admin-service entity (assoc data (:fk rel) (random-uuid)))]
    [data (merge (:errors result) parse-errors)]))

(defn- pad-rows
  "`rows`, with blank ones added up to `n`, so a form refused for too few
   rows still has them to fill in."
  [rows n]
  (let [used (set (map first rows))
        free (remove used (map str (range)))]
    (into (vec rows) (map (fn [i] [i {}]) (take (- n (count rows)) free)))))

(defn- parse-section
  "One has-many's submitted rows, parsed. Two rows under one index are
   refused on that row: merged, they would save a row nobody typed."
  [admin-service rel child-params zones]
  (let [filled (set (map first (forms/filled-rows rel child-params)))
        parsed (vec (for [[index raw] (get child-params (:entity rel))]
                      (cond
                        (forms/duplicate-row? rel raw)
                        {:index index :data {} :filled? true
                         :errors {(first (:fields rel)) [[:t :admin/child-row-duplicate]]}}

                        (filled index)
                        (let [[data errors] (parse-child-row admin-service rel zones index raw)]
                          {:index index :data data :errors errors :filled? true})

                        :else {:index index :data {}})))]
    {:rows           (map (juxt :index :data) parsed)
     :row-errors     (into {} (keep #(when (seq (:errors %)) [(:index %) (:errors %)])) parsed)
     :filled-indexes (mapv :index (filter :filled? parsed))
     :children       (mapv :data (filter :filled? parsed))}))

(defn- parse-nested
  "The child rows submitted for `rels`: {:sections (for the form) :children
   (for the service) :valid? bool}. A blank row is left out, not refused.
   Past `forms/max-child-rows` nothing is parsed, and none is shown again."
  [admin-service rels child-params zones]
  (let [too-few  (forms/too-few rels child-params)
        too-many (forms/too-many rels child-params)
        sections (vec (for [rel rels
                            :let [many    (get too-many (:entity rel))
                                  section (if many
                                            {:rows [] :row-errors {} :filled-indexes [] :children []}
                                            (parse-section admin-service rel child-params zones))]]
                        (-> section
                            (update :rows pad-rows (:min rel))
                            (assoc :rel rel
                                   :too-few (when-not many (get too-few (:entity rel)))
                                   :too-many many))))]
    {:sections sections
     :children (into {} (map (juxt (comp :entity :rel) :children)) sections)
     :valid?   (every? #(and (empty? (:row-errors %)) (nil? (:too-few %)) (nil? (:too-many %)))
                       sections)}))

(defn- nested-refusal
  "`nested` with a refusal from the service put where the form shows it: a
   row the database refused, or a has-many given too few rows."
  [nested e]
  (let [{:keys [child errors too-few too-many]} (ex-data e)]
    (update nested :sections
            (fn [sections]
              (mapv (fn [{:keys [rel filled-indexes] :as section}]
                      (cond-> section
                        (= (:entity child) (:entity rel))
                        (assoc-in [:row-errors (get filled-indexes (:index child))] errors)

                        (get too-few (:entity rel))
                        (assoc :too-few (get too-few (:entity rel)))

                        (get too-many (:entity rel))
                        (assoc :too-many (get too-many (:entity rel)))))
                    sections)))))

(defn create-entity-handler
  "Handler for creating new entity.

   Validates form data, creates entity, redirects to list with flash message."
  [admin-service schema-provider config]
  (fn [request]
    (let [user (support/require-admin-user! request)
          entity-name (support/get-entity-name request)

          ; Verify entity is accessible
          _ (when-not (ports/validate-entity-exists schema-provider entity-name)
              (throw (ex-info "Entity not allowed"
                              {:type :entity-not-allowed
                               :entity-name entity-name})))

          entity-config (ports/get-entity-config schema-provider entity-name)
          disabled (support/create-disabled-response request user entity-name entity-config)

          ; Check permissions
          _ (when-not disabled
              (shell-permissions/assert-can-create-entity! user entity-name entity-config))
          config-error (support/create-config-error-response request config schema-provider
                                                             user entity-name entity-config)

          [zones params] (support/form-zone-options config request (submitted-params request))
          [params child-params] (forms/split-child-params params)
          [form-data parse-errors] (support/parse-form-params-checked params entity-config zones)

          ; Validate data
          validation-result (ports/validate-entity-data admin-service entity-name form-data)

          ;; A parent with a :min has-many is created with its first
          ;; children (BOU-570); without one, as it always was.
          nested-rels (when-not (or disabled config-error)
                        (support/nested-relationships schema-provider entity-config))
          nested      (when (seq nested-rels)
                        (parse-nested admin-service nested-rels child-params zones))
          create!     (fn []
                        (if nested
                          (ports/create-entity-with-children admin-service entity-name form-data
                                                             (:children nested))
                          (ports/create-entity admin-service entity-name form-data)))]

      (cond
        disabled
        disabled

        config-error
        config-error

        (and (:valid? validation-result) (empty? parse-errors) (:valid? nested true))
        ; Create entity and return list page
        (try
          (if-let [return-to (support/safe-return-to request)]
            ;; Created from a parent's has-many panel: go back there (BOU-491).
            (do (create!)
                (-> (ring-response/response "")
                    (ring-response/header "HX-Redirect" return-to)))
            (let [_created-entity (create!)

                  ; Fetch list page data
                  entities (ports/list-available-entities schema-provider)
                  entity-configs (into {} (map (fn [e] [e (ports/get-entity-config schema-provider e)])) entities)

                  ; Get entity list with default options
                  result (ports/list-entities admin-service entity-name {})
                  records (support/with-workflow-states config entity-config (:records result))
                  total-count (:total-count result)
                  table-query {:page-size (:page-size result)
                               :page (:page-number result)}

                  permissions (permissions/get-entity-permissions user entity-name entity-config)]

              ; Return list page HTML with success message
              (support/html-response request
                                     (admin-ui/admin-layout
                                      (admin-ui/entity-list-page entity-name records entity-config table-query total-count permissions
                                                                 {:display (support/display-options config request)})
                                      {:user user
                                       :current-entity entity-name
                                       :entities entities
                                       :entity-configs entity-configs
                                       :logo-url (:logo-url config)
                                       :flash {:type :success
                                               :message [:t :admin/flash-created {:label (:label entity-config)}]}}))))
          (catch Exception e
            (let [;; A constraint the database enforced, reported on its field
                  ;; by the service (BOU-494).
                  refused? (= :validation-error (:type (ex-data e)))
                  ;; A refused child row carries its errors on the row.
                  field-errors (when refused?
                                 (log-rejected! entity-name entity-config
                                                (if (:child (ex-data e)) {} (:errors (ex-data e)))))
                  _ (when-not refused?
                      (log/error e "Failed to create entity" {:entity entity-name}))
                  nested (cond-> nested (and nested refused?) (nested-refusal e))
                  entities (ports/list-available-entities schema-provider)
                  entity-configs (into {} (map (fn [e] [e (ports/get-entity-config schema-provider e)])) entities)
                  permissions (permissions/get-entity-permissions user entity-name entity-config)]
              (cond->
               (support/html-response request
                                      (admin-ui/admin-layout
                                       (admin-ui/entity-detail-page entity-name entity-config nil (or field-errors {}) permissions (rejected-create-opts admin-service config entity-configs entity-name request form-data nested))
                                       {:user user
                                        :current-entity entity-name
                                        :entities entities
                                        :entity-configs entity-configs
                                        :logo-url (:logo-url config)
                                        :flash {:type :error
                                               ;; Only 4xx-mapped domain errors carry
                                               ;; client-safe messages; anything else is
                                               ;; internal — logged above, generic flash
                                               ;; (BOU-182: never echo raw exception text).
                                                :message (or (client-safe-error-message e)
                                                             [:t :admin/flash-create-failed
                                                              {:label (:label entity-config)}])}}))
                refused? (assoc :status 422)))))

        ; Validation errors - re-render form
        :else
        (let [entities (ports/list-available-entities schema-provider)
              entity-configs (into {} (map (fn [e] [e (ports/get-entity-config schema-provider e)])) entities)
              permissions (permissions/get-entity-permissions user entity-name entity-config)
              ;; {field [message]}, as the port returns it. Read as Malli
              ;; explain data it came out empty: 422 with nothing marked.
              errors (log-rejected! entity-name entity-config
                                    (merge (:errors validation-result) parse-errors))]

          (-> (support/html-response request
                                     (admin-ui/admin-layout
                                      (admin-ui/entity-detail-page entity-name entity-config nil errors permissions (rejected-create-opts admin-service config entity-configs entity-name request form-data nested))
                                      {:user user
                                       :current-entity entity-name
                                       :entities entities
                                       :entity-configs entity-configs
                                       :logo-url (:logo-url config)
                                       :flash {:type :error
                                               :message [:t :admin/flash-validation-errors]}}))
              ;; A form that was rejected is not a 200. The page is the same
              ;; either way, so the status is all that tells a caller —
              ;; anything that is not a browser — that nothing was written.
              (assoc :status 422)))))))

(defn update-entity-handler
  "Handler for updating existing entity.

   Validates form data, updates entity, redirects to list with flash message."
  [admin-service schema-provider config]
  (fn [request]
    (let [user (support/require-admin-user! request)
          entity-name (support/get-entity-name request)
          id (support/get-entity-id request)

          ; Verify entity is accessible
          _ (when-not (ports/validate-entity-exists schema-provider entity-name)
              (throw (ex-info "Entity not allowed"
                              {:type :entity-not-allowed
                               :entity-name entity-name})))

          entity-config (ports/get-entity-config schema-provider entity-name)

          ; Check permissions
          _ (shell-permissions/assert-can-edit-entity! user entity-name entity-config)

          [zones params] (support/form-zone-options config request (submitted-params request))
          [form-data parse-errors] (support/parse-form-params-checked params entity-config zones)

          ;; An update is validated as the entity it would leave behind, not as
          ;; the fields the request happened to carry. Validating `form-data`
          ;; alone failed every partial write on the fields it did not mention
          ;; — and the rejection branch answered 200, so the caller saw success
          ;; and an unchanged record (BOU-477).
          existing (ports/get-entity admin-service entity-name id)

          ;; A PUT to an id that is not there used to validate the form, write
          ;; zero rows and answer 200. It is a 404, and saying so is also what
          ;; keeps the merge below honest: merging into nothing would report a
          ;; missing row as a form full of missing fields.
          _ (when-not existing
              (throw (ex-info "Entity not found"
                              {:type        :not-found
                               :entity-name entity-name
                               :id          id})))

          merged   (merge existing form-data)

          validation-result (ports/validate-entity-data admin-service entity-name merged)]

      (if (and (:valid? validation-result) (empty? parse-errors))
        ; Update entity and re-render detail page with success flash
        (let [updated-record (ports/update-entity admin-service entity-name id form-data)
              permissions    (permissions/get-entity-permissions user entity-name entity-config)
              ctx             (support/build-entity-detail-opts admin-service schema-provider config entity-name entity-config updated-record request)]

          (support/html-response request
                                 (admin-ui/admin-layout
                                  (admin-ui/entity-detail-page entity-name entity-config updated-record {} permissions
                                                               (assoc (:page-opts ctx) :flash
                                                                      {:type :success
                                                                       :message [:t :admin/flash-updated {:label (:label entity-config)}]}))
                                  {:user user
                                   :current-entity entity-name
                                   :entities (:entities ctx)
                                   :entity-configs (:entity-configs ctx)
                                   :logo-url (:logo-url config)})))

        ; Validation errors - re-render form with flash inside page content
        (let [permissions (permissions/get-entity-permissions user entity-name entity-config)
              errors      (log-rejected! entity-name entity-config
                                         (merge (:errors validation-result) parse-errors))
              ctx         (support/build-entity-detail-opts admin-service schema-provider config entity-name entity-config merged request)]

          (-> (support/html-response request
                                     (admin-ui/admin-layout
                                      (admin-ui/entity-detail-page entity-name entity-config merged errors permissions
                                                                   (assoc (:page-opts ctx) :flash
                                                                          {:type :error
                                                                           :message [:t :admin/flash-validation-errors]}))
                                      {:user user
                                       :current-entity entity-name
                                       :entities (:entities ctx)
                                       :entity-configs (:entity-configs ctx)
                                       :logo-url (:logo-url config)}))
              (assoc :status 422)))))))
