(ns wagoe.user.core.revoke
  "Taking one permission away from a user (BOU-1001). Rights come only
   through groups, so each way ends a membership or an assignment; options
   are ranked by what else they take, read from their previews.")

(defn gives?
  "Whether `path`, a right as explain gives it, comes from `assignment` of
   its group: the same role and stored scope."
  [assignment path]
  (and (= (:role assignment) (:role path))
       (= (or (:scope assignment) {}) (or (:stored-scope path) {}))))

(defn- ends?
  "Whether option `o` ends `path`: leaving its group, or ending the
   assignment that gives it."
  [{:keys [kind group assignment]} path]
  (and (= (str (:id group)) (str (:group-id path)))
       (or (= :leave kind) (gives? assignment path))))

(def ^:private kind-order {:leave 0 :end-assignment 1})

(defn rank
  "`options`, [{:kind :group :assignment :preview ..}], each given `:removes?`,
   whether it ends every one of `paths`, the rights giving the permission, so
   the user no longer holds it in any scope; `:also-loses`, the user's other
   losses; and `:radius`, those and the other users it reaches. The ones that
   remove it first, then the smallest, a leave before an assignment on a tie;
   a refused one, with no preview, last."
  [options permission paths]
  (->> options
       (map (fn [{:keys [preview] :as o}]
              (if preview
                (let [xs (filterv #(not= permission (:permission %)) (get-in preview [:user :removed]))]
                  (assoc o
                         :removes? (every? (partial ends? o) paths)
                         :also-loses xs
                         :radius (+ (count xs) (:affected-count preview 0))))
                o)))
       (sort-by (juxt (complement :removes?) #(:radius % Long/MAX_VALUE) (comp kind-order :kind)
                      #(str (get-in % [:group :name]))))
       vec))
