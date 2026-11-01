(ns wagoe.core.http.error-status
  "The `:type` -> HTTP status table, and the level an error is logged at.

   Here rather than in platform so the SQL layer can log a refused key as the
   caller's, without the HTTP stack (BOU-871).
   `wagoe.platform.core.http.problem-details` delegates to these.")

(def default-error-mappings
  "Exception `:type` -> [status title]."
  {:validation-error          [400 "Validation Error"]
   :invalid-request           [400 "Invalid Request"]
   :unauthorized              [401 "Unauthorized"]
   :auth-failed               [401 "Authentication Failed"]
   :forbidden                 [403 "Forbidden"]
   :not-found                 [404 "Not Found"]
   :user-not-found            [404 "User Not Found"]
   :resource-not-found        [404 "Resource Not Found"]
   :conflict                  [409 "Conflict"]
   :user-exists               [409 "User Already Exists"]
   :resource-exists           [409 "Resource Already Exists"]
   :rate-limit-exceeded       [429 "Too Many Requests"]
   :business-rule-violation   [400 "Business Rule Violation"]
   :deletion-not-allowed      [403 "Deletion Not Allowed"]
   :hard-deletion-not-allowed [403 "Hard Deletion Not Allowed"]
   ;; The user service's refusals. Unmapped, the service pipeline took each
   ;; for a 500 and reported it (BOU-840).
   :session-invalid           [401 "Unauthorized"]
   :invalid-current-password  [400 "Invalid Current Password"]
   :password-policy-violation [400 "Password Policy Violation"]
   :account-locked            [429 "Too Many Requests"]})

(defn exception-status
  "The HTTP status and title for `ex`: its `:type` looked up in the default
   mappings merged with `error-mappings`. Untyped or unmapped is a 500."
  [ex error-mappings]
  (let [ex-type (:type (ex-data ex))]
    (if ex-type
      (get (merge default-error-mappings error-mappings) ex-type [500 "Internal Server Error"])
      [500 "Internal Server Error"])))

(defn status-log-level
  "The level an error answered with `status` is logged at: a 4xx is the
   client's, not a fault (BOU-704). 401/403 at :warn, worth seeing in bulk."
  [status]
  (cond (>= status 500)     :error
        (#{401 403} status) :warn
        :else               :info))

(defn exception-log-level
  "The level `ex` is logged at: its status's (`status-log-level`)."
  [ex error-mappings]
  (status-log-level (first (exception-status ex error-mappings))))
