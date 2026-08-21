(ns wagoe.platform.shell.modules-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [wagoe.platform.shell.modules :as sut]))

;; =============================================================================
;; Scaffolded module discovery (BOU-311)
;; =============================================================================

(deftest ^:unit discovery-wires-a-module-the-fixed-list-never-heard-of
  ;; `bb scaffold generate tasks` then adding :wagoe/tasks to config produced
  ;; nothing: ig-config built from a fixed list of known keys, so the key was
  ;; skipped without an init-key, a route, or a word about it. Quickstart
  ;; reported 8/8 and left dead code behind.
  (let [entries (sut/discover-module-config
                 {:wagoe/tasks {:enabled? true}}
                 #{}
                 "acme"
                 (constantly true))]

    (testing "the four keys the scaffolder generates are emitted"
      (is (= #{:wagoe/tasks-repository :wagoe/tasks-service
               :wagoe/tasks-routes :wagoe/tasks}
             (set (keys entries)))))

    (testing "and they are wired to each other, not left dangling"
      (is (= (ig/ref :wagoe/db-context) (:ctx (:wagoe/tasks-repository entries))))
      (is (= (ig/ref :wagoe/tasks-repository) (:repository (:wagoe/tasks-service entries))))
      (is (= (ig/ref :wagoe/tasks-service) (:service (:wagoe/tasks-routes entries))))
      (is (= (ig/ref :wagoe/tasks-routes) (:routes (:wagoe/tasks entries)))))))

(deftest ^:unit discovery-leaves-the-frameworks-own-keys-alone
  ;; The framework's modules have bespoke wiring and names that do not follow
  ;; the convention — :wagoe/payment-provider, :wagoe.external/smtp. Discovery
  ;; must not try to wire them a second time.
  (testing "a key the fixed list already handles is skipped"
    (is (empty? (sut/discover-module-config
                 {:wagoe/cache {:provider :in-memory}}
                 #{:wagoe/cache}
                 "acme"
                 (constantly true)))))

  (testing "a namespaced key that is not :wagoe/<module> is not a module"
    (is (empty? (sut/discover-module-config
                 {:wagoe.external/smtp {:host "x"}}
                 #{}
                 "acme"
                 (constantly true)))))

  (testing "settings and profile are configuration, not modules"
    (is (empty? (sut/discover-module-config
                 {:wagoe/settings {:name "app"} :wagoe/profile :dev}
                 #{}
                 "acme"
                 (constantly true))))))

