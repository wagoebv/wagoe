(ns wagoe.system-config-test
  (:require [wagoe.config :as sut]
            [wagoe.platform.shell.modules :as modules]
            [wagoe.system-config :as sys-config]
            [wagoe.platform.shell.adapters.database.protocols :as db-protocols]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]))

;; ---------------------------------------------------------------------------
;; Environment alias normalization
;; ---------------------------------------------------------------------------

(deftest ^:unit normalize-env-aliases-test
  (testing "long-form :profile values are normalized to short directory names"
    (is (map? (sut/load-config {:profile :development})))
    (is (map? (sut/load-config {:profile :production})))
    (is (map? (sut/load-config {:profile :testing})))
    (is (map? (sut/load-config {:profile :acceptance}))))

  (testing "short-form values still work unchanged"
    (is (map? (sut/load-config {:profile :dev})))
    (is (map? (sut/load-config {:profile :test})))
    (is (map? (sut/load-config {:profile :prod})))
    (is (map? (sut/load-config {:profile :acc}))))

  (testing "unknown profile still produces a clear error"
    (let [ex (is (thrown? clojure.lang.ExceptionInfo
                          (sut/load-config {:profile :nonexistent})))]
      (is (= "Configuration file not found" (ex-message ex))))))

(defn- base-config
  []
  {:active
   {:wagoe/settings {:name "Wagoe Test"
                        :version "0.1.0"
                        :user-validation {:password-policy {:min-length 12}}}
    :wagoe/http {:port 3000
                    :host "127.0.0.1"
                    :join? false
                    :port-range {:start 3000 :end 3010}}
    :wagoe/router {:adapter :reitit}
    :wagoe/logging {:provider :no-op}
    :wagoe/metrics {:provider :no-op}
    :wagoe/error-reporting {:provider :no-op}
    :wagoe/cache {:provider :memory}
    :wagoe/sqlite {:db "dev-database.db"
                      :pool {:maximum-pool-size 5}}}})

(deftest ^:unit db-spec-selects-active-adapter-test
  (testing "sqlite config is converted to a DB spec"
    (is (= {:adapter :sqlite
            :database-path "dev-database.db"
            :pool {:maximum-pool-size 5}}
           (sut/db-spec (base-config)))))

  (testing "h2 in-memory mode expands to a memory DSN"
    (let [config {:active {:wagoe/h2 {:memory true
                                         :pool {:maximum-pool-size 3}}}}]
      (is (= {:adapter :h2
              :database-path "mem:wagoe;DB_CLOSE_DELAY=-1"
              :pool {:maximum-pool-size 3}}
             (sut/db-spec config)))))

  (testing "missing adapters fail clearly"
    (let [ex (is (thrown? clojure.lang.ExceptionInfo
                          (sut/db-adapter {:active {:wagoe/http {:port 3000}}})))]
      (is (= "No active database adapter found in configuration" (ex-message ex))))))

