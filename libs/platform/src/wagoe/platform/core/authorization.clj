(ns wagoe.platform.core.authorization
  "The permission registry, built from what modules declare (ADR-041 §1–2).

  A module contributes under `:authorization`:

    {:permissions {:orders/refund {:label \"Refund orders\"}}
     :roles       {:orders/viewer {:description \"…\" :permissions #{:orders/read}}}
     :scopes      {:domain-id {:label \"Domain\" :entity :domains}}}

  `<module>/read` and `<module>/write` exist for every module without being
  declared."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]))

(def Declaration
  "What a module contributes under `:authorization`."
  [:map {:closed true}
   [:permissions {:optional true}
    [:map-of :keyword [:map [:label :string]
                       [:source {:optional true} [:enum :declared :derived :groups-schema]]]]]
   [:roles {:optional true}
    [:map-of :qualified-keyword [:map [:description {:optional true} :string]
                                 [:permissions [:set :keyword]]]]]
   [:scopes {:optional true}
    [:map-of :keyword [:map [:label :string]]]]])

(defn module-permissions
  "`<module>/read` and `<module>/write` for `module-key`, `:wagoe/orders`."
  [module-key]
  (let [m (name module-key)]
    {(keyword m "read")  {:label (str "Read " m) :source :derived}
     (keyword m "write") {:label (str "Change " m) :source :derived}}))

(defn entity-permissions
  "`admin.<entity>/read` and `admin.<entity>/write` for an admin entity."
  [entity]
  (let [ns (str "admin." (name entity))]
    {(keyword ns "read")  {:label (str "Read " (name entity) " in the admin") :source :derived}
     (keyword ns "write") {:label (str "Change " (name entity) " in the admin") :source :derived}}))

(defn- error [message data]
  {:error (merge {:type :configuration-error :message message} data)})

(def ^:private rank
  "Which of two declarations of one permission stands. Equal ranks clash."
  {:declared 2 :derived 1 :groups-schema 0})

(defn- add-permission
  [acc [p entry]]
  (let [have (get-in acc [:permissions p])]
    (cond
      (:error acc) acc
      (nil? have) (assoc-in acc [:permissions p] entry)
      (= (rank (:source have)) (rank (:source entry)))
      (error (str "Modules " (:module have) " and " (:module entry) " both declare " p ".")
             {:permission p})
      (> (rank (:source entry)) (rank (:source have))) (assoc-in acc [:permissions p] entry)
      :else acc)))

(defn- add-entries
  "`entries` into `acc` under `part`, refusing a key two modules set
   differently."
  [acc part entries]
  (reduce (fn [acc [k entry]]
            (let [have (get-in acc [part k])]
              (cond
                (:error acc) acc
                (nil? have) (assoc-in acc [part k] entry)
                (= (dissoc have :module) (dissoc entry :module)) acc
                :else (error (str "Modules " (:module have) " and " (:module entry)
                                  " declare " (name part) " " k " differently.")
                             {part k}))))
          acc
          entries))

(defn- tag [module-key entries]
  (update-vals entries #(assoc % :module module-key)))

(defn- invalid-declaration [declarations]
  (some (fn [[k decl]]
          (when-let [e (m/explain Declaration (or decl {}))]
            (error (str k " declares :authorization wrongly: " (pr-str (me/humanize e)))
                   {:module k})))
        declarations))

(defn- admin-permission? [p]
  (str/starts-with? (str (namespace p)) "admin."))

(defn- role-error
  "Why the roles in `registry` cannot stand, or nil: one names a permission
   outside the registry, or holds an `admin.*` one outside the admin module."
  [{:keys [permissions roles]}]
  (some (fn [[role {:keys [module] :as entry}]]
          (let [held    (:permissions entry)
                unknown (sort (remove permissions held))]
            (cond
              (seq unknown)
              (error (str "Role " role " names " (str/join ", " unknown)
                          ", which no module declares.")
                     {:role role :permissions (vec unknown)})

              (and (not= :wagoe/admin module) (some admin-permission? held))
              (error (str "Role " role " holds an admin.* permission; only the admin module's"
                          " code roles may.")
                     {:role role}))))
        roles))

(defn build-registry
  "One registry from `declarations`, {module-key declaration} for every enabled
   module, a declaration being nil when the module declares nothing.

   {:permissions {..} :roles {..} :scopes {..}}, each entry tagged with its
   `:module`, or {:error {:type :configuration-error …}}. A permission two
   modules declare is an error, except that a declared one stands over a
   derived one and either over the `:wagoe/user :groups` schema's."
  [declarations]
  (or (invalid-declaration declarations)
      (let [acc (reduce (fn [acc [k {:keys [permissions roles scopes]}]]
                          (-> (reduce add-permission acc
                                      (tag k (merge (module-permissions k)
                                                    (update-vals permissions #(merge {:source :declared} %)))))
                              (add-entries :roles (tag k roles))
                              (add-entries :scopes (tag k scopes))))
                        {:permissions {} :roles {} :scopes {}}
                        (sort-by key declarations))]
        (or (when (:error acc) acc)
            (role-error acc)
            acc))))

(defn- route-entries
  "{:path :permission} for each `:permission` in `route`, at the path or on a
   method, and in its children."
  [prefix route]
  (if (string? (first route))
    (let [[path & more] route
          path (str prefix path)
          data (when (map? (first more)) (first more))]
      (concat (for [p (cons (:permission data) (keep #(when (map? %) (:permission %)) (vals data)))
                    :when (some? p)]
                {:path path :permission p})
              (mapcat #(route-entries path %) (filter sequential? (if data (rest more) more)))))
    (mapcat #(route-entries prefix %) (filter sequential? route))))

(defn unknown-route-permissions
  "The `:permission`s in `routes` that `permissions` (the registry's map) does
   not hold, as [{:path :permission}]."
  [permissions routes]
  (vec (remove #(contains? permissions (:permission %))
               (mapcat #(route-entries "" %) routes))))
