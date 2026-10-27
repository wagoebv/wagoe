(ns wagoe.platform.shell.authorization
  "`:wagoe/permission-registry`: the registry `core.authorization` builds,
  behind `IPermissionRegistry`, the scope check of a route (ADR-041 §4) and
  the authorization interceptor that runs both (§5).
  Read it at the REPL with

    (ports/permissions (get (wagoe.platform.shell.system.wiring/system)
                            :wagoe/permission-registry))"
  (:require [clojure.tools.logging :as log]
            [wagoe.platform.core.authorization :as core]
            [wagoe.platform.core.http.access :as access]
            [wagoe.platform.core.scope :as scope]
            [wagoe.platform.ports.authorization :as ports]
            [wagoe.platform.throttle :as throttle]))

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
   `registry`, its `:scope` is not one `core.scope` reads or sits on a route
   that checks none, or its `:authorization` is neither `:signed-in` nor
   `:self`. `routes` are a
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
                                  " scope keys to :path/<param> parameters of the route: "
                                  (pr-str (mapv (juxt :path :scope) invalid)))
                    :routes  (vec invalid)}))
  (when-let [invalid (seq (core/invalid-route-markers routes))]
    (config-error! {:type    :configuration-error
                    :message (str "Routes say an :authorization other than :signed-in or :self: "
                                  (pr-str (mapv (juxt :path :authorization) invalid)))
                    :routes  (vec invalid)}))
  (when-let [unchecked (seq (core/unchecked-route-scopes routes))]
    (config-error! {:type    :configuration-error
                    :message (str "Routes say a :scope under :public or an :authorization marker, which"
                                  " check no scope: " (pr-str (mapv (juxt :path :method :scope) unchecked)))
                    :routes  (vec unchecked)})))

;; =============================================================================
;; Scopes
;; =============================================================================

