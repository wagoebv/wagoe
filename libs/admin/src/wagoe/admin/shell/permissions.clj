(ns wagoe.admin.shell.permissions
  "Permission assertions for admin access control.

   These wrappers throw typed `ex-info` (`:type :forbidden`) when a permission
   check fails, so they live in the shell — the pure decision logic (the
   `can-*?` predicates and `explain-admin-access-denial`) stays in
   `wagoe.admin.core.permissions`. HTTP handlers call these guards; the
   platform error interceptor maps `:forbidden` to a 403 response."
  (:require [wagoe.admin.core.permissions :as core-permissions]))

(defn assert-can-access-admin!
  "Assert user can access admin, throw exception if not.

   Returns true if permission granted.
   Throws ExceptionInfo with :type :forbidden if access denied."
  ([user]
   (assert-can-access-admin! user nil))
  ([user admin-config]
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
   (when-not (core-permissions/can-view-entity? user entity-name entity-config)
     (throw (ex-info (str "User cannot view entity: " (name entity-name))
                     {:type :forbidden
                      :entity entity-name
                      :user-role (:role user)
                      :required-role :admin})))
   true))

(defn assert-can-create-entity!
  "Assert user can create entity, throw exception if not.

   Returns true if permission granted.
   Throws ExceptionInfo with :type :forbidden if access denied."
  ([user entity-name]
   (assert-can-create-entity! user entity-name nil))
  ([user entity-name entity-config]
   (when-not (core-permissions/can-create-entity? user entity-name entity-config)
     (throw (ex-info (str "User cannot create entity: " (name entity-name))
                     {:type :forbidden
                      :entity entity-name
                      :user-role (:role user)
                      :required-role :admin})))
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
   (when-not (core-permissions/can-edit-entity? user entity-name entity-config record)
     (throw (ex-info (str "User cannot edit entity: " (name entity-name))
                     {:type :forbidden
                      :entity entity-name
                      :record-id (:id record)
                      :user-role (:role user)
                      :required-role :admin})))
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
   (when-not (core-permissions/can-delete-entity? user entity-name entity-config record)
     (throw (ex-info (str "User cannot delete entity: " (name entity-name))
                     {:type :forbidden
                      :entity entity-name
                      :record-id (:id record)
                      :user-role (:role user)
                      :required-role :admin})))
   true))
