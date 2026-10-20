(ns wagoe.platform.shell.authorization
  "`:wagoe/permission-registry`: the registry `core.authorization` builds,
  behind `IPermissionRegistry`, and the scope check of a route (ADR-041 §4).
  Read it at the REPL with

    (ports/permissions (get (wagoe.platform.shell.system.wiring/system)
                            :wagoe/permission-registry))"
  (:require [clojure.tools.logging :as log]
            [wagoe.platform.core.authorization :as core]
            [wagoe.platform.core.http.access :as access]
            [wagoe.platform.core.http.errors :as errors]
            [wagoe.platform.core.scope :as scope]
            [wagoe.platform.ports.authorization :as ports]))

(defrecord PermissionRegistry [registry]
  ports/IPermissionRegistry
  (permissions [_] (:permissions registry))
  (roles [_] (:roles registry))
  (scopes [_] (:scopes registry))
  (scope-sources [_] (:scope-sources registry))
  (permission? [_ permission] (contains? (:permissions registry) permission)))

(defn- config-error! [error]
  (throw (ex-info (:message error) error)))

(defn create-registry
  "The registry from `declarations`, {module-key declaration}. Throws
   :configuration-error, so a bad declaration fails boot."
  [declarations]
  (let [result (core/build-registry declarations)]
    (if-let [error (:error result)]
      (config-error! error)
      (->PermissionRegistry result))))

(defn assert-route-permissions!
  "Throw :configuration-error when a route's `:permission` is not in
   `registry`, or its `:scope` is not one `core.scope` reads. `routes` are a
   router's flattened [path data] pairs."
  [registry routes]
  (when-let [unknown (seq (core/unknown-route-permissions (ports/permissions registry) routes))]
    (config-error! {:type    :configuration-error
                    :message (str "Routes name permissions no module declares: "
                                  (pr-str (mapv (juxt :path :permission) unknown)))
                    :routes  (vec unknown)}))
  (when-let [invalid (seq (core/invalid-route-scopes (ports/scopes registry) routes))]
    (config-error! {:type    :configuration-error
                    :message (str "Routes say a :scope that is neither :handler nor a map of declared"
                                  " scope keys to :path/<param>: "
                                  (pr-str (mapv (juxt :path :scope) invalid)))
                    :routes  (vec invalid)})))

;; =============================================================================
;; Scopes
;; =============================================================================

(defn- scope-values
  "What `v`, the value of scope key `k`, stands for for `user`: a source
   resolved, then `k`'s `:expand`. Empty when a source resolves to nothing or
   has no resolver."
  [{:keys [scopes sources]} user k v]
  (let [values (if (scope/source? v)
                 (if-let [resolver (get-in sources [v :resolve])]
                   (set (resolver user))
                   (do (log/error "A right's scope names a source nothing resolves; it gives no rights"
                                  {:scope-key k :source v})
                       #{}))
                 #{v})
        expand (get-in scopes [k :expand])]
    (if (and expand (seq values)) (set (expand values)) values)))

(defn resolve-rights
  "`rights` as the user module hands them over for `user`
   ([{:permission :scope}], see `core.scope`), each scope as {key #{value}}:
   sources resolved, then expanded. Per request, so a move or a descendant
   added later counts. A right whose scope resolves to nothing for a key is
   dropped: it gives no rights, not rights everywhere."
  [registry user rights]
  (let [declared {:scopes (ports/scopes registry) :sources (ports/scope-sources registry)}]
    (into []
          (keep (fn [{:keys [scope] :as right}]
                  (when-let [resolved (reduce-kv (fn [acc k v]
                                                   (let [values (scope-values declared user k v)]
                                                     (if (seq values) (assoc acc k values) (reduced nil))))
                                                 {}
                                                 scope)]
                    (assoc right :scope resolved))))
          rights)))

(defn authorize-scope
  "The scope check of a route whose data says `route-scope`, for a caller
   holding the route's permission in `scopes` (`core.scope/scopes-for`).
   `{:request r}`, the scopes on it as `:wagoe/scopes` for
   `core.scope/scope-filter`, or `{:response r}`: 403, or 404 for a
   parameter outside their scope."
  [request scopes route-scope]
  (let [{:keys [error] :as decision} (scope/route-decision scopes route-scope (:path-params request))]
    (if error
      {:response (errors/response (if (= :not-found (:type error)) 404 403) (:type error) (:message error)
                                  {:correlation-id (access/correlation-id request (str (random-uuid)))})}
      {:request (assoc request :wagoe/scopes (:scopes decision))})))

(defn scope-middleware
  "Reitit middleware running `authorize-scope` on every endpoint not
   `:public true`, with the endpoint's `:scope`. A platform admin holds every
   permission everywhere.

   `rights-of` is (fn [request]) -> the caller's rights as the user module
   hands them over; `permission-of` (fn [request]) -> the permission the
   route needs. The authorization interceptor supplies both; until it does,
   nothing mounts this and routes behave as before."
  [registry {:keys [rights-of permission-of]}]
  {:name    ::scope
   :compile (fn [data _opts]
              (when-not (access/public? data)
                (let [route-scope (:scope data)]
                  (fn [handler]
                    (fn [request]
                      (let [user   (:user request)
                            scopes (if (access/platform-admin? user)
                                     [{}]
                                     ;; Only the route's permission's rights: resolvers may read.
                                     (let [p (permission-of request)]
                                       (->> (rights-of request)
                                            (filterv #(= p (:permission %)))
                                            (resolve-rights registry user)
                                            (#(scope/scopes-for % p)))))
                            {admitted :request response :response} (authorize-scope request scopes route-scope)]
                        (if admitted (handler admitted) response)))))))})