(deftest ^:unit ig-config-wires-tenant-membership-and-http-components-test
  (let [config (-> (base-config)
                   (assoc-in [:active :wagoe/admin] {:enabled? true})
                   ;; Tenancy is wired because the config asks for it. Until
                   ;; BOU-326 the tenant graph was emitted unconditionally, so a
                   ;; config that never mentioned tenants still got ten of their
                   ;; components.
                   (assoc-in [:active :wagoe/tenant] {:enabled? true}))
        ig-config (sys-config/ig-config config)]
    (testing "tenant and membership services are part of the Integrant graph"
      (is (contains? ig-config :wagoe/tenant-repository))
      (is (contains? ig-config :wagoe/tenant-service))
      (is (contains? ig-config :wagoe/membership-repository))
      (is (contains? ig-config :wagoe/membership-service))
      (is (contains? ig-config :wagoe/invite-repository))
      (is (contains? ig-config :wagoe/invite-service))
      (is (contains? ig-config :wagoe/membership-routes)))

    (testing "tenant route wiring includes the db-context needed for provisioning"
      (is (= {:tenant-service (ig/ref :wagoe/tenant-service)
              :db-context (ig/ref :wagoe/db-context)
              :config config}
             (:wagoe/tenant-routes ig-config))))

    (testing "http handler receives membership routes and the injected tenant middleware (BOU-200)"
      (is (= (ig/ref :wagoe/membership-routes)
             (get-in ig-config [:wagoe/http-handler :membership-routes])))
      ;; Tenant/membership middleware is no longer built inside http-handler; it is
      ;; injected via :extra-middleware from the tenant lib's component.
      (is (= (ig/ref :wagoe/tenant-http-middleware)
             (get-in ig-config [:wagoe/http-handler :extra-middleware])))
      (is (not (contains? (:wagoe/http-handler ig-config) :membership-service))))

    (testing "the tenant-http-middleware component wires the services it needs"
      (is (= {:tenant-service (ig/ref :wagoe/tenant-service)
              :membership-service (ig/ref :wagoe/membership-service)
              :db-context (ig/ref :wagoe/db-context)}
             (:wagoe/tenant-http-middleware ig-config))))

    (testing "cache-enabled user service wiring keeps the cache dependency"
      (is (= (ig/ref :wagoe/cache)
             (get-in ig-config [:wagoe/user-service :cache]))))))

;; =============================================================================
;; Every profile's database configuration must be one the framework accepts
;; =============================================================================

(deftest ^:integration every-profile-builds-a-valid-database-config
  ;; Found by BOU-89, by actually running the prod image: the prod and acc
  ;; profiles could not boot at all. Two independent defects, both invisible
  ;; without starting the thing —
  ;;
  ;;   * :port was `#env "POSTGRES_PORT"`, a string, against [:port pos-int?];
  ;;   * :pool carried :keepalive-time-ms, :validation-timeout-ms and
  ;;     :leak-detection-threshold-ms, which nothing applies and which the
  ;;     :closed pool schema rejects.
  ;;
  ;; Neither is reachable from the test profile, which uses H2. This checks the
  ;; profiles nobody runs locally.
  (let [placeholder (fn [spec]
                      ;; #env with no value yields nil under test, where the
                      ;; variables are not set. Substituting representative
                      ;; values keeps the check on the parts of the shape that
                      ;; the config file decides — the pool, the port, the
                      ;; adapter — rather than on the environment.
                      (cond-> spec
                        (nil? (:host spec))     (assoc :host "db.example")
                        (nil? (:name spec))     (assoc :name "app")
                        (nil? (:username spec)) (assoc :username "app")
                        (nil? (:password spec)) (assoc :password "secret")))]
    (doseq [profile [:dev :test :prod :acc]]
      (testing (str "the " (name profile) " profile")
        (let [spec (placeholder (sut/db-spec (sut/load-config {:profile profile})))]
          (testing "the port is a number, not the string an env var yields"
            (when (contains? spec :port)
              (is (pos-int? (:port spec))
                  (str profile " :port is " (pr-str (:port spec))))))

          (testing "and the whole spec is one the adapter accepts"
            (is (nil? (:errors (try (db-protocols/validate-db-config spec) nil
                                    (catch clojure.lang.ExceptionInfo e (ex-data e)))))
                (str profile " database configuration is rejected — this profile cannot boot"))))))))

(deftest ^:integration pool-settings-that-nothing-applies-are-not-configured
  ;; The specific shape of the second defect. A pool key the connection builder
  ;; ignores is dead config at best; because the schema is :closed it is also
  ;; fatal. Listing the applied set here means adding a key to a profile
  ;; without wiring it fails loudly.
  (let [applied #{:minimum-idle :maximum-pool-size :connection-timeout-ms
                  :idle-timeout-ms :max-lifetime-ms}]
    (doseq [profile [:dev :test :prod :acc]]
      (let [pool (:pool (sut/db-spec (sut/load-config {:profile profile})))
            extra (remove applied (keys pool))]
        (is (empty? extra)
            (str profile " sets pool keys nothing applies: " (pr-str extra)))))))

