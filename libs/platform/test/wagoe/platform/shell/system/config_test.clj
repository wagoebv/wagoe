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
    (is (empty? (get-in c [:wagoe/http-handler :module-routes])))))

(deftest ^:unit a-core-config-key-is-not-mistaken-for-a-module
  ;; :wagoe/http is a port number, not a module. Reading every :wagoe/* key as
  ;; one made the boot demand a wagoe.http library.
  (let [c (sut/system-config (config :wagoe/http {:port 4000 :enabled? true}))]
    (is (= 4000 (:port (:wagoe/http-server c))))
    (is (not (contains? c :wagoe/http)))))

(deftest ^:unit the-handler-and-the-server-agree-on-the-port
  ;; The server takes the http settings twice — once as its own config and once
  ;; nested — because the graceful-drain path reads the second.
  (let [c (sut/system-config (config :wagoe/http {:port 4100 :host "127.0.0.1"}))]
    (is (= 4100 (:port (:wagoe/http-server c))))
    (is (= 4100 (get-in c [:wagoe/http-server :config :port])))
    (is (= (ig/ref :wagoe/http-handler) (:handler (:wagoe/http-server c))))))
