(ns wagoe.tenant.ports)

(defprotocol ITenantRepository
  (find-tenant-by-id [this tenant-id])

  (find-tenant-by-slug [this slug])

  (find-all-tenants [this options])

  (create-tenant [this tenant-entity])

  (update-tenant [this tenant-entity])

  (delete-tenant [this tenant-id])

  (tenant-slug-exists? [this slug])

  (create-tenant-schema [this schema-name])

  (drop-tenant-schema [this schema-name]))

(defprotocol ITenantService
  (get-tenant [this tenant-id])

  (get-tenant-by-slug [this slug])

  (list-tenants [this options])

  (create-new-tenant [this tenant-input])

  (update-existing-tenant [this tenant-id update-data])

  (delete-existing-tenant [this tenant-id])

  (suspend-tenant [this tenant-id])

  (activate-tenant [this tenant-id]))

(defprotocol ITenantMembershipRepository
  (find-membership-by-id              [this membership-id])
  (find-membership-by-user-and-tenant [this user-id tenant-id])
  (find-memberships-by-tenant         [this tenant-id options])
  (find-memberships-by-user           [this user-id])
  (create-membership                  [this membership-entity])
  (update-membership                  [this membership-entity])
  (membership-exists?                 [this user-id tenant-id]))

(defprotocol ITenantMembershipService
  (invite-user           [this tenant-id user-id role])
  (bootstrap-open?       [this tenant-id])
  (bootstrap-first-member [this tenant-id user-id role])
  (accept-invitation     [this membership-id])
  (update-member-role    [this membership-id role])
  (suspend-member        [this membership-id])
  (revoke-member         [this membership-id])
  (get-membership        [this membership-id])
  (get-active-membership [this user-id tenant-id])
  (list-tenant-members   [this tenant-id options]))

(defprotocol ITenantInviteRepository
  (find-invite-by-id [this invite-id])
  (find-pending-invite-by-token-hash [this token-hash])
  (find-pending-invite-by-email-and-tenant [this tenant-id email])
  (find-invites-by-tenant [this tenant-id options])
  (create-invite [this invite-entity])
  (update-invite [this invite-entity]))

(defprotocol ITenantInviteService
  (invite-external-member [this tenant-id email role options])
  (get-external-invite [this invite-id])
  (get-external-invite-by-token [this token])
  (accept-external-invite [this token accepted-by-user-id])
  (revoke-external-invite [this invite-id])
  (list-tenant-invites [this tenant-id options]))

(defprotocol ITenantInviteAcceptanceService
  (load-external-invite-for-acceptance [this request]
    "Resolve a pending invite for an acceptance flow.

     Request shape:
       {:token raw-token
        :tx-context optional-db-tx}

     Returns:
       Pending invite entity

     Throws:
       - :not-found when no pending invite exists for the token
       - :validation-error when the invite has expired")
  (accept-external-invite! [this request]
    "Atomically accept a pending external invite.

     Request shape:
       {:token raw-token
        :accepted-by-user-id uuid
        :tx-context optional-db-tx
        :hooks {:after-accept-tx (fn [{:keys [invite membership tx-context]}] ...)}}

     Returns:
       {:invite updated-invite
        :membership created-membership
        :effects {:after-accept-tx hook-result}}"))

(defprotocol ITenantSchemaProvider
  "Protocol for executing code within a tenant's database schema context.
   
   This protocol abstracts tenant schema switching operations, allowing
   different implementations for different database types (PostgreSQL vs others)."

  (with-tenant-schema [this db-ctx schema-name f]
    "Execute function f within the specified tenant schema context.

     For PostgreSQL: Sets search_path to tenant schema for the duration of f
     For other databases: May use row-level filtering or other mechanisms

     Args:
       db-ctx: Database context map with :datasource
       schema-name: Tenant schema name (e.g., 'tenant_acme_corp')
       f: Function to execute, receives transaction context as argument

     Returns:
       Result of executing f

     Throws:
       ex-info with :type :unsupported-database if database doesn't support schemas
       ex-info with :type :schema-not-found if schema doesn't exist")

  (tenant-provisioned? [this db-ctx tenant-entity]
    "Check if a tenant's database schema has been provisioned.
     Returns true if the schema exists in PostgreSQL, false otherwise.
     Returns false for non-PostgreSQL databases.")

  (list-tenant-schemas [this db-ctx]
    "List all tenant_* schemas in the database.
     Returns a vector of schema name strings.
     Returns empty vector for non-PostgreSQL databases."))
