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
  ;; Takes :id and the keys to change; writes only those, so a partial
  ;; entity leaves the rest as stored. Sets :updated-at.
  ;; Returns the stored entity; throws {:type :user-not-found} when absent.

  (increment-failed-login-count [this user-id lockout])
  ;; lockout {:max-attempts n :until inst}. Adds one to :failed-login-count and,
  ;; when that reaches :max-attempts, sets :lockout-until to :until, in one
  ;; write: parallel failures each count, and a lockout cannot land after a
  ;; login that reset the count (BOU-696). Returns the new count, or nil when
  ;; absent.

  (consume-backup-code [this user code-hash])
  ;; Adds code-hash, one of `user`'s :mfa-backup-codes, to its
  ;; :mfa-backup-codes-used, only while both are still as `user` holds them:
  ;; two logins spending one code cannot both succeed (BOU-708). Returns
  ;; boolean; false when either changed since `user` was read.

  (soft-delete-user [this user-id])
  ;; Sets :deleted-at, hiding the user from every other query. Returns boolean.

  (hard-delete-user [this user-id])
  ;; Irreversible: removes the row and cascades. For GDPR erasure.
  ;; Returns boolean; throws a foreign-key error when references remain.

  (with-admins-locked [this f]))
  ;; Calls (f) in one transaction that first locks the admins' rows, so
  ;; a last-admin guard and the write it allows cannot interleave with another
  ;; (BOU-673). Returns what f returns.

(defprotocol IUserSessionRepository
  "Session persistence. A session is active when it has not expired and has no
   :revoked-at; every read here filters on both."

  (create-session [this session-entity])
  ;; Generates :id when absent. Returns the entity with its plaintext
  ;; :session-token, the only time it is handed out. An adapter stores a hash
  ;; of the token, never the token, and its reads return no :session-token
  ;; (BOU-696).

  (find-session-by-token [this session-token])
  ;; Returns the session entity, or nil when absent, expired or revoked.
  ;; Reads only; validate-session writes the access time.

  (find-sessions-by-user [this user-id])
  ;; Returns the user's active sessions, newest first.

  (find-all-sessions [this])
  ;; Every session including expired and revoked ones, newest first. Unbounded —
  ;; for operational inspection, not for request paths.

  (update-session [this session-entity])
  ;; Takes a complete entity with :id. Returns it, or nil when no row matched.

  (invalidate-session [this session-token])
  ;; Sets :revoked-at. Idempotent. Returns boolean.

  (invalidate-user-session [this user-id session-id])
  ;; Sets :revoked-at on the session with that id, when it belongs to the user
  ;; and is not yet revoked. Returns boolean.

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

(defprotocol IUserIdentityRepository
  "Links between an account and an external sign-in: an OpenID Connect
   (issuer, subject) pair. An issuer never reuses a subject, so the pair names
   one person for good, where an email address can change hands."

  (find-identity [this issuer subject])
  ;; Returns {:id :user-id :provider :issuer :subject :email :created-at
  ;;          :last-login-at}, or nil.

  (find-identities-by-user [this user-id])
  ;; Returns the user's links, oldest first.

  (link-identity [this identity])
  ;; identity: {:user-id :provider :issuer :subject :email}. Generates :id and
  ;; :created-at. Returns the stored link. Throws on a duplicate (issuer, subject).

  (touch-identity [this issuer subject at])
  ;; Sets :last-login-at. Returns boolean.

  (unlink-identity [this issuer subject]))
  ;; Removes the link. Returns boolean.

(defprotocol IUserGroupRepository
  "Groups, their members and their grants. A group is {:id :name :description
   :created-at :updated-at}; a grant {:id :group-id :permission :scope
   :created-at}. Deleting a group or a user removes their memberships and,
   with a group, its grants."

  (find-group [this group-id])
  ;; Returns the group, or nil.

  (find-groups [this])
  ;; Every group by name, each with :member-count and :grant-count.

  (insert-group [this group])
  ;; Generates :id and :created-at. Returns the stored group.

  (modify-group [this group])
  ;; Takes :id and :name, and :description when it is to change. Returns the
  ;; stored group, or nil.

  (remove-group [this group-id])
  ;; Removes the group, its memberships and grants. Returns boolean.

  (find-members [this group-id])
  ;; The group's users, {:id :email :name}, by email. Soft-deleted users are
  ;; left out.

  (insert-member [this group-id user-id])
  ;; Returns true, or false when the user was a member already.

  (remove-member [this group-id user-id])
  ;; Returns boolean.

  (find-grants [this group-id])
  ;; The group's grants, oldest first.

  (insert-grant [this grant])
  ;; grant: {:group-id :permission :scope}. Generates :id and :created-at.
  ;; Returns the stored grant.

  (remove-grant [this group-id grant-id])
  ;; Returns boolean.

  (find-user-grants [this user-id])
  ;; The grants of every group the user is in, each with :group-name. None for
  ;; a soft-deleted user.

  (find-user-groups [this user-id])
  ;; The groups the user is in, by name. None for a soft-deleted user.

  (find-permission-holders [this permission])
  ;; {:user-id :permission :scope} for each active, not soft-deleted user
  ;; holding `permission` through a group, distinct, by user id.

  (ensure-tables [this])
  ;; Creates the group tables, and the audit actions they record, where
  ;; missing. Returns nil.

  (in-transaction [this f]))
  ;; Calls (f repo), every read and write on repo in one transaction: committed
  ;; when f returns, rolled back when it throws. Returns what f returns.

