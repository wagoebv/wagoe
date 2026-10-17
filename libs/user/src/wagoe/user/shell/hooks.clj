(ns wagoe.user.shell.hooks
  "The sign-in hooks, `:mfa-required?` and `:login-redirect`. Each is set in
   `:wagoe/user` config or contributed by an application module's `:auth`
   part, which can be a ref to a component — config can only name a var."
  (:require [wagoe.config :as wagoe-config]
            [wagoe.user.ports :as ports]))

(def ^:private protocols
  {:mfa-required?  [ports/IMfaPolicy ports/mfa-required? "IMfaPolicy"]
   :login-redirect [ports/ILoginRedirect ports/login-path "ILoginRedirect"]})

(def ^:private hook-keys (set (keys protocols)))

(defn as-fn
  "Hook `x` as a `(fn [user login-context])`: a function, a qualified symbol
   naming one, or a component implementing the hook's protocol. Throws
   :configuration-error for anything else."
  [k x]
  (let [v                     (if (qualified-symbol? x) (wagoe-config/resolve-symbol x) x)
        [protocol method pname] (protocols k)]
    (cond
      (satisfies? protocol v)                         (fn [user ctx] (method v user ctx))
      (or (fn? v) (instance? clojure.lang.MultiFn v)) v
      :else
      (throw (ex-info (str k " must be a function, a qualified symbol naming one, or a component"
                           " implementing " pname)
                      {:type :configuration-error :key k :value x})))))

(defn prepare
  "The hooks as functions. `configured` is what `:wagoe/user` sets, already
   resolved for `:login-redirect`; `contributed` is the modules' `:auth`. A hook
   set in both, or an unknown `:auth` key, throws :configuration-error."
  [configured contributed]
  (when-let [unknown (seq (remove hook-keys (keys contributed)))]
    (throw (ex-info (str "Unknown :auth hook(s) " (vec unknown) "; expected " (vec (sort hook-keys)))
                    {:type :configuration-error :keys (vec unknown)})))
  (when-let [both (seq (filter #(and (some? (get configured %)) (some? (get contributed %)))
                               (sort hook-keys)))]
    (throw (ex-info (str (vec both) " set both under :wagoe/user and by a module's :auth."
                         " One hook each; drop one.")
                    {:type :configuration-error :keys (vec both)})))
  (let [{:keys [mfa-required? login-redirect]} configured]
    (cond-> {}
      mfa-required?  (assoc :mfa-required? (as-fn :mfa-required? mfa-required?))
      login-redirect (assoc :login-redirect login-redirect)
      (some? (:mfa-required? contributed))
      (assoc :mfa-required? (as-fn :mfa-required? (:mfa-required? contributed)))
      (some? (:login-redirect contributed))
      (assoc :login-redirect (as-fn :login-redirect (:login-redirect contributed))))))
