(ns wagoe.user.core.grant
  "Giving a user one permission, and nothing more, through groups (BOU-853):
   rights are never granted to a user directly. Options are ranked by what
   else they give, read from their previews (`IRightsPreview`)."
  (:require [wagoe.platform.core.scope :as scope]))

(defn smallest-role
  "The name of the role in `roles`, {role {:permissions #{..}}}, holding
   `permission` with the fewest others, by name on a tie. Nil when none does."
  [roles permission]
  (->> roles
       (filter #(contains? (:permissions (val %)) permission))
       (sort-by (juxt (comp count :permissions val) (comp str key)))
       ffirst))

(defn- gained
  "What the user a preview names gains of `permission`, or of every other one."
  [preview permission same?]
  (filterv #(= same? (= permission (:permission %))) (get-in preview [:user :added])))

(defn- wider?
  "Whether resolved `right` reaches beyond `scope`, {scope-key value}, as asked."
  [scope right]
  (not= (update-vals (:scope right) #(set (map str %))) (update-vals scope (comp hash-set str))))

(defn extra
  "What else the user `preview` names gains: other permissions, and
   `permission` itself where it reaches beyond `scope`."
  [preview permission scope]
  (into (filterv (partial wider? scope) (gained preview permission true))
        (gained preview permission false)))

(defn fixes?
  "Whether the user `preview` names gains `permission` in a scope admitting
   `scope`, {scope-key value}: {} asks for it everywhere."
  [preview permission scope]
  (scope/in-scope? (map :scope (gained preview permission true)) scope))

(defn blast-radius
  "How many rights the change `preview` describes gives beyond the one asked:
   one per user and permission gained, less one."
  [preview]
  (max 0 (dec (reduce + (map :added (:permissions preview))))))

(def ^:private kind-order
  "A join, then an assignment; a new managed role and a new group are last resorts."
  {:join 0 :narrow 1 :new-role 2 :new-group 3})

(defn rank
  "`options`, [{:kind :preview ..}], that give `permission` in `scope`: a
   `:join` or `:narrow` whose preview does not is dropped. Each gets `:extra`
   (`extra`) and `:radius`, `blast-radius` and one more for a wider scope than
   asked; the smallest
   first, a join before an assignment on a tie. A new managed role, then a new
   group, always come last."
  [options permission scope]
  (->> options
       (filter #(or (< 1 (kind-order (:kind %))) (fixes? (:preview %) permission scope)))
       (map (fn [{:keys [preview] :as o}]
              (if preview
                (let [xs (extra preview permission scope)]
                  (assoc o :extra xs :radius (cond-> (blast-radius preview)
                                               (some #(= permission (:permission %)) xs) inc)))
                o)))
       (sort-by (juxt #(max 0 (dec (kind-order (:kind %)))) #(:radius % 0) (comp kind-order :kind)
                      #(str (get-in % [:group :name]))))
       vec))
