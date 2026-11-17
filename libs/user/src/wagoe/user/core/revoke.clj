(ns wagoe.user.core.revoke
  "Taking one permission away from a user (BOU-1001). Rights come only
   through groups, so each way ends a membership or an assignment; options
   are ranked by what else they take, read from their previews.")

(defn- lost
  "What the user a preview names loses of `permission`, or of every other one."
  [preview permission same?]
  (filterv #(= same? (= permission (:permission %))) (get-in preview [:user :removed])))

(def ^:private kind-order {:leave 0 :end-assignment 1})

(defn rank
  "`options`, [{:kind :preview ..}], each given `:removes?`, whether the user
   no longer holds `permission` after it, `:also-loses`, their other losses,
   and `:radius`, those and the other users it reaches. The ones that remove
   it first, then the smallest, a leave before an assignment on a tie; a
   refused one, with no preview, last."
  [options permission]
  (->> options
       (map (fn [{:keys [preview] :as o}]
              (if preview
                (let [xs (lost preview permission false)]
                  (assoc o
                         :removes? (boolean (seq (lost preview permission true)))
                         :also-loses xs
                         :radius (+ (count xs) (:affected-count preview 0))))
                o)))
       (sort-by (juxt (complement :removes?) #(:radius % Long/MAX_VALUE) (comp kind-order :kind)
                      #(str (get-in % [:group :name]))))
       vec))
