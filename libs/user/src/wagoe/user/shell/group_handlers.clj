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
            [wagoe.platform.ports.authorization :as authz]
            [wagoe.shared.ui.core.combobox :as combobox]
            [wagoe.shared.ui.core.htmx :as htmx]
            [wagoe.shared.ui.core.table :as table-ui]
            [wagoe.user.core.group :as group-core]
            [wagoe.user.core.group-ui :as group-ui]
            [wagoe.user.core.rights-ui :as rights-ui]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.web-handlers :refer [html-response]]))


(defn page-opts [request]
  {:user  (:user request)
   :flash (:flash request)})

(defn list-response
  "A list page's response from its URL: for its own search and sort (an htmx
   request for the list's container `spec`), `items` in the list alone, else
   `(page table-query)`."
  [request spec items page]
  (let [table-query (table-ui/parse-table-params (:query-params request))]
    ;; One URL, two bodies: a cache must not answer one with the other
    (-> (html-response request
                       (if (htmx/fragment-request? (:headers request) [(:id spec)])
                         (rights-ui/list-fragment spec items table-query)
                         (page table-query)))
        (response/header "Vary" htmx/vary))))

(defn redirect [path flash-key]
  (assoc (response/redirect path :see-other) :flash {:success flash-key}))

(defn- path-uuid [request k]
  (parse-uuid (str (get-in request [:path-params k]))))

(defn form [request k]
  (some-> (get-in request [:form-params k]) str str/trim))

(defn scope-keys
  "The scope keys an assignment may set, as the declared scope map has them,
   each with its registry label, a lookup when it points at an admin entity
   (`[:domain-id {:wagoe/entity :domains} :int]`), and the user sources it takes."
  [group-service]
  (let [schema   (get-in group-service [:settings :scope-schema])
        registry (:registry group-service)
        scopes   (some-> registry authz/scopes)
        sources  (some-> registry authz/scope-sources)]
    (when (and schema (= :map (m/type schema)))
      (vec (for [[k props] (m/children schema)
                 :let  [entity (:wagoe/entity props)]]
             {:key     k
              :label   (get-in scopes [k :label])
              :url     (some-> entity rights-ui/entity-lookup-url)
              :sources (vec (for [src (sort (get-in scopes [k :sources]))]
                              [src (or (get-in sources [src :label]) (group-core/permission-str src))]))})))))

(defn scope-info
  "What `rights-ui/scope-view` reads to label a scope's keys and sources."
  [group-service]
  (let [registry (:registry group-service)
        scopes   (some-> registry authz/scopes)]
    {:labels   (update-vals scopes :label)
     :entities (into {} (keep (fn [[k {:keys [entity]}]] (when entity [k entity]))) scopes)
     :sources  (some-> registry authz/scope-sources (update-vals :label))}))

(defn- error-text
  "What to show for a refused change: the field's message, and the schema's
   explanation when there is one."
  [e]
  (let [{:keys [type field errors]} (ex-data e)]
    (list [:t (or ({:role       :user.groups/error-role
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
          (group-ui/group-page group (ports/list-roles group-service)
                               (merge (page-opts request)
                                      {:scope-keys       (scope-keys group-service)
                                       :scope-info       (scope-info group-service)}
                                      opts))
          status)
    (not-found request)))

(defn- refused?
  [e]
  (let [{:keys [type field]} (ex-data e)]
    (or (#{:validation-error :not-found} type)
        (= [:conflict :name] [type field]))))

(defn list-page [group-service]
  (fn [request]
    (let [groups (ports/list-groups group-service)]
      (list-response request group-ui/groups-list groups
                     #(group-ui/groups-page groups (assoc (page-opts request) :table-query %))))))

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
                                                        :form  (:form-params request)}))
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

(defn provider-members
  "GET /:id/provider-members — the users the membership hook puts in the group."
  [group-service]
  (fn [request]
    (if-let [group-id (some->> (path-uuid request :id) (ports/get-group group-service) :id)]
      (html-response request (group-ui/provider-members (ports/provider-members group-service group-id)) 200)
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
  "The assignment's scope: the EDN typed, with each key's user source or fixed
   value added, a value read as the declared scope schema reads it from a
   string. A source wins over a value typed beside it."
  [group-service request]
  (let [scope (parse-scope (form request "scope"))
        {:keys [sources values]}
        (reduce (fn [acc {:keys [key]}]
                  (if-let [src (group-core/parse-name (form request (name (group-ui/scope-source-field key))))]
                    (assoc-in acc [:sources key] src)
                    (if-let [v (not-empty (form request (name (group-ui/scope-lookup-field key))))]
                      (assoc-in acc [:values key] v)
                      acc)))
                {}
                (scope-keys group-service))]
    ;; `nil` typed is no scope: a key picked beside it is still the scope
    (if (and (or values sources) (or (nil? scope) (map? scope)))
      (merge scope
             (when values (m/decode (get-in group-service [:settings :scope-schema]) values (mt/string-transformer)))
             sources)
      scope)))

(defn add-role [group-service]
  (fn [request]
    (change group-service request :user.groups/flash-role-added
            #(ports/add-group-role group-service %
                                   {:role  (group-core/parse-name (form request "role"))
                                    :scope (form-scope group-service request)}))))

(defn remove-role [group-service]
  (fn [request]
    (change group-service request :user.groups/flash-role-removed
            #(when-let [assignment-id (path-uuid request :assignment-id)]
               (ports/remove-group-role group-service % assignment-id)))))