(deftest ^:unit an-active-key-with-no-wiring-fails-loudly
  ;; The silent skip is the defect. A typo in a key name has to be visible —
  ;; :active/:inactive is easy to get wrong and produced no signal at all.
  (let [e (try (sut/discover-module-config
                {:wagoe/tsaks {:enabled? true}}
                #{}
                "acme"
                (constantly false))
               nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (some? e) "an unresolvable module key must throw")
    (testing "the message names the key, the namespace it looked for, and the fix"
      (is (str/includes? (ex-message e) ":wagoe/tsaks"))
      (is (str/includes? (ex-message e) "acme.tsaks.shell.module-wiring")))
    (testing "and carries a BND code so the error catalogue can explain it"
      (is (= :wagoe/module-wiring-not-found (:type (ex-data e)))))))

(deftest ^:unit a-disabled-module-is-not-wired
  ;; :enabled? false is how you turn a module off without deleting its config.
  (is (empty? (sut/discover-module-config
               {:wagoe/tasks {:enabled? false}}
               #{}
               "acme"
               (constantly true)))))

(deftest ^:unit discovered-routes-reach-the-handler
  ;; Emitting the keys is half the job. :wagoe/http-handler destructures a fixed
  ;; list of *-routes keys — it cannot name a generated module — so a discovered
  ;; module initialised and served nothing until its routes arrived as a
  ;; collection on :module-routes.
  (testing "a ref per discovered module"
    (is (= [(ig/ref :wagoe/tasks-routes)]
           (sut/discovered-route-refs {:wagoe/tasks {:enabled? true}} #{}))))

  (testing "framework modules are not double-wired"
    (is (empty? (sut/discovered-route-refs {:wagoe/admin {:enabled? true}} #{:wagoe/admin}))))

  (testing "and a disabled module contributes none"
    (is (empty? (sut/discovered-route-refs {:wagoe/tasks {:enabled? false}} #{})))))

;; =============================================================================
;; Framework module assembly (BOU-326)
;; =============================================================================

(defn- assemble
  "Run the assembler against stub modules, so the test needs none on the
   classpath. `graphs` maps a wiring symbol to the map its `ig-config` returns;
   a symbol absent from it names a library that is not installed."
  ([active graphs] (assemble active graphs #{}))
  ([active graphs extra]
   (sut/framework-module-config
    active
    {:config {:active active} :validation-config {} :extra-modules extra}
    #(contains? graphs %)
    (fn [sym]
      (when-let [g (get graphs (symbol (namespace sym)))]
        (fn [settings ctx] (if (fn? g) (g settings ctx) g)))))))

(deftest ^:unit a-module-with-no-components-passes-its-settings-through
  ;; 13 of the framework's modules are a settings block and nothing else. They
  ;; define no ig-config, and requiring one from each would be ceremony.
  (let [{:keys [components]} (assemble {:wagoe/storage {:provider :s3}}
                                       {'wagoe.storage.shell.module-wiring nil})]
    (is (= {:wagoe/storage {:provider :s3}} components))))

(deftest ^:unit a-module-contributes-its-own-graph-and-its-http-refs
  (let [{:keys [components http]}
        (assemble {:wagoe/search {}}
                  {'wagoe.search.shell.module-wiring
                   {:components {:wagoe/search        {:db-ctx (ig/ref :wagoe/db-context)}
                                 :wagoe/search-routes {:search-service (ig/ref :wagoe/search)}}
                    :http       {:search-routes (ig/ref :wagoe/search-routes)}}})]
    (is (= #{:wagoe/search :wagoe/search-routes} (set (keys components))))

    (testing "the http-handler gets the ref, because it takes routes by name"
      (is (= {:search-routes (ig/ref :wagoe/search-routes)} http)))))

(deftest ^:unit a-module-sees-which-of-its-peers-are-on
  ;; user-service takes a cache ref only when there is a cache to ref; without
  ;; this the config carries a dangling ref and the boot fails.
  (let [with-cache (assemble {:wagoe/user {} :wagoe/cache {}}
                             {'wagoe.user.shell.module-wiring
                              (fn [_ ctx] {:components {:seen (:enabled ctx)}})
                              'wagoe.cache.shell.module-wiring nil})]
    (is (= #{:wagoe/user :wagoe/cache} (get-in with-cache [:components :seen])))))

(deftest ^:unit a-module-enabled-in-code-is-assembled-like-any-other
  ;; `wagoe new --no-user` writes a literal, not a config key: the user module
  ;; is the one an application switches on in code.
  (let [{:keys [components]} (assemble {}
                                       {'wagoe.user.shell.module-wiring
                                        {:components {:wagoe/user-service {}}}}
                                       #{:wagoe/user})]
    (is (= {:wagoe/user-service {}} components))))

(deftest ^:unit a-module-switched-off-is-not-assembled
  (let [{:keys [components]} (assemble {:wagoe/jobs {:enabled? false}}
                                       {'wagoe.jobs.shell.module-wiring nil})]
    (is (= {} components))))

(deftest ^:unit a-key-that-is-not-a-framework-module-is-left-alone
  ;; :wagoe/http is a port number and :wagoe/sqlite a database. Treating every
  ;; :wagoe/* key as a module made the boot demand a wagoe.h2 library.
  (let [{:keys [components]} (assemble {:wagoe/http {:port 3000} :wagoe/sqlite {:db "x"}} {})]
    (is (= {} components))))

(deftest ^:unit a-module-whose-library-is-missing-fails-the-boot
  ;; The alternative is a silent skip: the config asks for tenancy, the app
  ;; starts, and /api/v1/tenants is a 404 with nothing said.
  (let [e (is (thrown? clojure.lang.ExceptionInfo
                       (assemble {:wagoe/tenant {}} {})))]
    (is (= :wagoe/module-library-missing (:type (ex-data e))))
    (is (str/includes? (ex-message e) "wagoe.tenant.shell.module-wiring"))
    (testing "and it says both ways out"
      (is (str/includes? (ex-message e) "deps.edn"))
      (is (str/includes? (ex-message e) ":active")))))

(deftest ^:unit the-dev-error-enricher-is-the-one-module-allowed-to-be-absent
  ;; devtools lives in the :repl alias, so `clojure -M:run` against the dev
  ;; config has the key and not the jar. Failing the boot over a dev-only
  ;; nicety is worse than starting without it.
  (is (= {} (:components (assemble {:wagoe/dev-error-enricher {}} {}))))

  (testing "and when it is there, it wires and hands the handler its enricher"
    (let [{:keys [components http]}
          (assemble {:wagoe/dev-error-enricher {}}
                    {'wagoe.devtools.shell.module-wiring
                     {:components {:wagoe/dev-error-enricher {}}
                      :http       {:error-enricher (ig/ref :wagoe/dev-error-enricher)}}})]
      (is (contains? components :wagoe/dev-error-enricher))
      (is (contains? http :error-enricher)))))

(deftest ^:unit two-modules-claiming-one-key-fail-rather-than-overwrite
  ;; merge is last-write-wins, and the loser here is a component that silently
  ;; does not exist. Worth a boot failure naming the key.
  (let [e (is (thrown? clojure.lang.ExceptionInfo
                       (assemble {:wagoe/search {} :wagoe/reports {}}
                                 {'wagoe.search.shell.module-wiring
                                  {:components {:wagoe/index {}}}
                                  'wagoe.reports.shell.module-wiring
                                  {:components {:wagoe/index {}}}})))]
    (is (= :wagoe/module-key-conflict (:type (ex-data e))))
    (is (str/includes? (ex-message e) ":wagoe/index"))))

(deftest ^:unit every-framework-module-names-a-library-that-exists
  ;; The table is the one place a module is named. An entry naming a library
  ;; that is not published is a boot failure nobody sees until they enable it.
  (doseq [[k lib] sut/framework-modules]
    (is (string? lib) (str k " must name the library that owns it"))
    (is (not (str/blank? lib)))))

(deftest ^:unit only-the-enabled-modules-are-loaded
  ;; The whole point of assembling from config rather than from a static list:
  ;; a project that does not enable tenancy must not load — and so must not
  ;; ship — the tenant jar. Three tickets were spent moving these requires out
  ;; of platform and then out of wagoe.config; a mechanism that loads all of
  ;; them anyway would undo that quietly.
  (let [loaded (atom [])
        graphs {'wagoe.search.shell.module-wiring nil
                'wagoe.tenant.shell.module-wiring nil
                'wagoe.email.shell.module-wiring  nil
                'wagoe.i18n.shell.module-wiring   nil}]
    (sut/framework-module-config
     {:wagoe/search {} :wagoe/tenant {:enabled? false} :wagoe/http {:port 3000}}
     {:config {} :extra-modules sut/always-on-modules}
     (fn [sym] (swap! loaded conj sym) (contains? graphs sym))
     (constantly nil))

    (is (= ['wagoe.email.shell.module-wiring
            'wagoe.i18n.shell.module-wiring
            'wagoe.search.shell.module-wiring]
           (sort @loaded))
        "only the always-on modules and the one the config switched on")))
