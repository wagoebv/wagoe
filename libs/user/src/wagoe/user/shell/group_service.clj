(ns wagoe.user.shell.group-service
  "IUserGroupService: groups, members and grants through one place, so each
   change is checked and audited. Reads go to the database every time."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [malli.core :as m]
            [malli.error :as me]
            [wagoe.config :as config]
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
  (let [v (if (symbol? x) (config/resolve-symbol x) x)]
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

(defn- existing-group [group-repository group-id]
  (or (ports/find-group group-repository group-id)
      (throw (not-found "No such group" {:group-id group-id}))))

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

;; =============================================================================
;; Import
;; =============================================================================

(defn- import-refusal [message data]
  (ex-info message (merge {:type :validation-error} data)))

(defn- import-spec
  "`spec` checked and trimmed: its grants against `settings`, its members
   looked up. Throws :validation-error naming the group."
  [user-repository settings spec]
  (when-let [explanation (m/explain user-schema/GroupImport spec)]
    (throw (import-refusal "Invalid group import" {:group  (:name spec)
                                                   :errors (me/humanize explanation)})))
  (let [data   (group-data spec)
        grants (mapv (fn [g] {:permission (:permission g) :scope (or (:scope g) {})}) (:grants spec))]
    (doseq [grant grants]
      (when-let [{:keys [error]} (group-core/grant-refusal grant settings)]
        (throw (ex-info (:message error) (assoc error :group (:name data))))))
    (assoc data
           :grants  grants
           :members (mapv #(or (ports/find-user-by-id user-repository %)
                               (throw (import-refusal "No such user" {:field :members :group (:name data) :user-id %})))
                          (distinct (:members spec))))))

(defn- import-group!
  "Upsert `spec` through `repo`, auditing each user whose rights change in
   the order the single changes would: leaving, grants removed, grants added,
   joining."
  [repo audit-repository groups-by-name {:keys [name members grants] :as spec} replace?]
  (let [[existing more] (groups-by-name name)
        _        (when more
                   (throw (ex-info "Several groups have this name" {:type :conflict :group name})))
        group    (cond
                   (nil? existing) (ports/insert-group repo (select-keys spec [:name :description]))
                   (and (contains? spec :description) (not= (:description spec) (:description existing)))
                   (ports/modify-group repo (assoc (select-keys spec [:name :description]) :id (:id existing)))
                   :else existing)
        before   (if existing (ports/find-members repo (:id group)) [])
        held     (if existing (ports/find-grants repo (:id group)) [])
        changes  (group-core/import-changes {:member-ids (map :id before) :held held}
                                            {:members (map :id members) :grants grants}
                                            replace?)
        joiners  (filter (comp (set (:join changes)) :id) members)
        leavers  (filter (comp (set (:leave changes)) :id) before)
        stayers  (remove (set leavers) before)]
    (doseq [user leavers] (ports/remove-member repo (:id group) (:id user)))
    (doseq [grant (:remove changes)] (ports/remove-grant repo (:id group) (:id grant)))
    (let [added (mapv #(ports/insert-grant repo (assoc % :group-id (:id group))) (:add changes))]
      (doseq [user joiners] (ports/insert-member repo (:id group) (:id user)))
      ;; Not through audit!, which swallows a failure: inside the transaction
      ;; (the audit repository's queries join it), a failed entry rolls back.
      (doseq [[action users grants] [[:group-leave leavers held]
                                     [:grant-remove stayers (:remove changes)]
                                     [:grant-add stayers added]
                                     [:group-join joiners (ports/find-grants repo (:id group))]]
              :when (or (#{:group-leave :group-join} action) (seq grants))
              entry (audit-core/group-audit-entries action (user-service/effective-audit-context {})
                                                    group users grants)]
        (ports/create-audit-log audit-repository entry))
      {:group          group
       :created?       (nil? existing)
       :joined         (mapv :id joiners)
       :left           (mapv :id leavers)
       :grants-added   added
       :grants-removed (:remove changes)})))

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
            grants  (when (seq members) (ports/find-grants group-repository group-id))
            removed (ports/remove-group group-repository group-id)]
        (when (and removed (seq members))
          (audit! audit-repository :group-leave group members grants))
        removed)
      false))

  (add-group-member [_ group-id user-id]
    (let [group (existing-group group-repository group-id)
          user  (or (ports/find-user-by-id user-repository user-id)
                    (throw (not-found "No such user" {:user-id user-id})))
          added (ports/insert-member group-repository group-id (:id user))]
      (when added
        (audit! audit-repository :group-join group [user] (ports/find-grants group-repository group-id)))
      added))

  (remove-group-member [_ group-id user-id]
    (let [removed (ports/remove-member group-repository group-id user-id)]
      ;; A soft-deleted user holds nothing, so leaving changes nothing to audit.
      (when-let [user (and removed (ports/find-user-by-id user-repository user-id))]
        (audit! audit-repository :group-leave (ports/find-group group-repository group-id) [user]
                (ports/find-grants group-repository group-id)))
      removed))

  (add-group-grant [_ group-id grant]
    (let [group (existing-group group-repository group-id)
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
      []))

  (permission-holders [_ permission]
    (ports/find-permission-holders group-repository permission))

  (import-groups! [_ groups {:keys [replace?]}]
    (ports/ensure-tables group-repository)
    (let [specs (mapv #(import-spec user-repository settings %) groups)]
      (when-let [twice (first (for [[n xs] (group-by :name specs) :when (next xs)] n))]
        (throw (import-refusal "A group is listed twice" {:group twice})))
      (ports/in-transaction group-repository
                            (fn [repo]
                              (let [by-name (group-by :name (ports/find-groups repo))]
                                (mapv #(import-group! repo audit-repository by-name % replace?) specs)))))))

(defn create-group-service
  "A group service. `settings` is what `prepare-settings` returns."
  [group-repository user-repository audit-repository settings]
  (->UserGroupService group-repository user-repository audit-repository settings))
