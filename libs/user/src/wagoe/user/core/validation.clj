(ns wagoe.user.core.validation
  "Enhanced domain validation functions for user business rules.
   
   This namespace contains comprehensive validation functions that implement
   business rules beyond basic schema validation. All functions are pure
   and follow FC/IS architectural principles."
  (:require [wagoe.user.schema :as schema]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]))

;; =============================================================================
;; Enhanced Domain Validation Functions
;; =============================================================================

;; Forward declarations for functions used before they're defined
(declare valid-email-domain? validate-email-domain-constraint
         valid-user-name? validate-name-constraint
         valid-user-role? validate-role-constraint
         valid-password? validate-password-constraint
         validate-cross-field-constraints
         admin-email-domain-valid? validate-enhanced-role-transition)

(defn- humanized-errors->error-maps
  "Convert a humanized Malli error structure to a flat vector of error maps.

  The output shape is designed to work with platform error conversion, which expects
  each error to include a :field key.

  Example humanized output:
    {:password [Password must be at least 8 characters]}

  Output:
    [{:field [:password]
      :code :schema-validation-failed
      :message Password must be at least 8 characters}]"
  ([humanized]
   (humanized-errors->error-maps [] humanized))
  ([path humanized]
   (cond
     (map? humanized)
     (mapcat (fn [[k v]]
               (humanized-errors->error-maps (conj path k) v))
             humanized)

     ;; Common case: vector of messages
     (sequential? humanized)
     (map (fn [msg]
            {:field (vec path)
             :code :schema-validation-failed
             :message (str msg)})
          humanized)

     ;; Fallback: a single message/value
     :else
     [{:field (vec path)
       :code :schema-validation-failed
       :message (str humanized)}])))

(defn format-schema-errors
  "Pure function: Convert Malli explain data to structured domain error format.

  Accepts the output of (m/explain ...) and returns a vector of error maps.
  This avoids leaking internal Malli explain keys like :schema/:value/:errors into
  user-facing validation output."
  [malli-explain]
  (-> malli-explain
      (me/humanize)
      (humanized-errors->error-maps)
      (vec)))

(defn validate-user-business-constraints
  "Pure function: Validate comprehensive business constraints for user data.
   
   Args:
     user-data: User data to validate
     validation-config: Configuration containing business rules
   
   Returns:
     {:valid? true} or {:valid? false :errors [...]}
   
   Pure - comprehensive business rule validation."
  [user-data validation-config]
  (let [errors (cond-> []
                 ;; Email domain validation
                 (not (valid-email-domain? (:email user-data) validation-config))
                 (conj (validate-email-domain-constraint (:email user-data) validation-config))

                 ;; Name validation
                 (not (valid-user-name? (:name user-data) validation-config))
                 (conj (validate-name-constraint (:name user-data) validation-config))

                 ;; Role validation
                 (not (valid-user-role? (:role user-data) validation-config))
                 (conj (validate-role-constraint (:role user-data) validation-config))

                 ;; Password validation (if password provided)
                 (and (:password user-data)
                      (not (valid-password? (:password user-data) validation-config)))
                 (conj (validate-password-constraint (:password user-data) validation-config)))]
    (if (empty? errors)
      {:valid? true}
      {:valid? false :errors (remove nil? errors)})))

;; =============================================================================
;; Email Domain Validation
;; =============================================================================

