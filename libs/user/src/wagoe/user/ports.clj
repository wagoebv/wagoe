(ns wagoe.user.ports
  "User module ports — persistence and service seams for the user domain.

   Three repositories (users, sessions, audit log) and one service facade.
   The repositories are what an alternative backend implements; the service is
   what the rest of the framework calls.

   Email, notifications, events and metrics are not here — email lives in
   libs/email, observability owns logging/metrics/audit.")

;; =============================================================================
;; Persistence
;; =============================================================================

(defprotocol IUserRepository
  "User persistence. Entities are kebab-case maps per wagoe.user.schema."

  (find-user-by-id [this user-id])
  ;; Returns the user entity, or nil when absent or soft-deleted.

  (find-user-by-email [this email])
  ;; Returns the user entity, or nil when absent or soft-deleted.

  (find-users [this options])
  ;; options: {:limit :offset :sort-by :sort-direction :filter-role
  ;;           :filter-active :filter-email-contains :include-deleted?}
  ;; Returns {:users [...] :total-count n}. Soft-deleted users are excluded
  ;; unless :include-deleted? is true.

  (create-user [this user-entity])
  ;; Generates :id and :created-at. Returns the stored entity.
  ;; Throws on a duplicate email.

  (update-user [this user-entity])
  ;; Takes a complete entity with :id, sets :updated-at.
  ;; Returns the stored entity; throws {:type :user-not-found} when absent.

  (soft-delete-user [this user-id])
  ;; Sets :deleted-at, hiding the user from every other query. Returns boolean.

  (hard-delete-user [this user-id]))
  ;; Irreversible: removes the row and cascades. For GDPR erasure.
  ;; Returns boolean; throws a foreign-key error when references remain.

(defprotocol IUserSessionRepository
  "Session persistence. A session is active when it has not expired and has no
   :revoked-at; every read here filters on both."

  (create-session [this session-entity])
  ;; Generates :id and :session-token when absent. Returns the stored entity.

  (find-session-by-token [this session-token])
  ;; Returns the session entity, or nil when absent, expired or revoked.
  ;; Side effect: bumps :last-accessed-at on a hit.

  (find-sessions-by-user [this user-id])
  ;; Returns the user's active sessions, newest first.

  (find-all-sessions [this])
  ;; Every session including expired and revoked ones, newest first. Unbounded —
  ;; for operational inspection, not for request paths.

  (update-session [this session-entity])
  ;; Takes a complete entity with :id. Returns it, or nil when no row matched.

  (invalidate-session [this session-token])
  ;; Sets :revoked-at. Idempotent. Returns boolean.

  (invalidate-all-user-sessions [this user-id])
  ;; Revokes every active session for the user. Returns the count revoked.

  (delete-sessions-expired-before [this cutoff]))
  ;; Removes every session whose expiry is before `cutoff`, revoked or not.
  ;; Expiry hides a session from the reads above; this is what takes the row
  ;; out of the table, which nothing did until BOU-429. Returns the count.

(defprotocol IUserAuditRepository
  "Audit-log persistence. Entries are immutable once written."

  (create-audit-log [this audit-entity])
  ;; Generates :id and :created-at. Returns the stored entry.

  (find-audit-logs [this options])
  ;; options: {:limit :offset :sort-by :sort-direction :filter-target-user-id
  ;;           :filter-actor-id :filter-action :filter-result
  ;;           :filter-created-after :filter-created-before}
  ;; Returns {:audit-logs [...] :total-count n}.

  (find-audit-logs-by-user [this user-id options]))
  ;; Entries where the user is the target. options: {:limit :offset :sort-by
  ;; :sort-direction}. Returns a seq of entries.

;; =============================================================================
;; Service
;; =============================================================================

(defprotocol IUserService
  "User domain service — orchestrates the repositories, the auth shell, the
   cache and the audit trail. Every method may throw an ex-info carrying
   :type (ADR-022); the listed types are the ones callers branch on."

  (register-user [this user-data])
  ;; user-data: {:email :name :role :password …}. Hashes the password, writes an
  ;; audit entry. Returns the created user without :password-hash.
  ;; Throws :validation-error, :user-exists, :business-rule-violation.

  (register-or-authenticate-user [this user-data login-context])
  ;; Invite and activation flows: register a new account, or prove ownership of
  ;; an existing one with the same email by authenticating it.
  ;; login-context: {:ip-address :user-agent :mfa-code}
  ;; Returns {:user … :created? bool :authenticated? bool :auth-result …};
  ;; :authenticated? is false and :auth-result nil on the created branch.
  ;; Throws :unauthorized when an existing account cannot be verified.

  (claim-user-identity [this request])
  ;; Transaction-aware form of register-or-authenticate-user.
  ;; request: {:user-data … :login-context … :tx-context optional-db-tx} —
  ;; with :tx-context every repository call runs in that transaction.
  ;; Returns {:mode :registered|:authenticated :user … :created? :authenticated?
  ;;          :auth-result …}.

  (get-user-by-id [this user-id])
  ;; Returns the user without :password-hash, or nil. Cached for 300s.

  (get-user-by-email [this email])
  ;; Returns the user without :password-hash, or nil.

  (list-users [this options])
  ;; options as IUserRepository/find-users.
  ;; Returns {:users [...] :total-count n}, none carrying :password-hash.

  (update-user-profile [this user-entity])
  ;; Takes a complete entity with :id. Returns it without :password-hash.
  ;; A changed :role revokes the user's sessions (BOU-191).
  ;; Throws :user-not-found, :validation-error, :business-rule-violation.

  (deactivate-user [this user-id])
  ;; Soft-deletes after checking deletion policy. Returns boolean.
  ;; Throws :user-not-found, :deletion-not-allowed.

  (permanently-delete-user [this user-id])
  ;; Irreversible erasure. Returns boolean.
  ;; Throws :user-not-found, :hard-deletion-not-allowed (another record still references the user).

  (authenticate-user [this user-credentials])
  ;; user-credentials: {:email :password :ip-address :user-agent :mfa-code}
  ;; Returns, on success, {:authenticated true :user … :session …
  ;;                       :session-token "…" :jwt-token "…"};
  ;; on a pending second factor, {:authenticated false :requires-mfa? true
  ;;                              :user … :message "…"};
  ;; otherwise {:authenticated false :reason <keyword> :message "…"
  ;;            :retry-after <seconds-or-nil>}.
  ;; Does not throw on a bad password — it returns the failure shape.

  (validate-session [this session-token])
  ;; Returns the session with :last-accessed-at refreshed, or nil when the token
  ;; is unknown. Throws {:type :session-invalid :reason …} for a session that
  ;; exists but no longer validates, so middleware answers 401.

  (logout-user [this session-token])
  ;; Returns {:invalidated true :session-id …}, or {:invalidated false} when the
  ;; token is unknown.

  (logout-user-everywhere [this user-id])
  ;; Returns the count of sessions revoked.

  (get-user-sessions [this user-id])
  ;; Returns the user's active sessions, newest first.

  (list-audit-logs [this options])
  ;; options as IUserAuditRepository/find-audit-logs.
  ;; Returns {:audit-logs [...] :total-count n}.

  (get-audit-logs-for-user [this user-id options])
  ;; Entries where the user is the target. Returns a seq of entries.

  (change-password [this user-id current-password new-password]))
  ;; Verifies the current password, checks the new one against policy, rehashes,
  ;; revokes every session (BOU-191) and audits. Returns true.
  ;; Throws :user-not-found, :invalid-current-password, :password-policy-violation.
