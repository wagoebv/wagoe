(ns wagoe.platform.shell.system.config-test
  "What `system-config` assembles, for a config with no module libraries behind
   it.

   These use the framework's own keys but never load a module: `:wagoe/storage`
   and friends have no `ig-config`, so the assembler passes their settings
   through, and that is enough to check the parts this namespace owns — the core
   components, the HTTP handler's inputs, and scaffolded-module discovery."
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [wagoe.platform.shell.system.config :as sut]))

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
