(ns wagoe.platform.shell.authorization
  "`:wagoe/permission-registry`: the registry `core.authorization` builds,
  behind `IPermissionRegistry`. Read it at the REPL with

    (ports/permissions (get (wagoe.platform.shell.system.wiring/system)
                            :wagoe/permission-registry))"
  (:require [wagoe.platform.core.authorization :as core]
            [wagoe.platform.ports.authorization :as ports]))

(defrecord PermissionRegistry [registry]
  ports/IPermissionRegistry
  (permissions [_] (:permissions registry))
  (roles [_] (:roles registry))
  (scopes [_] (:scopes registry))
  (permission? [_ permission] (contains? (:permissions registry) permission)))

(defn- config-error! [{:keys [error]}]
  (throw (ex-info (:message error) error)))

(defn create-registry
  "The registry from `declarations`, {module-key declaration}. Throws
   :configuration-error, so a bad declaration fails boot."
  [declarations]
  (let [result (core/build-registry declarations)]
    (if (:error result)
      (config-error! result)
      (->PermissionRegistry result))))

(defn assert-route-permissions!
  "Throw :configuration-error when a route's `:permission` is not in
   `registry`."
  [registry routes]
  (when-let [unknown (seq (core/unknown-route-permissions (ports/permissions registry) routes))]
    (config-error! {:error {:type    :configuration-error
                            :message (str "Routes name permissions no module declares: "
                                          (pr-str (mapv (juxt :path :permission) unknown)))
                            :routes  (vec unknown)}})))