;; =============================================================================
;; Service
;; =============================================================================

(defprotocol IUserService
  "User domain service — orchestrates the repositories, the auth shell and
   the audit trail. Every method may throw an ex-info carrying
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
  ;; Returns the user without :password-hash, or nil. Not cached.

  (get-user-by-email [this email])
  ;; Returns the user without :password-hash, or nil.

  (list-users [this options])
  ;; options as IUserRepository/find-users.
  ;; Returns {:users [...] :total-count n}, none carrying :password-hash.

  (update-user-profile [this user-entity])
  ;; Takes an entity with :id; required keys it leaves out keep their stored
  ;; values. Returns it without :password-hash. A changed :role or a
  ;; deactivation revokes the user's sessions (BOU-191, BOU-665).
  ;; Throws :conflict for removing your own or the last admin's rights.
  ;; Throws :user-not-found, :validation-error, :business-rule-violation.

  (deactivate-user [this user-id])
  ;; Sets :active false and ends the user's sessions; the user stays listed
  ;; and can be reactivated (BOU-681). Returns boolean. Throws :conflict for
  ;; your own or the last admin's account.

  (delete-user [this user-id])
  ;; Soft delete: sets :deleted-at, hiding the user from lists and reads, and
  ;; ends their sessions. Returns boolean. Throws :conflict for your own or the
  ;; last admin's account.

  (permanently-delete-user [this user-id])
  ;; Irreversible erasure. Returns boolean.
  ;; Throws :user-not-found, :hard-deletion-not-allowed (another record still references the user),
  ;; and :conflict for your own or the last admin's account.

  (delete-users [this user-ids opts])
  ;; opts {:permanent? bool}. Deletes every user, as delete-user or
  ;; permanently-delete-user would, or none: the admin guard judges the whole
  ;; set first (BOU-673). Returns the ids deleted; unknown ids are skipped.
  ;; Throws :conflict and :hard-deletion-not-allowed.

  (authenticate-user [this user-credentials])
  ;; user-credentials: {:email :password :ip-address :user-agent :mfa-code}
  ;; Returns, on success, {:authenticated true :user … :session …
  ;;                       :session-token "…" :jwt-token "…"};
  ;; on a pending second factor, {:authenticated false :requires-mfa? true
  ;;                              :user … :message "…"};
  ;; otherwise {:authenticated false :reason <keyword> :message "…"
  ;;            :retry-after <Instant-or-nil>}.
  ;; Does not throw on a bad password — it returns the failure shape.

  (validate-session [this session-token])
  ;; Returns the session and its :user (no credentials), or nil when the token
  ;; is unknown. :last-accessed-at is written at most once a minute, so it may
  ;; trail the request by that much. Throws
  ;; {:type :session-invalid :reason …} for a session that exists but no longer
  ;; validates, its user gone or inactive included, so middleware answers 401.

  (logout-user [this session-token])
  ;; Returns {:invalidated true :session-id …}, or {:invalidated false} when the
  ;; token is unknown.

  (logout-user-everywhere [this user-id] [this user-id keep-session-id])
  ;; Revokes the user's sessions, except keep-session-id when given, and
  ;; audits one :session-revoked for them all. Returns the count revoked.

  (get-user-sessions [this user-id])
  ;; Returns the user's active sessions, newest first.

  (revoke-user-session [this user-id session-id])
  ;; Revokes one of the user's sessions, named by id, and audits a logout.
  ;; Returns {:invalidated boolean}; false when the user holds no such live session.

  (list-audit-logs [this options])
  ;; options as IUserAuditRepository/find-audit-logs.
  ;; Returns {:audit-logs [...] :total-count n}.

  (get-audit-logs-for-user [this user-id options])
  ;; Entries where the user is the target. Returns a seq of entries.

  (change-password [this user-id current-password new-password]))
  ;; Verifies the current password, checks the new one against policy, rehashes,
  ;; revokes every session (BOU-191) and audits. Returns true.
  ;; Throws :user-not-found, :invalid-current-password, :password-policy-violation.

