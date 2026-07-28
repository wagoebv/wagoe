(ns wagoe.user.ports
  "User module port definitions (abstract interfaces).
   
   This namespace defines all ports (abstract interfaces) that the user module
   needs to interact with external systems and services. These ports follow
   Wagoe's hexagonal architecture pattern, allowing core business logic to
   remain pure and testable while enabling flexible adapter implementations.
   
   Ports:
   - IUserRepository        — user persistence
   - IUserSessionRepository — session persistence
   - IUserAuditRepository   — audit-log persistence
   - IUserService           — user service facade

   Each port is implemented by adapters in the shell layer, enabling dependency
   inversion and supporting multiple implementations (PostgreSQL, H2, in-memory, etc.).

   Cross-cutting concerns (email, notifications, events, audit, time) are NOT
   defined here — email lives in libs/email, observability owns logging/metrics/
   audit. Previously-dead duplicate protocols were removed in BOU-170.")

;; =============================================================================
;; Data Persistence Ports
;; =============================================================================

(defprotocol IUserRepository
  "User data persistence interface with comprehensive CRUD and business operations.
   
   This port abstracts all user data access patterns, supporting:
   - Full CRUD operations operations
   - Business-specific queries for roles, status, and date ranges
   - Batch operations for bulk data processing
   - Soft/hard delete operations for data lifecycle management
   
   All methods return
   domain entities as defined in wagoe.user.schema."

  ;; Basic CRUD Operations
  (find-user-by-id [this user-id]
    "Retrieve user by unique identifier.
     
     Args:
       user-id: UUID of the user to find
     
     Returns:
       User entity map or nil if not found
       User must be active (not soft-deleted) to be returned
     
     Example:
       (find-user-by-id repo #uuid \"123e4567-e89b-12d3-a456-426614174000\")")

  (find-user-by-email [this email]
    "Retrieve user by email address.
     
     Args:
       email: User's email address (string)
     
     Returns:
       User entity map or nil if not found
       Only returns active users
     
     Example:
       (find-user-by-email repo \"john@example.com\")")

  (find-users [this options]
    "Retrieve paginated users with filtering and sorting.
     
     Args:
       options: Map with pagination and filtering options:
                {:limit 20
                 :offset 0
                 :sort-by :created-at
                 :sort-direction :desc
                 :filter-role :user
                 :filter-active true
                 :filter-email-contains \"@company.com\"}
     
     Returns:
       Map with :users (vector of user entities) and :total-count
       Only returns active users unless :include-deleted? true in options
     
     Example:
       (find-users repo {:limit 10 :filter-role :admin})")

  (create-user [this user-entity]
    "Create new user with automatic ID and timestamp generation.
     
     Args:
       user-entity: User entity map conforming to schema/User
                   (ID, created-at, updated-at will be generated)
     
     Returns:
       Created user entity with generated ID and timestamps
       Throws exception if email already exists
     
     Example:
       (create-user repo {:email \"new@example.com\"
                         :name \"New User\"
                         :role :user
                         :active true})")

  (update-user [this user-entity]
    "Update existing user with automatic updated-at timestamp.
     
     Args:
       user-entity: Complete user entity map with ID
                   (updated-at will be set automatically)
     
     Returns:
       Updated user entity with new updated-at timestamp
       Throws exception if user not found
     
     Example:
       (update-user repo (assoc existing-user :name \"Updated Name\"))")

  (soft-delete-user [this user-id]
    "Mark user as deleted without physical removal.
     
     Args:
       user-id: UUID of user to soft-delete
     
     Returns:
       Boolean indicating success
       Sets deleted-at timestamp, user will not appear in normal queries
       Related data (sessions, preferences) may be cleaned up separately
     
     Example:
       (soft-delete-user repo user-id)")

  (hard-delete-user [this user-id]
    "Permanently delete user and all related data.
     
     Args:
       user-id: UUID of user to permanently delete
     
     Returns:
       Boolean indicating success
       ⚠️  IRREVERSIBLE: Physically removes user and cascades to related data
       Should only be used for GDPR compliance or similar requirements
     
     Example:
       (hard-delete-user repo user-id)")

  ;; Business-Specific Queries
  (find-active-users-by-role [this role] [this role options]
    "Find active users with specific role.

     Args:
       role: Keyword role (:admin, :user, :viewer)
       options: Optional map with :limit and :offset for pagination.
                Without options, results are bounded by the platform
                max pagination limit (defense against unbounded scans).

     Returns:
       Vector of user entities with specified role
       Only returns active (non-deleted) users

     Example:
       (find-active-users-by-role repo :admin)
       (find-active-users-by-role repo :admin {:limit 50 :offset 100})")

  (count-users [this]
    "Count total active users.
     
     Returns:
       Integer count of active users (excludes soft-deleted)
     
     Example:
       (count-users repo)")

  (find-users-created-since [this since-date] [this since-date options]
    "Find users created after specified date.

     Args:
       since-date: Instant representing cutoff date
       options: Optional map with :limit and :offset for pagination.
                Without options, results are bounded by the platform
                max pagination limit (defense against unbounded scans).

     Returns:
       Vector of user entities created after since-date
       Useful for analytics, recent user tracking, etc.

     Example:
       (find-users-created-since repo (time/minus (time/instant) (time/days 7)))
       (find-users-created-since repo cutoff {:limit 50 :offset 100})")

  (find-users-by-email-domain [this email-domain] [this email-domain options]
    "Find users with email addresses from specific domain.

     Args:
       email-domain: String domain to match (e.g., \"company.com\")
       options: Optional map with :limit and :offset for pagination.
                Without options, results are bounded by the platform
                max pagination limit (defense against unbounded scans).

     Returns:
       Vector of user entities with matching email domain
       Useful for organization-based user management

     Example:
       (find-users-by-email-domain repo \"company.com\")
       (find-users-by-email-domain repo \"company.com\" {:limit 50 :offset 100})")

  ;; Batch Operations
  (create-users-batch [this user-entities]
    "Create multiple users in single transaction.
     
     Args:
       user-entities: Vector of user entity maps
     
     Returns:
       Vector of created user entities with generated IDs and timestamps
       All operations succeed or all fail (transaction boundary)
       Useful for bulk user imports, organization setup, etc.
     
     Example:
       (create-users-batch repo [{:email \"user1@example.com\" ...}
                                {:email \"user2@example.com\" ...}])")

  (update-users-batch [this user-entities]
    "Update multiple users in single transaction.
     
     Args:
       user-entities: Vector of complete user entity maps with IDs
     
     Returns:
       Vector of updated user entities
       All operations succeed or all fail (transaction boundary)
       Useful for bulk status changes, role assignments, etc.
     
     Example:
       (update-users-batch repo [updated-user1 updated-user2])"))

(defprotocol IUserSessionRepository
  "User session persistence for authentication and session management.
   
   This port manages user authentication sessions, supporting:
   - Session creation and validation
   - Token-based session lookup
   - Session lifecycle management (creation, validation, invalidation)
   - Expired session cleanup for maintenance
   
   Sessions provide secure, stateful authentication tracking across
   user interactions with the system."

  (create-session [this session-entity]
    "Create new user session with secure token generation.
     
     Args:
       session-entity: Session entity map conforming to schema/UserSession
                      (ID and session-token will be generated if not provided)
     
     Returns:
       Created session entity with generated ID, token, and timestamps
       Session token should be cryptographically secure random string
     
     Example:
       (create-session repo {:user-id user-id
                            :expires-at (time/plus (time/instant) (time/hours 24))
                            :user-agent \"Mozilla/5.0...\"
                            :ip-address \"192.168.1.1\"})")

  (find-session-by-token [this session-token]
    "Retrieve active session by token.
     
     Args:
       session-token: String session token to look up
     
     Returns:
       Session entity map or nil if not found or expired
       Should automatically check expiration and return nil for expired sessions
       Updates last-accessed-at timestamp when session is found
     
     Example:
       (find-session-by-token repo \"abc123def456...\")")

  (find-sessions-by-user [this user-id]
    "Find all active sessions for a user.
     
     Args:
       user-id: UUID of user to find sessions for
     
     Returns:
       Vector of active session entities for the user
       Excludes expired and revoked sessions
       Useful for session management, security monitoring
     
     Example:
       (find-sessions-by-user repo user-id)")

  (invalidate-session [this session-token]
    "Invalidate session by token (logout).
     
     Args:
       session-token: String session token to invalidate
     
     Returns:
       Boolean indicating success
       Sets revoked-at timestamp, making session permanently invalid
       Should be idempotent (safe to call multiple times)
     
     Example:
       (invalidate-session repo session-token)")

  (invalidate-all-user-sessions [this user-id]
    "Invalidate all sessions for a user (force logout everywhere).
     
     Args:
       user-id: UUID of user whose sessions to invalidate
     
     Returns:
       Integer count of invalidated sessions
       Useful for security incidents, password changes, etc.
     
     Example:
       (invalidate-all-user-sessions repo user-id)")

  (cleanup-expired-sessions [this before-timestamp]
    "Remove expired sessions for maintenance.
     
     Args:
       before-timestamp: Instant cutoff for cleanup
     
     Returns:
       Integer count of deleted sessions
       Should be called periodically to prevent session table growth
       Only removes sessions that expired before the cutoff
     
     Example:
       (cleanup-expired-sessions repo (time/minus (time/instant) (time/days 30)))")

  (update-session [this session-entity]
    "Update existing session (for access time updates, extensions, etc.).
     
     Args:
       session-entity: Complete session entity map with ID
     
     Returns:
       Updated session entity
       Used for updating access times, extending expiry, etc.
     
     Example:
       (update-session repo (assoc session :last-accessed-at (time/instant)))")

  (find-all-sessions [this]
    "Find all sessions (used for cleanup operations).
     
     Returns:
       Vector of all session entities
       Should be used carefully - may need pagination for large datasets
     
     Example:
       (find-all-sessions repo)")

  (delete-session [this session-id]
    "Permanently delete session by ID.
     
     Args:
       session-id: UUID of session to delete
     
     Returns:
       Boolean indicating success
       Used for cleanup operations
     
     Example:
       (delete-session repo session-id)"))

(defprotocol IUserAuditRepository
  "User audit log persistence for compliance and security monitoring.
   
   This port manages audit trail records, supporting:
   - Recording all user-related actions and changes
   - Querying audit history by user, actor, action type, or date range
   - Compliance reporting and security investigations
   - Tracking bulk operations and their impact
   
   Audit logs are immutable once created and provide a complete
   history of who did what, when, and what changed."

  (create-audit-log [this audit-entity]
    "Create a new audit log entry.
     
     Args:
       audit-entity: Audit log entity map conforming to schema/UserAuditLog
                    ID and created-at will be generated if not provided
     
     Returns:
       Created audit log entity with generated ID and timestamp
     
     Example:
       (create-audit-log repo {:action :update
                              :actor-id actor-uuid
                              :actor-email \"admin@example.com\"
                              :target-user-id user-uuid
                              :target-user-email \"user@example.com\"
                              :changes {:field \"role\" :old \"user\" :new \"admin\"}
                              :result :success})")

  (find-audit-logs [this options]
    "Retrieve audit logs with filtering, sorting, and pagination.
     
     Args:
       options: Map with query options:
                {:limit 50
                 :offset 0
                 :sort-by :created-at
                 :sort-direction :desc
                 :filter-target-user-id user-uuid
                 :filter-actor-id actor-uuid
                 :filter-action :update
                 :filter-result :success
                 :filter-created-after instant
                 :filter-created-before instant}
     
     Returns:
       Map with :audit-logs (vector of audit log entities) and :total-count
     
     Example:
       (find-audit-logs repo {:filter-target-user-id user-id :limit 20})")

  (find-audit-logs-by-user [this user-id options]
    "Retrieve audit logs for a specific user (as target).
     
     Args:
       user-id: UUID of user to get audit trail for
       options: Optional map with :limit, :offset, :sort-by, :sort-direction
     
     Returns:
       Vector of audit log entities for the user
     
     Example:
       (find-audit-logs-by-user repo user-id {:limit 50})")

  (find-audit-logs-by-actor [this actor-id options]
    "Retrieve audit logs for a specific actor (who performed actions).
     
     Args:
       actor-id: UUID of actor to get action history for
       options: Optional map with :limit, :offset, :sort-by, :sort-direction
     
     Returns:
       Vector of audit log entities for the actor
     
     Example:
       (find-audit-logs-by-actor repo actor-id {:limit 50})")

  (count-audit-logs [this filters]
    "Count audit logs matching filters (for pagination).
     
     Args:
       filters: Optional map with filter criteria
                {:filter-target-user-id uuid
                 :filter-actor-id uuid
                 :filter-action :update
                 :filter-created-after instant}
     
     Returns:
       Integer count of matching audit logs
     
     Example:
       (count-audit-logs repo {:filter-action :delete})"))

;; =============================================================================
;; Service Layer Ports
;; =============================================================================

(defprotocol IUserService
  "User domain service interface for business operations.
   
   This port defines the service layer interface for user domain operations.
   The service layer coordinates between pure business logic (core) and
   I/O operations (repositories), managing external dependencies like time,
   UUIDs, and logging.
   
   Service layer responsibilities:
   - Orchestrate complex business operations
   - Coordinate between multiple repositories
   - Handle external dependencies (time, IDs, logging)
   - Enforce business rules and validation
   - Manage transaction boundaries"

  ;; User Management Operations
  (register-user [this user-data]
    "Register a new user with full validation and business rule enforcement.
     
     Business operation that coordinates user registration including validation,
     uniqueness checks, and initial setup.
     
     Args:
       user-data: Map with user creation data
                 {:email string :name string :role keyword ...}
     
     Returns:
       Created user entity with generated ID and timestamps
       
     Throws:
       - ExceptionInfo with :type :validation-error for invalid data
       - ExceptionInfo with :type :user-exists for duplicate email
       - ExceptionInfo with :type :business-rule-violation for rule violations
     
     Example:
       (register-user service {:email \"new@example.com\"
                              :name \"New User\"
                              :role :user})")

  (register-or-authenticate-user [this user-data login-context]
    "Create a new user or claim an existing account by authenticating it.

     Intended for invite and activation flows where:
     - a brand new user should be registered with a password, or
     - an existing user with the same email should prove account ownership
       by authenticating with that password.

     Args:
       user-data: Map with at least {:email string :name string :password string :role keyword}
       login-context: Map with optional login/session context
                     {:ip-address string :user-agent string :mfa-code string}

     Returns:
       {:user user-entity
        :created? boolean
        :authenticated? boolean
        :auth-result optional-auth-result}

     Throws:
       - ExceptionInfo with :type :unauthorized when existing account cannot be verified
       - Same validation/registration exceptions as register-user for invalid new accounts")

  (claim-user-identity [this request]
    "Transaction-aware identity claim for invite and activation flows.

     Args:
       request: {:user-data {...}
                 :login-context {...}
                 :tx-context optional-db-tx}

     Returns:
       {:mode :registered|:authenticated
        :user user-entity
        :created? boolean
        :authenticated? boolean
        :auth-result optional-auth-result}

     Behavior:
       - registers a new user when the email does not exist yet
       - authenticates an existing user to prove account ownership
       - returns an authenticated session result for the claimed identity
       - when :tx-context is provided, all repository operations use that DB transaction")

  (get-user-by-id [this user-id]
    "Retrieve user by ID with service-level validation.
     
     Args:
       user-id: UUID of user to find
     
     Returns:
       User entity or nil if not found
       
     Example:
       (get-user-by-id service user-id)")

  (get-user-by-email [this email]
    "Retrieve user by email.
     
     Args:
       email: String email address
     
     Returns:
       User entity or nil if not found
       
     Example:
       (get-user-by-email service \"user@example.com\")")

  (list-users [this options]
    "List users with pagination and filtering.
     
     Args:
       options: Map with pagination/filtering options
     
     Returns:
       Map with :users vector and :total-count
       
     Example:
       (list-users service {:limit 10 :offset 0})")

  (update-user-profile [this user-entity]
    "Update user profile with validation and business rule enforcement.
     
     Business operation that validates profile changes and applies business rules.
     
     Args:
       user-entity: Complete user entity map with ID
     
     Returns:
       Updated user entity
       
     Throws:
       - ExceptionInfo with :type :user-not-found if user doesn't exist
       - ExceptionInfo with :type :validation-error for invalid data
       - ExceptionInfo with :type :business-rule-violation for rule violations
       
     Example:
       (update-user-profile service updated-user)")

  (deactivate-user [this user-id]
    "Deactivate user account with business rule validation.
     
     Business operation that soft-deletes a user after checking deletion policies.
     
     Args:
       user-id: UUID of user to deactivate
     
     Returns:
       Boolean indicating success
       
     Throws:
       - ExceptionInfo with :type :user-not-found if user doesn't exist
       - ExceptionInfo with :type :deletion-not-allowed if deletion not permitted
       
     Example:
       (deactivate-user service user-id)")

  (permanently-delete-user [this user-id]
    "Permanently delete user (irreversible) with strict validation.
     
     Business operation for GDPR compliance and permanent user removal.
     
     Args:
       user-id: UUID of user to permanently delete
     
     Returns:
       Boolean indicating success
       
     Throws:
       - ExceptionInfo with :type :user-not-found if user doesn't exist
       - ExceptionInfo with :type :hard-deletion-not-allowed if not permitted
       
     Example:
       (permanently-delete-user service user-id)")

  ;; Session Management Operations
  (authenticate-user [this session-data]
    "Authenticate user and create session with token generation.
     
     Business operation that creates authenticated session after validation.
     
     Args:
       session-data: Map with session creation data
                    {:user-id uuid :user-agent string ...}
     
     Returns:
       Created session entity with generated token and expiry
       
     Throws:
       - ExceptionInfo with :type :validation-error for invalid data
       
     Example:
       (authenticate-user service {:user-id user-id
                                  :user-agent \"Mozilla/5.0...\"})")

  (validate-session [this session-token]
    "Validate and retrieve session by token.
     
     Business operation that checks session validity and updates access time.
     
     Args:
       session-token: String session token
     
     Returns:
       Valid session entity or nil if not found/expired
       
     Side effects:
       - Updates last-accessed-at if session is valid
       
     Example:
       (validate-session service \"session-token-123\")")

  (logout-user [this session-token]
    "Log out user by invalidating session.
     
     Business operation that terminates a user session.
     
     Args:
       session-token: String session token to invalidate
     
     Returns:
       Boolean indicating success
       
     Example:
       (logout-user service \"session-token-123\")")

  (logout-user-everywhere [this user-id]
    "Log out user from all sessions (force logout everywhere).
     
     Business operation for security incidents, password changes, etc.
     
     Args:
       user-id: UUID of user whose sessions to invalidate
     
     Returns:
       Integer count of invalidated sessions
       
     Example:
       (logout-user-everywhere service user-id)")

  (get-user-sessions [this user-id]
    "Get all active sessions for a user.
     
     Business operation to retrieve session list for management/monitoring.
     
     Args:
       user-id: UUID of user to get sessions for
     
     Returns:
        Vector of active session entities (non-expired, non-revoked)
        Sessions include session-token, ip-address, user-agent, created-at, last-accessed-at
        
       Example:
         (get-user-sessions service user-id)")

  ;; Audit Log Query Operations
  (list-audit-logs [this options]
    "List audit logs with pagination, filtering, and sorting.
     
     Business operation to query audit trail for compliance and monitoring.
     
     Args:
       options: Map with pagination/filtering options:
                {:limit 50
                 :offset 0
                 :sort-by :created-at
                 :sort-direction :desc
                 :filter-target-user-id uuid
                 :filter-target-email string
                 :filter-actor-id uuid
                 :filter-actor-email string
                 :filter-action :create|:update|:delete|:login|etc
                 :filter-result :success|:failure
                 :filter-created-after instant
                 :filter-created-before instant}
     
     Returns:
       Map with :audit-logs vector and :total-count
       
     Example:
       (list-audit-logs service {:limit 20 :filter-action :update})")

  (get-audit-logs-for-user [this user-id options]
    "Get audit trail for a specific user (as target).
     
     Business operation to retrieve user-specific audit history.
     
     Args:
       user-id: UUID of user to get audit trail for
       options: Optional map with :limit, :offset, :sort-by, :sort-direction
     
     Returns:
       Vector of audit log entities for the user
       
     Example:
       (get-audit-logs-for-user service user-id {:limit 50})")

  ;; Password Management Operations
  (change-password [this user-id current-password new-password]
    "Change user's password after verifying current password.
     
     Business operation that validates current password, checks new password
     against policy, hashes and updates password, and creates audit trail.
     
     Args:
       user-id: UUID of user changing password
       current-password: Current plain text password for verification
       new-password: New plain text password (will be hashed)
     
     Returns:
       Boolean indicating success
       
     Throws:
       - ExceptionInfo with :type :user-not-found if user doesn't exist
       - ExceptionInfo with :type :invalid-current-password if current password wrong
       - ExceptionInfo with :type :password-policy-violation if new password too weak
       
     Example:
       (change-password service user-id \"OldPass123!\" \"NewSecurePass456!\")"))
