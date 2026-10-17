(ns wagoe.user.shell.group-service
  "IUserGroupService: groups, members and grants through one place, so each
   change is checked and audited. Reads go to the database every time."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [malli.core :as m]
            [malli.error :as me]
            [wagoe.user.core.audit :as audit-core]
            [wagoe.user.core.group :as group-core]
            [wagoe.user.ports :as ports]
            [wagoe.user.schema :as user-schema]
            [wagoe.user.shell.service :as user-service]))

;; =============================================================================
;; Settings
;; =============================================================================

(defn- config-error [message data]
  (ex-info message (merge {:type :configuration-error} data)))

(defn- schema-of
  "The Malli schema `x` is, or a symbol names. Throws :configuration-error."
  [k x]
  (let [v (if (symbol? x)
            (let [found (try (requiring-resolve x)
                             (catch Exception e
                               (throw (config-error (str ":wagoe/user :groups " k " names " x ", whose namespace does not load")
                                                    {:key k :symbol x :cause (ex-message e)}))))]
              (when-not found
                (throw (config-error (str ":wagoe/user :groups " k " names " x ", which does not resolve")
                                     {:key k :symbol x})))
              @found)
            x)]
    (try (m/schema v)
         (catch Exception e
           (throw (config-error (str ":wagoe/user :groups " k " is not a Malli schema: " (ex-message e))
                                {:key k}))))))

(defn prepare-settings
  "`:wagoe/user :groups` checked and resolved, for the service. Throws
   :configuration-error, so a bad declaration fails boot."
  [groups]
  (when-let [explanation (m/explain user-schema/GroupSettings (or groups {}))]
    (throw (config-error (str "Invalid :wagoe/user :groups: " (pr-str (me/humanize explanation)))
                         {:errors (me/humanize explanation)})))
  {:permissions  (some->> (:permissions groups) (schema-of :permissions))
   :scope-schema (some->> (:scope groups) (schema-of :scope))})

;; =============================================================================
;; Service
;; =============================================================================

(defn- not-found [message data]
  (ex-info message (merge {:type :not-found} data)))

(defn- group-data
  "`group`'s name and description, trimmed and checked."
  [group]
  (let [data (cond-> {:name (str/trim (str (:name group)))}
               (some? (:description group))
               (assoc :description (not-empty (str/trim (str (:description group))))))]
    (when-let [explanation (m/explain user-schema/GroupRequest data)]
      (throw (ex-info "Invalid group" {:type   :validation-error
                                       :errors (me/humanize explanation)})))
    data))

(defn- audit!
  "Record `action` for each of `members`. A failed write warns and does not
   undo the change, as for the user service's other entries."
  [audit-repository action group members grants]
  (doseq [entry (audit-core/group-audit-entries action (user-service/effective-audit-context {})
                                                group members grants)]
    (try (ports/create-audit-log audit-repository entry)
         (catch Exception e
           (log/warn e "Failed to write a group audit entry" {:action action :group-id (:id group)})))))

(defn- user-id-of [user]
  (if (map? user) (:id user) user))

(defrecord UserGroupService [group-repository user-repository audit-repository settings]
  ports/IUserGroupService

  (list-groups [_]
    (ports/find-groups group-repository))

  (get-group [_ group-id]
    (when-let [group (ports/find-group group-repository group-id)]
      (assoc group
             :members (ports/find-members group-repository group-id)
             :grants  (ports/find-grants group-repository group-id))))

  (create-group [_ group]
    (ports/insert-group group-repository (group-data group)))

  (update-group [_ group]
    (or (ports/modify-group group-repository (assoc (group-data group) :id (:id group)))
        (throw (not-found "No such group" {:group-id (:id group)}))))

  (delete-group [_ group-id]
    (if-let [group (ports/find-group group-repository group-id)]
      (let [members (ports/find-members group-repository group-id)
            grants  (ports/find-grants group-repository group-id)
            removed (ports/remove-group group-repository group-id)]
        (when removed
          (audit! audit-repository :group-leave group members grants))
        removed)
      false))

  (add-group-member [_ group-id user-id]
    (let [group (or (ports/find-group group-repository group-id)
                    (throw (not-found "No such group" {:group-id group-id})))
          user  (or (ports/find-user-by-id user-repository user-id)
                    (throw (not-found "No such user" {:user-id user-id})))
          added (ports/insert-member group-repository group-id (:id user))]
      (when added
        (audit! audit-repository :group-join group [user] (ports/find-grants group-repository group-id)))
      added))

  (remove-group-member [_ group-id user-id]
    (let [group   (ports/find-group group-repository group-id)
          user    (ports/find-user-by-id user-repository user-id)
          removed (boolean (and group (ports/remove-member group-repository group-id user-id)))]
      ;; A soft-deleted user holds nothing, so leaving changes nothing to audit.
      (when (and removed user)
        (audit! audit-repository :group-leave group [user] (ports/find-grants group-repository group-id)))
      removed))

  (add-group-grant [_ group-id grant]
    (let [group (or (ports/find-group group-repository group-id)
                    (throw (not-found "No such group" {:group-id group-id})))
          grant {:permission (:permission grant) :scope (or (:scope grant) {})}]
      (when-let [{:keys [error]} (group-core/grant-refusal grant settings)]
        (throw (ex-info (:message error) error)))
      (let [stored (ports/insert-grant group-repository (assoc grant :group-id group-id))]
        (audit! audit-repository :grant-add group (ports/find-members group-repository group-id) [stored])
        stored)))

  (remove-group-grant [_ group-id grant-id]
    (let [group (ports/find-group group-repository group-id)
          grant (when group
                  (some #(when (= (str grant-id) (str (:id %))) %)
                        (ports/find-grants group-repository group-id)))
          removed (boolean (and grant (ports/remove-grant group-repository group-id (:id grant))))]
      (when removed
        (audit! audit-repository :grant-remove group (ports/find-members group-repository group-id) [grant]))
      removed))

  (user-grants [_ user]
    (if-let [id (user-id-of user)]
      (ports/find-user-grants group-repository id)
      []))

  (user-groups [_ user]
    (if-let [id (user-id-of user)]
      (ports/find-user-groups group-repository id)
      [])))

(defn create-group-service
  "A group service. `settings` is what `prepare-settings` returns."
  [group-repository user-repository audit-repository settings]
  (->UserGroupService group-repository user-repository audit-repository settings))