(deftest ^:integration prod-does-not-acquire-dependencies-it-did-not-have
  ;; A profile is a deployment contract: a component appearing in :active is a
  ;; service every existing deployment suddenly has to provide. This pins the
  ;; opt-in ones so a block edited near them cannot drift across the boundary —
  ;; which is easy to do by hand, and invisible in a diff that shows only the
  ;; lines that moved.
  (let [prod (sut/load-config {:profile :prod})
        active (set (keys (:active prod)))]

    (testing "the cache is opt-in"
      ;; Moving it to :active turns the prod default into a Redis dependency
      ;; for deployments that never provisioned one.
      (is (not (contains? active :wagoe/cache)))
      (is (empty? (filter #(re-find #"cache" (str %))
                          (keys (sys-config/ig-config prod))))
          "and no cache component is built"))

    (testing "so is every other optional module"
      (doseq [k [:wagoe/workflow :wagoe/search :wagoe/payments :wagoe/admin]]
        (is (not (contains? active k)) (str k " must stay opt-in in prod"))))

    (testing "and the components a deployment already had are still there"
      ;; The other half: this must fail if something was removed from :active
      ;; as well as if something was added.
      (doseq [k [:wagoe/postgresql :wagoe/logging :wagoe/error-reporting :wagoe/http]]
        (is (contains? active k) (str k " disappeared from the prod profile"))))))

(deftest ^:integration conditional-keys-register-themselves
  ;; The rule stated in `ig-config`'s docstring: a key emitted only when it is
  ;; configured must have its wiring required at the same moment, so that a
  ;; caller who is not the entry point still gets a usable component.
  ;; Unconditional keys are the entry point's job — asserted separately in
  ;; wagoe.main-test.
  (require 'wagoe.config :reload)
  (let [emitted    (set (keys (sys-config/ig-config (sut/load-config {:profile :test}))))
        registered (set (keys (methods ig/init-key)))
        conditional (filter emitted [:wagoe/events :wagoe/email :wagoe/cache
                                     :wagoe/payment-provider :wagoe/i18n])]

    (testing "the profile builds some conditional keys, or this proves nothing"
      (is (seq conditional)))

    (testing "each is registered by loading wagoe.config alone"
      (doseq [k conditional]
        (is (contains? registered k)
            (str k " is emitted conditionally but its wiring is not required "
                 "where it is emitted"))))))

(deftest ^:integration every-emitted-key-has-an-init-key
  ;; The generated config used to enumerate 41 Integrant keys and separately
  ;; require the wiring that registered each one. Forgetting one half produced
  ;; "No method in multimethod 'init-key' for dispatch value", at boot, in a
  ;; user's project — so a regex test in wagoe-cli paired the two by reading the
  ;; template. Both halves now live in the module's own library, and this is the
  ;; check that replaces it: run the real assembler over a config with every
  ;; framework module enabled, and ask Integrant whether it could build each key.
  (let [everything (reduce (fn [c k] (assoc-in c [:active k] {:enabled? true}))
                           (base-config)
                           (keys modules/framework-modules))
        ig-config  (sys-config/ig-config everything)
        missing    (remove #(contains? (methods ig/init-key) %) (keys ig-config))]

    (testing "the modules really are built here — otherwise this is vacuous"
      (is (< 40 (count ig-config))
          (str "only " (count ig-config) " keys; the modules did not assemble"))
      (is (contains? ig-config :wagoe/tenant-service))
      (is (contains? ig-config :wagoe/workflow-db-schema)))

    (testing "and every one of them can be initialised"
      (is (empty? missing)
          (str "no init-key registered for: " (pr-str (sort missing))
               " — the library that emits a key must also register it")))))
