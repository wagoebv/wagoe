(ns wagoe.user.shell.interceptors
  "User-specific interceptors for the user creation pipeline.
   
   These interceptors handle user domain-specific concerns like:
   - User input validation (required fields, types)
   - User data transformation (normalization, type conversion)
   - User business logic invocation (register-user service call)
   - User-specific response formatting"
  (:require [wagoe.platform.shell.interceptors :as interceptors]
            [wagoe.core.interceptor-context :as ctx]
            [wagoe.core.utils.type-conversion :as type-conv]
            [wagoe.user.shell.service :as user-service]
            [wagoe.user.ports :as ports]
            [wagoe.user.schema :as schema]
            [clojure.string :as str]))

(defn- extract-audit-context
  "Extract actor/network metadata from interceptor context for audit logging."
  [context]
  (let [request (:request context)
        actor (or (:user request)
                  (get-in request [:session :user]))]
    {:actor-id (:id actor)
     :actor-email (:email actor)
     :ip-address (:remote-addr request)
     :user-agent (get-in request [:headers "user-agent"])}))

(def validate-user-creation-input
  "Validates required fields for user creation.
   
   HTTP: Validates request body has email, name, role, and optionally password
   CLI: Validates opts has email, name, role, and optionally password
   
   On validation error, adds validation details to context and throws."
  {:name ::validate-user-creation-input
   :enter (fn [context]
            (let [interface-type (ctx/get-interface-type context)
                  input-data (case interface-type
                               :http (get-in context [:request :parameters :body])
                               :cli (:opts context))
                  ;; Required fields by interface
                  required-fields (case interface-type
                                    :http [:email :name :role]
                                    :cli [:email :name :role])
                  optional-fields (case interface-type
                                    :http [:password]
                                    :cli [:password])
                  missing-fields (remove #(get input-data %) required-fields)]

              (if (seq missing-fields)
                  ;; Validation failed
                (let [validation-error {:type :validation-error
                                        :message (format "Missing required fields: %s"
                                                         (str/join ", " missing-fields))
                                        :missing-fields missing-fields
                                        :provided-fields (keys input-data)
                                        :interface-type interface-type}]
                  (-> context
                      (ctx/add-validation-error :missing-required-fields validation-error)
                      (ctx/fail-with-exception (ex-info (:message validation-error) validation-error))))

                  ;; Validation passed
                (ctx/add-breadcrumb context :validation :user-create-input-valid
                                    {:required-fields required-fields
                                     :optional-fields optional-fields
                                     :provided-fields (keys input-data)
                                     :has-password (some? (:password input-data))}))))})

(def transform-user-creation-data
  "Transforms and normalizes user creation input data.
   
   HTTP: Converts camelCase to kebab-case
   CLI: Normalizes data types, ensures consistent field names
   
   Adds the normalized user-data to context for downstream interceptors."
  {:name ::transform-user-creation-data
   :enter (fn [context]
            (let [interface-type (ctx/get-interface-type context)
                  raw-input (case interface-type
                              :http (get-in context [:request :parameters :body])
                              :cli (:opts context))

                  ;; Transform based on interface
                  user-data (case interface-type
                              :http (cond-> {:email (:email raw-input)
                                             :name (:name raw-input)
                                             :password (:password raw-input)
                                             :role (keyword (:role raw-input))
                                             :active (get raw-input :active true)}
                                     ;; Preferences (flattened)
                                      (contains? raw-input :notificationsEmail)
                                      (assoc :notifications-email (:notificationsEmail raw-input))
                                      (contains? raw-input :notificationsPush)
                                      (assoc :notifications-push (:notificationsPush raw-input))
                                      (contains? raw-input :notificationsSms)
                                      (assoc :notifications-sms (:notificationsSms raw-input))
                                      (:theme raw-input)
                                      (assoc :theme (keyword (:theme raw-input)))
                                      (:language raw-input)
                                      (assoc :language (:language raw-input))
                                      (:timezone raw-input)
                                      (assoc :timezone (:timezone raw-input))
                                      (:dateFormat raw-input)
                                      (assoc :date-format (keyword (:dateFormat raw-input)))
                                      (:timeFormat raw-input)
                                      (assoc :time-format (keyword (:timeFormat raw-input))))

                              :cli (cond-> {:email (:email raw-input)
                                            :name (:name raw-input)
                                            :password (:password raw-input)
                                            :role (keyword (:role raw-input))
                                            :active (:active raw-input)}
                                     ;; Preferences (flattened)
                                     (contains? raw-input :notifications-email)
                                     (assoc :notifications-email (:notifications-email raw-input))
                                     (contains? raw-input :notifications-push)
                                     (assoc :notifications-push (:notifications-push raw-input))
                                     (contains? raw-input :notifications-sms)
                                     (assoc :notifications-sms (:notifications-sms raw-input))
                                     (:theme raw-input)
                                     (assoc :theme (:theme raw-input))
                                     (:language raw-input)
                                     (assoc :language (:language raw-input))
                                     (:timezone raw-input)
                                     (assoc :timezone (:timezone raw-input))
                                     (:date-format raw-input)
                                     (assoc :date-format (:date-format raw-input))
                                     (:time-format raw-input)
                                     (assoc :time-format (:time-format raw-input))))]

              (-> context
                  (assoc :user-data user-data)
                  (ctx/add-breadcrumb :transformation :user-data-normalized
                                      {:interface-type interface-type
                                       :email (:email user-data)
                                       :role (:role user-data)
                                       :active (:active user-data)
                                       :has-password (some? (:password user-data))}))))})

(def invoke-user-registration
  "Invokes the core user registration business logic.
   
   Calls ports/register-user with the normalized user-data.
   Adds the created user result to context for response formatting."
  {:name ::invoke-user-registration
   :enter (fn [context]
            (let [user-data (:user-data context)
                  service (ctx/get-service context)
                  ;; Add operation start breadcrumb
                  updated-context (ctx/add-breadcrumb context :operation :user-registration-start
                                                      {:email (:email user-data)
                                                       :role (:role user-data)})
                  ;; Call the core service; it returns the created user entity directly
                  created-user (user-service/with-audit-context
                                 (extract-audit-context context)
                                 #(ports/register-user service user-data))]
              ;; Add success breadcrumb and result
              (-> updated-context
                  (assoc :created-user created-user)
                  (ctx/add-breadcrumb :operation :user-registration-success
                                      {:user-id (:id created-user)
                                       :email (:email created-user)
                                       :role (:role created-user)}))))})
(def format-user-creation-response
  "Formats the successful user creation response based on interface type.
   
   HTTP: Returns 201 status with camelCase JSON body
   CLI: Returns success status with user data for formatting"
  {:name ::format-user-creation-response
   :enter (fn [context]
            (let [interface-type (ctx/get-interface-type context)
                  created-user (:created-user context)]

              (case interface-type
                :http (assoc context :response
                             {:status 201
                              :body (schema/user-specific-kebab->camel created-user)})

                :cli (assoc context :response
                            {:status 0
                             :entity-type :user
                             :data created-user}))))})

;; Pipeline assembly functions

(defn create-user-creation-pipeline
  "Creates the complete user creation interceptor pipeline.
   
   Pipeline stages:
   1. Context setup (universal)
   2. Logging start (universal)  
   3. Input validation (user-specific)
   4. Data transformation (user-specific)
   5. Business logic invocation (user-specific)
   6. Response formatting (user-specific)
   7. Effects dispatch (universal)
   8. Logging completion (universal)
   9. Metrics completion (universal)
   10. Response shaping (universal)
   
   Args:
     interface-type: :http or :cli
     
   Returns:
     Vector of interceptors for the pipeline"
  [interface-type]
  (case interface-type
    :http (interceptors/create-http-pipeline
           validate-user-creation-input
           transform-user-creation-data
           invoke-user-registration
           format-user-creation-response)

    :cli (interceptors/create-cli-pipeline
          validate-user-creation-input
          transform-user-creation-data
          invoke-user-registration
          format-user-creation-response)))

;; =============================================================================
;; User Get Operation Interceptors
;; =============================================================================

(def validate-user-id-input
  "Validates user ID input for get operations."
  {:name ::validate-user-id-input
   :enter (fn [context]
            (let [interface-type (ctx/get-interface-type context)
                  user-id (case interface-type
                            :http (get-in context [:request :parameters :path :id])
                            :cli (get-in context [:opts :user-id]))]

              (if user-id
                (-> context
                    (assoc :user-id user-id)
                    (ctx/add-breadcrumb :validation :user-id-valid {:user-id user-id}))

                (let [validation-error {:type :validation-error
                                        :message "User ID is required"
                                        :interface-type interface-type}]
                  (-> context
                      (ctx/add-validation-error :missing-user-id validation-error)
                      (ctx/fail-with-exception (ex-info (:message validation-error) validation-error)))))))})

(def transform-user-id
  "Transforms user ID to UUID for internal use."
  {:name ::transform-user-id
   :enter (fn [context]
            (let [user-id-str (:user-id context)
                  user-id-uuid (if (string? user-id-str)
                                 (type-conv/string->uuid user-id-str)
                                 user-id-str)]

              (-> context
                  (assoc :user-id user-id-uuid)
                  (ctx/add-breadcrumb :transformation :user-id-converted
                                      {:user-id user-id-uuid}))))})

(def fetch-user-by-id
  "Fetches user by ID from the service."
  {:name ::fetch-user-by-id
   :enter (fn [context]
            (let [user-id (:user-id context)
                  service (ctx/get-service context)
                  updated-context (ctx/add-breadcrumb context :operation :user-fetch-start
                                                      {:user-id user-id})
                  user (ports/get-user-by-id service user-id)]

              (if user
                (-> updated-context
                    (assoc :user user)
                    (ctx/add-breadcrumb :operation :user-fetch-success
                                        {:user-id (:id user)
                                         :email (:email user)
                                         :active (:active user)}))

                (let [not-found-error {:type :user-not-found
                                       :message "User not found"
                                       :user-id user-id}]
                  (-> updated-context
                      (ctx/add-breadcrumb :operation :user-fetch-not-found
                                          {:user-id user-id})
                      (ctx/fail-with-exception (ex-info (:message not-found-error) not-found-error)))))))})

(def format-user-response
  "Formats single user response based on interface type."
  {:name ::format-user-response
   :enter (fn [context]
            (let [interface-type (ctx/get-interface-type context)
                  user (:user context)]

              (case interface-type
                :http (assoc context :response
                             {:status 200
                              :body (schema/user-specific-kebab->camel user)})

                :cli (assoc context :response
                            {:status 0
                             :entity-type :user
                             :data user}))))})

(defn create-user-get-pipeline
  "Creates pipeline for getting a single user by ID."
  [interface-type]
  (case interface-type
    :http (interceptors/create-http-pipeline
           validate-user-id-input
           transform-user-id
           fetch-user-by-id
           format-user-response)

    :cli (interceptors/create-cli-pipeline
          validate-user-id-input
          transform-user-id
          fetch-user-by-id
          format-user-response)))

;; ==============================================================================
;; LIST USERS OPERATION INTERCEPTORS
;; ==============================================================================

(def validate-list-users-input
  "Validates input parameters for listing users.
   
   HTTP: Validates query parameters (:limit, :offset)
   CLI: Validates opts (:limit, :offset)"
  {:name ::validate-list-users-input
   :enter (fn [context]
            (let [interface-type (ctx/get-interface-type context)
                  input-data (case interface-type
                               :http (get-in context [:request :parameters :query])
                               :cli (:opts context))
                  limit (or (:limit input-data) 20)
                  offset (or (:offset input-data) 0)]

              (cond
                ;; Validate limit bounds
                (or (not (int? limit)) (< limit 1) (> limit 100))
                (ctx/fail-with-exception context
                                         (ex-info "Invalid limit parameter"
                                                  {:type :validation-error
                                                   :field :limit
                                                   :message "Limit must be between 1 and 100"
                                                   :interface-type interface-type}))

                ;; Validate offset bounds
                (or (not (int? offset)) (< offset 0))
                (ctx/fail-with-exception context
                                         (ex-info "Invalid offset parameter"
                                                  {:type :validation-error
                                                   :field :offset
                                                   :message "Offset must be non-negative"
                                                   :interface-type interface-type}))

                ;; All validations passed
                :else
                (-> context
                    (assoc :raw-query-params input-data)
                    (ctx/add-breadcrumb :operation :list-users-validation-success
                                        {:limit limit
                                         :offset offset
                                         :interface-type interface-type})))))})

(def transform-list-users-params
  "Transforms and normalizes list users parameters.
   
   HTTP: Extracts query params
   CLI: Extracts options from opts"
  {:name ::transform-list-users-params
   :enter (fn [context]
            (let [interface-type (ctx/get-interface-type context)
                  query-params (:raw-query-params context)
                  options {:limit (or (:limit query-params) 20)
                           :offset (or (:offset query-params) 0)
                           :filter-role (when (:role query-params)
                                          (keyword (:role query-params)))
                           :filter-active (:active query-params)}]

              (-> context
                  (assoc :list-options options)
                  (ctx/add-breadcrumb :operation :list-users-transform-complete
                                      {:options options
                                       :interface-type interface-type}))))})

(def fetch-users
  "Fetches all users with options."
  {:name ::fetch-users
   :enter (fn [context]
            (let [options (:list-options context)
                  service (ctx/get-service context)
                  updated-context (ctx/add-breadcrumb context :operation :user-list-start
                                                      {:options options})]

              (try
                (let [result (ports/list-users service options)
                      users (:users result)
                      total-count (:total-count result)]

                  (-> updated-context
                      (assoc :users users
                             :total-count total-count)
                      (ctx/add-breadcrumb :operation :user-list-success
                                          {:user-count (count users)
                                           :total-count total-count})))

                (catch Exception ex
                  (-> updated-context
                      (ctx/add-breadcrumb :operation :user-list-error
                                          {:error-type (or (:type (ex-data ex)) "unknown")
                                           :error-message (.getMessage ex)})
                      (ctx/fail-with-exception ex))))))})

(def format-users-list-response
  "Formats the list of users for response with pagination support."
  {:name ::format-users-list-response
   :enter (fn [context]
            (let [users (:users context)
                  total-count (:total-count context)
                  options (:list-options context)
                  interface-type (ctx/get-interface-type context)]

              (case interface-type
                :http
                (let [;; Format users for JSON response (handles Instant conversion)
                      formatted-users (mapv schema/user-specific-kebab->camel users)

                      ;; Import pagination utilities (lazy require for performance)
                      calculate-offset-pagination (requiring-resolve 'wagoe.platform.core.pagination.pagination/calculate-offset-pagination)
                      generate-link-header (requiring-resolve 'wagoe.platform.shell.pagination.link-headers/generate-link-header)

                      limit (:limit options)
                      offset (:offset options)
                      pagination-raw (calculate-offset-pagination total-count offset limit)

                      ;; Transform pagination to camelCase for JSON API
                      pagination-meta {:type (:type pagination-raw)
                                       :total (:total pagination-raw)
                                       :offset (:offset pagination-raw)
                                       :limit (:limit pagination-raw)
                                       :hasNext (:has-next? pagination-raw)
                                       :hasPrev (:has-prev? pagination-raw)
                                       :page (:page pagination-raw)
                                       :pages (:pages pagination-raw)}

                      ;; Build paginated response body
                      response-body {:data formatted-users
                                     :pagination pagination-meta
                                     :meta {:version "v1"
                                            :timestamp (type-conv/instant->string (java.time.Instant/now))}}

                      ;; Generate RFC 5988 Link header
                      request-uri (get-in context [:request :uri] "/api/v1/users")
                      query-params (select-keys options [:limit :offset :filter-role :filter-active])
                      link-header (generate-link-header request-uri query-params pagination-raw)

                      ;; Build response with Link header
                      response (cond-> {:status 200
                                        :body response-body}
                                 link-header
                                 (assoc-in [:headers "Link"] link-header))]

                  (-> context
                      (assoc :response response)
                      (ctx/add-breadcrumb :operation :user-list-formatted
                                          {:user-count (count formatted-users)
                                           :total-count total-count
                                           :pagination-type "offset"
                                           :has-link-header (some? link-header)
                                           :response-format :json})))

                :cli
                (let [response {:status :success
                                :data {:users users
                                       :total-count total-count}
                                :message (str "Found " (count users) " users")}]
                  (-> context
                      (assoc :response response)
                      (ctx/add-breadcrumb :operation :user-list-formatted
                                          {:user-count (count users)
                                           :total-count total-count
                                           :response-format :cli}))))))})

(defn create-user-list-pipeline
  "Creates pipeline for listing users with pagination."
  [interface-type]
  (case interface-type
    :http (interceptors/create-http-pipeline
           validate-list-users-input
           transform-list-users-params
           fetch-users
           format-users-list-response)

    :cli (interceptors/create-cli-pipeline
          validate-list-users-input
          transform-list-users-params
          fetch-users
          format-users-list-response)))

;; ==============================================================================
;; DELETE USER OPERATION INTERCEPTORS
;; ==============================================================================

(def validate-user-delete-input
  "Validates input for deleting (deactivating) a user.
   
   HTTP: Validates path has id parameter
   CLI: Validates opts has id parameter"
  {:name ::validate-user-delete-input
   :enter (fn [context]
            (let [interface-type (ctx/get-interface-type context)
                  user-id-str (case interface-type
                                :http (get-in context [:request :parameters :path :id])
                                :cli (:id (:opts context)))]

              (if (nil? user-id-str)
                (ctx/fail-with-exception context
                                         (ex-info "User ID is required"
                                                  {:type :validation-error
                                                   :field :id
                                                   :message (case interface-type
                                                              :http "User ID is required in path"
                                                              :cli "User ID is required (--id)")
                                                   :interface-type interface-type}))

                (-> context
                    (assoc :raw-user-id user-id-str)
                    (ctx/add-breadcrumb :operation :user-delete-validation-success
                                        {:user-id user-id-str
                                         :interface-type interface-type})))))})

(def transform-user-delete-id
  "Transforms user ID for deletion.
   
   HTTP: Converts string ID to UUID
   CLI: Uses UUID directly (already parsed)"
  {:name ::transform-user-delete-id
   :enter (fn [context]
            (let [interface-type (ctx/get-interface-type context)
                  user-id-str (:raw-user-id context)
                  user-id (case interface-type
                            :http (type-conv/string->uuid user-id-str)
                            :cli user-id-str)] ; Already a UUID from CLI parsing

              (-> context
                  (assoc :user-id user-id)
                  (ctx/add-breadcrumb :operation :user-delete-transform-complete
                                      {:user-id user-id
                                       :interface-type interface-type}))))})

(def deactivate-user-account
  "Deactivates (soft deletes) the user account."
  {:name ::deactivate-user-account
   :enter (fn [context]
            (let [user-id (:user-id context)
                  service (ctx/get-service context)
                  updated-context (ctx/add-breadcrumb context :operation :user-deactivate-start
                                                      {:user-id user-id})]

              (try
                (let [result (user-service/with-audit-context
                               (extract-audit-context context)
                               #(ports/deactivate-user service user-id))]
                  (-> updated-context
                      (assoc :deactivation-result result)
                      (ctx/add-breadcrumb :operation :user-deactivate-success
                                          {:user-id user-id
                                           :success result})))

                (catch Exception ex
                  (-> updated-context
                      (ctx/add-breadcrumb :operation :user-deactivate-error
                                          {:user-id user-id
                                           :error-type (or (:type (ex-data ex)) "unknown")
                                           :error-message (.getMessage ex)})
                      (ctx/fail-with-exception ex))))))})

(def format-user-delete-response
  "Formats the deletion response."
  {:name ::format-user-delete-response
   :enter (fn [context]
            (let [user-id (:user-id context)
                  interface-type (:interface-type context)]

              (case interface-type
                :http
                (let [response {:status 204}] ; No content for successful deletion
                  (-> context
                      (assoc :response response)
                      (ctx/add-breadcrumb :operation :user-delete-formatted
                                          {:user-id user-id
                                           :response-format :json})))

                :cli
                (let [response {:status :success
                                :data {:user-id user-id}
                                :message "User deactivated successfully"}]
                  (-> context
                      (assoc :response response)
                      (ctx/add-breadcrumb :operation :user-delete-formatted
                                          {:user-id user-id
                                           :response-format :cli}))))))})

(defn create-user-delete-pipeline
  "Creates pipeline for deleting (deactivating) a user."
  [interface-type]
  (case interface-type
    :http (interceptors/create-http-pipeline
           validate-user-delete-input
           transform-user-delete-id
           deactivate-user-account
           format-user-delete-response)

    :cli (interceptors/create-cli-pipeline
          validate-user-delete-input
          transform-user-delete-id
          deactivate-user-account
          format-user-delete-response)))

;; ==============================================================================
;; UPDATE USER OPERATION INTERCEPTORS
;; ==============================================================================

(def validate-user-update-input
  "Validates input for updating a user.
   
   HTTP: Validates path has id parameter and body has update fields
   CLI: Validates opts has id and update fields"
  {:name ::validate-user-update-input
   :enter (fn [context]
            (let [interface-type (ctx/get-interface-type context)
                  user-id-str (case interface-type
                                :http (get-in context [:request :parameters :path :id])
                                :cli (:id (:opts context)))
                  update-data (case interface-type
                                :http (get-in context [:request :parameters :body])
                                :cli (dissoc (:opts context) :id))]

              (cond
                (nil? user-id-str)
                (ctx/fail-with-exception context
                                         (ex-info "User ID is required"
                                                  {:type :validation-error
                                                   :field :id
                                                   :message (case interface-type
                                                              :http "User ID is required in path"
                                                              :cli "User ID is required (--id)")
                                                   :interface-type interface-type}))

                (empty? update-data)
                (ctx/fail-with-exception context
                                         (ex-info "No update fields provided"
                                                  {:type :validation-error
                                                   :message "At least one field to update is required"
                                                   :interface-type interface-type}))

                :else
                (-> context
                    (assoc :raw-user-id user-id-str
                           :raw-update-data update-data)
                    (ctx/add-breadcrumb :operation :user-update-validation-success
                                        {:user-id user-id-str
                                         :update-fields (keys update-data)
                                         :interface-type interface-type})))))})

(def transform-user-update-data
  "Transforms user update data.
   
   HTTP: Converts string ID to UUID, normalizes field names
   CLI: Normalizes field names"
  {:name ::transform-user-update-data
   :enter (fn [context]
            (let [interface-type (ctx/get-interface-type context)
                  user-id-str (:raw-user-id context)
                  raw-data (:raw-update-data context)
                  user-id (case interface-type
                            :http (type-conv/string->uuid user-id-str)
                            :cli user-id-str)
                  update-data (cond-> {}
                                (:name raw-data) (assoc :name (:name raw-data))
                                (:email raw-data) (assoc :email (:email raw-data))
                                (:role raw-data) (assoc :role (keyword (:role raw-data)))
                                (some? (:active raw-data)) (assoc :active (:active raw-data))
                                ;; Preferences (flattened)
                                (contains? raw-data :notificationsEmail) (assoc :notifications-email (:notificationsEmail raw-data))
                                (contains? raw-data :notificationsPush) (assoc :notifications-push (:notificationsPush raw-data))
                                (contains? raw-data :notificationsSms) (assoc :notifications-sms (:notificationsSms raw-data))
                                (:theme raw-data) (assoc :theme (keyword (:theme raw-data)))
                                (:language raw-data) (assoc :language (:language raw-data))
                                (:timezone raw-data) (assoc :timezone (:timezone raw-data))
                                (:dateFormat raw-data) (assoc :date-format (keyword (:dateFormat raw-data)))
                                (:timeFormat raw-data) (assoc :time-format (keyword (:timeFormat raw-data))))]

              (-> context
                  (assoc :user-id user-id
                         :update-data update-data)
                  (ctx/add-breadcrumb :operation :user-update-transform-complete
                                      {:user-id user-id
                                       :update-fields (keys update-data)
                                       :interface-type interface-type}))))})

(def fetch-current-user-for-update
  "Fetches the current user for update validation."
  {:name ::fetch-current-user-for-update
   :enter (fn [context]
            (let [user-id (:user-id context)
                  service (ctx/get-service context)
                  updated-context (ctx/add-breadcrumb context :operation :user-fetch-for-update-start
                                                      {:user-id user-id})]

              (try
                (let [existing-user (ports/get-user-by-id service user-id)]
                  (if existing-user
                    (-> updated-context
                        (assoc :existing-user existing-user)
                        (ctx/add-breadcrumb :operation :user-fetch-for-update-success
                                            {:user-id user-id
                                             :email (:email existing-user)}))
                    (-> updated-context
                        (ctx/fail-with-exception (ex-info "User not found"
                                                          {:type :user-not-found
                                                           :user-id user-id})))))

                (catch Exception ex
                  (-> updated-context
                      (ctx/add-breadcrumb :operation :user-fetch-for-update-error
                                          {:user-id user-id
                                           :error-message (.getMessage ex)})
                      (ctx/fail-with-exception ex))))))})

(def apply-user-updates
  "Applies updates to the user."
  {:name ::apply-user-updates
   :enter (fn [context]
            (let [user-id (:user-id context)
                  update-data (:update-data context)
                  existing-user (:existing-user context)
                  service (ctx/get-service context)
                  updated-context (ctx/add-breadcrumb context :operation :user-update-apply-start
                                                      {:user-id user-id
                                                       :update-fields (keys update-data)})
                  ;; Merge updates into existing user
                  user-entity (merge existing-user update-data)]

              (try
                (let [updated-user (user-service/with-audit-context
                                     (extract-audit-context context)
                                     #(ports/update-user-profile service user-entity))]
                  (-> updated-context
                      (assoc :updated-user updated-user)
                      (ctx/add-breadcrumb :operation :user-update-apply-success
                                          {:user-id user-id
                                           :email (:email updated-user)})))

                (catch Exception ex
                  (-> updated-context
                      (ctx/add-breadcrumb :operation :user-update-apply-error
                                          {:user-id user-id
                                           :error-message (.getMessage ex)})
                      (ctx/fail-with-exception ex))))))})

(def format-user-update-response
  "Formats the update response."
  {:name ::format-user-update-response
   :enter (fn [context]
            (let [updated-user (:updated-user context)
                  interface-type (ctx/get-interface-type context)]

              (case interface-type
                :http
                (let [response {:status 200
                                :body (schema/user-specific-kebab->camel updated-user)}]
                  (-> context
                      (assoc :response response)
                      (ctx/add-breadcrumb :operation :user-update-formatted
                                          {:user-id (:id updated-user)
                                           :response-format :json})))

                :cli
                (let [response {:status :success
                                :data (select-keys updated-user [:id :email :name :role :active])
                                :message (str "User " (:email updated-user) " updated successfully")}]
                  (-> context
                      (assoc :response response)
                      (ctx/add-breadcrumb :operation :user-update-formatted
                                          {:user-id (:id updated-user)
                                           :response-format :cli}))))))})

(defn create-user-update-pipeline
  "Creates pipeline for updating an existing user."
  [interface-type]
  (case interface-type
    :http (interceptors/create-http-pipeline
           validate-user-update-input
           transform-user-update-data
           fetch-current-user-for-update
           apply-user-updates
           format-user-update-response)

    :cli (interceptors/create-cli-pipeline
          validate-user-update-input
          transform-user-update-data
          fetch-current-user-for-update
          apply-user-updates
          format-user-update-response)))

;; ==============================================================================
;; SESSION MANAGEMENT INTERCEPTORS
;; ==============================================================================

;; -----------------------------------------------------------------------------
;; CREATE SESSION (LOGIN) INTERCEPTORS
;; -----------------------------------------------------------------------------

(def validate-session-creation-input
  "Validates input for creating a session (login).
   
   HTTP: Validates body has either userId OR (email + password)
   CLI: Validates opts has user-id (or email/password for authentication)"
  {:name ::validate-session-creation-input
   :enter (fn [context]
            (let [interface-type (ctx/get-interface-type context)
                  input-data (case interface-type
                               :http (get-in context [:request :parameters :body])
                               :cli (:opts context))
                  user-id-str (case interface-type
                                :http (:userId input-data)
                                :cli (:user-id input-data))
                  [email password] [(:email input-data) (:password input-data)]]

              (cond
                ;; HTTP: Must have either userId OR (email + password)
                (= interface-type :http)
                (cond
                  ;; Valid user ID flow
                  user-id-str
                  (-> context
                      (assoc :raw-session-data input-data)
                      (ctx/add-breadcrumb :operation :session-create-validation-success
                                          {:auth-type :user-id
                                           :user-id user-id-str
                                           :interface-type interface-type}))

                  ;; Valid email/password flow
                  (and email password)
                  (-> context
                      (assoc :raw-session-data input-data)
                      (ctx/add-breadcrumb :operation :session-create-validation-success
                                          {:auth-type :email-password
                                           :email email
                                           :interface-type interface-type}))

                  ;; Invalid - missing required fields for either flow
                  :else
                  (ctx/fail-with-exception context
                                           (ex-info "Invalid authentication parameters"
                                                    {:type :validation-error
                                                     :message "Must provide either userId or (email + password)"
                                                     :provided-fields (keys input-data)
                                                     :interface-type interface-type})))

                ;; CLI: Check if we have either user-id or email/password
                (= interface-type :cli)
                (cond
                  ;; Valid user ID flow
                  user-id-str
                  (-> context
                      (assoc :raw-session-data input-data)
                      (ctx/add-breadcrumb :operation :session-create-validation-success
                                          {:auth-type :user-id
                                           :user-id user-id-str
                                           :interface-type interface-type}))

                  ;; Valid email/password flow
                  (and email password)
                  (-> context
                      (assoc :raw-session-data input-data)
                      (ctx/add-breadcrumb :operation :session-create-validation-success
                                          {:auth-type :email-password
                                           :email email
                                           :interface-type interface-type}))

                  ;; Invalid - missing required fields
                  :else
                  (ctx/fail-with-exception context
                                           (ex-info "Invalid authentication parameters"
                                                    {:type :validation-error
                                                     :message "Must provide either user-id or (email + password)"
                                                     :provided-fields (keys input-data)
                                                     :interface-type interface-type})))

                ;; Should not reach here
                :else
                (ctx/fail-with-exception context
                                         (ex-info "Unknown interface type"
                                                  {:type :system-error
                                                   :interface-type interface-type})))))})

(def transform-session-creation-data
  "Transforms and normalizes session creation data.

   HTTP: Supports both userId and email/password flows, extracts device info and MFA code
   CLI: Uses provided data, handles both user-id and email/password flows with MFA support"
  {:name ::transform-session-creation-data
   :enter (fn [context]
            (let [interface-type (ctx/get-interface-type context)
                  body (:raw-session-data context)

                  session-data (case interface-type
                                 :http (cond-> {}
                                         (:userId body) (assoc :user-id (type-conv/string->uuid (:userId body)))
                                         (:email body) (assoc :email (:email body))
                                         (:password body) (assoc :password (:password body))
                                         (:mfaCode body) (assoc :mfa-code (:mfaCode body))
                                         true (assoc :user-agent (get-in body [:deviceInfo :userAgent] "Unknown")
                                                     :ip-address (get-in body [:deviceInfo :ipAddress] "Unknown")))

                                 :cli (cond-> {}
                                        (:user-id body) (assoc :user-id (:user-id body))
                                        (:email body) (assoc :email (:email body))
                                        (:password body) (assoc :password (:password body))
                                        (:mfa-code body) (assoc :mfa-code (:mfa-code body))
                                        true (assoc :user-agent "CLI"
                                                    :ip-address "127.0.0.1")))]

              (-> context
                  (assoc :session-data session-data)
                  (ctx/add-breadcrumb :operation :session-create-transform-complete
                                      {:user-id (:user-id session-data)
                                       :has-email (some? (:email session-data))
                                       :has-mfa-code (some? (:mfa-code session-data))
                                       :auth-type (if (:email session-data) :email-password :user-id)
                                       :user-agent (:user-agent session-data)
                                       :interface-type interface-type}))))})

(def authenticate-user-session
  "Authenticates user and creates session.

   Returns either:
   - A session with :token when authentication succeeds
   - A map with :requires-mfa? true when MFA verification is needed
   - Throws exception on authentication failure"
  {:name ::authenticate-user-session
   :enter (fn [context]
            (let [session-data (:session-data context)
                  service (ctx/get-service context)
                  updated-context (ctx/add-breadcrumb context :operation :user-authenticate-start
                                                      {:user-id (:user-id session-data)})]

              (try
                (let [auth-result (ports/authenticate-user service session-data)
                      ;; Normal authentication success - session with token
                      masked-token (str (take 8 (:token auth-result)) "...")]
                  (-> updated-context
                      (assoc :session auth-result)
                      (ctx/add-breadcrumb :operation :user-authenticate-success
                                          {:user-id (:user-id auth-result)
                                           :session-token masked-token
                                           :expires-at (:expires-at auth-result)})))

                (catch Exception ex
                  (-> updated-context
                      (ctx/add-breadcrumb :operation :user-authenticate-error
                                          {:user-id (:user-id session-data)
                                           :error-type (or (:type (ex-data ex)) "unknown")
                                           :error-message (.getMessage ex)})
                      (ctx/fail-with-exception ex))))))})

(def format-session-creation-response
  "Formats the created session or MFA-required response.

   Handles three cases:
   - MFA required: Returns 200 with requiresMfa flag and limited user info
   - Authentication success: Returns 200 with session token and user details
   - CLI: Returns appropriate status and data for command-line interface"
  {:name ::format-session-creation-response
   :enter (fn [context]
            (let [auth-result (:session context)
                  interface-type (:interface-type context)]

              (case interface-type
                :http
                (if (:requires-mfa? auth-result)
                  ;; MFA required - return 200 with requiresMfa flag
                  (let [user (:user auth-result)
                        response {:status 200
                                  :body {:requiresMfa true
                                         :message "MFA code required"
                                         :user {:id (str (:id user))
                                                :email (:email user)
                                                :name (:name user)}}}]
                    (-> context
                        (assoc :response response)
                        (ctx/add-breadcrumb :operation :mfa-required-response
                                            {:user-id (:id user)
                                             :response-format :json})))
                  ;; Normal authentication success - return session
                  (let [response {:status 200
                                  :body (schema/user-specific-kebab->camel auth-result)}]
                    (-> context
                        (assoc :response response)
                        (ctx/add-breadcrumb :operation :session-create-formatted
                                            {:user-id (get-in auth-result [:user :id])
                                             :response-format :json}))))

                :cli
                (if (:requires-mfa? auth-result)
                  ;; MFA required for CLI
                  (let [user (:user auth-result)
                        response {:status :mfa-required
                                  :data {:user-id (:id user)
                                         :email (:email user)}
                                  :message "MFA code required. Please provide mfa-code parameter."}]
                    (-> context
                        (assoc :response response)
                        (ctx/add-breadcrumb :operation :mfa-required-response
                                            {:user-id (:id user)
                                             :response-format :cli})))
                  ;; Normal authentication success for CLI
                  (let [response {:status :success
                                  :data (select-keys auth-result [:user-id :session-token :expires-at])
                                  :message "Session created successfully"}]
                    (-> context
                        (assoc :response response)
                        (ctx/add-breadcrumb :operation :session-create-formatted
                                            {:user-id (get-in auth-result [:user :id])
                                             :response-format :cli})))))))})

(defn create-session-creation-pipeline
  "Creates pipeline for creating a session (login)."
  [interface-type]
  (case interface-type
    :http (interceptors/create-http-pipeline
           validate-session-creation-input
           transform-session-creation-data
           authenticate-user-session
           format-session-creation-response)

    :cli (interceptors/create-cli-pipeline
          validate-session-creation-input
          transform-session-creation-data
          authenticate-user-session
          format-session-creation-response)))

;; -----------------------------------------------------------------------------
;; VALIDATE SESSION INTERCEPTORS
;; -----------------------------------------------------------------------------

(def validate-session-token-input
  "Validates session token input.
   URL-decodes the token from the path parameter to handle percent-encoded chars."
  {:name ::validate-session-token-input
   :enter (fn [context]
            (let [path-params (get-in context [:request :parameters :path])
                  raw-token (:token path-params)
                  session-token (when raw-token
                                  (java.net.URLDecoder/decode raw-token "UTF-8"))]

              (if (nil? session-token)
                (ctx/fail-with-exception context
                                         (ex-info "Session token is required"
                                                  {:type :validation-error
                                                   :field :token
                                                   :message "Session token is required in path"}))

                (-> context
                    (assoc :session-token session-token
                           :masked-token (str (take 8 session-token) "..."))
                    (ctx/add-breadcrumb :operation :session-validate-input-success
                                        {:session-token (str (take 8 session-token) "...")})))))})

(def validate-user-session
  "Validates the session token."
  {:name ::validate-user-session
   :enter (fn [context]
            (let [session-token (:session-token context)
                  masked-token (:masked-token context)
                  service (ctx/get-service context)
                  updated-context (ctx/add-breadcrumb context :operation :session-validate-start
                                                      {:session-token masked-token})]

              (try
                (let [session (ports/validate-session service session-token)]
                  (if session
                    (-> updated-context
                        (assoc :session session
                               :session-valid true)
                        (ctx/add-breadcrumb :operation :session-validate-success
                                            {:user-id (:user-id session)
                                             :session-token masked-token
                                             :valid true}))

                    (-> updated-context
                        (assoc :session-valid false)
                        (ctx/add-breadcrumb :operation :session-validate-invalid
                                            {:session-token masked-token
                                             :valid false})
                        (ctx/fail-with-exception (ex-info "Session not found or expired"
                                                          {:type :session-not-found
                                                           :valid false
                                                           :token session-token})))))

                (catch Exception ex
                  (-> updated-context
                      (ctx/add-breadcrumb :operation :session-validate-error
                                          {:session-token masked-token
                                           :error-type (or (:type (ex-data ex)) "unknown")
                                           :error-message (.getMessage ex)})
                      (ctx/fail-with-exception ex))))))})

(def format-session-validation-response
  "Formats the session validation response."
  {:name ::format-session-validation-response
   :enter (fn [context]
            (let [session (:session context)
                  interface-type (:interface-type context)]

              (case interface-type
                :http
                (let [response {:status 200
                                :body {:valid true
                                       :userId (type-conv/uuid->string (:user-id session))
                                       :expiresAt (type-conv/instant->string (:expires-at session))}}]
                  (-> context
                      (assoc :response response)
                      (ctx/add-breadcrumb :operation :session-validate-formatted
                                          {:user-id (:user-id session)
                                           :response-format :json})))

                :cli
                (let [response {:status :success
                                :data {:valid true
                                       :user-id (:user-id session)
                                       :expires-at (:expires-at session)}
                                :message "Session is valid"}]
                  (-> context
                      (assoc :response response)
                      (ctx/add-breadcrumb :operation :session-validate-formatted
                                          {:user-id (:user-id session)
                                           :response-format :cli}))))))})

(defn create-session-validation-pipeline
  "Creates pipeline for validating a session token."
  [interface-type]
  (case interface-type
    :http (interceptors/create-http-pipeline
           validate-session-token-input
           validate-user-session
           format-session-validation-response)

    :cli (interceptors/create-cli-pipeline
          validate-session-token-input
          validate-user-session
          format-session-validation-response)))

;; -----------------------------------------------------------------------------
;; INVALIDATE SESSION (LOGOUT) INTERCEPTORS
;; -----------------------------------------------------------------------------

(def invalidate-user-session
  "Invalidates (logs out) the session."
  {:name ::invalidate-user-session
   :enter (fn [context]
            (let [session-token (:session-token context)
                  masked-token (:masked-token context)
                  service (ctx/get-service context)
                  updated-context (ctx/add-breadcrumb context :operation :session-logout-start
                                                      {:session-token masked-token})]

              (try
                (let [result (ports/logout-user service session-token)]
                  (-> updated-context
                      (assoc :logout-result result)
                      (ctx/add-breadcrumb :operation :session-logout-success
                                          {:session-token masked-token})))

                (catch Exception ex
                  (-> updated-context
                      (ctx/add-breadcrumb :operation :session-logout-error
                                          {:session-token masked-token
                                           :error-type (or (:type (ex-data ex)) "unknown")
                                           :error-message (.getMessage ex)})
                      (ctx/fail-with-exception ex))))))})

(def format-session-invalidation-response
  "Formats the session invalidation response."
  {:name ::format-session-invalidation-response
   :enter (fn [context]
            (let [masked-token (:masked-token context)
                  interface-type (:interface-type context)]

              (case interface-type
                :http
                (let [response {:status 204}] ; No content for successful logout
                  (-> context
                      (assoc :response response)
                      (ctx/add-breadcrumb :operation :session-logout-formatted
                                          {:session-token masked-token
                                           :response-format :json})))

                :cli
                (let [response {:status :success
                                :data {:session-token masked-token}
                                :message "Session invalidated successfully"}]
                  (-> context
                      (assoc :response response)
                      (ctx/add-breadcrumb :operation :session-logout-formatted
                                          {:session-token masked-token
                                           :response-format :cli}))))))})

(defn create-session-invalidation-pipeline
  "Creates pipeline for invalidating a session (logout)."
  [interface-type]
  (case interface-type
    :http (interceptors/create-http-pipeline
           validate-session-token-input
           invalidate-user-session
           format-session-invalidation-response)

    :cli (interceptors/create-cli-pipeline
          validate-session-token-input
          invalidate-user-session
          format-session-invalidation-response)))
