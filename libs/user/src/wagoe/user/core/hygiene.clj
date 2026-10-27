(ns wagoe.user.core.hygiene
  "Authorization hygiene signals (BOU-855): what the groups, roles and
   permissions lists badge and filter on. Each item gets `:signals`, a set of
   keywords. Assignments as `all-assignments` gives them."
  (:require [clojure.set :as set]
            [wagoe.user.core.group :as group]))

(defn group-signals
  "A group from `list-groups`: `:empty` without stored members, `:single-member`
   with one (informational: rights go through groups only, so a group of one
   is normal), `:everyone` for Everyone when it holds roles. Members an
   identity provider adds are not counted."
  [{:keys [id member-count role-count]}]
  (if (group/everyone? id)
    (if (pos? (or role-count 0)) #{:everyone} #{})
    (case (long (or member-count 0))
      0 #{:empty}
      1 #{:single-member}
      #{})))

(defn near-duplicates
  "The code roles in `roles` whose permissions differ from managed `role`'s by
   at most one permission, added or missing: a managed role that is (almost)
   a code role, which could be assigned instead."
  [role roles]
  (when (= :managed (:source role))
    (let [ps (set (:permissions role))]
      (vec (for [r     roles
                 :let  [qs (set (:permissions r))]
                 :when (and (= :code (:source r))
                            (<= (+ (count (set/difference ps qs)) (count (set/difference qs ps))) 1))]
             (:name r))))))

(defn role-signals
  "`roles` from `list-roles`, each with `:signals`: `:near-duplicate` (and
   `:near-duplicates`, the code roles), `:unused` for a managed role nobody is
   assigned (a code role comes with its module and cannot be removed), and
   `:everyone` when assigned to Everyone."
  [roles assignments]
  (let [assigned (set (map :role assignments))
        everyone (set (keep #(when (:everyone? %) (:role %)) assignments))]
    (for [role roles
          :let [near (near-duplicates role roles)]]
      (assoc role
             :near-duplicates near
             :signals (cond-> #{}
                        (seq near)                                                     (conj :near-duplicate)
                        (and (= :managed (:source role)) (not (assigned (:name role)))) (conj :unused)
                        (everyone (:name role))                                         (conj :everyone))))))

(defn permission-signals
  "Signals for `permission`, held by the roles `role-names`: `:unheld` when
   none of them is assigned to any group, `:everyone` when one is assigned to
   Everyone."
  [role-names assignments]
  (let [held (set role-names)
        as   (filter #(held (:role %)) assignments)]
    (cond-> #{}
      (empty? as)            (conj :unheld)
      (some :everyone? as)   (conj :everyone))))
