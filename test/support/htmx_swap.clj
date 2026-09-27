(ns support.htmx-swap
  "htmx swaps simulated over hiccup, to check that a fragment swapped into
   its target leaves one element with the target's id, not two (BOU-386).")

(def ^:private verbs [:hx-get :hx-post :hx-put :hx-patch :hx-delete])

(defn- element? [node]
  (and (vector? node) (keyword? (first node))))

(defn- attrs [node]
  (let [a (second node)] (if (map? a) a {})))

(defn- id [node]
  (or (:id (attrs node))
      (second (re-find #"#([^.#]+)" (name (first node))))))

(defn- children [node]
  (if (map? (second node)) (drop 2 node) (rest node)))

(defn requests
  "Every element in `page` that issues a request, as {:target :swap}, with
   hx-target and hx-swap inherited from ancestors as htmx does. The swap is
   the style alone, without modifiers."
  [page]
  (letfn [(walk [node inherited]
            (cond
              (element? node)
              (let [a    (attrs node)
                    here (merge inherited (select-keys a [:hx-target :hx-swap]))
                    self (when (some a verbs)
                           [{:target (:hx-target here)
                             :swap   (or (some->> (:hx-swap here) (re-find #"^\S+"))
                                         "innerHTML")}])]
                (concat self (mapcat #(walk % here) (children node))))

              (seq? node) (mapcat #(walk % inherited) node)
              :else nil))]
    (walk page {})))

(defn swap
  "`page` with `fragment` swapped into the element whose id is `target-id`."
  [page target-id fragment style]
  (letfn [(walk [node]
            (cond
              (and (element? node) (= target-id (id node)))
              (case style
                "outerHTML" fragment
                "innerHTML" [(first node) (attrs node) fragment])

              (element? node) (into [(first node) (attrs node)] (map walk (children node)))
              (seq? node)     (map walk node)
              :else           node))]
    (walk page)))

(defn count-id
  "How many elements in `page` carry `target-id`."
  [page target-id]
  (count (filter #(and (element? %) (= target-id (id %)))
                 (tree-seq #(or (vector? %) (seq? %)) seq page))))

(defn ids-after-swaps
  "For each request in `page` aimed at `#target-id`: its swap style, and how
   many elements carry `target-id` once `fragment` is swapped in that way."
  [page target-id fragment]
  (->> (requests page)
       (filter #(= (str "#" target-id) (:target %)))
       (map (fn [r]
              (assoc r :ids (count-id (swap page target-id fragment (:swap r)) target-id))))))
