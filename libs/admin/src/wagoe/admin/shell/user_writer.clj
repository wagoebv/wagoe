(ns wagoe.admin.shell.user-writer
  "The users entity's writes, through the user service, so an admin edit gets
   its audit entry, session reset and last-admin guard (BOU-665)."
  (:require
   [wagoe.admin.ports :as ports]
   [wagoe.user.ports :as user-ports]))

(def ^:private editable
  "What the user service lets an admin change. Others, such as login_count,
   are the service's own to write."
  #{:email :name :role :active :avatar-url :theme :language :timezone
    :date-format :time-format :notifications-email :notifications-push :notifications-sms})

(def ^:private keyword-fields
  "Enums the admin form sends as strings and the user schema holds as keywords."
  #{:role :theme :date-format :time-format})

(defn- user-changes
  "`changes` as the user schema reads them. An emptied optional field is
   left as it was: the schema has no nil for it."
  [changes]
  (into {}
        (keep (fn [[k v]]
                (when (and (editable k) (some? v))
                  [k (if (and (keyword-fields k) (string? v)) (keyword v) v)])))
        changes))

(defn- user-id [id]
  (if (string? id) (parse-uuid id) id))

(defn- audit-context [actor]
  {:actor-id    (:id actor)
   :actor-email (:email actor)
   :ip-address  (:ip-address actor)
   :user-agent  (:user-agent actor)})

(defrecord UserWriter [user-service with-audit-context]
  ports/IEntityWriter
  (update-record! [_ actor id changes]
    (let [user (or (user-ports/get-user-by-id user-service (user-id id))
                   (throw (ex-info "User not found" {:type :not-found :id id})))]
      (with-audit-context
        (audit-context actor)
        ;; Only what the schema requires, plus the change: writing back the
        ;; rest of a cached user could undo a login or MFA change since.
        #(user-ports/update-user-profile
          user-service
          (merge (select-keys user [:id :email :name :role :active :created-at])
                 (user-changes changes))))))

  (delete-record! [_ actor id {:keys [soft?]}]
    (with-audit-context
      (audit-context actor)
      #(if soft?
         (user-ports/deactivate-user user-service (user-id id))
         (user-ports/permanently-delete-user user-service (user-id id))))))

(defn create-user-writer
  "A writer for the users entity. `with-audit-context` is the user service's
   (fn [audit-context f]); the composition root passes it in, since this
   module may not require the user module's shell."
  [user-service with-audit-context]
  (->UserWriter user-service with-audit-context))
