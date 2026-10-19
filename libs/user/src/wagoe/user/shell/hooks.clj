(ns wagoe.user.shell.hooks
  "The sign-in hooks, `:mfa-required?`, `:login-redirect` and `:login-layout`.
   Each is set in `:wagoe/user` config or contributed by an application
   module's `:auth` part, which can be a ref to a component — config can only
   name a var."
  (:require [wagoe.config :as wagoe-config]
            [wagoe.user.ports :as ports]))

(def ^:private protocols
  "Per hook: its protocol, how a component becomes a function, and the name
   errors use."
  {:mfa-required?  [ports/IMfaPolicy #(fn [user ctx] (ports/mfa-required? % user ctx)) "IMfaPolicy"]
   :login-redirect [ports/ILoginRedirect #(fn [user ctx] (ports/login-path % user ctx)) "ILoginRedirect"]
   :login-layout   [ports/ILoginLayout
                    #(fn [title body opts] (ports/login-page % title body opts)) "ILoginLayout"]})

(def ^:private hook-keys (set (keys protocols)))

(defn as-fn
  "Hook `x` as a function — `(fn [user login-context])`, or for `:login-layout`
   `(fn [title body opts])`: a function, a qualified symbol naming one, or a
   component implementing the hook's protocol. Throws :configuration-error for
   anything else."
  [k x]
  (let [v                     (if (qualified-symbol? x) (wagoe-config/resolve-symbol x) x)
        [protocol ->fn pname] (protocols k)]
    (cond
      (satisfies? protocol v)                         (->fn v)
      (or (fn? v) (instance? clojure.lang.MultiFn v)) v
      :else
      (throw (ex-info (str k " must be a function, a qualified symbol naming one, or a component"
                           " implementing " pname)
                      {:type :configuration-error :key k :value x})))))

(defn prepare
  "The hooks as functions. `configured` is what `:wagoe/user` sets, already
   resolved for `:login-redirect` and `:login-layout`; `contributed` is the
   modules' `:auth`. A hook set in both, or an unknown `:auth` key, throws
   :configuration-error."
  [configured contributed]
  (when-let [unknown (seq (remove hook-keys (keys contributed)))]
    (throw (ex-info (str "Unknown :auth hook(s) " (vec unknown) "; expected " (vec (sort hook-keys)))
                    {:type :configuration-error :keys (vec unknown)})))
  (when-let [both (seq (filter #(and (get configured %) (some? (get contributed %)))
                               (sort hook-keys)))]
    (throw (ex-info (str (vec both) " set both under :wagoe/user and by a module's :auth."
                         " One hook each; drop one.")
                    {:type :configuration-error :keys (vec both)})))
  (let [{:keys [mfa-required? login-redirect login-layout]} configured]
    (cond-> {}
      mfa-required?  (assoc :mfa-required? (as-fn :mfa-required? mfa-required?))
      login-redirect (assoc :login-redirect login-redirect)
      login-layout   (assoc :login-layout login-layout)
      (some? (:mfa-required? contributed))
      (assoc :mfa-required? (as-fn :mfa-required? (:mfa-required? contributed)))
      (some? (:login-redirect contributed))
      (assoc :login-redirect (as-fn :login-redirect (:login-redirect contributed)))
      (some? (:login-layout contributed))
      (assoc :login-layout (as-fn :login-layout (:login-layout contributed))))))
