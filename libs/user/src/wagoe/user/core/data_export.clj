(ns wagoe.user.core.data-export
  "What the user module puts in a user's data export (GDPR Art. 20)."
  (:require [wagoe.user.core.user :as user-core]))

(defn- own-part
  "`entry` without what identifies someone other than `user-id`: who else acted
   on them, from where, or whom they acted on and what they changed."
  [user-id entry]
  (cond-> entry
    (not= user-id (:actor-id entry))       (dissoc :actor-id :actor-email :ip-address :user-agent)
    (not= user-id (:target-user-id entry)) (dissoc :target-user-id :target-user-email :changes)))

(defn user-export
  "The user module's section of `user`'s export. `audit-entries` are those
   naming the user as target or actor; one naming them as both appears once,
   and none names anyone else."
  [{:keys [user sessions identities groups audit-entries]}]
  {:profile    (user-core/without-credentials user)
   :sessions   (mapv #(select-keys % [:id :created-at :expires-at :last-accessed-at
                                      :revoked-at :ip-address :user-agent])
                     sessions)
   :identities (mapv #(select-keys % [:provider :issuer :subject :email :created-at :last-login-at])
                     identities)
   :groups     (mapv #(select-keys % [:id :name :description]) groups)
   :audit-log  (->> (into {} (map (juxt :id identity)) audit-entries)
                    vals
                    (sort-by :created-at)
                    (mapv #(own-part (:id user) %)))})
