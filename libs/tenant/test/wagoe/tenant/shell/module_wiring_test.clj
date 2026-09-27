(ns wagoe.tenant.shell.module-wiring-test
  (:require [wagoe.tenant.shell.module-wiring]
            [wagoe.tenant.shell.http]
            [wagoe.tenant.shell.membership-http]
            [wagoe.tenant.shell.persistence]
            [wagoe.tenant.shell.provisioning]
            [wagoe.tenant.shell.service]
            [wagoe.tenant.shell.membership-persistence]
            [wagoe.tenant.shell.membership-service]
            [wagoe.tenant.shell.invite-persistence]
            [wagoe.tenant.shell.invite-service]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [wagoe.platform.database :as db]
            [wagoe.platform.shell.adapters.database.factory :as factory]))

(deftest ^:unit tenant-module-init-keys-delegate-to-constructor-functions
  (let [ctx {:datasource ::ds}
        logger ::logger
        error-reporter ::error-reporter
        metrics ::metrics
        tenant-repo ::tenant-repo
        membership-repo ::membership-repo
        invite-repo ::invite-repo
        tenant-service ::tenant-service
        membership-service ::membership-service
        db-context {:datasource ::db}
        config {:active {:wagoe/settings {:name "Wagoe"}}}]
    (with-redefs [wagoe.tenant.shell.provisioning/refuse-unsupported-database! (fn [& _] nil)
                  wagoe.tenant.shell.persistence/initialize-tenant-schema! (fn [arg]
                                                                             (is (= ctx arg))
                                                                             :initialized)
                  wagoe.tenant.shell.persistence/create-tenant-repository (fn [arg log err]
                                                                            (is (= ctx arg))
                                                                            (is (= logger log))
                                                                            (is (= error-reporter err))
                                                                            tenant-repo)
                  wagoe.tenant.shell.service/create-tenant-service (fn [repo validation-cfg log metrics-emitter err]
                                                                     (is (= tenant-repo repo))
                                                                     (is (= {:password-policy {:min-length 12}} validation-cfg))
                                                                     (is (= logger log))
                                                                     (is (= metrics metrics-emitter))
                                                                     (is (= error-reporter err))
                                                                     tenant-service)
                  wagoe.tenant.shell.membership-persistence/create-membership-repository (fn [arg log err]
                                                                                           (is (= ctx arg))
                                                                                           (is (= logger log))
                                                                                           (is (= error-reporter err))
                                                                                           membership-repo)
                  wagoe.tenant.shell.membership-service/create-membership-service (fn [repo log metrics-emitter err]
                                                                                    (is (= membership-repo repo))
                                                                                    (is (= logger log))
                                                                                    (is (= metrics metrics-emitter))
                                                                                    (is (= error-reporter err))
                                                                                    membership-service)
                  wagoe.tenant.shell.invite-persistence/create-invite-repository (fn [arg log err]
                                                                                   (is (= ctx arg))
                                                                                   (is (= logger log))
                                                                                   (is (= error-reporter err))
                                                                                   invite-repo)
                  wagoe.tenant.shell.invite-service/create-invite-service (fn [repo membership log metrics-emitter err]
                                                                            (is (= invite-repo repo))
                                                                            (is (= membership-repo membership))
                                                                            (is (= logger log))
                                                                            (is (= metrics metrics-emitter))
                                                                            (is (= error-reporter err))
                                                                            ::invite-service)
                  wagoe.tenant.shell.http/tenant-routes (fn [service db-ctx cfg]
                                                          (is (= tenant-service service))
                                                          (is (= db-context db-ctx))
                                                          (is (= config cfg))
                                                          {:api [["/tenants" {}]]})
                  wagoe.tenant.shell.membership-http/membership-routes (fn [service]
                                                                         (is (= membership-service service))
                                                                         {:api [["/tenants/:tenant-id/memberships" {}]]})]
      (testing "schema, repositories, services, and routes initialize through their constructors"
        (is (= {:status :initialized}
               (ig/init-key :wagoe/tenant-db-schema {:ctx ctx})))
        (is (= tenant-repo
               (ig/init-key :wagoe/tenant-repository {:ctx ctx
                                                      :logger logger
                                                      :error-reporter error-reporter})))
        (is (= tenant-service
               (ig/init-key :wagoe/tenant-service {:tenant-repository tenant-repo
                                                   :validation-config {:password-policy {:min-length 12}}
                                                   :logger logger
                                                   :metrics-emitter metrics
                                                   :error-reporter error-reporter})))
        (is (= membership-repo
               (ig/init-key :wagoe/membership-repository {:ctx ctx
                                                          :logger logger
                                                          :error-reporter error-reporter})))
        (is (= membership-service
               (ig/init-key :wagoe/membership-service {:repository membership-repo
                                                       :logger logger
                                                       :metrics-emitter metrics
                                                       :error-reporter error-reporter})))
        (is (= invite-repo
               (ig/init-key :wagoe/invite-repository {:ctx ctx
                                                      :logger logger
                                                      :error-reporter error-reporter})))
        (is (= ::invite-service
               (ig/init-key :wagoe/invite-service {:repository invite-repo
                                                   :membership-repository membership-repo
                                                   :logger logger
                                                   :metrics-emitter metrics
                                                   :error-reporter error-reporter})))
        (is (= {:api [["/tenants" {}]]}
               (ig/init-key :wagoe/tenant-routes {:tenant-service tenant-service
                                                  :db-context db-context
                                                  :config config})))
        (is (= {:api [["/tenants/:tenant-id/memberships" {}]]}
               (ig/init-key :wagoe/membership-routes {:service membership-service})))))))

