(ns wagoe.admin.shell.permissions
  "Permission assertions for admin access control.

   These wrappers throw typed `ex-info` (`:type :forbidden`) when a permission
   check fails, so they live in the shell — the pure decision logic (the
   `can-*?` predicates and `explain-admin-access-denial`) stays in
   `wagoe.admin.core.permissions`. HTTP handlers call these guards; the
   platform error interceptor maps `:forbidden` to a 403 response.

   Under `:wagoe/authorization :mode :report` they also log what `:enforce`
   would refuse, and let it through, once a minute per user and entity."
  (:require [clojure.tools.logging :as log]
            [wagoe.admin.core.permissions :as core-permissions]
            [wagoe.platform.throttle :as throttle]))

(def ^:private throttled (throttle/once-per-interval))

(defn- report!
  "Log, under :report, the refusal :enforce would make of `action` on
   `entity-name`."
  [user entity-name action entity-config]
  (when-let [permission (core-permissions/report-refusal user entity-name action entity-config)]
    (throttled [(:id user) permission]
               #(log/warn "Admin authorization would refuse this under :enforce"
                          {:user-id (:id user) :entity entity-name :permission permission}))))

(defn- refusal
  "ex-data for a refused `action` on `entity-name`: the permission it needed
   under :enforce, else the role."
  [user entity-name action extra]
  (merge {:type :forbidden :entity entity-name}
         (if (= :enforce (core-permissions/mode user))
           {:required-permission (core-permissions/entity-permission entity-name action)}
           {:user-role (:role user) :required-role :admin})
         extra))

(defn assert-can-access-admin!
  "Assert user can access admin, throw exception if not.

   Returns true if permission granted.
   Throws ExceptionInfo with :type :forbidden if access denied."
  ([user]
   (assert-can-access-admin! user nil))
  ([user admin-config]
   (when (core-permissions/report-access-refusal user)
     (throttled [(:id user) ::access]
                #(log/warn "Admin authorization would keep this user out of the admin under :enforce"
                           {:user-id (:id user)})))
   (when-not (core-permissions/can-access-admin? user admin-config)
     (let [explanation (core-permissions/explain-admin-access-denial user)]
       (throw (ex-info (:reason explanation)
                       (assoc explanation :type :forbidden)))))
   true))

(defn assert-can-view-entity!
  "Assert user can view entity, throw exception if not.

   Returns true if permission granted.
   Throws ExceptionInfo with :type :forbidden if access denied."
  ([user entity-name]
   (assert-can-view-entity! user entity-name nil))
  ([user entity-name entity-config]
   (report! user entity-name :read entity-config)
   (when-not (core-permissions/can-view-entity? user entity-name entity-config)
     (throw (ex-info (str "User cannot view entity: " (name entity-name))
                     (refusal user entity-name :read nil))))
   true))

(defn assert-can-create-entity!
  "Assert user can create entity, throw exception if not.

   Returns true if permission granted.
   Throws ExceptionInfo with :type :forbidden if access denied."
  ([user entity-name]
   (assert-can-create-entity! user entity-name nil))
  ([user entity-name entity-config]
   (when (false? (get-in entity-config [:permissions :create]))
     (throw (ex-info (str "Entity cannot be created in the admin: " (name entity-name))
                     {:type   :forbidden
                      :entity entity-name
                      :reason :create-disabled})))
   (report! user entity-name :write entity-config)
   (when-not (core-permissions/can-create-entity? user entity-name entity-config)
     (throw (ex-info (str "User cannot create entity: " (name entity-name))
                     (refusal user entity-name :write nil))))
   true))

(defn assert-can-edit-entity!
  "Assert user can edit entity, throw exception if not.

   Returns true if permission granted.
   Throws ExceptionInfo with :type :forbidden if access denied."
  ([user entity-name]
   (assert-can-edit-entity! user entity-name nil nil))
  ([user entity-name entity-config]
   (assert-can-edit-entity! user entity-name entity-config nil))
  ([user entity-name entity-config record]
   (report! user entity-name :write entity-config)
   (when-not (core-permissions/can-edit-entity? user entity-name entity-config record)
     (throw (ex-info (str "User cannot edit entity: " (name entity-name))
                     (refusal user entity-name :write {:record-id (:id record)}))))
   true))

(defn assert-can-delete-entity!
  "Assert user can delete entity, throw exception if not.

   Returns true if permission granted.
   Throws ExceptionInfo with :type :forbidden if access denied."
  ([user entity-name]
   (assert-can-delete-entity! user entity-name nil nil))
  ([user entity-name entity-config]
   (assert-can-delete-entity! user entity-name entity-config nil))
  ([user entity-name entity-config record]
   (report! user entity-name :delete entity-config)
   (when-not (core-permissions/can-delete-entity? user entity-name entity-config record)
     (throw (ex-info (str "User cannot delete entity: " (name entity-name))
                     (refusal user entity-name :delete {:record-id (:id record)}))))
   true))