(defprotocol IUserGroupService
  "Groups of users and the permissions they grant. Joining, leaving and a
   changed grant are audited for each user whose rights change. Nothing is
   cached: `user-grants` reads the database, so a change applies to the next
   request. The :admin role is separate and needs no group. Throws ex-infos
   carrying :type: :validation-error, :not-found, :conflict."

  (list-groups [this])
  ;; Every group by name, with :member-count and :grant-count.

  (get-group [this group-id])
  ;; The group with its :members and :grants, or nil.

  (create-group [this group])
  ;; group: {:name :description}. Returns the stored group.

  (update-group [this group])
  ;; group: {:id :name :description}. Returns the stored group.

  (delete-group [this group-id])
  ;; Removes the group, auditing each member's leaving. Returns boolean.

  (add-group-member [this group-id user-id])
  ;; Returns true, or false when the user was a member already.

  (remove-group-member [this group-id user-id])
  ;; Returns boolean.

  (add-group-grant [this group-id grant])
  ;; grant: {:permission kw :scope map}, checked against the declared
  ;; `:wagoe/user :groups` schemas. Returns the stored grant.

  (remove-group-grant [this group-id grant-id])
  ;; Returns boolean.

  (user-grants [this user])
  ;; user: a user map or id. The grants the user holds through their groups,
  ;; for wagoe.user.core.group/holds?, grants-for and authorized?.

  (user-groups [this user])
  ;; user: a user map or id. The groups the user is in.

  (permission-holders [this permission])
  ;; {:user-id :permission :scope} for each active user holding `permission`,
  ;; in one query. Judge the scope with wagoe.user.core.group/users-with-permission.

  (import-groups! [this groups opts]))
  ;; groups: [{:name :description :members [user-id …] :grants [{:permission :scope}]}].
  ;; Checks all, then upserts by name in one audited transaction; additive
  ;; unless opts {:replace? true}. Returns per group {:group :created? :joined
  ;; :left :grants-added :grants-removed}.

;; =============================================================================
;; Sign-in hooks
;; =============================================================================
;;
;; What an application component implements to decide, per sign-in, whether a
;; second factor is asked and where the user lands. A plain function of the same
;; arguments works too.

(defprotocol IMfaPolicy
  (mfa-required? [this user login-context]))
  ;; login-context: :ip-address, :user-agent, :provider for OIDC.
  ;; Returns truthy to ask for a code.

(defprotocol ILoginRedirect
  (login-path [this user login-context]))
  ;; login-context: :ip-address, :user-agent and the Ring :request.
  ;; Returns a path on this site, or nil for the default.
