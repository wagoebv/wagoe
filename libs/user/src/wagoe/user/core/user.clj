(ns wagoe.user.core.user
  "Functional Core - Pure user business logic.

   This namespace contains ONLY pure functions with no side effects:
   - No I/O operations (no database calls, no logging, no HTTP)
   - No external dependencies (time, random generation, etc.)
   - Deterministic behavior (same input always produces same output)
   - Immutable data structures only

   All business rules and domain logic for users belong here.
   The shell layer orchestrates I/O and calls these pure functions."
  (:require [wagoe.user.schema :as schema]
            [wagoe.user.core.validation :as validation]
            [malli.core :as m]
            [clojure.string :as str]
            [clojure.set :as set])
  (:import (java.time LocalDate ZoneOffset)
           (java.time.temporal ChronoUnit)))

;; =============================================================================
;; User Creation Business Logic
;; =============================================================================

(defn validate-user-creation-request
  "Pure function: Validate user creation request against comprehensive business rules.

       Args:
         user-data: User creation request data
         validation-config: Map containing validation policies and settings
           {:email-domain-allowlist #{\"example.com\" \"acme.com\"}
            :password-policy {:min-length 8 :require-special-chars? true :require-numbers? true}
            :name-restrictions {:min-length 2 :max-length 100 :allowed-chars-regex #\"[a-zA-Z\\s-'.]\"}}

       Returns:
         {:valid? true :data processed-data} or
         {:valid? false :errors [{:field :email :code :invalid-domain :message \"...\"}]}

       Pure - no side effects, comprehensive domain validation."
  [user-data validation-config]
  ;; Use the comprehensive validation from the validation namespace
  (validation/validate-comprehensive-user-creation user-data validation-config {}))

(defn check-duplicate-user-decision
  "Pure function: Determine if user creation should proceed based on existing user lookup.

       Args:
         user-data: User data to create
         existing-user: Result of looking up user by email (or nil if not found)

       Returns:
         {:decision :proceed} or
         {:decision :reject :reason :duplicate-email :email string}

       Pure - no database calls, just decision logic based on inputs."
  [user-data existing-user]
  (if existing-user
    {:decision :reject
     :reason :duplicate-email
     :email (:email user-data)}
    {:decision :proceed}))

(defn prepare-user-for-creation
  "Pure function: Prepare user data for creation with business defaults.

       Args:
         user-data: Validated user creation request
         current-time: Instant representing current time
         user-id: UUID for the new user

       Returns:
         Complete user entity ready for persistence

       Pure - takes time and ID as parameters instead of generating them."
  [user-data current-time user-id]
  (-> user-data
      (assoc :id user-id)
      (assoc :created-at current-time)
      (assoc :updated-at nil)
      (assoc :deleted-at nil)
      (assoc :active true) ; Business rule: new users are active by default
      (dissoc :password))) ; Business rule: don't store raw password

;; =============================================================================
;; User Update Business Logic
;; =============================================================================

(def ^:private user-validator (m/validator schema/User))
(def ^:private user-explainer (m/explainer schema/User))

(defn validate-user-update-request
  "Pure function: Validate user update request against business rules.

       Args:
         user-entity: Complete user entity for update

       Returns:
         {:valid? true :data user-entity} or
         {:valid? false :errors validation-errors}

       Pure - schema validation only, no external dependencies."
  [user-entity]
  (if (user-validator user-entity)
    {:valid? true :data user-entity}
    {:valid? false :errors (validation/format-schema-errors (user-explainer user-entity))}))

(defn calculate-user-changes
  "Pure function: Calculate what fields are being changed.

       Args:
         current-user: Current user entity from database
         updated-user: Updated user data

       Returns:
         Map with changes: {:field {:from old-value :to new-value}}

       Pure - data comparison only."
  [current-user updated-user]
  (reduce-kv
   (fn [changes key new-value]
     (let [old-value (get current-user key)]
       (if (= old-value new-value)
         changes
         (assoc changes key {:from old-value :to new-value}))))
   {}
   (select-keys updated-user (keys current-user))))

(defn validate-user-business-rules
  "Pure function: Validate business rules for user changes.

       Args:
         updated-user: Updated user entity
         changes: Map of changes (from calculate-user-changes)

       Returns:
         {:valid? true} or
         {:valid? false :errors error-details}

       Pure - business rule validation only."
  [_updated-user changes]
  ;; Example business rules:
  (cond
    ;; Cannot change email - would require verification
    (and (:email changes)
         (not= (get-in changes [:email :from]) (get-in changes [:email :to])))
    {:valid? false
     :errors {:email "Email changes require separate verification process"}}

    :else
    {:valid? true}))

(defn check-user-exists-for-update-decision
  "Pure function: Determine if user update should proceed based on existing user.

       Args:
         user-entity: User entity to update
         existing-user: Current user from database (or nil if not found)

       Returns:
         {:decision :proceed} or
         {:decision :reject :reason :user-not-found :user-id uuid}

       Pure - decision logic only, no database operations."
  [user-entity existing-user]
  (if existing-user
    {:decision :proceed}
    {:decision :reject
     :reason :user-not-found
     :user-id (:id user-entity)}))

(defn prepare-user-for-update
  "Pure function: Prepare user entity for update with business rules.
   
   Handles the active field by setting deleted-at:
   - When active is false (deactivated): sets deleted-at to current-time
   - When active is true (activated): sets deleted-at to nil
   
   Args:
     user-entity: User entity to update
     current-time: Instant representing current time
     
   Returns:
     User entity prepared for update with updated timestamp and deleted-at
     
   Pure - takes time as parameter instead of generating it."
  [user-entity current-time]
  (let [base-update (assoc user-entity :updated-at current-time)]
    (if (contains? user-entity :active)
      ;; Handle active field by setting deleted-at
      (if (:active user-entity)
        ;; User is being activated - clear deleted-at
        (assoc base-update :deleted-at nil)
        ;; User is being deactivated - set deleted-at to current time
        (assoc base-update :deleted-at current-time))
      ;; No active field provided, just return base update
      base-update)))

;; =============================================================================
;; User Query Business Logic
;; =============================================================================

(defn filter-active-users
  "Pure function: Filter list of users to only include active ones.

       Args:
         users: Vector of user entities

       Returns:
         Vector of active users (deleted-at is nil)

       Pure - data transformation only."
  [users]
  (filter #(nil? (:deleted-at %)) users))

(defn sort-users-by-criteria
  "Pure function: Sort users by specified criteria.

       Args:
         users: Vector of user entities
         sort-by: Keyword field to sort by (:created-at, :email, :role)
         sort-direction: :asc or :desc

       Returns:
         Sorted vector of users

       Pure - data sorting only."
  [users sort-by sort-direction]
  (let [comparator (case sort-direction
                     :asc compare
                     :desc #(compare %2 %1))]
    (sort-by sort-by comparator users)))

(defn apply-user-filters
  "Pure function: Apply business filters to user list.

       Args:
         users: Vector of user entities
         filters: Map of filter criteria {:role :admin, :active true, etc.}

       Returns:
         Filtered vector of users

       Pure - data filtering based on criteria."
  [users filters]
  (cond->> users
    (:role filters) (filter #(= (:role %) (:role filters)))
    (:active filters) (filter #(= (:active %) (:active filters)))
    (:email-contains filters) (filter #(.contains (:email %) (:email-contains filters)))))

;; =============================================================================
;; User Business Rules
;; =============================================================================

(defn calculate-user-membership-tier
  "Pure function: Calculate membership tier based on user data.

       Args:
         user: User entity with join date and activity data
         current-date: Current date for calculation

       Returns:
         Keyword tier (:bronze :silver :gold :platinum)

       Pure - business calculation based on input data only."
  [user current-date]
  (let [join-date (:created-at user)
        days-member (/ (.between ChronoUnit/DAYS join-date current-date) 1)
        years-member (/ days-member 365.25)]
    (cond
      (>= years-member 5) :platinum
      (>= years-member 3) :gold
      (>= years-member 1) :silver
      :else :bronze)))

(defn calculate-user-permissions
  "Pure function: Calculate user permissions based on role.

       Args:
         user: User entity with role information

       Returns:
         Set of permission keywords

       Pure - permission calculation based on input data."
  [user]
  (let [base-permissions #{:read-profile :update-profile}
        role-permissions (case (:role user)
                           :admin #{:read-all-users :create-user :update-user :delete-user}
                           :moderator #{:read-users :update-user}
                           :user #{}
                           #{})]
    (set/union base-permissions role-permissions)))

(defn should-require-password-reset?
  "Pure function: Determine if user should be required to reset password.

       Args:
         user: User entity with password metadata
         current-time: Current time instant
         password-policy: Password policy configuration

       Returns:
         Boolean indicating if password reset is required

       Pure - business rule evaluation based on policy and user data."
  [user current-time password-policy]
  (let [password-age-days (/ (.between ChronoUnit/DAYS
                                       (:password-updated-at user current-time)
                                       current-time) 1)
        max-password-age (:max-password-age-days password-policy 90)]
    (> password-age-days max-password-age)))

;; =============================================================================
;; User Validation Business Rules
;; =============================================================================

(defn validate-user-role-transition
  "Pure function: Validate if role transition is allowed by business rules.

       Args:
         current-role: Current user role keyword
         new-role: Proposed new role keyword
         requesting-user-role: Role of user making the request

       Returns:
         {:valid? true} or {:valid? false :reason string}

       Pure - business rule evaluation for role transitions."
  [current-role new-role requesting-user-role]
  (let [role-hierarchy {:user 1 :moderator 2 :admin 3}
        current-level (role-hierarchy current-role 0)
        new-level (role-hierarchy new-role 0)
        requester-level (role-hierarchy requesting-user-role 0)]
    (cond
      ;; Can't promote to a level higher than your own
      (> new-level requester-level)
      {:valid? false :reason "Cannot promote user to a role higher than your own"}

      ;; Can't demote someone at your level or higher (unless demoting yourself)
      (and (>= current-level requester-level)
           (not= current-role requesting-user-role))
      {:valid? false :reason "Cannot modify user at your role level or higher"}

      ;; Valid transition
      :else
      {:valid? true})))

(defn validate-email-domain
  "Pure function: Validate email domain against allowed domains.

       Args:
         email: Email address string
         allowed-domains: Set of allowed domain strings

       Returns:
         {:valid? true} or {:valid? false :reason string}

       Pure - string validation against domain allowlist."
  [email allowed-domains]
  (if (empty? allowed-domains)
    {:valid? true} ; No domain restrictions
    (let [email-domain (second (str/split email #"@"))]
      (if (contains? allowed-domains email-domain)
        {:valid? true}
        {:valid? false :reason (str "Email domain " email-domain " not allowed")}))))

;; =============================================================================
;; User Deletion Business Logic
;; =============================================================================

(defn can-delete-user?
  "Pure function: Determine if user can be deleted.

       Args:
         user: User entity to check

       Returns:
         {:allowed? true} or
         {:allowed? false :reason keyword}

       Pure - business rule checking only."
  [user]
  (cond
    ;; System users cannot be deleted
    (= "system@example.com" (:email user))
    {:allowed? false :reason :system-user}

    ;; Last admin user cannot be deleted (full check in service layer)
    (= :admin (:role user))
    {:allowed? false :reason :last-admin-user}

    :else
    {:allowed? true}))

(defn prepare-user-for-soft-deletion
  "Pure function: Prepare user for soft deletion.

       Args:
         user: Current user entity
         current-time: Current time instant

       Returns:
         User entity marked as deleted

       Pure - data transformation only."
  [user current-time]
  (-> user
      (assoc :deleted-at current-time)
      (assoc :active false)
      (assoc :updated-at current-time)))

(defn can-hard-delete-user?
  "Pure function: Determine if user can be hard deleted.

       Args:
         user: User entity to check

       Returns:
         {:allowed? true} or
         {:allowed? false :reason keyword}

       Pure - business rule checking only."
  [user]
  (let [deletion-check (can-delete-user? user)]
    (if (:allowed? deletion-check)
      ;; Additional hard-deletion specific rules
      {:allowed? true}
      deletion-check)))

;; =============================================================================
;; User Analysis Functions
;; =============================================================================

(defn analyze-user-activity
  "Pure function: Analyze user activity patterns from activity data.

       Args:
         user: User entity
         activity-events: Vector of activity event maps
         analysis-period-days: Number of days to analyze
         now: Instant cutoff for recent activity filtering

       Returns:
         Map with activity analysis results

       Pure - data analysis based on input events."
  [user activity-events analysis-period-days now]
  (let [recent-events (filter #(> (:timestamp %)
                                  now)
                              activity-events)
        event-count (count recent-events)
        unique-days (count (distinct (map #(LocalDate/ofInstant (:timestamp %) ZoneOffset/UTC) recent-events)))]
    {:total-events event-count
     :active-days unique-days
     :avg-events-per-day (if (> unique-days 0) (/ event-count unique-days) 0)
     :activity-score (min 100 (* (/ unique-days analysis-period-days) 100))
     :user-id (:id user)}))
