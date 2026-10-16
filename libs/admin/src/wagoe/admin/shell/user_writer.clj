(ns wagoe.admin.shell.user-writer
  "The users entity's writes, through the user service, so an admin edit gets
   its audit entry, session reset and last-admin guard (BOU-665)."
  (:require
   [malli.core :as m]
   [wagoe.admin.ports :as ports]
   [wagoe.core.utils.type-conversion :as type-conversion]
   [wagoe.core.validation :as cv]
   [wagoe.user.ports :as user-ports]
   [wagoe.user.schema :as user-schema]))

(def ^:private decode
  (cv/decoder user-schema/UpdateUserRequest user-schema/user-request-transformer))

(def ^:private updatable
  (set (map first (m/children user-schema/UpdateUserRequest))))

(defn- user-changes
  "`changes` as the user service reads them. An emptied optional field is
   left as it was: the schema has no nil for it."
  [changes]
  (into {} (remove (comp nil? val)) (select-keys (decode changes) updatable)))

(defrecord UserWriter [user-service]
  ports/IEntityWriter
  (update-record! [_ id changes]
    (user-ports/update-user-profile
     user-service (assoc (user-changes changes) :id (type-conversion/string->uuid id))))

  (delete-records! [_ ids {:keys [soft?]}]
    (mapv str (user-ports/delete-users user-service
                                       (keep #(type-conversion/string->uuid (str %)) ids)
                                       {:permanent? (not soft?)}))))

(defn create-user-writer
  "A writer for the users entity, through `user-service`."
  [user-service]
  (->UserWriter user-service))
