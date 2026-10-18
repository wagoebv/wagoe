(ns wagoe.platform.shell.system.config-test
  "What `system-config` assembles, for a config with no module libraries behind
   it.

   These use the framework's own keys but never load a module: `:wagoe/storage`
   and friends have no `ig-config`, so the assembler passes their settings
   through, and that is enough to check the parts this namespace owns — the core
   components, the HTTP handler's inputs, and scaffolded-module discovery."
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [wagoe.platform.ports.privacy :as privacy]
            [wagoe.platform.shell.system.config :as sut]
            [wagoe.platform.shell.system.wiring]))

(defn- config
  "A loaded config with a database and whatever else the caller adds."
  [& kvs]
  {:wagoe/profile :test
   :active (into {:wagoe/settings {:name "test"}
                  :wagoe/h2       {:memory true}}
                 (partition-all 2)
                 kvs)})

(deftest ^:unit the-components-every-application-has
  (let [c (sut/system-config (config))]
    (is (every? (set (keys c))
                [:wagoe/settings :wagoe/db-context :wagoe/logging :wagoe/metrics
                 :wagoe/tracing :wagoe/error-reporting :wagoe/router
                 :wagoe/http-handler :wagoe/http-server]))

    (testing "the database spec comes from whichever adapter is active"
      (is (= :h2 (:adapter (:wagoe/db-context c))))
      (is (= "mem:wagoe;DB_CLOSE_DELAY=-1" (:database-path (:wagoe/db-context c)))))

    (testing "and observability defaults to inert rather than absent"
      (is (= {:provider :no-op} (:wagoe/logging c)))
      (is (= {:provider :no-op} (:wagoe/tracing c))))))

(deftest ^:unit a-scaffolded-module-is-wired-and-its-routes-reach-the-handler
  ;; Two halves of one defect. `bb scaffold generate tasks` produced a module
  ;; the config enumeration had never heard of, so it was skipped in silence
  ;; (BOU-311). Discovering it is not enough: nothing mounted its routes, so
  ;; /api/v1/tasks was a 404 while `bb quickstart` reported 8/8 Done (BOU-312).
  (let [c (sut/system-config (config :wagoe/tasks {:enabled? true})
                             {:base-ns "acme"})]

    (testing "the module's four components are built"
      (is (every? (set (keys c))
                  [:wagoe/tasks :wagoe/tasks-repository
                   :wagoe/tasks-service :wagoe/tasks-routes])))

    (testing "and the handler is handed its routes, as a collection"
      (is (= [(ig/ref :wagoe/tasks-routes)]
             (get-in c [:wagoe/http-handler :module-routes]))))))

(deftest ^:unit a-framework-module-is-not-mistaken-for-a-scaffolded-one
  ;; Discovery treats any `:wagoe/<name>` carrying `:enabled?` as scaffolded.
  ;; A framework key is one of those, so without the known-keys filter the
  ;; assembler would build both graphs for it — a `:wagoe/storage-repository`
  ;; nothing defines.
  (let [c (sut/system-config (config :wagoe/storage {:enabled? true :provider :local}))]
    (is (contains? c :wagoe/storage))
    (is (not (contains? c :wagoe/storage-repository)))
    ;; Empty: storage assembles a routes component since BOU-421 but mounts it
    ;; only on `:expose-http? true`, because those endpoints carry no auth.
    (is (empty? (get-in c [:wagoe/http-handler :module-routes])))))

(deftest ^:unit storage-mounts-its-routes-only-when-asked
  ;; The opt-in half of the above: with `:expose-http?` the module's routes do
  ;; reach the handler, through the same collection every module uses.
  (let [c (sut/system-config (config :wagoe/storage {:enabled? true
                                                     :provider :local
                                                     :expose-http? true}))]
    (is (= [(ig/ref :wagoe/storage-routes)]
           (get-in c [:wagoe/http-handler :module-routes])))))

