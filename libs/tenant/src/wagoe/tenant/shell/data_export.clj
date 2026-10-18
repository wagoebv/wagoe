(ns wagoe.tenant.shell.data-export
  "The tenant module's contribution to data export: a tenant's record, members
   and invitations, and the tenants a user belongs to."
  (:require [wagoe.core.utils.type-conversion :as type-conversion]
            [wagoe.platform.ports.privacy :as privacy]
            [wagoe.tenant.core.data-export :as core]
            [wagoe.tenant.ports :as ports]))

(def ^:private page-size 500)

(defn- all-pages
  "Every row `(read {:limit :offset})` returns, a page at a time."
  [read]
  (loop [offset 0 acc []]
    (let [page (read {:limit page-size :offset offset})
          acc  (into acc page)]
      (if (< (count page) page-size)
        acc
        (recur (+ offset page-size) acc)))))

(defrecord TenantDataExporter [tenant-repository membership-repository invite-repository]
  privacy/IDataExporter
  (export-data [_ {:keys [type id]}]
    (when-let [id (if (uuid? id) id (type-conversion/parse-uuid-string (str id)))]
      (case type
        :tenant (when-let [tenant (ports/find-tenant-by-id tenant-repository id)]
                  (core/tenant-export
                   {:tenant      tenant
                    :memberships (all-pages #(ports/find-memberships-by-tenant membership-repository id %))
                    :invites     (all-pages #(ports/find-invites-by-tenant invite-repository id %))}))
        :user   (core/user-export (ports/find-memberships-by-user membership-repository id))
        nil))))
