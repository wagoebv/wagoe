(ns wagoe.user.core.group
  "The rights a user holds through their groups, and whether a role or an
   assignment may be made (ADR-041 §2–3).

   An assignment is a group, a role and a scope. A user's grants are the
   permissions of the roles assigned to their groups, each
   {:permission kw :scope map} plus where it came from (:group-id :group-name
   :role). `holds?` and `grants-for` answer for the permission, and the
   application judges the scope."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [wagoe.core.utils.text :as text]
            [malli.error :as me]))

(def everyone-id
  "The built-in group every signed-in user is in without being added."
  #uuid "00000000-0000-0000-0000-000000000001")

(def everyone-name "Everyone")

(defn parse-name
  "A permission or role name as typed, `invoice/approve` or `:invoice/approve`."
  [s]
  (some-> s str str/trim (str/replace #"^:" "") not-empty keyword))

(defn everyone? [group-id]
  (= (str everyone-id) (str group-id)))

(defn permission-str
  "`permission` as stored and shown: its namespace kept, no colon."
  [permission]
  (subs (str permission) 1))

(defn scope-key-label
  "What a scope key declared in `:groups :scope` is called: its schema's
   `:title`, else its name without `-id`, `:domain-id` as \"Domain\"."
  [k props]
  (or (:title props)
      (text/capitalize (str/replace (str/replace (name k) #"-id$" "") "-" " "))))

(defn holds?
  "Whether `grants` hold `permission`, in any scope."
  [grants permission]
  (boolean (some #(= permission (:permission %)) grants)))

(defn grants-for
  "The distinct scopes in which `grants` hold `permission`."
  [grants permission]
  (into [] (comp (filter #(= permission (:permission %))) (map :scope) (distinct)) grants))

(defn authorized?
  "Whether `grants` hold `permission` in a scope `scope-pred` accepts."
  [grants permission scope-pred]
  (boolean (some scope-pred (grants-for grants permission))))

(def default-permissions
  "What a grant may name when the application declares nothing."
  :keyword)

(def default-scope
  "What a scope may be when the application declares nothing."
  [:map-of :keyword any?])

(defn edn-value?
  "Whether `x` is plain EDN data: written out, it reads back as it was."
  [x]
  (cond
    (or (nil? x) (boolean? x) (string? x)
        (integer? x) (double? x) (decimal? x) (uuid? x)
        ;; Not an Instant: it prints as #object.
        (instance? java.util.Date x)) true
    ;; (keyword "a b") prints as two forms.
    (or (keyword? x) (symbol? x)) (boolean (re-matches #"[^\s,;()\[\]{}\"\\]+" (str x)))
    ;; A record prints with a tag the EDN reader does not know.
    (record? x)     false
    (map? x)        (and (every? edn-value? (keys x)) (every? edn-value? (vals x)))
    (or (vector? x) (set? x) (seq? x)) (every? edn-value? x)
    :else false))

(defn- refusal [field message errors]
  {:error {:type    :validation-error
           :field   field
           :message message
           :errors  errors}})

(defn- scope-refusal [scope scope-schema]
  (let [scope-schema (or scope-schema default-scope)]
    (cond
      (not (map? scope))
      (refusal :scope "A scope is a map" nil)

      (not (m/validate scope-schema scope))
      (refusal :scope "Not a scope this application declares"
               (me/humanize (m/explain scope-schema scope)))

      (not (edn-value? scope))
      (refusal :scope "A scope holds EDN data only" nil))))

(defn grant-refusal
  "Nil when `grant` names a permission the `permissions` schema takes and a
   scope the `scope` schema takes, else a :validation-error naming the field."
  [{:keys [permission scope]} {:keys [permissions scope-schema]}]
  (let [permissions (or permissions default-permissions)]
    (cond
      (not (keyword? permission))
      (refusal :permission "A permission is a keyword" nil)

      (not (m/validate permissions permission))
      (refusal :permission "Not a permission this application declares"
               (me/humanize (m/explain permissions permission)))

      :else (scope-refusal scope scope-schema))))

(defn assignment-refusal
  "Nil when `assignment` names one of `roles` and a scope the `scope` schema
   takes, else a :validation-error naming the field."
  [{:keys [role scope]} roles {:keys [scope-schema]}]
  (if (contains? roles role)
    (scope-refusal scope scope-schema)
    (refusal :role "No such role" nil)))

(defn role-refusal
  "Nil when managed `role` may be stored beside `code-roles`, holding only
   `known` permissions, else a :validation-error naming the field."
  [{:keys [name permissions]} code-roles known]
  (let [unknown (sort (remove known permissions))]
    (cond
      (contains? code-roles name)
      (refusal :name "A module ships a role of this name" nil)

      (seq unknown)
      (refusal :permissions (str "No module declares " (str/join ", " unknown)) (vec unknown)))))

(defn user-grants
  "The grants `assignments` give through `roles`, {role {:permissions #{..}}}.
   An assignment of a role that no longer exists gives nothing."
  [assignments roles]
  (into []
        (comp (mapcat (fn [{:keys [role] :as a}]
                        (for [p (sort (get-in roles [role :permissions]))]
                          (assoc (select-keys a [:group-id :group-name :role :scope]) :permission p))))
              (distinct))
        assignments))

(defn roles-holding
  "The names of `roles` that hold `permission`."
  [roles permission]
  (into #{} (keep (fn [[k {:keys [permissions]}]] (when (contains? permissions permission) k))) roles))

(defn module-roles
  "The code roles named `<module>/<kind>` (\"editor\", \"viewer\") that hold
   none of the `gated` permissions, and not the admin module's: a migration
   that hands them out must widen no check that already reads grants, and make
   nobody an admin (the registry lets no other module's role hold an
   `admin.*` permission)."
  [code-roles kind gated]
  (into (sorted-set)
        (keep (fn [[k {:keys [module permissions]}]]
                (when (and (= kind (name k))
                           (not= :wagoe/admin module)
                           (not-any? gated permissions))
                  k)))
        code-roles))

(defn gated-permissions
  "The permissions something already checks as grants: those a module
   declares itself, the groups schema's, and those `grants` hold."
  [registry-permissions groups-permissions grants]
  (into (set (map :permission grants))
        (keep (fn [[p {:keys [source]}]]
                (when (#{:declared :groups-schema} source) p)))
        (merge registry-permissions groups-permissions)))

(defn users-with-permission
  "The distinct user ids among `holders`, as `permission-holders` returns
   them, whose scope `scope-pred` accepts."
  [holders scope-pred]
  (into [] (comp (filter (comp scope-pred :scope)) (map :user-id) (distinct)) holders))

(defn assignment-key [assignment]
  [(:role assignment) (:scope assignment)])

(defn import-changes
  "What importing wanted `members` and `roles` (assignments) into a group
   holding `members` and `held` assignments changes: users to :join, :leave
   and :stay, compared by :id, and assignments to :add and :remove. Nothing
   leaves unless `replace?`."
  [{:keys [held] present :members} {:keys [members roles]} replace?]
  (let [present-ids (set (map :id present))
        wanted-ids  (set (map :id members))
        leave       (if replace? (into [] (remove (comp wanted-ids :id)) present) [])
        leave-ids   (set (map :id leave))
        held-keys   (set (map assignment-key held))
        wanted-keys (set (map assignment-key roles))]
    {:join   (into [] (comp (remove (comp present-ids :id)) (distinct)) members)
     :leave  leave
     :stay   (into [] (remove (comp leave-ids :id)) present)
     :add    (into [] (comp (remove (comp held-keys assignment-key)) (distinct)) roles)
     :remove (if replace? (into [] (remove (comp wanted-keys assignment-key)) held) [])}))

(defn grant->assignment
  "A grant as the assignment that replaces it: the managed role named after
   its permission, holding only that permission."
  [{:keys [permission scope]}]
  {:role permission :scope (or scope {})})
