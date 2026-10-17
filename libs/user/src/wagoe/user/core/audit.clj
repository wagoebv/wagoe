(ns wagoe.user.core.audit
  "Pure functions for creating audit log entries.
   
   This namespace contains CORE (pure) functions for audit trail management.
   In FC/IS architecture, this is the CORE that:
   - Contains pure business logic for audit entry creation
   - No side effects - returns data structures only
   - All I/O is delegated to shell layer (persistence)
   
   Key FC/IS principles:
   - Pure functions only - deterministic, no side effects
   - Returns data that shell layer will persist
   - Business rules for what gets audited and how")

;; =============================================================================
;; Pure Audit Entry Creation Functions
;; =============================================================================

(defn create-user-audit-entry
  "Create audit log entry for user creation.
   
   Pure function that constructs audit log data structure.
   
   Args:
     actor-id: UUID of user who performed the action (nil for system)
     actor-email: Email of actor
     target-user: Created user entity map
     ip-address: Optional IP address
     user-agent: Optional user agent string
     
   Returns:
     Audit log entity map ready for persistence
     
   Example:
     (create-user-audit-entry admin-id \"admin@example.com\" new-user \"192.168.1.1\" \"Mozilla...\")"
  [actor-id actor-email target-user ip-address user-agent]
  {:action :create
   :actor-id actor-id
   :actor-email actor-email
   :target-user-id (:id target-user)
   :target-user-email (:email target-user)
   :changes {:created true}
   :metadata {:role (name (:role target-user))
              :active (:active target-user)}
   :ip-address ip-address
   :user-agent user-agent
   :result :success})

(defn update-user-audit-entry
  "Create audit log entry for user update.
   
   Args:
     actor-id: UUID of user who performed the action
     actor-email: Email of actor
     target-user-id: UUID of user being updated
     target-user-email: Email of user being updated
     old-values: Map of old field values
     new-values: Map of new field values
     ip-address: Optional IP address
     user-agent: Optional user agent string
     
   Returns:
     Audit log entity map
     
   Example:
     (update-user-audit-entry actor-id \"admin@example.com\" user-id \"user@example.com\"
                             {:role :user} {:role :admin} \"192.168.1.1\" nil)"
  [actor-id actor-email target-user-id target-user-email old-values new-values ip-address user-agent]
  (let [changed-fields (reduce-kv
                        (fn [acc k new-val]
                          (let [old-val (get old-values k)]
                            (if (not= old-val new-val)
                              (conj acc {:field (name k)
                                         :old (str old-val)
                                         :new (str new-val)})
                              acc)))
                        []
                        new-values)]
    {:action :update
     :actor-id actor-id
     :actor-email actor-email
     :target-user-id target-user-id
     :target-user-email target-user-email
     :changes {:fields changed-fields}
     :metadata {:field-count (count changed-fields)}
     :ip-address ip-address
     :user-agent user-agent
     :result :success}))

(defn deactivate-user-audit-entry
  "Create audit log entry for user deactivation (soft delete).
   
   Args:
     actor-id: UUID of user who performed the action
     actor-email: Email of actor
     target-user-id: UUID of user being deactivated
     target-user-email: Email of user being deactivated
     ip-address: Optional IP address
     user-agent: Optional user agent string
     
   Returns:
     Audit log entity map"
  [actor-id actor-email target-user-id target-user-email ip-address user-agent]
  {:action :deactivate
   :actor-id actor-id
   :actor-email actor-email
   :target-user-id target-user-id
   :target-user-email target-user-email
   :changes {:active {:old true :new false}}
   :ip-address ip-address
   :user-agent user-agent
   :result :success})

(defn activate-user-audit-entry
  "Create audit log entry for user activation.
   
   Args:
     actor-id: UUID of user who performed the action
     actor-email: Email of actor
     target-user-id: UUID of user being activated
     target-user-email: Email of user being activated
     ip-address: Optional IP address
     user-agent: Optional user agent string
     
   Returns:
     Audit log entity map"
  [actor-id actor-email target-user-id target-user-email ip-address user-agent]
  {:action :activate
   :actor-id actor-id
   :actor-email actor-email
   :target-user-id target-user-id
   :target-user-email target-user-email
   :changes {:active {:old false :new true}}
   :ip-address ip-address
   :user-agent user-agent
   :result :success})

(defn delete-user-audit-entry
  "Create audit log entry for a soft delete: the user is marked deleted and
   can no longer sign in, the row stays.

   Args:
     actor-id: UUID of user who performed the action
     actor-email: Email of actor
     target-user-id: UUID of user being deleted
     target-user-email: Email of user being deleted
     ip-address: Optional IP address
     user-agent: Optional user agent string

   Returns:
     Audit log entity map"
  [actor-id actor-email target-user-id target-user-email ip-address user-agent]
  {:action :delete
   :actor-id actor-id
   :actor-email actor-email
   :target-user-id target-user-id
   :target-user-email target-user-email
   :changes {:deleted true}
   :ip-address ip-address
   :user-agent user-agent
   :result :success})

(defn permanently-delete-user-audit-entry
  "Audit log entry for a permanent delete: the user's rows are gone (BOU-682).
   Same arguments as `delete-user-audit-entry`."
  [actor-id actor-email target-user-id target-user-email ip-address user-agent]
  (assoc (delete-user-audit-entry actor-id actor-email target-user-id target-user-email
                                  ip-address user-agent)
         :action :permanent-delete))

(defn role-change-audit-entry
  "Create audit log entry for user role change.
   
   Args:
     actor-id: UUID of user who performed the action
     actor-email: Email of actor
     target-user-id: UUID of user whose role changed
     target-user-email: Email of user
     old-role: Previous role keyword
     new-role: New role keyword
     ip-address: Optional IP address
     user-agent: Optional user agent string
     
   Returns:
     Audit log entity map"
  [actor-id actor-email target-user-id target-user-email old-role new-role ip-address user-agent]
  {:action :role-change
   :actor-id actor-id
   :actor-email actor-email
   :target-user-id target-user-id
   :target-user-email target-user-email
   :changes {:role {:old (name old-role) :new (name new-role)}}
   :ip-address ip-address
   :user-agent user-agent
   :result :success})

(defn bulk-action-audit-entry
  "Create audit log entry for bulk operations.
   
   Args:
     actor-id: UUID of user who performed the action
     actor-email: Email of actor
     action-type: Type of bulk action (:deactivate, :activate, :delete, :role-change)
     user-ids: Vector of affected user IDs
     ip-address: Optional IP address
     user-agent: Optional user agent string
     additional-metadata: Optional map with extra context
     
   Returns:
     Audit log entity map
     
   Note: For bulk operations, we create one audit entry per affected user,
         each with metadata indicating it was part of a bulk operation."
  [actor-id actor-email action-type user-ids ip-address user-agent additional-metadata]
  {:action :bulk-action
   :actor-id actor-id
   :actor-email actor-email
   :metadata (merge {:bulk-action-type (name action-type)
                     :affected-user-count (count user-ids)
                     :user-ids (mapv str user-ids)}
                    additional-metadata)
   :ip-address ip-address
   :user-agent user-agent
   :result :success})

(defn login-audit-entry
  "Create audit log entry for user login.
   
   Args:
     user-id: UUID of user who logged in
     user-email: Email of user
     ip-address: IP address of login
     user-agent: User agent string
     success?: Whether login was successful
     error-message: Optional error message if login failed
     
   Returns:
     Audit log entity map"
  [user-id user-email ip-address user-agent success? error-message]
  {:action :login
   :actor-id user-id
   :actor-email user-email
   :target-user-id user-id
   :target-user-email user-email
   :ip-address ip-address
   :user-agent user-agent
   :result (if success? :success :failure)
   :error-message error-message})

(defn logout-audit-entry
  "Create audit log entry for user logout.
   
   Args:
     user-id: UUID of user who logged out
     user-email: Email of user
     ip-address: Optional IP address
     user-agent: Optional user agent string
     
   Returns:
     Audit log entity map"
  [user-id user-email ip-address user-agent]
  {:action :logout
   :actor-id user-id
   :actor-email user-email
   :target-user-id user-id
   :target-user-email user-email
   :ip-address ip-address
   :user-agent user-agent
   :result :success})

;; =============================================================================
;; Audit Entry Validation
;; =============================================================================

(defn valid-audit-entry?
  "Validate that an audit entry has all required fields.
   
   Args:
     audit-entry: Audit entry map to validate
     
   Returns:
     Boolean indicating if entry is valid"
  [audit-entry]
  (and (map? audit-entry)
       (contains? audit-entry :action)
       (contains? audit-entry :target-user-id)
       (contains? audit-entry :target-user-email)
       (contains? audit-entry :result)))

(defn sanitize-audit-metadata
  "Remove sensitive data from audit metadata before persistence.
   
   Pure function that filters out password fields, tokens, etc.
   
   Args:
     metadata: Metadata map that may contain sensitive data
     
   Returns:
     Sanitized metadata map"
  [metadata]
  (when metadata
    (dissoc metadata :password :password-hash :session-token :reset-token)))

;; =============================================================================
;; Groups
;; =============================================================================

(defn- grant-record [{:keys [permission scope]}]
  {:permission (subs (str permission) 1)
   :scope      (pr-str scope)})

(defn group-audit-entries
  "An entry for each of `members` whose rights changed: on :group-join or
   :group-leave the member who joined or left, gaining or losing the group's
   `grants`; on :grant-add or :grant-remove every member, gaining or losing
   the one grant. The log is per user, so a grant changed in an empty group
   records nothing: nobody's rights changed. A join lists the grants, so each
   user's entries account for what they hold."
  [action {:keys [actor-id actor-email ip-address user-agent]} group members grants]
  (for [{:keys [id email]} members]
    {:action            action
     :actor-id          actor-id
     :actor-email       actor-email
     :target-user-id    id
     :target-user-email email
     :changes           {:grants (mapv grant-record grants)}
     :metadata          {:group-id (str (:id group)) :group-name (:name group)}
     :ip-address        ip-address
     :user-agent        user-agent
     :result            :success}))
