(ns wagoe.platform.core.scope
  "Scopes on rights (ADR-041 §4).

  The user module hands over a user's rights as
  `[{:permission kw :scope nil-or-{key value}}]`, each value fixed or a
  `:user/<name>` source. Resolved (`shell.authorization/resolve-rights`), a
  scope is `{key #{value}}`, and `{}` holds everywhere. Rights add up: a
  permission is held in each of its scopes.

  Safe by default: a scope admits only where every one of its keys is checked.")

(defn source?
  "Whether scope value `v` is taken from the user, `:user/department`."
  [v]
  (and (qualified-keyword? v) (= "user" (namespace v))))

(defn scope-schema
  "The Malli form a stored scope fits, from the registry's `scopes`: a closed
   map of the declared keys, each a value of its `:schema` (any without one)
   or one of its `:sources`. A key's `:entity` is kept as `:wagoe/entity`."
  [scopes]
  (into [:map {:closed true}]
        (for [[k {:keys [entity schema sources]}] (sort-by key scopes)
              :let [value (or schema :any)]]
          [k (cond-> {:optional true} entity (assoc :wagoe/entity entity))
           (if (seq sources) [:or value (into [:enum] (sort sources))] value)])))

(defn scopes-for
  "The distinct scopes in which resolved `rights` hold `permission`, `{}` for
   everywhere. None when they do not hold it."
  [rights permission]
  (into [] (comp (filter #(= permission (:permission %))) (map #(or (:scope %) {})) (distinct)) rights))

(defn everywhere?
  "Whether one of `scopes` holds everywhere."
  [scopes]
  (boolean (some empty? scopes)))

(defn- admits?
  "Whether `scope` admits `values`: each of its keys has a value, inside it.
   Compared as strings, as a path parameter arrives."
  [scope values]
  (every? (fn [[k allowed]]
            (let [v (get values k)]
              (and (some? v) (boolean (some #(= (str v) (str %)) allowed)))))
          scope))

(defn in-scope?
  "Whether one of `scopes` admits `values`, {scope-key value}: a record's or a
   request's. A scope with a key `values` lacks does not."
  [scopes values]
  (boolean (some #(admits? % values) scopes)))

(defn- refusal [type message]
  {:error {:type type :message message}})

(defn route-decision
  "Whether a holder of `scopes` (`scopes-for` the route's permission) may use
   a route whose data says `route-scope`:

   - nil: only one holding it everywhere.
   - `{:domain-id :path/domain-id}`: one whose scope the path parameters fall
     in, and whose scope has no key the route leaves unchecked.
   - `:handler`: any holder; the handler filters with `scope-filter`.

   {:scopes scopes} when admitted, else an error: `:forbidden` when nothing
   here can check their scope, `:not-found` when a parameter lies outside it,
   so its existence does not leak."
  [scopes route-scope path-params]
  (let [checked   (when (map? route-scope) route-scope)
        checkable (filterv #(every? (partial contains? checked) (keys %)) scopes)]
    (cond
      (empty? scopes)          (refusal :forbidden "You do not hold the permission this route needs")
      (= :handler route-scope) {:scopes scopes}
      (empty? checkable)       (refusal :forbidden "This route cannot check the scope you hold it in")
      (in-scope? checkable (update-vals checked #(get path-params (keyword (name %)))))
      {:scopes scopes}
      :else                    (refusal :not-found "Not found"))))

(defn- condition [field values]
  (let [values (vec (sort-by str values))]
    (if (fn? field) (field values) [:in field values])))

(defn scope-filter
  "A HoneySQL WHERE admitting the rows inside one of `scopes`, `[:= 1 1]` when
   one holds everywhere. `fields` maps a scope key to the column it lies in,
   `:orders.domain_id`, or to a fn from the allowed values to a condition, for
   a scope on a related record:

     {:domain-id (fn [ids] [:in :customer_id {:select [:id] :from [:customers]
                                              :where [:in :domain_id ids]}])}

   A scope with a key `fields` does not map admits nothing; so do no scopes:
   `[:= 1 0]`."
  [scopes fields]
  (if (everywhere? scopes)
    [:= 1 1]
    (let [conds (for [scope scopes
                      :when (every? #(contains? fields %) (keys scope))]
                  (into [:and] (map (fn [[k vs]] (condition (get fields k) vs))) (sort-by key scope)))]
      (case (count conds)
        0 [:= 1 0]
        1 (first conds)
        (into [:or] conds)))))
