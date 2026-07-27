(ns wagoe.devtools.core.security-analyzer
  "Pure analysis of security configuration.
   FC/IS: no I/O, no logging.")

(defn analyze-password-policy
  "Analyze password policy strength. Returns map with :strength and details."
  [policy]
  (when policy
    (let [{:keys [min-length require-uppercase? require-lowercase?
                  require-numbers? require-special-chars?]} policy
          requirements (count (filter true? [require-uppercase? require-lowercase?
                                             require-numbers? require-special-chars?]))
          strength (cond
                     (and (>= (or min-length 0) 12) (>= requirements 3)) :strong
                     (and (>= (or min-length 0) 8) (>= requirements 2))  :moderate
                     :else :weak)]
      {:strength            strength
       :min-length          (or min-length 0)
       :require-uppercase?  (boolean require-uppercase?)
       :require-lowercase?  (boolean require-lowercase?)
       :require-numbers?    (boolean require-numbers?)
       :require-special?    (boolean require-special-chars?)})))

(defn analyze-auth-methods
  "Detect which authentication methods are configured.
   Derives methods from presence of auth-related Integrant keys in config
   rather than hardcoding, so projects without the user/auth stack show
   an accurate (possibly empty) list."
  [config]
  (let [settings (get config :boundary/settings {})
        features (get settings :features {})
        methods  (cond-> []
                   (contains? config :boundary/auth-service)      (conj :jwt)
                   (contains? config :boundary/session-repository) (conj :session)
                   (get-in features [:mfa :enabled?])              (conj :mfa))]
    {:methods methods
     :mfa-enabled? (boolean (get-in features [:mfa :enabled?]))}))

(defn analyze-role-config
  "Analyze role restriction configuration."
  [config]
  (let [role-cfg (get-in config [:boundary/settings :user-validation :role-restrictions])]
    {:allowed-roles  (or (:allowed-roles role-cfg) #{})
     :default-role   (or (:default-role role-cfg) :user)}))

(defn analyze-csp-config
  "Analyze Content-Security-Policy header configuration."
  [config]
  (let [http-cfg (get config :boundary/http {})
        csp      (get-in http-cfg [:security :csp])]
    {:configured? (some? csp)
     :policy      csp}))

(defn build-security-summary
  "Build a complete security summary from system config and runtime data."
  ([config] (build-security-summary config {}))
  ([config {:keys [active-sessions recent-auth-failures rate-limiting?]}]
   (let [settings   (get config :boundary/settings {})
         validation (get settings :user-validation {})]
     {:password-policy    (analyze-password-policy (:password-policy validation))
      :auth-methods       (analyze-auth-methods config)
      :roles              (analyze-role-config config)
      :csp                (analyze-csp-config config)
      :lockout            {:max-attempts  (get validation :max-failed-attempts 5)
                           :duration-mins (get validation :lockout-duration-minutes 15)}
      :csrf-enabled?      (not (false? (get-in config [:boundary/http :security :csrf :enabled?])))
      :rate-limiting?     (boolean rate-limiting?)
      :active-sessions    (or active-sessions 0)
      :recent-failures    (or recent-auth-failures [])})))