(deftest ^:unit a-core-config-key-is-not-mistaken-for-a-module
  ;; :wagoe/http is a port number, not a module. Reading every :wagoe/* key as
  ;; one made the boot demand a wagoe.http library.
  (let [c (sut/system-config (config :wagoe/http {:port 4000 :enabled? true}))]
    (is (= 4000 (:port (:wagoe/http-server c))))
    (is (not (contains? c :wagoe/http)))))

(deftest ^:unit a-key-nothing-will-assemble-fails-the-boot
  ;; `:wagoe/dashboard {:port 9999}` produced no component and no message: it
  ;; is not a core key, no module claimed it, and without `:enabled?` it was
  ;; not discovered either, so it fell through every branch (BOU-477). The
  ;; silence is the defect — a misspelled module key looks exactly the same.
  (testing "the key is named, and so is each way out"
    (let [e (is (thrown-with-msg?
                 clojure.lang.ExceptionInfo #":wagoe/dashbaord"
                 (sut/system-config (config :wagoe/dashbaord {:port 9999}))))]
      (is (= :wagoe/unclaimed-config-keys (:type (ex-data e))))
      (is (= [:wagoe/dashbaord] (:keys (ex-data e))))))

  (testing "a module says so with :enabled?, and is looked for by name"
    ;; Not this check's error any more: it reaches module discovery, which
    ;; reports the missing wiring namespace.
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"No wiring for module"
         (sut/system-config (config :wagoe/dashbaord {:enabled? true})))))

  (testing "an application names its own settings blocks and is left alone"
    (let [c (sut/system-config (config :wagoe/config-keys #{:wagoe/billing-limits}
                                       :wagoe/billing-limits {:max 10}))]
      (is (not (contains? c :wagoe/billing-limits))
          "declared settings are read by their owner, not assembled here")))

  (testing "and the framework's own settings blocks need no declaring"
    ;; Each is read with `get-in` by a component wired under another key.
    (doseq [k [:wagoe/api-versioning :wagoe/pagination :wagoe/session-pruner
               :wagoe/rpc :wagoe/services]]
      (is (map? (sut/system-config (config k {}))) (str k " fails the boot")))))

(deftest ^:unit the-handler-and-the-server-agree-on-the-port
  ;; The server takes the http settings twice — once as its own config and once
  ;; nested — because the graceful-drain path reads the second.
  (let [c (sut/system-config (config :wagoe/http {:port 4100 :host "127.0.0.1"}))]
    (is (= 4100 (:port (:wagoe/http-server c))))
    (is (= 4100 (get-in c [:wagoe/http-server :config :port])))
    (is (= (ig/ref :wagoe/http-handler) (:handler (:wagoe/http-server c))))))

(deftest ^:unit the-request-body-limit-reaches-the-server
  ;; Absent, the server applies its default; nil lifts it (BOU-644).
  (let [server #(:wagoe/http-server (sut/system-config (config :wagoe/http %)))]
    (is (= 1024 (:max-request-body (server {:max-request-body 1024}))))
    (is (contains? (server {:max-request-body nil}) :max-request-body))
    (is (not (contains? (server {}) :max-request-body)))))

(deftest ^:unit the-virtual-threads-flag-reaches-the-server
  (let [server #(:wagoe/http-server (sut/system-config (config :wagoe/http %)))]
    (is (true? (:virtual-threads? (server {:virtual-threads? true}))))
    (is (false? (:virtual-threads? (server {}))))))

(deftest ^:unit the-server-knows-the-boot-profile
  ;; The port strategy decides by profile; without it a :prod boot searched
  ;; ports like dev (BOU-566).
  (let [c (sut/system-config (assoc (config) :wagoe/profile :prod))]
    (is (= :prod (get-in c [:wagoe/http-server :config :profile])))))

(deftest ^:unit request-capture-is-supplied-and-dev-only
  ;; The handler has always read :request-capture?; nothing supplied it, so the
  ;; dashboard's Request Inspector could not capture in any app (BOU-506).
  (testing "the dev profile switches request capture on"
    (let [c (sut/system-config (assoc (config) :wagoe/profile :dev))]
      (is (true? (:request-capture? (:wagoe/http-handler c))))))

  (testing "every other profile leaves it off"
    (doseq [profile [:test :acc :prod]]
      (let [c (sut/system-config (assoc (config) :wagoe/profile profile))]
        (is (false? (:request-capture? (:wagoe/http-handler c)))
            (str profile " must not capture requests"))))))

(deftest ^:integration an-app-modules-middleware-reaches-the-handler
  ;; An application module had no way to add middleware: wiring it meant
  ;; editing system_config.clj, and adding its one component meant restating
  ;; the four keys discovery builds.
  (let [c (sut/system-config (config :wagoe/guards {:enabled? true}) {:base-ns "acme"})]
    (testing "its ig-config names one component, and the scaffold keys are still built"
      (is (every? (set (keys c))
                  [:wagoe/guards :wagoe/guards-repository :wagoe/guards-service
                   :wagoe/guards-routes :wagoe/guards-http-middleware]))
      (is (= {:ctx (ig/ref :wagoe/db-context)} (:wagoe/guards-repository c))))

    (testing "its :extra-middleware is handed to the handler"
      (is (= [(ig/ref :wagoe/guards-http-middleware)]
             (get-in c [:wagoe/http-handler :module-middleware]))))

    (testing "and runs on a request"
      (let [system (ig/init c [:wagoe/http-handler])]
        (try
          (is (= "on" (get-in ((:wagoe/http-handler system)
                               {:request-method :get :uri "/health" :headers {}})
                              [:headers "X-Guard"])))
          (finally (ig/halt! system)))))))

(deftest ^:unit data-export-is-always-there-and-retention-only-when-configured
  (let [c (sut/system-config (config))]
    (is (= {:contributed {}} (:wagoe/data-export c)))
    (is (not (contains? c :wagoe/retention))))

  (testing "a module's exporter reaches the export"
    (let [c      (sut/system-config (config :wagoe/archive {:enabled? true}) {:base-ns "acme"})
          system (ig/init c [:wagoe/data-export])]
      (try
        (is (= {:archive {:archived-for 7}}
               (:sections (privacy/export-subject (:wagoe/data-export system) {:type :user :id 7}))))
        (finally (ig/halt! system)))))

  (testing "a configured policy runs as a recurring job"
    (let [c (sut/system-config (config :wagoe/jobs {}
                                       :wagoe/archive {:enabled? true}
                                       :wagoe/privacy {:retention {:archive/old {:retain "P2Y"}}
                                                       :retention-schedule {:zone "Europe/Amsterdam"}})
                               {:base-ns "acme"})]
      (is (= {:policies    {:archive/old {:retain "P2Y"}}
              :contributed {:archive/old      (ig/ref :wagoe/archive-retention)
                            :jobs/dead-letter (ig/ref :wagoe/job-dead-letter-retention)}}
             (:wagoe/retention c)))
      (is (some #{(ig/ref :wagoe/retention)} (get-in c [:wagoe/job-registry :handler-maps])))
      (is (= {:cron "0 1 * * *"}
             (get-in (sut/system-config (config :wagoe/jobs {:recurring {:wagoe/retention {:cron "0 1 * * *"}}}
                                                :wagoe/archive {:enabled? true}
                                                :wagoe/privacy {:retention {:archive/old {:retain "P2Y"}}})
                                        {:base-ns "acme"})
                     [:wagoe/job-scheduler :recurring :wagoe/retention]))
          "a schedule under :wagoe/jobs wins")
      (is (= {:cron "0 3 * * *" :zone "Europe/Amsterdam"}
             (get-in c [:wagoe/job-scheduler :recurring :wagoe/retention])))))

  (doseq [[why settings] {"a policy no module provides" {:retention {:archive/olf {:retain "P2Y"}}}
                          "a period that is not one"    {:retention {:archive/old {:retain "2 years"}}}
                          "a misspelled key"            {:retension {:archive/old {:retain "P2Y"}}}
                          "a retention that is no map"  {:retention "P2Y"}}]
    (testing (str why " fails the boot")
      (is (= :configuration-error
             (:type (ex-data (is (thrown? clojure.lang.ExceptionInfo
                                          (sut/system-config (config :wagoe/jobs {} :wagoe/archive {:enabled? true}
                                                                     :wagoe/privacy settings)
                                                             {:base-ns "acme"})))))))))

  (testing "and retention is refused without the jobs module to run it"
    (is (= :configuration-error
           (:type (ex-data (is (thrown? clojure.lang.ExceptionInfo
                                        (sut/system-config
                                         (config :wagoe/privacy {:retention {:user/audit-log {:retain "P2Y"}}}))))))))))
