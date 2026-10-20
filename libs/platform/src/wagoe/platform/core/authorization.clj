(ns wagoe.platform.core.authorization
  "The permission registry, built from what modules declare (ADR-041 §1–2).

  A module contributes under `:authorization`:

    {:permissions {:orders/refund {:label \"Refund orders\"}}
     :roles       {:orders/viewer {:description \"…\" :permissions #{:orders/read}}}
     :scopes      {:domain-id {:label \"Domain\" :entity :domains}}
     :scope-sources {:user/department {:label   \"The member's department\"
                                       :resolve (fn [user] #{7})}}}

  `<module>/read` and `<module>/write` exist for every module without being
  declared. What a scope means is ADR-041 §4, `core.scope`."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [wagoe.platform.core.scope :as scope]))

(def ^:private Source
  [:fn {:error/message "a scope source is a :user/<name> keyword"} scope/source?])

(def Declaration
  "What a module contributes under `:authorization`. A permission's or scope
   key's `:source` is `:declared` unless it says otherwise.

   A scope key may name its value's `:schema`, the `:sources` its value may be
   taken from, and `:expand`, a fn from values to them and everything beneath."
  [:map {:closed true}
   [:permissions {:optional true}
    [:map-of :keyword [:map [:label :string] [:source {:optional true} :keyword]]]]
   [:roles {:optional true}
    [:map-of :qualified-keyword [:map [:description {:optional true} :string]
                                 [:permissions [:set :keyword]]]]]
   [:scopes {:optional true}
    [:map-of :keyword [:map [:label :string]
                       [:entity {:optional true} :keyword]
                       [:schema {:optional true} any?]
                       [:sources {:optional true} [:set Source]]
                       [:expand {:optional true} fn?]
                       [:source {:optional true} :keyword]]]]
   [:scope-sources {:optional true}
    [:map-of Source [:map [:label :string] [:resolve fn?]]]]])

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

(defn- scope-by-rank
  "As `by-rank`, except that two of equal rank stand when they are the same.
   The loser's `:schema` is kept when the winner names none, so a key the
   groups schema typed `:int` still decodes a lookup's string."
  [have entry]
  (if (= (rank have) (rank entry))
    (if-equal have entry)
    (let [kept (by-rank have entry)]
      (merge (select-keys (if (identical? kept have) entry have) [:schema]) kept))))

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

(defn- source-error
  "Why the scope keys in `registry` cannot stand, or nil: one is taken from a
   source nothing resolves."
  [{:keys [scopes scope-sources]}]
  (some (fn [[k {:keys [sources]}]]
          (when-let [missing (seq (sort (remove scope-sources sources)))]
            (error (str "Scope key " k " is taken from " (str/join ", " missing)
                        ", which no module resolves under :scope-sources.")
                   {:scope k :sources (vec missing)})))
        scopes))

(defn build-registry
  "One registry from `declarations`, {module-key declaration} for every enabled
   module, a declaration being nil when the module declares nothing.

   {:permissions {..} :roles {..} :scopes {..} :scope-sources {..}}, each
   entry tagged with its `:module`, or {:error {:type :configuration-error …}}.
   A permission two modules declare is an error, except that a declared one
   stands over a derived one, and either over any other source. A scope key
   declared twice must be declared the same, or once from another source."
  [declarations]
  (or (invalid-declaration declarations)
      (let [acc (reduce (fn [acc [k {:keys [permissions roles scopes scope-sources]}]]
                          (let [acc (-> acc
                                        ;; Apart, so a module's own groups schema yields
                                        ;; to its derived read/write too.
                                        (add-entries :permissions by-rank k (read-write-permissions (name k) (name k)))
                                        (add-entries :permissions by-rank k
                                                     (update-vals permissions #(merge {:source :declared} %)))
                                        (add-entries :roles if-equal k roles)
                                        (add-entries :scopes scope-by-rank k
                                                     (update-vals scopes #(merge {:source :declared} %)))
                                        (add-entries :scope-sources if-equal k scope-sources))]
                            (cond-> acc (:error acc) reduced)))
                        {:permissions {} :roles {} :scopes {} :scope-sources {}}
                        (sort-by key declarations))]
        (or (when (:error acc) acc)
            (role-error acc)
            (source-error acc)
            acc))))

(defn- route-values
  "`k` in route `data` and in each of its methods'."
  [k data]
  (cons (k data) (keep #(when (map? %) (k %)) (vals data))))

(defn unknown-route-permissions
  "The `:permission`s in `routes`, a router's flattened [path data] pairs, that
   `permissions` (the registry's map) does not hold, as [{:path :permission}].
   A `:permission` may sit in the route data or in a method's."
  [permissions routes]
  (vec (for [[path data] routes
             p (route-values :permission data)
             :when (and (some? p) (not (contains? permissions p)))]
         {:path path :permission p})))

(defn- path-param-names
  "The parameter names in a Reitit `path`: `:id`, `*rest`, `{id}`, `{*rest}`."
  [path]
  (into #{} (map (fn [[_ bare braced]] (or bare braced)))
        (re-seq #"[:*]([^/{}:*]+)|\{\*?([^}]+)\}" (str path))))

(defn- unknown-param?
  "Whether route scope `s` names a `:path/<param>` the route's `path` lacks,
   which would answer 404 to every scoped holder."
  [path s]
  (and (map? s)
       (not-every? (comp (path-param-names path) name) (vals s))))

(defn invalid-route-scopes
  "The `:scope`s in `routes` that `core.scope/route-scope?` refuses for the
   registry's `scopes`, or that name a path parameter the route lacks, as
   [{:path :scope}]. Like `:permission`."
  [scopes routes]
  (vec (for [[path data] routes
             s (route-values :scope data)
             :when (or (not (scope/route-scope? scopes s)) (unknown-param? path s))]
         {:path path :scope s})))
