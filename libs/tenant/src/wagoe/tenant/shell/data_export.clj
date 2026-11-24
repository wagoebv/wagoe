(ns wagoe.tenant.shell.data-export
  "The tenant module's contribution to data export: a tenant's record, members
   and invitations, and the tenants a user belongs to."
  (:require [wagoe.core.utils.type-conversion :as type-conversion]
            [wagoe.platform.ports.privacy :as privacy]
            [wagoe.tenant.core.data-export :as core]
            [wagoe.tenant.ports :as ports]
            [wagoe.tenant.shell.membership-service :as membership-service]))

(defrecord TenantDataExporter [tenant-repository membership-repository invite-repository]
  privacy/IDataExporter
  (export-data [_ {:keys [type id]}]
    (when-let [id (type-conversion/string->uuid (str id))]
      (case type
        :tenant (when-let [tenant (ports/find-tenant-by-id tenant-repository id)]
                  (core/tenant-export
                   {:tenant      tenant
                    :memberships (membership-service/read-memberships membership-repository id {})
                    :invites     (membership-service/all-pages #(ports/find-invites-by-tenant invite-repository id %))}))
        :user   (core/user-export (ports/find-memberships-by-user membership-repository id))
        nil))))
