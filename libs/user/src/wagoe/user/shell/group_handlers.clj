(ns wagoe.user.shell.group-handlers
  "Admin pages for groups, under /web/admin/groups. Every change goes through
   the group service, so it is checked and audited with the signed-in admin as
   actor."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [malli.core :as m]
            [malli.transform :as mt]
            [ring.util.response :as response]
            [wagoe.shared.ui.core.combobox :as combobox]
            [wagoe.user.core.group-ui :as group-ui]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.web-handlers :refer [html-response]]))


(defn- page-opts [request]
  {:user  (:user request)
   :flash (when-let [[kind k] (first (:flash request))]
            (when (keyword? k) {kind [:t k]}))})

(defn- redirect [path flash-key]
  (assoc (response/redirect path :see-other) :flash {:success flash-key}))

(defn- path-uuid [request k]
  (parse-uuid (str (get-in request [:path-params k]))))

(defn- form [request k]
  (some-> (get-in request [:form-params k]) str str/trim))

(defn- permission-options
  "The declared permissions when they are a fixed set, for a select."
  [group-service]
  (let [schema (get-in group-service [:settings :permissions])]
    (when (and schema (= :enum (m/type schema)))
      (m/children schema))))

(defn- scope-lookups
  "The scope keys a grant chooses through a lookup: those the declared scope
   map says point at an admin entity, `[:domain-id {:wagoe/entity :domains} :int]`."
  [group-service]
  (let [schema (get-in group-service [:settings :scope-schema])]
    (when (and schema (= :map (m/type schema)))
      (for [[k props] (m/children schema)
            :let  [entity (:wagoe/entity props)]
            :when entity]
        {:key k :url (str "/web/admin/" (name entity) "/lookup")}))))

(defn- error-text
  "What to show for a refused change: the field's message, and the schema's
   explanation when there is one."
  [e]
  (let [{:keys [type field errors]} (ex-data e)]
    (list [:t (or ({:permission :user.groups/error-permission
                    :scope      :user.groups/error-scope
                    :email      :user.groups/error-no-user} field)
                  ({:not-found :user.groups/not-found} type)
                  (when (= [:conflict :name] [type field]) :user.groups/error-name-taken)
                  :user.groups/error-name)]
          (when errors [:code " " (pr-str errors)]))))

(defn- not-found [request]
  (html-response request [:p [:t :user.groups/not-found]] 404))

(defn- group-page [request group-service group-id status opts]
  (if-let [group (ports/get-group group-service group-id)]
    (html-response request
          (group-ui/group-page group (permission-options group-service)
                               (merge (page-opts request) {:scope-lookups (scope-lookups group-service)} opts))
          status)
    (not-found request)))

(defn- refused?
  [e]
  (let [{:keys [type field]} (ex-data e)]
    (or (#{:validation-error :not-found} type)
        (= [:conflict :name] [type field]))))

(defn list-page [group-service]
  (fn [request]
    (html-response request (group-ui/groups-page (ports/list-groups group-service) (page-opts request)) 200)))

(defn create [group-service]
  (fn [request]
    (let [data {:name (form request "name") :description (form request "description")}]
      (try
        (let [group (ports/create-group group-service data)]
          (redirect (group-ui/group-path (:id group)) :user.groups/flash-created))
        (catch clojure.lang.ExceptionInfo e
          (if (refused? e)
            (html-response request
                  (group-ui/groups-page (ports/list-groups group-service)
                                        (assoc (page-opts request) :error (error-text e) :form data))
                  422)
            (throw e)))))))

(defn show [group-service]
  (fn [request]
    (if-let [id (path-uuid request :id)]
      (group-page request group-service id 200 {})
      (not-found request))))

(defn- change
  "Run `f` on the group named in the path, redirecting back to it with
   `flash-key`, or with no message when `f` answers falsey: nothing changed. A
   refused change re-renders the page saying why."
  [group-service request flash-key f]
  (if-let [id (path-uuid request :id)]
    (try
      (if (f id)
        (redirect (group-ui/group-path id) flash-key)
        (response/redirect (group-ui/group-path id) :see-other))
      (catch clojure.lang.ExceptionInfo e
        (if (refused? e)
          (do (log/info "Group change refused" (select-keys (ex-data e) [:type :field]))
              (group-page request group-service id 422 {:error (error-text e)
                                                        :form  {:scope (form request "scope")}}))
          (throw e))))
    (not-found request)))

(defn update-group [group-service]
  (fn [request]
    (change group-service request :user.groups/flash-saved
            #(ports/update-group group-service {:id          %
                                                :name        (form request "name")
                                                :description (form request "description")}))))

