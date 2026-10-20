(ns wagoe.user.shell.hooks
  "The sign-in hooks, `:mfa-required?`, `:login-redirect` and `:login-layout`,
   and `:group-membership`, which the group service reads.
   Each is set in `:wagoe/user` config or contributed by an application
   module's `:auth` part, which can be a ref to a component — config can only
   name a var."
  (:require [wagoe.config :as wagoe-config]
            [wagoe.user.ports :as ports]))

(def ^:private protocols
  {:mfa-required?  [ports/IMfaPolicy ports/mfa-required? "IMfaPolicy"]
   :login-redirect [ports/ILoginRedirect ports/login-path "ILoginRedirect"]
   :login-layout   [ports/ILoginLayout ports/login-page "ILoginLayout"]
   :group-membership [ports/IGroupMembership ports/group-names "IGroupMembership"]})

(def ^:private hook-keys (apply sorted-set (keys protocols)))

(def config-hooks
  "The hooks handlers read from `[:active :wagoe/user]`; `:mfa-required?` goes
   to the auth service instead."
  (disj hook-keys :mfa-required?))

(defn as-fn
  "Hook `x` as a function of the protocol method's arguments: a function, a
   qualified symbol naming one, or a component implementing the hook's
   protocol. Throws :configuration-error for anything else."
  [k x]
  (let [v                       (if (qualified-symbol? x) (wagoe-config/resolve-symbol x) x)
        [protocol method pname] (protocols k)]
    (cond
      (satisfies? protocol v)                         (partial method v)
      (or (fn? v) (instance? clojure.lang.MultiFn v)) v
      :else
      (throw (ex-info (str k " must be a function, a qualified symbol naming one, or a component"
                           " implementing " pname)
                      {:type :configuration-error :key k :value x})))))

(defn prepare
  "The hooks as functions. `configured` is what `:wagoe/user` sets, already
   resolved except for `:mfa-required?`; a `:login-redirect` role map is kept
   as it is. `contributed` is the modules' `:auth`. A hook set in both, or an
   unknown `:auth` key, throws :configuration-error."
  [configured contributed]
  (when-let [unknown (seq (remove hook-keys (keys contributed)))]
    (throw (ex-info (str "Unknown :auth hook(s) " (vec unknown) "; expected " (vec hook-keys))
                    {:type :configuration-error :keys (vec unknown)})))
  (when-let [both (seq (filter #(and (get configured %) (some? (get contributed %))) hook-keys))]
    (throw (ex-info (str (vec both) " set both under :wagoe/user and by a module's :auth."
                         " One hook each; drop one.")
                    {:type :configuration-error :keys (vec both)})))
  (reduce (fn [hooks k]
            (let [own (get configured k) theirs (get contributed k)]
              (cond
                own           (assoc hooks k (if (map? own) own (as-fn k own)))
                (some? theirs) (assoc hooks k (as-fn k theirs))
                :else         hooks)))
          {}
          hook-keys))
