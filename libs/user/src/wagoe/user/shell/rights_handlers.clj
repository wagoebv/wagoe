(ns wagoe.user.shell.rights-handlers
  "Admin pages for roles, permissions and a user's rights (ADR-041 §7). Changes
   go through the group service, so they are checked and audited."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [wagoe.platform.core.http.access :as access]
            [wagoe.platform.core.scope :as scope]
            [wagoe.platform.ports.authorization :as authz]
            [wagoe.user.core.access-ui :as access-ui]
            [wagoe.user.core.audit :as audit]
            [wagoe.user.core.grant-ui :as grant-ui]
            [wagoe.user.core.group :as group-core]
            [wagoe.user.core.hygiene :as hygiene]
            [wagoe.user.core.rights-ui :as rights-ui]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.group-handlers :as group-handlers]
            [wagoe.user.shell.web-handlers :refer [html-response]]))

(def ^:private page-opts group-handlers/page-opts)
(def ^:private form group-handlers/form)
(def ^:private redirect group-handlers/redirect)

(defn- form-permissions
  "The permissions ticked in the form: one value arrives as a string, several as a vector."
  [request]
  (let [v (get-in request [:form-params "permissions"])]
    (into #{} (keep group-core/parse-name) (if (sequential? v) v (some-> v vector)))))

(defn- registry-permissions [group-service]
  (some-> (:registry group-service) authz/permissions))

(defn- not-found [request message-key]
  (html-response request [:p [:t message-key]] 404))

(defn- find-role [group-service role-name]
  (when role-name
    (some #(when (= role-name (:name %)) %) (ports/list-roles group-service))))

(defn- refused? [e]
  (#{:validation-error :conflict :not-found} (:type (ex-data e))))

(defn- error-text [e]
  (let [{:keys [type field errors]} (ex-data e)]
    (list [:t (cond
                (= :conflict type)      :user.roles/error-name-taken
                (= :not-found type)     :user.roles/not-found
                (= :name field)         :user.roles/error-name-code
                (= :permissions field)  :user.roles/error-permissions
                :else                   :user.roles/error-invalid)]
          (when (and (= :permissions field) errors)
            [:code " " (str/join ", " (map group-core/permission-str errors))]))))

;; =============================================================================
;; Roles
;; =============================================================================

(defn- signalled-roles
  "Every role with its hygiene signals (BOU-855)."
  [group-service]
  (hygiene/role-signals (ports/list-roles group-service) (ports/all-assignments group-service)))

(defn- roles-page [request group-service status opts]
  (html-response request
                 (rights-ui/roles-page (signalled-roles group-service) (registry-permissions group-service)
                                       (merge (page-opts request) opts))
                 status))

(defn list-roles [group-service]
  (fn [request]
    (let [roles (signalled-roles group-service)]
      (group-handlers/list-response
       request rights-ui/roles-list roles
       #(rights-ui/roles-page roles (registry-permissions group-service)
                              (assoc (page-opts request) :table-query %))))))

(defn create-role [group-service]
  (fn [request]
    (let [data {:name        (form request "name")
                :description (not-empty (form request "description"))
                :permissions (form-permissions request)}]
      (try
        (let [role (ports/create-role group-service data)]
          (redirect (rights-ui/role-path (:name role)) :user.roles/flash-created))
        (catch clojure.lang.ExceptionInfo e
          (if (refused? e)
            (roles-page request group-service 422 {:error (error-text e) :form data})
            (throw e)))))))

(defn- role-page [request group-service role status opts]
  (html-response request
                 (rights-ui/role-page role (registry-permissions group-service)
                                      (ports/role-holders group-service [(:name role)])
                                      (merge (page-opts request)
                                             {:scope-info (group-handlers/scope-info group-service)}
                                             opts))
                 status))

(defn show-role [group-service]
  (fn [request]
    (if-let [role (find-role group-service (group-core/parse-name (get-in request [:query-params "name"])))]
      (role-page request group-service role 200 {})
      (not-found request :user.roles/not-found))))

(defn- role-change
  "Run `f` on the role named in the form; a refused change re-renders its page
   saying why."
  [group-service request f]
  (if-let [role (find-role group-service (group-core/parse-name (form request "name")))]
    (try
      (f role)
      (catch clojure.lang.ExceptionInfo e
        (if (refused? e)
          (do (log/info "Role change refused" (select-keys (ex-data e) [:type :field]))
              (role-page request group-service role 422
                         {:error (error-text e)
                          :form  {:new-name    (form request "new-name")
                                  :description (form request "description")
                                  :permissions (form-permissions request)}}))
          (throw e))))
    (not-found request :user.roles/not-found)))

(defn save-role [group-service]
  (fn [request]
    (role-change group-service request
                 (fn [role]
                   (let [data {:name        (:name role)
                               :description (not-empty (form request "description"))
                               :permissions (form-permissions request)}]
                     (if (group-handlers/confirmed? request)
                       (do (ports/update-role group-service data)
                           (redirect (rights-ui/role-path (:name role)) :user.roles/flash-saved))
                       (group-handlers/preview-response request group-service (assoc data :op :update-role :role (:name role))
                                                        (rights-ui/role-path (:name role)))))))))

(defn delete-role [group-service]
  (fn [request]
    (role-change group-service request
                 (fn [role]
                   (cond
                     (not (group-handlers/confirmed? request))
                     (group-handlers/preview-response request group-service {:op :delete-role :role (:name role)}
                                                      (rights-ui/role-path (:name role)))

                     (ports/delete-role group-service (:name role))
                     (redirect rights-ui/roles-path :user.roles/flash-deleted)

                     :else (not-found request :user.roles/not-found))))))

(defn duplicate-role
  "A managed role holding the permissions of the role named in the form, which
   is then adapted on its own page."
  [group-service]
  (fn [request]
    (role-change group-service request
                 (fn [role]
                   (let [copy (ports/create-role group-service
                                                 {:name        (form request "new-name")
                                                  :description (:description role)
                                                  :permissions (:permissions role)})]
                     (redirect (rights-ui/role-path (:name copy)) :user.roles/flash-duplicated))))))

;; =============================================================================
;; Permissions
;; =============================================================================

(defn list-permissions [group-service]
  (fn [request]
    (let [items (rights-ui/permission-items (registry-permissions group-service) (ports/list-roles group-service)
                                            (ports/all-assignments group-service))]
      (group-handlers/list-response
       request rights-ui/permissions-list items
       #(rights-ui/permissions-page items (assoc (page-opts request) :table-query %))))))

(defn show-permission [group-service]
  (fn [request]
    (let [permission (group-core/parse-name (get-in request [:query-params "name"]))
          registry   (registry-permissions group-service)
          roles      (when permission
                       (filterv #(contains? (:permissions %) permission) (ports/list-roles group-service)))]
      (if (and permission (or (contains? registry permission) (seq roles)))
        (html-response request
                       (rights-ui/permission-page permission (get registry permission) roles
                                                  (ports/role-holders group-service (map :name roles))
                                                  (assoc (page-opts request)
                                                         :scope-info (group-handlers/scope-info group-service)))
                       200)
        (not-found request :user.permissions/not-found)))))

;; =============================================================================
;; A user's rights
;; =============================================================================

(defn- resolve-sources
  "Each user source `grants` take a scope value from, resolved for `user` and
   expanded by its key, as enforcement reads it: {[key source] values},
   :error when that throws, absent when nothing registers the source."
  [group-service user grants]
  (let [registry  (:registry group-service)
        resolvers (some-> registry authz/scope-sources)
        keys-info (some-> registry authz/scopes)
        ;; A source under several keys is resolved once
        values-of (memoize (fn [src] (into #{} (remove nil?) ((get-in resolvers [src :resolve]) user))))]
    (into {}
          (for [[k src] (distinct (filter (comp scope/source? val) (mapcat :scope grants)))
                :when (get-in resolvers [src :resolve])]
            [[k src] (try (scope/scope-values keys-info values-of k src)
                          (catch Exception e
                            (log/warn e "A scope source failed to resolve for the rights page" {:key k :source src})
                            :error))]))))

(def ^:private timeline-size 50)

(defn- rights-changes
  "The newest audit entries of changes to the user's rights, one query per
   action: the log filters on one. Everyone's since the user was created,
   filed under the group, count too (BOU-862)."
  [user-service {:keys [id created-at]}]
  (let [entries (fn [target action & [after]]
                  (:audit-logs (ports/list-audit-logs user-service
                                                      (cond-> {:filter-target-user-id target :filter-action action
                                                               :sort-by :created-at :sort-direction :desc
                                                               :limit timeline-size :count? false}
                                                        after (assoc :filter-created-after after)))))]
    (audit/rights-timeline
     (concat (mapcat #(entries id %) audit/rights-actions)
             (mapcat #(entries group-core/everyone-id % created-at) [:grant-add :grant-remove]))
     timeline-size)))

(defn- user-of [user-service request]
  (some->> (get-in request [:path-params :id]) str parse-uuid (ports/get-user-by-id user-service)))

(defn user-rights
  "GET /web/users/:id/rights — the user's groups, effective permissions and
   the changes to them."
  [user-service group-service]
  (fn [request]
    (if-let [user (user-of user-service request)]
      ;; Groups and grants both ask the membership hook: ask it once
      (let [group-service (cond-> group-service (:membership group-service) (update :membership memoize))
            grants        (ports/user-grants group-service user)]
        (html-response request
                       (rights-ui/rights-page user (ports/user-groups group-service user) grants
                                              (assoc (page-opts request)
                                                     :registry (registry-permissions group-service)
                                                     :roles (rights-ui/role-index (ports/list-roles group-service))
                                                     :platform-admin? (access/platform-admin? user)
                                                     :changes (rights-changes user-service user)
                                                     :scope-info (assoc (group-handlers/scope-info group-service)
                                                                        :resolved (resolve-sources group-service user grants))))
                       200))
      (not-found request :user.rights/user-not-found))))

(defn- asked
  "The permission and record `params` ask: `permission`, and a `scope.<key>`
   parameter per scope key the registry declares, or `everywhere`, as a route
   without a scope asks."
  [params scope-keys]
  {:permission (group-core/parse-name (get params "permission"))
   :record     (if (= "true" (get params "everywhere"))
                 :everywhere
                 (into {} (for [k scope-keys
                                :let [v (some-> (get params (str "scope." (group-core/permission-str k))) str str/trim)]
                                :when (seq v)]
                            [k v])))})

(defn explain-access
  "GET /web/users/:id/explain — why the user may or may not use a permission,
   on a record when its scope keys are given (BOU-851)."
  [user-service group-service]
  (fn [request]
    (if-let [user (user-of user-service request)]
      (let [scope-info (group-handlers/scope-info group-service)
            {:keys [permission record] :as q} (asked (:query-params request) (keys (:labels scope-info)))]
        (html-response request
                       (rights-ui/explain-page user
                                               (when permission
                                                 (ports/explain-access group-service user permission record))
                                               (assoc (page-opts request)
                                                      :asked q
                                                      :registry (registry-permissions group-service)
                                                      :scope-info scope-info))
                       200))
      (not-found request :user.rights/user-not-found))))

;; =============================================================================
;; Access overview (BOU-854)
;; =============================================================================

(defn- superusers
  "The platform admins, who pass every check."
  [user-service]
  ;; Paged: tenant admins share the role and would push platform admins past one page
  (->> (iterate #(+ % 100) 0)
       (map #(:users (ports/list-users user-service {:filter-role :admin :filter-active true
                                                     :sort-by :email :sort-direction :asc
                                                     :limit 100 :offset %})))
       (take-while seq)
       (apply concat)
       (filter access/platform-admin?)))

(defn access-overview
  "GET /web/admin/access — groups by modules; `module` and `group` filter it,
   and a filter's htmx request gets the matrix alone."
  [user-service group-service]
  (fn [request]
    (let [registry   (registry-permissions group-service)
          roles      (rights-ui/role-index (ports/list-roles group-service))
          params     (:query-params request)
          filters    {:module (not-empty (get params "module")) :group (not-empty (get params "group"))}
          ov         (access-ui/overview (ports/list-groups group-service) (ports/all-assignments group-service)
                                         roles registry filters)
          scope-info (group-handlers/scope-info group-service)]
      (group-handlers/fragment-response
       request access-ui/matrix-id
       #(access-ui/matrix ov roles registry scope-info)
       #(access-ui/page ov (superusers user-service)
                        (assoc (page-opts request) :filters filters :roles roles
                               :registry registry :scope-info scope-info))))))

;; =============================================================================
;; Grant wizard (BOU-853)
;; =============================================================================

(defn- asked-scope
  "The permission and scope `params` ask, the scope's values read as the
   declared scope schema reads a form's: {} is everywhere."
  [group-service params]
  (let [{:keys [permission record]} (asked params (keys (:labels (group-handlers/scope-info group-service))))]
    {:permission permission
     :record     (if (and (map? record) (seq record)) (group-handlers/decode-scope group-service record) {})}))

(defn- wizard-opts [request group-service q opts]
  (merge (page-opts request)
         {:asked      q
          :registry   (registry-permissions group-service)
          :scope-info (group-handlers/scope-info group-service)}
         opts))

(defn- grant-response
  "The wizard for the user in the path, asking what `params` ask; a refused
   question shows why."
  [request user-service group-service params status opts]
  (if-let [user (user-of user-service request)]
    (let [{:keys [permission record] :as q} (asked-scope group-service params)
          [result error] (try [(when permission (ports/grant-options group-service user permission record))]
                              (catch clojure.lang.ExceptionInfo e
                                (if (group-handlers/refused? e) [nil (group-handlers/error-text e)] (throw e))))]
      (html-response request
                     (grant-ui/grant-page user result (wizard-opts request group-service q
                                                                   (cond-> opts error (assoc :error error))))
                     (if error 422 status)))
    (not-found request :user.rights/user-not-found)))

(defn grant-wizard
  "GET /web/users/:id/grant — the ways to give the user a permission, and
   nothing more, through groups."
  [user-service group-service]
  (fn [request]
    (grant-response request user-service group-service (:query-params request) 200 {})))

(defn grant-role
  "POST /web/users/:id/grant/role — a managed role holding only the permission
   asked; then back to the wizard, which offers to assign it."
  [user-service group-service]
  (fn [request]
    (let [{:keys [permission record]} (asked-scope group-service (:form-params request))]
      (if-let [user (user-of user-service request)]
        (try
          (ports/create-role group-service {:name (form request "name") :permissions #{permission}})
          (redirect (rights-ui/grant-path (:id user) permission record) :user.grant/flash-role-created)
          (catch clojure.lang.ExceptionInfo e
            (if (refused? e)
              (grant-response request user-service group-service (:form-params request) 422 {:error (error-text e)})
              (throw e))))
        (not-found request :user.rights/user-not-found)))))

(defn grant-group
  "POST /web/users/:id/grant/group — the last option: a group holding only
   the user and the assignment, named and with its purpose said."
  [user-service group-service]
  (fn [request]
    (let [{:keys [record]} (asked-scope group-service (:form-params request))
          user        (user-of user-service request)
          assignment  {:role (group-core/parse-name (form request "role")) :scope record}
          description (not-empty (form request "description"))
          retry       #(grant-response request user-service group-service (:form-params request) 422 {:error %})]
      (cond
        (nil? user)        (not-found request :user.rights/user-not-found)
        ;; Unconfirmed, as on the group pages (BOU-852): the wizard shows its preview
        (not (group-handlers/confirmed? request))
        (grant-response request user-service group-service (:form-params request) 200 {})
        (nil? description) (retry [:t :user.grant/error-purpose])
        :else
        (try
          ;; Refuse the assignment and the expiry before the group exists
          (let [expires-at (group-handlers/form-expiry request)
                _ (ports/preview-change group-service {:op :new-group :user-id (:id user) :assignment assignment
                                                       :expires-at expires-at})
                g (ports/create-group group-service {:name (form request "name") :description description})]
            (ports/add-group-member-until group-service (:id g) (:id user) expires-at)
            (ports/add-group-role group-service (:id g) assignment)
            (redirect (rights-ui/group-path (:id g)) :user.grant/flash-group-created))
          (catch clojure.lang.ExceptionInfo e
            (if (group-handlers/refused? e) (retry (group-handlers/error-text e)) (throw e))))))))

(defn- named-users
  "The users `text` names, ids or emails separated by space, comma or line,
   or pasted as a log's `:user-id #uuid \"..\"`, at most
   `group-core/preview-page-size`, and what named nobody."
  [user-service text]
  (let [found (for [s (take group-core/preview-page-size (distinct (remove str/blank? (remove #{"#uuid" ":user-id"} (str/split (str text) #"[\s,;\"{}]+")))))]
                [s (if-let [id (parse-uuid s)]
                     (ports/get-user-by-id user-service id)
                     (ports/get-user-by-email user-service s))])]
    [(distinct (keep second found)) (keep (fn [[s u]] (when-not u s)) found)]))

(defn grant-bulk
  "GET /web/admin/permissions/grant — of the users pasted, those the
   permission asked refuses, each with its smallest fix. Under `:report`
   a refusal is only logged, so the users come from the log."
  [user-service group-service]
  (fn [request]
    (let [params (:query-params request)
          {:keys [permission record] :as q} (asked-scope group-service params)
          [users unknown] (if permission (named-users user-service (get params "users")) [[] []])
          [rows error] (try [(mapv (fn [u] {:user u :result (ports/grant-options group-service u permission record)}) users)]
                            (catch clojure.lang.ExceptionInfo e
                              (if (group-handlers/refused? e) [[] (group-handlers/error-text e)] (throw e))))]
      (html-response request
                     (grant-ui/bulk-page rows (if error [] unknown)
                                         (wizard-opts request group-service q (cond-> {:users (get params "users")}
                                                                                error (assoc :error error))))
                     (if error 422 200)))))
