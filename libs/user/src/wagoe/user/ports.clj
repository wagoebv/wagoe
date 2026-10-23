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
  ;;           :filter-active :filter-email-contains
  ;;           :filter-name-or-email-contains :exclude-group-members (a group id)
  ;;           :include-deleted?}
  ;; Returns {:users [...] :total-count n}. Soft-deleted users are excluded
  ;; unless :include-deleted? is true.

  (create-user [this user-entity])
  ;; Generates :id and :created-at. Returns the stored entity.
  ;; Throws on a duplicate email.

  (update-user [this user-entity])
  ;; Takes :id and the keys to change; writes only those, so a partial
  ;; entity leaves the rest as stored. Sets :updated-at.
  ;; Returns the stored entity; throws {:type :user-not-found} when absent.

  (claim-login-attempts [this user-id n lockout])
  ;; lockout {:max-attempts m :until inst :now inst}. Unless the account is
  ;; locked at :now, adds n to :failed-login-count, setting :lockout-until to
  ;; :until when that reaches m, in one write. Callers claim before checking a
  ;; password or code, so parallel attempts cannot all pass a lockout check made
  ;; before any of them counted (BOU-785). Returns {:claimed? true
  ;; :failed-login-count c}, {:claimed? false :lockout-until inst}, or nil when
  ;; absent.

  (release-login-attempt [this user-id unlock?])
  ;; Takes back one claimed attempt, never below zero; with unlock?, clears
  ;; :lockout-until too. For an attempt whose password was right but which is
  ;; not yet a sign-in. Returns nil.

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
  ;;           :filter-created-after :filter-created-before
  ;;           :after-id :count?}
  ;; Returns {:audit-logs [...] :total-count n}. :after-id with :sort-by :id
  ;; pages by key instead of offset; :count? false skips the total.

  (find-audit-logs-by-user [this user-id options])
  ;; Entries where the user is the target. options: {:limit :offset :sort-by
  ;; :sort-direction}. Returns a seq of entries.

  (delete-audit-logs-before [this cutoff]))
  ;; Removes every entry created before `cutoff`, an Instant: the audit-log
  ;; retention policy. Returns the count.

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
  "Groups, their members, managed roles and the roles assigned to groups. A
   group is {:id :name :description :created-at :updated-at}; a managed role
   {:id :name kw :description :permissions #{kw} :created-at :updated-at}; an
   assignment {:id :group-id :role kw :scope map :created-at}. Deleting a
   group or a user removes their memberships and, with a group, its
   assignments."

  (find-group [this group-id])
  ;; Returns the group, or nil.

  (find-groups [this])
  ;; Every group by name, each with :member-count and :role-count.

  (insert-group [this group])
  ;; Generates :id and :created-at. Returns the stored group.

  (modify-group [this group])
  ;; Takes :id and :name, and :description when it is to change. Returns the
  ;; stored group, or nil.

  (remove-group [this group-id])
  ;; Removes the group, its memberships and assignments. Returns boolean.

  (find-members [this group-id])
  ;; The group's users, {:id :email :name}, by email. Soft-deleted users are
  ;; left out.

  (insert-member [this group-id user-id])
  ;; Returns true, or false when the user was a member already.

  (remove-member [this group-id user-id])
  ;; Returns boolean.

  (find-user-groups [this user-id])
  ;; The groups the user was added to, by name. None for a soft-deleted user.

  (find-roles [this])
  ;; Every managed role, by name.

  (insert-role [this role])
  ;; role: {:name :description :permissions}. Returns the stored role. Throws
  ;; :conflict on :name when another role has it.

  (modify-role [this role])
  ;; Sets the :description and :permissions of the role named :name. Returns
  ;; boolean.

  (remove-role [this role-name])
  ;; Removes the managed role and every assignment of it. Returns boolean.

  (find-assignments [this group-id])
  ;; The group's assignments, oldest first.

  (find-role-assignments [this role-name])
  ;; Every assignment of the role, each with :group-name.

  (insert-assignment [this assignment])
  ;; assignment: {:group-id :role :scope}, and :id to keep one. Returns the
  ;; stored assignment.

  (remove-assignment [this group-id assignment-id])
  ;; Returns boolean.

  (find-user-assignments [this user-id group-names])
  ;; The assignments of every group the user was added to, of Everyone, and
  ;; of the groups named in `group-names`, each with :group-name, and with
  ;; :permissions when the role is a managed one. None for a soft-deleted user.

  (find-role-holders [this role-names])
  ;; {:user-id :role :scope} for each active, not soft-deleted user holding
  ;; one of the roles through a stored membership or Everyone.

  (ensure-everyone [this])
  ;; Creates the Everyone group when it is missing, renaming a group already
  ;; called that. Returns true when it created it.

  (find-legacy-grants [this])
  ;; The rows of user_group_grants, which roles replaced: {:id :group-id
  ;; :permission :scope :migrated-at}. Kept until 2.0 for nodes on the
  ;; previous version; rights are read from assignments only.

  (mark-legacy-grant-migrated [this grant-id])
  ;; Sets the grant's :migrated-at. Returns boolean.

  (find-legacy-viewers [this])
  ;; The ids of live users whose stored role is still "viewer".

  (clear-legacy-roles [this])
  ;; Stores no role for every "viewer". Returns the count.

  (any-non-admin-users? [this])
  ;; Whether a user without role admin exists. An admin passes every check,
  ;; so the Everyone migration does not count one.

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
  ;; user-credentials: {:email :password :ip-address :user-agent :mfa-code
  ;;                    :registration?}, the last true just after registering.
  ;; Returns, on success, {:authenticated true :user … :session …
  ;;                       :session-token "…" :jwt-token "…"};
  ;; on a pending second factor, {:authenticated false :requires-mfa? true
  ;;                              :user … :message "…"};
  ;; otherwise {:authenticated false :reason <keyword> :message "…"
  ;;            :retry-after <Instant-or-nil>}. On :mfa-enrolment-required it
  ;; adds the :user and their :password-fingerprint when the password alone may
  ;; set up the factor: on registering, or within :mfa-reset-enrolment-hours
  ;; (72) of an admin reset.
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
  ;; revokes every session (BOU-191) and audits. Returns true. A wrong current
  ;; password counts towards the sign-in lockout.
  ;; Throws :user-not-found, :account-locked (with :retry-after),
  ;; :invalid-current-password, :password-policy-violation.

(defprotocol IUserAccountRecovery
  "What an admin does for a user who cannot sign in. Each change is audited
   with the admin as actor."

  (unlock-user [this user-id])
  ;; Ends a sign-in lockout and clears the failed-attempt count. Returns true,
  ;; or false when there was nothing to clear. Throws :user-not-found.

  (reset-user-mfa [this user-id]))
  ;; Turns off the user's second factor, for one who lost both the device and
  ;; the backup codes; they sign in with the password until they set it up
  ;; again. Returns true, or false when it was off. Throws :user-not-found.

(defprotocol IUserGroupService
  "Groups of users and the roles assigned to them (ADR-041). A user's grants
   are the permissions of the roles of their groups: those they were added
   to, the built-in Everyone, and those the `:group-membership` hook names.
   Joining, leaving, a changed assignment and a changed managed role are
   audited for each user whose rights change; a change to Everyone once, for
   the actor. Nothing is cached: `user-grants` reads the database, so a change
   applies to the next request. The :admin role is separate and needs no
   group. Throws ex-infos carrying :type: :validation-error, :not-found,
   :conflict."

  (list-groups [this])
  ;; Every group by name, with :member-count and :role-count.

  (get-group [this group-id])
  ;; The group with its :members, its :roles (assignments) and :everyone?, or
  ;; nil. Everyone lists no members.

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

  (list-roles [this])
  ;; Code and managed roles, {:name :description :permissions :source
  ;; (:code or :managed) :module}, by name.

  (create-role [this role])
  ;; role: {:name kw :description :permissions #{kw}}, permissions from the
  ;; registry. Returns the stored managed role.

  (update-role [this role])
  ;; Changes a managed role's description and permissions. Returns it.

  (delete-role [this role-name])
  ;; Removes a managed role and its assignments. Returns boolean.

  (add-group-role [this group-id assignment])
  ;; assignment: {:role kw :scope map}, the scope checked against the declared
  ;; `:wagoe/user :groups :scope`. Returns the stored assignment.

  (remove-group-role [this group-id assignment-id])
  ;; Returns boolean.

  (add-group-grant [this group-id grant])
  ;; Deprecated: use add-group-role. Assigns the managed role named after the
  ;; grant's permission, creating it. Returns the assignment.

  (remove-group-grant [this group-id grant-id])
  ;; Deprecated: use remove-group-role.

  (user-grants [this user])
  ;; user: a user map or id. {:permission :scope :group-id :group-name :role}
  ;; for each right the user holds, for wagoe.user.core.group/holds?,
  ;; grants-for and authorized?.

  (user-groups [this user])
  ;; user: a user map or id. The groups the user is in, each with :source:
  ;; :member, :everyone or :provider.

  (permission-holders [this permission])
  ;; {:user-id :permission :scope} for each active user holding `permission`
  ;; through a group they were added to or Everyone; the hook's groups are
  ;; not stored, so not listed. Judge the scope with
  ;; wagoe.user.core.group/users-with-permission.

  (role-holders [this role-names])
  ;; Every assignment of one of the roles, {:role :group-id :group-name :scope
  ;; :everyone? :members}, by group name. Everyone lists no members.

  (provider-members [this group-id])
  ;; The active users the `:group-membership` hook puts in the group,
  ;; {:id :email :name}, by email. Runs the hook for every active user; none
  ;; without a hook.

  (import-groups! [this groups opts]))
  ;; groups: [{:name :description :members [user-id …] :roles [{:role :scope}]}];
  ;; :grants [{:permission :scope}] is read as roles, as add-group-grant does.
  ;; Checks all, then upserts by name in one audited transaction; additive
  ;; unless opts {:replace? true}. Returns per group {:group :created? :joined
  ;; :left :roles-added :roles-removed}.

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

(defprotocol IGroupMembership
  (group-names [this user]))
  ;; The names of the Wagoe groups an identity provider puts `user` in, such
  ;; as Entra or LDAP groups mapped by name. They count as membership and are
  ;; not stored; a name no group has gives nothing.

(defprotocol ILoginLayout
  (login-page [this title body opts]))
  ;; The whole page around Wagoe's sign-in forms: login, MFA, registration.
  ;; opts: :page (:login, :mfa, :oidc-mfa, :oidc-continue, :register,
  ;; :mfa-enrolment), :request,
  ;; :user, :flash, :return-to, :logo-url. Returns Hiccup for the full page.

(defprotocol IProfileLayout
  (profile-page [this title body opts]))
  ;; The whole page around the profile and its MFA pages, under /web/profile.
  ;; opts: :user, :flash, :request. Returns Hiccup for the full page, with
  ;; htmx and the CSRF meta tag the cards' requests need, and
  ;; /css/profile.css or the app's own styles.
