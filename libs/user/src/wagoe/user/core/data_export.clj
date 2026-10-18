(ns wagoe.user.core.data-export
  "What the user module puts in a user's data export (GDPR Art. 20).")

(def ^:private secret-keys
  "Credentials: not personal data the user provided, and dangerous in a file."
  #{:password-hash :mfa-secret :mfa-backup-codes :mfa-backup-codes-used})

(defn user-export
  "The user module's section of `user`'s export. `audit-entries` are those
   naming the user as target or actor; one naming them as both appears once."
  [{:keys [user sessions identities groups audit-entries]}]
  {:profile    (apply dissoc user secret-keys)
   :sessions   (mapv #(select-keys % [:id :created-at :expires-at :last-accessed-at
                                      :revoked-at :ip-address :user-agent])
                     sessions)
   :identities (mapv #(select-keys % [:provider :issuer :subject :email :created-at :last-login-at])
                     identities)
   :groups     (mapv #(select-keys % [:id :name :description]) groups)
   :audit-log  (->> audit-entries
                    (reduce (fn [acc e] (assoc acc (:id e) e)) {})
                    vals
                    (sort-by :created-at)
                    vec)})
