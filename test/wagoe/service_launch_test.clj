(ns wagoe.service-launch-test
  "Service launch mode against the real configuration (BOU-91).

   The pure selection logic is tested in
   `wagoe.platform.core.system-selection-test` against a small hand-built
   config. That proves the algorithm; it says nothing about whether this
   application's components tolerate losing the inputs it takes away from
   them. Only booting the real thing shows that, and the acceptance criterion —
   absent modules' routes not mounted — is about a running router."
  (:require [wagoe.config :as config]
            [wagoe.main :as main]
            [wagoe.platform.core.system-selection :as selection]
            [wagoe.payments.ports :as pay-ports]
            [wagoe.platform.shell.rpc.client :as rpc-client]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]))

(def ^:private service-key
  "Long enough for the RPC handler, which refuses anything shorter."
  "service-launch-test-key-at-least-32-chars")

(defn- test-config [] (config/load-config {:profile :test}))

(defn- with-system
  "Boot `ig-config`, call `f` with the started system, halt it."
  [ig-config f]
  (let [system (ig/init ig-config)]
    (try (f system) (finally (ig/halt! system)))))

(deftest ^:integration a-service-runs-fewer-components-than-the-application
  (let [config     (test-config)
        full       (config/ig-config config)
        [user-cfg summary] (main/service-ig-config config #{:user})]

    (testing "it is genuinely smaller"
      ;; The failure this guards is a selection that silently keeps everything:
      ;; it boots, it is healthy, and it is not a service.
      (is (< (count user-cfg) (count full)))
      (is (= (count user-cfg) (:running summary)))
      (is (= (count full) (:available summary))))

    (testing "the selected module is present"
      (is (contains? user-cfg :wagoe/user-service))
      (is (contains? user-cfg :wagoe/user-routes)))

    (testing "the platform it needs comes with it"
      (is (contains? user-cfg :wagoe/db-context))
      (is (contains? user-cfg :wagoe/logging))
      (is (contains? user-cfg :wagoe/router))
      (is (contains? user-cfg :wagoe/http-handler))
      (is (contains? user-cfg :wagoe/http-server)))

    (testing "and the modules it does not need do not"
      (is (not (contains? user-cfg :wagoe/tenant-service)))
      (is (not (contains? user-cfg :wagoe/membership-service)))
      (is (not (contains? user-cfg :wagoe/admin-service)))
      (is (not (contains? user-cfg :wagoe/payment-provider))))

    (testing "the summary names what was left out"
      (is (= ["user"] (:services summary)))
      (is (some #{":wagoe/tenant-service"} (:omitted summary))))))

(deftest ^:integration nothing-in-a-service-config-points-at-a-key-it-dropped
  ;; Integrant refuses a config with a dangling ref, so this is the difference
  ;; between a service that starts and one that dies at boot — and it has to
  ;; hold for every module, not just the one that happened to be tried.
  (let [config    (test-config)
        catalogue (config/service-catalogue config)
        full      (config/ig-config config)
        buildable (remove #(selection/selection-problem catalogue full #{%})
                          (keys catalogue))]
    (is (seq buildable) "the test profile must build at least one service")
    (doseq [service buildable]
      (let [[cfg _] (main/service-ig-config config #{service})
            present (set (keys cfg))
            dangling (->> cfg vals
                          (mapcat #(tree-seq coll? seq %))
                          (filter ig/ref?)
                          (map :key)
                          (remove present)
                          set)]
        (is (empty? dangling) (str "dangling refs running " service ": " dangling))))))

(deftest ^:integration a-user-service-serves-its-own-routes-and-not-the-others
  ;; The acceptance criterion, against a running router.
  (let [config (test-config)
        [ig-config _] (main/service-ig-config config #{:user})]
    (with-system (dissoc ig-config :wagoe/http-server)   ; no need to bind a port
      (fn [system]
        (let [handler (:wagoe/http-handler system)
              status  (fn [uri] (:status (handler {:request-method :get
                                                   :uri uri
                                                   :headers {}})))]

          (testing "health is up"
            (is (= 200 (status "/health"))))

          (testing "the module's own routes are mounted"
            ;; Not asserting on the exact status — a login page may redirect —
            ;; only that the router knows the path.
            (is (not= 404 (status "/web/login"))))

          (testing "and an absent module's routes are not"
            (is (= 404 (status "/api/v1/tenants")))
            (is (= 404 (status "/web/admin/")))))))))

(deftest ^:integration the-application-itself-still-boots-unchanged
  ;; The whole point of deriving core keys from the catalogue is that a full
  ;; boot is unaffected. If selecting every service is not the identity, the
  ;; catalogue and the config have drifted.
  (let [config    (test-config)
        catalogue (config/service-catalogue config)
        full      (config/ig-config config)]
    (is (= full (selection/service-config full catalogue (set (keys catalogue)))))))

(deftest ^:integration the-shipped-catalogue-is-well-formed
  (let [catalogue (config/service-catalogue (test-config))]
    (is (nil? (selection/catalogue-problem catalogue)))

    (testing "and every module the test profile builds can be selected"
      ;; Optional modules are off in this profile and are meant to be
      ;; unselectable here; the ones that are built must work.
      (let [full (config/ig-config (test-config))
            buildable (remove #(selection/selection-problem catalogue full #{%})
                              (keys catalogue))]
        (is (contains? (set buildable) :user))
        (is (contains? (set buildable) :tenant))))))

(deftest ^:integration an-operator-error-is-refused-with-something-readable
  (let [config (test-config)]
    (testing "a module nobody has heard of"
      (let [e (is (thrown? clojure.lang.ExceptionInfo
                           (main/service-ig-config config #{:reporting})))]
        (is (re-find #"Unknown service" (ex-message e)))
        (is (re-find #"user" (ex-message e)) "and lists what it could have been")
        (is (= :configuration-error (:type (ex-data e))))))

    (testing "no module at all"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No service named"
                            (main/service-ig-config config #{}))))))

(deftest ^:integration a-service-without-rpc-config-says-it-is-unreachable
  ;; Booting a module alone with nothing able to call it is a working process
  ;; that does no work. The summary carries the fact so the boot can say so.
  (let [config (test-config)
        [ig-config summary] (main/service-ig-config config #{:user})]
    (is (false? (:rpc summary)))
    (is (not (contains? ig-config :wagoe/rpc-server)))))

(deftest ^:integration a-module-booted-as-a-service-can-be-called-from-outside
  ;; BOU-90 left this criterion open: the adapter was proven over a real socket
  ;; but inside one JVM, because nothing could boot payments on its own. This
  ;; is the two halves meeting — the module runs as its own selected subset,
  ;; and a caller holding only the protocol reaches it over HTTP.
  (let [config (-> (test-config)
                   ;; Port 0: the OS picks a free one and the started server is
                   ;; asked which. A fixed port fails on a machine that happens
                   ;; to have it bound, or when two runs overlap — and the
                   ;; failure looks like the RPC layer being broken rather than
                   ;; the port being taken. (Reintroduced here after being
                   ;; fixed in BOU-90's own tests; same mistake, same fix.)
                   (assoc-in [:active :wagoe/rpc] {:port 0
                                                   :service-key service-key}))
        [ig-config summary] (main/service-ig-config config #{:payments})]

    (testing "the RPC endpoint is part of the service"
      (is (true? (:rpc summary)))
      (is (contains? ig-config :wagoe/rpc-server))
      (is (= (ig/ref :wagoe/payment-provider)
             (get-in ig-config [:wagoe/rpc-server :implementation]))))

    (testing "and a caller with only the protocol gets an answer from it"
      ;; Without :wagoe/http-server. This test is about the RPC listener, and
      ;; the application's HTTP port comes from a shared range — two suites
      ;; running at once, or a dev server left up, and the boot fails on a port
      ;; that has nothing to do with what is being tested. The RPC listener
      ;; binds port 0 and is asked which it got.
      (with-system (dissoc ig-config :wagoe/http-server)
        (fn [system]
          (let [port     (.getLocalPort
                          ^org.eclipse.jetty.server.ServerConnector
                          (first (.getConnectors (:wagoe/rpc-server system))))
                payments (rpc-client/remote-adapter
                          pay-ports/IPaymentProvider
                          (str "http://localhost:" port)
                          {:retries 0 :service-key service-key})]
            (is (satisfies? pay-ports/IPaymentProvider payments))
            (is (keyword? (pay-ports/provider-name payments))
                "answered by the provider in the service process")))))))

;; =============================================================================
;; Every key the config emits must be accounted for
;; =============================================================================

(def ^:private platform-keys
  "Components every service runs, whichever module it is.

   Listed rather than derived, because \"derived\" is what went wrong: the
   catalogue named `:wagoe/workflow-service`, the config emits `:wagoe/workflow`,
   and an unclaimed key counts as platform — so the workflow engine ran inside
   every service, and nothing said anything. Naming these here means a module
   component the catalogue misses is a test failure instead of a silent
   passenger."
  #{:wagoe/db-context :wagoe/logging :wagoe/metrics :wagoe/tracing
    :wagoe/error-reporting :wagoe/router :wagoe/email :wagoe/cache
    :wagoe/i18n :wagoe/i18n-http-middleware
    ;; The event bus is infrastructure: every service that runs needs one,
    ;; whichever module it is running.
    :wagoe/events
    :wagoe/http-handler :wagoe/http-server :wagoe/dashboard
    ;; Emitted only when running as a service, and by definition part of it.
    :wagoe/rpc-server})

(defn- everything-enabled-config
  "The test profile with every optional module switched on.

   Without this the check is worthless for exactly the modules that broke it:
   workflow, search and payments are off in this profile, so their keys are
   never emitted and a wrong name in the catalogue is invisible."
  []
  (-> (test-config)
      (assoc-in [:active :wagoe/workflow] {:enabled? true})
      (assoc-in [:active :wagoe/search] {:enabled? true})
      (assoc-in [:active :wagoe/admin] {:enabled? true})))

(deftest ^:integration no-module-component-is-mistaken-for-the-platform
  (let [config    (everything-enabled-config)
        full      (config/ig-config config)
        catalogue (config/service-catalogue config)
        claimed   (selection/owned-keys catalogue)
        unclaimed (remove (some-fn claimed platform-keys) (keys full))]

    (testing "the optional modules really are built here"
      ;; Otherwise this passes by having nothing to check.
      (is (contains? full :wagoe/workflow))
      (is (contains? full :wagoe/search))
      (is (contains? full :wagoe/admin-service)))

    (testing "and every component belongs to a module or to the platform"
      (is (empty? unclaimed)
          (str "unclaimed components run in every service: " (pr-str (sort unclaimed)))))))

(deftest ^:integration an-optional-module-does-not-follow-other-services-around
  ;; The specific consequence of the wrong names: with workflow enabled,
  ;; `service user` started the workflow engine too.
  (let [config (everything-enabled-config)
        [user-cfg _] (main/service-ig-config config #{:user})]
    (is (not (contains? user-cfg :wagoe/workflow)))
    (is (not (contains? user-cfg :wagoe/workflow-routes)))
    (is (not (contains? user-cfg :wagoe/search)))
    (is (not (contains? user-cfg :wagoe/admin-service)))

    (testing "and it is still selectable in its own right"
      (let [[wf-cfg _] (main/service-ig-config config #{:workflow})]
        (is (contains? wf-cfg :wagoe/workflow))
        (is (contains? wf-cfg :wagoe/workflow-routes))
        (is (not (contains? wf-cfg :wagoe/search)))))))

(deftest ^:integration a-catalogue-override-is-read-wherever-it-was-written
  ;; Everything else in these files lives under :active, so that is where an
  ;; application will put this. Reading only the top level meant the override
  ;; was ignored and `service my-module` answered "unknown service", which
  ;; reads as a typo rather than a config that was never consulted.
  (let [entry {:my-module {:keys [:wagoe/user-service]}}]

    (testing "under :active"
      (let [catalogue (config/service-catalogue
                       (assoc-in (test-config) [:active :wagoe/services] entry))]
        (is (contains? catalogue :my-module))))

    (testing "at the top level"
      (let [catalogue (config/service-catalogue
                       (assoc (test-config) :wagoe/services entry))]
        (is (contains? catalogue :my-module))))

    (testing "and either way the framework's own modules survive"
      (let [catalogue (config/service-catalogue
                       (assoc-in (test-config) [:active :wagoe/services] entry))]
        (is (contains? catalogue :user))
        (is (contains? catalogue :payments))))

    (testing "an override replaces the entry of the same name outright"
      ;; Merging into it would leave an application that split a module up with
      ;; the framework's idea of its keys as well as its own.
      (let [catalogue (config/service-catalogue
                       (assoc-in (test-config) [:active :wagoe/services]
                                 {:user {:keys [:wagoe/user-service]}}))]
        (is (= [:wagoe/user-service] (get-in catalogue [:user :keys])))))))

(deftest ^:integration two-rpc-capable-modules-in-one-process-are-refused
  ;; One listener serves one protocol. Taking the first of several and carrying
  ;; on leaves the rest reachable by nobody — a healthy process, nothing in the
  ;; log, and a caller that times out against a service that is running.
  ;;
  ;; Reachable in practice since `user` gained an :rpc entry: `service user
  ;; payments` is a plausible thing to type.
  (let [config    (-> (test-config)
                      (assoc-in [:active :wagoe/rpc] {:port 0 :service-key service-key})
                      ;; Both offer a protocol, and both are built here.
                      (assoc-in [:active :wagoe/services]
                                {:alpha {:keys [:wagoe/user-service]
                                         :rpc  {:protocol  'wagoe.user.ports/IUserService
                                                :component :wagoe/user-service}}
                                 :beta  {:keys [:wagoe/tenant-service]
                                         :rpc  {:protocol  'wagoe.tenant.ports/ITenantService
                                                :component :wagoe/tenant-service}}}))]

    (testing "it is refused, naming both"
      (let [e (is (thrown? clojure.lang.ExceptionInfo
                           (main/service-ig-config config #{:alpha :beta})))]
        (is (re-find #"alpha, beta" (ex-message e)))
        (is (re-find #"one process serves one" (ex-message e)))
        (is (= :configuration-error (:type (ex-data e))))))

    (testing "and the message says what to do instead"
      (is (re-find #"separate services"
                   (try (main/service-ig-config config #{:alpha :beta}) ""
                        (catch clojure.lang.ExceptionInfo e (ex-message e))))))

    (testing "either one alone is fine"
      (is (true? (:rpc (second (main/service-ig-config config #{:alpha})))))
      (is (true? (:rpc (second (main/service-ig-config config #{:beta}))))))

    (testing "and two modules where only one offers a protocol is fine"
      ;; The ordinary co-location case must keep working: `service user tenant`
      ;; where tenant offers nothing.
      (let [cfg (assoc-in config [:active :wagoe/services :beta] {:keys [:wagoe/tenant-service]})
            [ig-config summary] (main/service-ig-config cfg #{:alpha :beta})]
        (is (true? (:rpc summary)))
        (is (contains? ig-config :wagoe/rpc-server))))

    (testing "and without :wagoe/rpc configured, two of them is not an error"
      ;; Nothing is exposed either way, so there is nothing ambiguous to refuse.
      (let [cfg (update config :active dissoc :wagoe/rpc)
            [_ summary] (main/service-ig-config cfg #{:alpha :beta})]
        (is (false? (:rpc summary)))))))
