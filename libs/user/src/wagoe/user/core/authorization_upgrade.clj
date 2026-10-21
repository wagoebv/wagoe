(ns wagoe.user.core.authorization-upgrade
  "What moving an app from `:wagoe/authorization :off` to `:enforce` changes
   for its users, as data and as text for `wagoe upgrade authorization`.

   Under `:off` every signed-in user passes every route, and the admin admits
   role `:admin`. A finding is a user, endpoint and permission where
   `:enforce` decides differently. A user here is {:id :email :role :tenant-id
   :platform-admin? :rights}, `:rights` resolved as the interceptor reads them."
  (:require [clojure.string :as str]
            [wagoe.core.utils.text :as text]
            [wagoe.user.core.group :as group-core]
            [wagoe.platform.core.scope :as scope]))

(defn- access
  "How a holder of `rights` fares on `permission` under :enforce, on a route
   whose data says `route-scope`: :full, :narrowed (held only in a scope the
   route narrows to) or :refused."
  [rights permission route-scope]
  (let [scopes (scope/scopes-for rights permission)]
    (cond
      (scope/everywhere? scopes)       :full
      (or (empty? scopes)
          (nil? route-scope))          :refused
      :else                            :narrowed)))

(defn route-findings
  "Each user, endpoint (`route-report`) and permission :enforce refuses or
   narrows, as {:user :endpoint :permission :access}. `:permission` is nil on
   an endpoint no rule covers. A platform admin passes everything."
  [report users]
  (for [{:keys [rule permissions scope] :as endpoint} report
        :when (#{:permission :module :none} rule)
        user  users
        :when (not (:platform-admin? user))
        [permission result] (if (= :none rule)
                              [[nil :refused]]
                              (for [p permissions] [p (access (:rights user) p scope)]))
        :when (not= :full result)]
    {:user user :endpoint endpoint :permission permission :access result}))

(defn- legacy-admin? [user]
  (= :admin (some-> (:role user) keyword)))

(defn admin-findings
  "Each user and `admin.<entity>/read|write` permission where :enforce differs
   from role `:admin`: lost or narrowed by an admin outside the platform,
   `:gained` by a user who was no admin."
  [admin-permissions users]
  (for [permission admin-permissions
        user       users
        :let [after  (if (:platform-admin? user) :full (access (:rights user) permission :entity))
              result (cond
                       (legacy-admin? user)  (when (not= :full after) after)
                       (not= :refused after) :gained)]
        :when result]
    {:user user :endpoint {:admin permission} :permission permission :access result}))

(defn blocking?
  "Whether `findings` hold a refusal or narrowing, which `enforce` refuses to
   switch over. A gain is reported, not blocking."
  [findings]
  (boolean (some #(#{:refused :narrowed} (:access %)) findings)))

;; =============================================================================
;; Text
;; =============================================================================

(def ^:private shown 5)

(defn- user-label [user] (or (:email user) (str (:id user))))

(defn- some-users
  "Up to `shown` users by label, and how many more."
  [users]
  (let [labels (sort (distinct (map user-label users)))]
    (str (str/join ", " (take shown labels))
         (when (> (count labels) shown) (str " and " (- (count labels) shown) " more")))))

(defn- permission-str [p] (if p (group-core/permission-str p) "(no rule)"))

(defn endpoint-line [{:keys [path method admin]}]
  (if admin
    (str "admin entity " (subs (namespace admin) (count "admin.")))
    (str (text/upper-case (name method)) " " path)))

(defn right-str
  "A permission or role and its scope, `orders/read {:domain-id 1}`."
  [{:keys [permission scope]}]
  (str (permission-str permission) (when (seq scope) (str " " (pr-str scope)))))

(def ^:private verbs
  {:refused  "refused to"
   :narrowed "held only in a scope by"
   :gained   "newly held by"})

(defn format-findings
  "`findings` grouped by permission and outcome, the endpoints and users of
   each, for a terminal."
  [findings]
  (if (empty? findings)
    ["  None: every user keeps the access they had under :off."]
    (for [[[permission outcome] group] (sort-by (fn [[[p o]]] [(permission-str p) (name o)])
                                                (group-by (juxt :permission :access) findings))
          :let [users (distinct (map :user group))]
          line (concat [(str "  " (permission-str permission) " — " (verbs outcome) " "
                             (count users) " user(s): " (some-users users))]
                       (map #(str "      " (endpoint-line %)) (sort-by endpoint-line (distinct (map :endpoint group)))))]
      line)))

(defn format-rights
  "Users grouped by what they hold, a platform admin apart."
  [users]
  (let [groups (group-by #(if (:platform-admin? %) ::admin (set (map right-str (:rights %)))) users)]
    (for [[held group] (sort-by (fn [[held group]] [(not= ::admin held) (- (count group))]) groups)
          line (concat [(str "  " (count group) " user(s): " (some-users group))]
                       (cond
                         (= ::admin held) ["      platform admin: every permission"]
                         (empty? held)    ["      nothing beyond signed-in routes"]
                         :else            (map #(str "      " %) (sort held))))]
      line)))

(defn plan-pending?
  "Whether `plan` (`group-service/migration-plan`) moves anything."
  [{:keys [everyone-roles viewers grants]}]
  (boolean (or everyone-roles (seq viewers) (seq grants))))

(defn format-plan
  "What the first start of this version does to rights, from
   `group-service/migration-plan`; `group-names` {group-id name}."
  [{:keys [everyone-roles viewers readers-roles grants] :as plan} group-names]
  (let [roles #(str/join ", " (map permission-str %))]
    (concat
     (if everyone-roles
       [(str "  Everyone is created and given " (if (seq everyone-roles) (roles everyone-roles) "no roles")
             ". A module whose permission a group grant already holds gets none.")]
       ["  Everyone exists; it keeps its roles."])
     (when (seq viewers)
       [(str "  " (count viewers) " user(s) with role :viewer join Readers, with " (roles readers-roles)
             "; their role is cleared.")])
     (when (seq grants)
       (cons (str "  " (count grants) " group grant(s) become managed roles named after the permission:")
             (for [{:keys [group-id permission scope]} grants]
               (str "      " (get group-names group-id (str group-id)) ": "
                    (right-str {:permission permission :scope scope})))))
     (when-not (plan-pending? plan)
       ["  Nothing left to move."]))))

(defn legacy-roles
  "`users` counted by stored role, admins outside the platform apart.
   `viewers` holds the ids stored as :viewer, which the user map reads as no role."
  [users viewers]
  (let [viewers (set viewers)
        label   #(cond (:platform-admin? %) "admin (platform)"
                       (legacy-admin? %)    "admin (in a tenant)"
                       (viewers (:id %))    "viewer"
                       :else                "user")]
    (for [[role n] (sort (frequencies (map label users)))]
      (str "  " role ": " n))))
