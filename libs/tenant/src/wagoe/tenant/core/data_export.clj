(ns wagoe.tenant.core.data-export
  "What the tenant module puts in a data export (GDPR Art. 20).")

(def ^:private membership-keys
  [:tenant-id :user-id :role :status :invited-at :accepted-at :created-at :updated-at])

(defn tenant-export
  "A tenant's section: the tenant, its members and its invitations. An
   invitation's token hash is a credential and stays out."
  [{:keys [tenant memberships invites]}]
  {:tenant      (select-keys tenant [:id :slug :name :status :settings :created-at :updated-at])
   :memberships (mapv #(select-keys % membership-keys) memberships)
   :invites     (mapv #(select-keys % [:email :role :status :expires-at :accepted-at :revoked-at :created-at])
                      invites)})

(defn user-export
  "A user's section: the tenants they belong to, and in what role. nil when
   they belong to none."
  [memberships]
  (when (seq memberships)
    {:memberships (mapv #(select-keys % membership-keys) memberships)}))