(defn valid-email-domain?
  "Pure function: Check if email domain is allowed."
  [email validation-config]
  (let [allowed-domains (get-in validation-config [:email-domain-allowlist])]
    (if (empty? allowed-domains)
      true ; No restrictions
      (let [domain (second (str/split email #"@"))]
        (contains? allowed-domains domain)))))

(defn validate-email-domain-constraint
  "Pure function: Generate error for invalid email domain."
  [email validation-config]
  (let [domain (second (str/split email #"@"))
        allowed-domains (get-in validation-config [:email-domain-allowlist])]
    {:field :email
     :code :invalid-domain
     :message (str "Email domain '" domain "' is not allowed. Allowed domains: "
                   (str/join ", " (sort allowed-domains)))}))

;; =============================================================================
;; User Name Validation
;; =============================================================================

(defn valid-user-name?
  "Pure function: Check if user name meets business requirements.
   Supports Unicode letters including accented characters."
  [name validation-config]
  (let [name-config (get-in validation-config [:name-restrictions] {})
        min-len (get name-config :min-length 2)
        max-len (get name-config :max-length 100)
        ;; Updated regex to support Unicode letters (\p{L}) instead of just ASCII (a-zA-Z)
        allowed-regex-pattern (get name-config :allowed-chars-regex "[\\p{L}\\s\\-'.]+")]
    (and (>= (count name) min-len)
         (<= (count name) max-len)
         (re-matches (re-pattern allowed-regex-pattern) name)
         (not (str/blank? (str/trim name))))))

(defn validate-name-constraint
  "Pure function: Generate error for invalid user name."
  [name validation-config]
  (let [name-config (get-in validation-config [:name-restrictions] {})
        min-len (get name-config :min-length 2)
        max-len (get name-config :max-length 100)]
    (cond
      (< (count name) min-len)
      {:field :name
       :code :too-short
       :message (str "Name must be at least " min-len " characters long")}

      (> (count name) max-len)
      {:field :name
       :code :too-long
       :message (str "Name cannot exceed " max-len " characters")}

      (str/blank? (str/trim name))
      {:field :name
       :code :blank
       :message "Name cannot be blank or contain only whitespace"}

      :else
      {:field :name
       :code :invalid-characters
       :message "Name contains invalid characters. Only letters (including accented characters), spaces, hyphens, apostrophes, and periods are allowed"})))

;; =============================================================================
;; User Role Validation
;; =============================================================================

(defn valid-user-role?
  "Pure function: Check if user role is valid for creation context."
  [role validation-config]
  (let [allowed-roles (get-in validation-config [:role-restrictions :allowed-for-creation] #{:user :admin :viewer})
        restricted-roles (get-in validation-config [:role-restrictions :creation-restricted] #{})]
    (and (contains? allowed-roles role)
         (not (contains? restricted-roles role)))))

(defn validate-role-constraint
  "Pure function: Generate error for invalid user role."
  [role validation-config]
  (let [allowed-roles (get-in validation-config [:role-restrictions :allowed-for-creation] #{:user :admin :viewer})
        restricted-roles (get-in validation-config [:role-restrictions :creation-restricted] #{})]
    (cond
      (nil? role)
      {:field :role
       :code :role-required
       :message "Role is required"}

      (contains? restricted-roles role)
      {:field :role
       :code :role-creation-restricted
       :message (str "Role '" (name role) "' cannot be assigned during user creation")}

      (not (contains? allowed-roles role))
      {:field :role
       :code :role-not-allowed
       :message (str "Role '" (name role) "' is not allowed. Allowed roles: "
                     (str/join ", " (map name (sort allowed-roles))))}

      :else
      {:field :role
       :code :invalid-role
       :message "Invalid role specified"})))

;; =============================================================================
;; Password Policy Validation
;; =============================================================================

(defn valid-password?
  "Pure function: Check if password meets policy requirements."
  [password validation-config]
  (let [policy (get-in validation-config [:password-policy] {})
        min-length (get policy :min-length 8)
        require-uppercase? (get policy :require-uppercase? false)
        require-lowercase? (get policy :require-lowercase? false)
        require-numbers? (get policy :require-numbers? true)
        require-special-chars? (get policy :require-special-chars? false)
        forbidden-patterns (get policy :forbidden-patterns [])
        max-length (get policy :max-length 255)]
    (and (>= (count password) min-length)
         (<= (count password) max-length)
         (or (not require-uppercase?) (re-find #"[A-Z]" password))
         (or (not require-lowercase?) (re-find #"[a-z]" password))
         (or (not require-numbers?) (re-find #"\d" password))
         (or (not require-special-chars?) (re-find #"[!@#$%^&*(),.?\":{}|<>]" password))
         (not-any? #(str/includes? (str/lower-case password) (str/lower-case %)) forbidden-patterns))))

(defn validate-password-constraint
  "Pure function: Generate detailed error for invalid password."
  [password validation-config]
  (let [policy (get-in validation-config [:password-policy] {})
        min-length (get policy :min-length 8)
        require-uppercase? (get policy :require-uppercase? false)
        require-lowercase? (get policy :require-lowercase? false)
        require-numbers? (get policy :require-numbers? true)
        require-special-chars? (get policy :require-special-chars? false)
        forbidden-patterns (get policy :forbidden-patterns [])
        max-length (get policy :max-length 255)

        violations (cond-> []
                     (< (count password) min-length)
                     (conj (str "at least " min-length " characters"))

                     (> (count password) max-length)
                     (conj (str "no more than " max-length " characters"))

                     (and require-uppercase? (not (re-find #"[A-Z]" password)))
                     (conj "at least one uppercase letter")

                     (and require-lowercase? (not (re-find #"[a-z]" password)))
                     (conj "at least one lowercase letter")

                     (and require-numbers? (not (re-find #"\d" password)))
                     (conj "at least one number")

                     (and require-special-chars? (not (re-find #"[!@#$%^&*(),.?\":{}|<>]" password)))
                     (conj "at least one special character (!@#$%^&*(),.?\":{}|<>)")

                     (some #(str/includes? (str/lower-case password) (str/lower-case %)) forbidden-patterns)
                     (conj "not contain common weak patterns"))]
    {:field :password
     :code :password-policy-violation
     :message (str "Password must have: " (str/join ", " violations))}))

;; =============================================================================
;; User Limits Validation
;; =============================================================================

;; =============================================================================
;; Cross-Field Validation
;; =============================================================================

(defn validate-cross-field-constraints
  "Pure function: Validate constraints that depend on multiple fields.
   
   Args:
     user-data: Complete user data map
     validation-config: Validation configuration
   
   Returns:
     {:valid? true} or {:valid? false :errors [...]}
   
   Pure - cross-field business rule validation."
  [user-data validation-config]
  (let [errors (cond-> []
                 ;; Admin users must have stricter email domain restrictions
                 (and (= :admin (:role user-data))
                      (not (admin-email-domain-valid? (:email user-data) validation-config)))
                 (conj {:field :email
                        :code :admin-email-domain-restricted
                        :message "Admin users must have email from approved corporate domains"})

                 ;; Viewer role cannot be active=false on creation
                 (and (= :viewer (:role user-data))
                      (= false (:active user-data)))
                 (conj {:field :active
                        :code :viewer-must-be-active
                        :message "Viewer users must be created as active"}))]
    (if (empty? errors)
      {:valid? true}
      {:valid? false :errors errors})))

(defn admin-email-domain-valid?
  "Pure function: Check if email domain is valid for admin users."
  [email validation-config]
  (let [admin-domains (get-in validation-config [:admin-restrictions :email-domains])]
    (if (empty? admin-domains)
      true ; No admin-specific restrictions
      (let [domain (second (str/split email #"@"))]
        (contains? admin-domains domain)))))

;; =============================================================================
;; Enhanced Role Transition Validation
;; =============================================================================

(defn validate-enhanced-role-transition
  "Pure function: Enhanced role transition validation with context.
   
   Args:
     current-role: Current user role
     new-role: Proposed new role
     requesting-user: User making the request (with role and context)
     validation-config: Enhanced role validation configuration
   
   Returns:
     {:valid? true} or {:valid? false :errors [...]}
   
   Pure - enhanced role transition business rules."
  [current-role new-role requesting-user validation-config]
  (let [role-hierarchy (get-in validation-config [:role-hierarchy] {:user 1 :viewer 2 :admin 3})
        current-level (role-hierarchy current-role 0)
        new-level (role-hierarchy new-role 0)
        requester-level (role-hierarchy (:role requesting-user) 0)
        role-transitions (get-in validation-config [:role-transitions] {})
        allowed-transitions (get role-transitions current-role #{})

        errors (cond-> []
                 ;; Can't promote to a level higher than your own
                 (> new-level requester-level)
                 (conj {:field :role
                        :code :insufficient-privileges
                        :message "Cannot promote user to a role higher than your own"})

                 ;; Can't modify someone at your level or higher (unless self)
                 (and (>= current-level requester-level)
                      (not= (:id requesting-user) (:target-user-id requesting-user)))
                 (conj {:field :role
                        :code :cannot-modify-peer-or-superior
                        :message "Cannot modify user at your role level or higher"})

                 ;; Role transition not allowed by business rules
                 (and (not (empty? allowed-transitions))
                      (not (contains? allowed-transitions new-role)))
                 (conj {:field :role
                        :code :transition-not-allowed
                        :message (str "Cannot transition from " (name current-role) " to " (name new-role))}))]
    (if (empty? errors)
      {:valid? true}
      {:valid? false :errors errors})))

;; =============================================================================
;; Comprehensive User Creation Validation
;; =============================================================================

(def ^:private create-user-request-validator (m/validator schema/CreateUserRequest))
(def ^:private create-user-request-explainer (m/explainer schema/CreateUserRequest))

(defn validate-comprehensive-user-creation
  "Pure function: Comprehensive validation combining all business rules.
   
   Args:
     user-data: User creation request data
     validation-config: Complete validation configuration
     context: Additional context like current user count, requesting user, etc.
   
   Returns:
     {:valid? true :data processed-data} or 
     {:valid? false :errors [...]}
   
    Pure - orchestrates all validation functions."
  [user-data validation-config _context]
  (let [;; Schema validation first
        schema-result (if (create-user-request-validator user-data)
                        {:valid? true}
                        {:valid? false :errors (format-schema-errors (create-user-request-explainer user-data))})

        ;; Business constraints validation
        business-result (validate-user-business-constraints user-data validation-config)

        ;; Cross-field validation
        cross-field-result (validate-cross-field-constraints user-data validation-config)

        ;; Collect all errors
        all-errors (cond-> []
                     (not (:valid? schema-result))
                     (concat (:errors schema-result))

                     (not (:valid? business-result))
                     (concat (:errors business-result))

                     (not (:valid? cross-field-result))
                     (concat (:errors cross-field-result)))]

    (if (empty? all-errors)
      {:valid? true :data user-data}
      {:valid? false :errors all-errors})))