(deftest ^:unit tenant-http-middleware-builds-injectable-middleware-seq
  ;; BOU-200: platform's http-handler no longer requires the tenant lib; the
  ;; tenant module owns its middleware and the app injects it via :extra-middleware.
  ;; The entries are (fn [handler] ...) built lazily, so absent services simply
  ;; contribute nothing — building the seq must not invoke the wrap-* fns.
  (testing "both services present -> tenant then membership middleware (2 fns)"
    (let [mw (ig/init-key :wagoe/tenant-http-middleware
                          {:tenant-service ::ts :membership-service ::ms :db-context ::db})]
      (is (= 2 (count mw)))
      (is (every? fn? mw))))
  (testing "no services -> empty seq (platform pipeline gets no tenant middleware)"
    (is (empty? (ig/init-key :wagoe/tenant-http-middleware {}))))
  (testing "tenant needs db-context; membership stands alone"
    (is (empty? (ig/init-key :wagoe/tenant-http-middleware {:tenant-service ::ts})))
    (is (= 1 (count (ig/init-key :wagoe/tenant-http-middleware
                                 {:tenant-service ::ts :db-context ::db}))))
    (is (= 1 (count (ig/init-key :wagoe/tenant-http-middleware
                                 {:membership-service ::ms}))))))

(deftest ^:integration tenancy-refuses-to-boot-on-sqlite
  ;; Each tenant gets its own PostgreSQL schema. On SQLite a tenant row was
  ;; inserted, provisioning then failed, and the row was left behind (BOU-576).
  (let [path (str (System/getProperty "java.io.tmpdir") "/tenant-boot-" (System/nanoTime) ".db")
        ctx  (factory/db-context (factory/sqlite-config path))]
    (try
      (let [e (is (thrown? clojure.lang.ExceptionInfo
                           (ig/init-key :wagoe/tenant-db-schema {:ctx ctx})))]
        (is (= :not-supported (:type (ex-data e))))
        (is (str/includes? (ex-message e) "PostgreSQL"))
        (is (str/includes? (ex-message e) "SQLite")))
      (is (not (db/table-exists? ctx :tenants))
          "refused before creating anything")
      (finally
        (factory/close-db-context! ctx)
        (.delete (java.io.File. path))))))

(deftest ^:integration tenancy-on-h2-needs-the-test-profiles-say-so
  ;; H2 runs the test profile, with tenants but no per-tenant schemas. Anywhere
  ;; else it would be the same trap as SQLite, so it takes an explicit flag.
  (let [open #(factory/db-context
               (factory/h2-config (str "mem:tenant_boot_" (System/nanoTime) ";DB_CLOSE_DELAY=-1")))]
    (testing "without :allow-h2? it is refused like SQLite"
      (let [ctx (open)]
        (try
          (let [e (is (thrown? clojure.lang.ExceptionInfo
                               (ig/init-key :wagoe/tenant-db-schema {:ctx ctx})))]
            (is (= :not-supported (:type (ex-data e))))
            (is (str/includes? (ex-message e) "H2"))
            (is (str/includes? (ex-message e) ":allow-h2?")))
          (is (not (db/table-exists? ctx :tenants)))
          (finally (factory/close-db-context! ctx)))))
    (testing "with it, it boots"
      (let [ctx (open)]
        (try
          (is (= {:status :initialized}
                 (ig/init-key :wagoe/tenant-db-schema {:ctx ctx :allow-h2? true})))
          (is (db/table-exists? ctx :tenants))
          (finally (factory/close-db-context! ctx)))))
    (testing "the flag comes from the module's settings"
      (is (= true (get-in (wagoe.tenant.shell.module-wiring/ig-config {:allow-h2? true} {})
                          [:components :wagoe/tenant-db-schema :allow-h2?])))
      (is (not (get-in (wagoe.tenant.shell.module-wiring/ig-config {:enabled? true} {})
                       [:components :wagoe/tenant-db-schema :allow-h2?]))))))