(defn- source-resolver
  "A fn from a source to its values for `user`, each source resolved once.
   No values when nothing resolves it, or a member has none: a nil is no value."
  [sources user]
  (memoize
   (fn [source]
     (if-let [resolver (get-in sources [source :resolve])]
       (into #{} (remove nil?) (resolver user))
       (do (log/error "A right's scope names a source nothing resolves; it gives no rights"
                      {:source source})
           #{})))))

(defn resolve-rights
  "`rights` as the user module hands them over for `user`
   ([{:permission :scope}], see `core.scope`), each scope as {key #{value}}:
   sources resolved, then expanded. Per request, so a move or a descendant
   added later counts. A right whose scope resolves to nothing for a key is
   dropped: it gives no rights, not rights everywhere."
  [registry user rights]
  (let [scopes         (ports/scopes registry)
        resolve-source (source-resolver (ports/scope-sources registry) user)]
    (into []
          (keep (fn [{:keys [scope] :as right}]
                  (when-let [resolved (reduce-kv (fn [acc k v]
                                                   (let [values (scope/scope-values scopes resolve-source k v)]
                                                     (if (seq values) (assoc acc k values) (reduced nil))))
                                                 {}
                                                 scope)]
                    (assoc right :scope resolved))))
          rights)))

(defn authorize-scope
  "The scope check of a route whose data says `route-scope`, for a caller
   holding the route's permission in `scopes` (`core.scope/scopes-for`).
   `{:request r}`, the scopes on it as `:wagoe/scopes` for
   `core.scope/scope-filter`, or `{:response r :error e}`: 403, or 404 for a
   parameter outside their scope."
  [request scopes route-scope]
  (let [{:keys [error] :as decision} (scope/route-decision scopes route-scope (:path-params request))]
    (if error
      {:response (access/refusal-response (if (= :not-found (:type error)) 404 403) (:type error) (:message error)
                                          (access/correlation-id request (str (random-uuid))))
       :error    error}
      {:request (assoc request :wagoe/scopes (:scopes decision))})))

;; =============================================================================
;; The interceptor (ADR-041 §5)
;; =============================================================================

(defn- log-refusal! [{:keys [mode] :as entry}]
  (if (= :report mode)
    (log/warn "Authorization would refuse this request under :enforce" entry)
    (log/info "Authorization refused a request" entry)))

(defn- resolve-matching
  "`rights` whose permission `pred` admits, resolved. Only those: a resolver may read."
  [registry user rights pred]
  (->> rights
       (filterv (comp pred :permission))
       (resolve-rights registry user)))

(defn rights-for
  "`user`'s rights whose permission `pred` admits, read once and resolved.
   Only those are resolved: a resolver may read. None without a source."
  [registry rights-source user pred]
  (resolve-matching registry user (when rights-source (ports/rights rights-source user)) pred))

(defn request-rights
  "The rights of the caller of `request` whose permission `pred` admits,
   resolved as `rights-for` does. Taken from `:wagoe/rights` when the
   authorization middleware read them, else read now: one read per request
   on a permission route, one per call elsewhere (`:self`, `:signed-in`).
   None for a platform admin, who holds every permission, or without a user."
  [registry rights-source request pred]
  (let [user (:user request)]
    (cond (or (nil? user) (access/platform-admin? user)) []
          (contains? request :wagoe/rights) (resolve-matching registry user (:wagoe/rights request) pred)
          :else (rights-for registry rights-source user pred))))

(defn- with-rights
  "`request` with its caller's rights under `:wagoe/rights`, unresolved, as
   `IRights` returns them. A platform admin's are not read."
  [rights-source request]
  (let [user (:user request)]
    (cond-> request
      (not (access/platform-admin? user))
      (assoc :wagoe/rights (vec (when rights-source (ports/rights rights-source user)))))))

(defn- scopes-of
  "The scopes in which the caller of `request` holds `permission`."
  [registry request permission]
  (let [user (:user request)]
    (if (access/platform-admin? user)
      [{}]
      (scope/scopes-for (resolve-matching registry user (:wagoe/rights request) #{permission}) permission))))

(defn- check
  "`{:request r}`, plus `{:response r :error e}` when refused and `:permission p`
   when the rule asks one: the request carries the rights read either way.
   Only for a rule that can refuse: `:permission`, `:module` or `:none`."
  [registry rights-source rule route-scope request]
  (if-let [permission (core/required-permission rule (:request-method request))]
    (let [request  (with-rights rights-source request)
          decision (authorize-scope request (scopes-of registry request permission) route-scope)]
      (merge {:request request}
             (cond-> decision
               ;; What the 403 page links an admin to: why (BOU-851)
               (= 403 (get-in decision [:response :status]))
               (assoc-in [:response :wagoe/refusal]
                         {:user-id    (get-in request [:user :id])
                          :permission permission
                          :record     (scope/route-record route-scope (:path-params request))}))
             {:permission permission}))
    (if (access/platform-admin? (:user request))
      {:request request}
      (let [error {:type :forbidden :message "No authorization rule covers this route"}]
        {:request  request
         :error    error
         :response (access/refusal-response 403 :forbidden (:message error)
                                            (access/correlation-id request (str (random-uuid))))}))))

(defn authorization-middleware
  "Reitit middleware deciding every endpoint by `core/route-rule`, then its
   scope (`authorize-scope`); a platform admin passes everything. Mounted
   after the sign-in guard, so it sees only signed-in callers, and not at all
   on a rule that admits every one of them. `rights-source` is an `IRights`,
   read once per request and kept on it as `:wagoe/rights` for
   `request-rights`, under `:report` too. `mode`: `:report` logs what
   `:enforce` refuses and lets it through, as it does a check that throws;
   `:off` mounts nothing."
  [registry rights-source {:keys [mode]}]
  (let [throttled (throttle/once-per-interval)]
    {:name    ::authorization
     :compile (fn [data _opts]
                (let [rule        (core/route-rule data)
                      route-scope (:scope data)]
                  (when-not (or (= :off mode) (#{:public :signed-in :self} (:rule rule)))
                    (fn [handler]
                      (fn [request]
                        (let [route  (or (:template (:reitit.core/match request)) (:uri request))
                              method (:request-method request)
                              {checked :request :keys [response error permission]}
                              (try (check registry rights-source rule route-scope request)
                                   (catch Exception e
                                     (when-not (= :report mode) (throw e))
                                     (throttled [::failed route method (class e)]
                                                #(log/error e "Authorization report failed; the request goes through"
                                                            {:route route :method method}))
                                     {:request request}))]
                          (when error
                            (let [user-id (get-in request [:user :id])]
                              (throttled [user-id route method permission]
                                         #(log-refusal! {:mode mode :user-id user-id :route route :method method
                                                         :permission permission :reason (:type error)}))))
                          (if (and response (not= :report mode))
                            response
                            (handler checked))))))))}))

;; =============================================================================
;; Explain (BOU-851)
;; =============================================================================

(defn explain
  "Why `user` may or may not use `permission` on `record` ({scope-key value},
   nil for anywhere), as `core.scope/explain` gives it: the rights read from
   `rights-source` and resolved as the interceptor does. Each right keeps the
   keys `IRights` gave it, and its stored scope as `:stored-scope`."
  [registry rights-source user permission record]
  (if (access/platform-admin? user)
    (scope/explain {:platform-admin? true} permission record)
    (let [held     (into [] (comp (filter #(= permission (:permission %)))
                                  (map-indexed #(assoc %2 ::i %1 :stored-scope (:scope %2))))
                         (when rights-source (ports/rights rights-source user)))
          resolved (resolve-rights registry user held)
          kept     (set (map ::i resolved))
          strip    (partial mapv #(dissoc % ::i))]
      (scope/explain {:rights (strip resolved) :dropped (strip (remove (comp kept ::i) held))}
                     permission record))))
