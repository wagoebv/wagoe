(ns wagoe.user.shell.rights-handlers
  "Admin pages for roles, permissions and a user's rights (ADR-041 §7). Changes
   go through the group service, so they are checked and audited."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [wagoe.platform.core.http.access :as access]
            [wagoe.platform.core.scope :as scope]
            [wagoe.platform.ports.authorization :as authz]
            [wagoe.user.core.group :as group-core]
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

(defn- roles-page [request group-service status opts]
  (html-response request
                 (rights-ui/roles-page (ports/list-roles group-service) (registry-permissions group-service)
                                       (merge (page-opts request) opts))
                 status))

(defn list-roles [group-service]
  (fn [request]
    (roles-page request group-service 200 {})))

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
                   (ports/update-role group-service {:name        (:name role)
                                                     :description (not-empty (form request "description"))
                                                     :permissions (form-permissions request)})
                   (redirect (rights-ui/role-path (:name role)) :user.roles/flash-saved)))))

(defn delete-role [group-service]
  (fn [request]
    (role-change group-service request
                 (fn [role]
                   (if (ports/delete-role group-service (:name role))
                     (redirect rights-ui/roles-path :user.roles/flash-deleted)
                     (not-found request :user.roles/not-found))))))

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
    (html-response request
                   (rights-ui/permissions-page (registry-permissions group-service)
                                               (ports/list-roles group-service)
                                               (page-opts request))
                   200)))

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
        keys-info (some-> registry authz/scopes)]
    (into {}
          (for [[k src] (distinct (filter (comp scope/source? val) (mapcat :scope grants)))
                :let  [resolver (get-in resolvers [src :resolve])]
                :when resolver]
            [[k src] (try (scope/scope-values keys-info (fn [_] (into #{} (remove nil?) (resolver user))) k src)
                          (catch Exception e
                            (log/warn e "A scope source failed to resolve for the rights page" {:key k :source src})
                            :error))]))))

(defn user-rights
  "GET /web/users/:id/rights — the user's groups and effective permissions."
  [user-service group-service]
  (fn [request]
    (if-let [user (some->> (get-in request [:path-params :id]) str parse-uuid (ports/get-user-by-id user-service))]
      ;; Groups and grants both ask the membership hook: ask it once
      (let [group-service (cond-> group-service (:membership group-service) (update :membership memoize))
            grants        (ports/user-grants group-service user)]
        (html-response request
                       (rights-ui/rights-page user (ports/user-groups group-service user) grants
                                              (assoc (page-opts request)
                                                     :platform-admin? (access/platform-admin? user)
                                                     :scope-info (assoc (group-handlers/scope-info group-service)
                                                                        :resolved (resolve-sources group-service user grants))))
                       200))
      (not-found request :user.rights/user-not-found))))
