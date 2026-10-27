(ns wagoe.platform.core.scope
  "Scopes on rights (ADR-041 §4).

  The user module hands over a user's rights as
  `[{:permission kw :scope nil-or-{key value}}]`, each value fixed or a
  `:user/<name>` source. Resolved (`shell.authorization/resolve-rights`), a
  scope is `{key #{value}}`, and `{}` holds everywhere. Rights add up: a
  permission is held in each of its scopes.

  Safe by default: a scope admits only where every one of its keys is checked."
  (:require [wagoe.platform.core.http.access :as access]))

(defn- in-ns? [ns v]
  (and (qualified-keyword? v) (= ns (namespace v))))

(defn source?
  "Whether scope value `v` is taken from the user, `:user/department`."
  [v]
  (in-ns? "user" v))

(defn route-scope?
  "Whether `s`, route data's `:scope`, is one `route-decision` reads: absent,
   `:handler`, or a map of keys of `scopes` (the registry's) to path
   parameters, `{:domain-id :path/domain-id}`."
  [scopes s]
  (or (nil? s)
      (= :handler s)
      (and (map? s)
           (seq s)
           (every? (fn [[k v]] (and (contains? scopes k) (in-ns? "path" v))) s))))

(defn scope-schema
  "The Malli form a stored scope fits, from the registry's `scopes`: a closed
   map of the declared keys, each a value of its `:schema` (any without one)
   or one of its `:sources`; a source it does not take is refused, unless a
   `:schema` declared without `:sources` admits it. A key's `:entity` is kept
   as `:wagoe/entity`."
  [scopes]
  (into [:map {:closed true}]
        (for [[k {:keys [entity schema sources]}] (sort-by key scopes)
              :let [not-source [:not [:fn source?]]
                    value      (cond (nil? schema)  not-source
                                     (seq sources)  [:and schema not-source]
                                     :else          schema)]]
          [k (cond-> {:optional true} entity (assoc :wagoe/entity entity))
           (if (seq sources) [:or value (into [:enum] (sort sources))] value)])))

(defn scope-values
  "What `v`, the value of scope key `k`, stands for: a source resolved by
   `resolve-source`, then `k`'s `:expand` in the registry's `scopes`."
  [scopes resolve-source k v]
  (let [values (if (source? v) (resolve-source v) #{v})
        expand (get-in scopes [k :expand])]
    (if (and expand (seq values)) (set (expand values)) values)))

(defn scopes-for
  "The distinct scopes in which resolved `rights` hold `permission`, `{}` for
   everywhere. None when they do not hold it."
  [rights permission]
  (into [] (comp (filter #(= permission (:permission %))) (map #(or (:scope %) {})) (distinct)) rights))

(defn everywhere?
  "Whether one of `scopes` holds everywhere."
  [scopes]
  (boolean (some empty? scopes)))

(defn- allows?
  "Whether `v` is one of `allowed`, compared as strings, as a path parameter arrives."
  [allowed v]
  (boolean (some #(access/same-id? v %) allowed)))

(defn- admits?
  "Whether `scope` admits `values`: each of its keys has a value, inside it."
  [scope values]
  (every? (fn [[k allowed]] (allows? allowed (get values k))) scope))

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
      (if (seq conds) (into [:or] conds) [:= 1 0]))))

;; =============================================================================
;; Explain (BOU-851)
;; =============================================================================

(defn record-route
  "The route `:scope` and path parameters that check `record`, {scope-key
   value}: a parameter per key. `:everywhere`: a route without `:scope`, which
   needs the permission held everywhere. Nil: `:handler`, held anywhere."
  [record]
  (cond
    (= :everywhere record) [nil {}]
    (seq record)           [(into {} (map (fn [k] [k (keyword "path" (name k))])) (keys record))
                            (update-keys record (comp keyword name))]
    :else                  [:handler {}]))

(defn route-record
  "The record, as `record-route` reads it, that a route whose data says
   `route-scope` checks, from its `path-params`."
  [route-scope path-params]
  (cond
    (nil? route-scope)  :everywhere
    (map? route-scope)  (not-empty (into {} (keep (fn [[k p]] (when-some [v (get path-params (keyword (name p)))] [k v])))
                                         route-scope))))

(defn- mismatch
  "Why `scope` does not admit `record`: per key, its allowed values and the
   record's value, nil when the record has none."
  [scope record]
  (into {} (for [[k allowed] scope
                 :let [v (get record k)]
                 :when (not (allows? allowed v))]
             [k {:allowed allowed :value v}])))

(defn explain
  "Why a caller may or may not use `permission` on `record` ({scope-key
   value}, `:everywhere` or nil for anywhere: see `record-route`), decided by
   `route-decision` as a route checking `record` decides it. `rights`: the caller's, resolved; `dropped`: those
   whose scope resolved to nothing. A platform admin holds everything.

   {:allowed? :via (:platform-admin or :rights) :error :paths [right]
    :misses [{:right r :reason :scope-mismatch|:unresolved :keys {k {:allowed :value}}}]}"
  [{:keys [platform-admin? rights dropped]} permission record]
  (if platform-admin?
    {:allowed? true :via :platform-admin :paths [] :misses []}
    (let [[route-scope params] (record-route record)
          decide   #(route-decision (scopes-for % permission) route-scope params)
          held     (filterv #(= permission (:permission %)) rights)
          {admitted true missed false} (group-by #(nil? (:error (decide [%]))) held)
          {:keys [error]} (decide held)]
      {:allowed? (nil? error)
       :via      :rights
       :error    error
       :paths    (vec admitted)
       :misses   (-> []
                     (into (map (fn [r] {:right r :reason :scope-mismatch :keys (mismatch (or (:scope r) {}) record)}))
                           missed)
                     (into (comp (filter #(= permission (:permission %))) (map (fn [r] {:right r :reason :unresolved})))
                           dropped))})))
