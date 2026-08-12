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
                   (assoc-in [:active :wagoe/rpc] {:port 3821
                                                   :service-key service-key}))
        [ig-config summary] (main/service-ig-config config #{:payments})]

    (testing "the RPC endpoint is part of the service"
      (is (true? (:rpc summary)))
      (is (contains? ig-config :wagoe/rpc-server))
      (is (= (ig/ref :wagoe/payment-provider)
             (get-in ig-config [:wagoe/rpc-server :implementation]))))

    (testing "and a caller with only the protocol gets an answer from it"
      (with-system ig-config
        (fn [_]
          (let [payments (rpc-client/remote-adapter
                          pay-ports/IPaymentProvider
                          "http://localhost:3821"
                          {:retries 0 :service-key service-key})]
            (is (satisfies? pay-ports/IPaymentProvider payments))
            (is (keyword? (pay-ports/provider-name payments))
                "answered by the provider in the service process")))))))
