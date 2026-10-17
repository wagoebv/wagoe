(ns wagoe.user.core.group
  "Grants a user holds through their groups, and whether a grant may be made.

   A grant is {:permission kw :scope map}, plus where it came from (:id
   :group-id :group-name). Wagoe does not say what a permission or a scope
   means: `holds?` and `grants-for` answer for the permission, and the
   application judges the scope."
  (:require [malli.core :as m]
            [malli.error :as me]))

(defn permission-str
  "`permission` as stored and shown: its namespace kept, no colon."
  [permission]
  (subs (str permission) 1))

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

(defn grant-refusal
  "Nil when `grant` names a permission the `permissions` schema takes and a
   scope the `scope` schema takes, else a :validation-error naming the field."
  [{:keys [permission scope]} {:keys [permissions scope-schema]}]
  (let [permissions  (or permissions default-permissions)
        scope-schema (or scope-schema default-scope)]
    (cond
      (not (keyword? permission))
      (refusal :permission "A permission is a keyword" nil)

      (not (m/validate permissions permission))
      (refusal :permission "Not a permission this application declares"
               (me/humanize (m/explain permissions permission)))

      (not (map? scope))
      (refusal :scope "A scope is a map" nil)

      (not (m/validate scope-schema scope))
      (refusal :scope "Not a scope this application declares"
               (me/humanize (m/explain scope-schema scope)))

      (not (edn-value? scope))
      (refusal :scope "A scope holds EDN data only" nil))))

(defn users-with-permission
  "The distinct user ids among `holders`, as `permission-holders` returns
   them, whose scope `scope-pred` accepts."
  [holders scope-pred]
  (into [] (comp (filter (comp scope-pred :scope)) (map :user-id) (distinct)) holders))

(defn- grant-key [grant]
  [(:permission grant) (:scope grant)])

(defn import-changes
  "What importing wanted `members` (ids) and `grants` into a group holding
   `member-ids` and `held` grants changes: users to :join and :leave, grants
   to :add and :remove. Nothing leaves unless `replace?`."
  [{:keys [member-ids held]} {:keys [members grants]} replace?]
  (let [present     (set member-ids)
        wanted      (set members)
        held-keys   (set (map grant-key held))
        wanted-keys (set (map grant-key grants))]
    {:join   (into [] (comp (remove present) (distinct)) members)
     :leave  (if replace? (into [] (remove wanted) member-ids) [])
     :add    (into [] (comp (remove (comp held-keys grant-key)) (distinct)) grants)
     :remove (if replace? (into [] (remove (comp wanted-keys grant-key)) held) [])}))