(defn delete-group [group-service]
  (fn [request]
    (if-let [id (path-uuid request :id)]
      (if (ports/delete-group group-service id)
        (redirect group-ui/base-path :user.groups/flash-deleted)
        (not-found request))
      (not-found request))))

(defn add-member [user-service group-service]
  (fn [request]
    (change group-service request :user.groups/flash-member-added
            (fn [id]
              ;; The lookup submits the user's id; a script may still send an email
              (let [user (if-let [user-id (some-> (form request "user-id") parse-uuid)]
                           (ports/get-user-by-id user-service user-id)
                           (some->> (form request "email") not-empty (ports/get-user-by-email user-service)))]
                (if user
                  (ports/add-group-member group-service id (:id user))
                  (throw (ex-info "No such user" {:type :not-found :field :email}))))))))

(defn- user-label [{:keys [name email]}]
  (if (str/blank? name) email (str name " <" email ">")))

(defn member-options
  "GET /:id/members/options?q= — the users whose name or email contains the
   typed text and who are not members yet, for the member lookup."
  [user-service group-service]
  (fn [request]
    (if-let [group-id (some->> (path-uuid request :id) (ports/get-group group-service) :id)]
      (let [q     (combobox/query request)
            users (:users (ports/list-users user-service
                                            (cond-> {:limit                 combobox/limit
                                                     :filter-active         true
                                                     :exclude-group-members group-id
                                                     :sort-by               :email
                                                     :sort-direction        :asc}
                                              q (assoc :filter-name-or-email-contains q))))]
        (html-response request
                       (combobox/options :user-id (map (juxt :id user-label) users)
                                         [:t :user.groups/member-no-matches])
                       200))
      (not-found request))))

(defn remove-member [group-service]
  (fn [request]
    (change group-service request :user.groups/flash-member-removed
            #(when-let [user-id (path-uuid request :user-id)]
               (ports/remove-group-member group-service % user-id)))))

(defn- parse-scope
  "The scope typed into the form, read as EDN; blank is {}."
  [s]
  (if (str/blank? s)
    {}
    (try (edn/read-string s)
         (catch Exception _
           (throw (ex-info "The scope is not EDN" {:type :validation-error :field :scope}))))))

(defn- form-scope
  "The grant's scope: the EDN typed, with each key chosen through a lookup
   added, read as the declared scope schema reads it from a string."
  [group-service request]
  (let [scope  (parse-scope (form request "scope"))
        chosen (into {}
                     (keep (fn [{:keys [key]}]
                             (some->> (form request (name (group-ui/scope-lookup-field key)))
                                      not-empty
                                      (vector key))))
                     (scope-lookups group-service))]
    (if (and (seq chosen) (map? scope))
      (merge scope (m/decode (get-in group-service [:settings :scope-schema]) chosen (mt/string-transformer)))
      scope)))

(defn add-grant [group-service]
  (fn [request]
    (change group-service request :user.groups/flash-grant-added
            #(ports/add-group-grant group-service %
                                    {:permission (some-> (form request "permission")
                                                         (str/replace #"^:" "")
                                                         not-empty
                                                         keyword)
                                     :scope      (form-scope group-service request)}))))

(defn remove-grant [group-service]
  (fn [request]
    (change group-service request :user.groups/flash-grant-removed
            #(when-let [grant-id (path-uuid request :grant-id)]
               (ports/remove-group-grant group-service % grant-id)))))
