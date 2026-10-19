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
  "What a module contributes under `:authorization`. A permission's `:source`
   is `:declared` unless it says otherwise."
  [:map {:closed true}
   [:permissions {:optional true}
    [:map-of :keyword [:map [:label :string] [:source {:optional true} :keyword]]]]
   [:roles {:optional true}
    [:map-of :qualified-keyword [:map [:description {:optional true} :string]
                                 [:permissions [:set :keyword]]]]]
   [:scopes {:optional true}
    [:map-of :keyword [:map [:label :string]]]]])

(def ^:private explain-declaration (m/explainer Declaration))

(defn read-write-permissions
  "`<ns>/read` and `<ns>/write`, derived, labelled with `noun`."
  [ns noun]
  {(keyword ns "read")  {:label (str "Read " noun) :source :derived}
   (keyword ns "write") {:label (str "Change " noun) :source :derived}})

(defn- error [message data]
  {:error (merge {:type :configuration-error :message message} data)})

(defn- rank
  "Which of two declarations of one permission stands; equal ranks clash. Any
   other source, such as the user module's groups schema, yields to both."
  [entry]
  (case (:source entry) :declared 2 :derived 1 0))

(defn- by-rank [have entry]
  (cond (= (rank have) (rank entry)) nil
        (> (rank entry) (rank have)) entry
        :else have))

(defn- if-equal [have entry]
  (when (= (dissoc have :module) (dissoc entry :module)) have))

(defn- add-entries
  "`entries` of `module-key` into `acc` under `part`. Where a key is already
   there, `settle` returns the entry that stands, or nil for a clash."
  [acc part settle module-key entries]
  (if (:error acc)
    acc
    (reduce (fn [acc [k entry]]
              (let [entry (assoc entry :module module-key)
                    have  (get-in acc [part k])
                    kept  (if have (settle have entry) entry)]
                (if kept
                  (assoc-in acc [part k] kept)
                  (reduced (error (str "Modules " (:module have) " and " module-key
                                       " both declare " (name part) " " k ".")
                                  {part k})))))
            acc
            entries)))

(defn- invalid-declaration [declarations]
  (some (fn [[k decl]]
          (when-let [e (explain-declaration (or decl {}))]
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
   derived one, and either over any other source."
  [declarations]
  (or (invalid-declaration declarations)
      (let [acc (reduce (fn [acc [k {:keys [permissions roles scopes]}]]
                          (let [acc (-> acc
                                        (add-entries :permissions by-rank k
                                                     (merge (read-write-permissions (name k) (name k))
                                                            (update-vals permissions #(merge {:source :declared} %))))
                                        (add-entries :roles if-equal k roles)
                                        (add-entries :scopes if-equal k scopes))]
                            (cond-> acc (:error acc) reduced)))
                        {:permissions {} :roles {} :scopes {}}
                        (sort-by key declarations))]
        (or (when (:error acc) acc)
            (role-error acc)
            acc))))

(defn unknown-route-permissions
  "The `:permission`s in `routes`, a router's flattened [path data] pairs, that
   `permissions` (the registry's map) does not hold, as [{:path :permission}].
   A `:permission` may sit in the route data or in a method's."
  [permissions routes]
  (vec (for [[path data] routes
             p (cons (:permission data) (keep #(when (map? %) (:permission %)) (vals data)))
             :when (and (some? p) (not (contains? permissions p)))]
         {:path path :permission p})))
