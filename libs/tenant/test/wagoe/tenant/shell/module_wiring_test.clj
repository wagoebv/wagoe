(ns wagoe.tenant.shell.module-wiring-test
  (:require [wagoe.tenant.shell.module-wiring]
            [wagoe.tenant.shell.http]
            [wagoe.tenant.shell.membership-http]
            [wagoe.tenant.shell.persistence]
            [wagoe.tenant.shell.service]
            [wagoe.tenant.shell.membership-persistence]
            [wagoe.tenant.shell.membership-service]
            [wagoe.tenant.shell.invite-persistence]
            [wagoe.tenant.shell.invite-service]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]))

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
        config {:active {:boundary/settings {:name "Boundary"}}}]
    (with-redefs [wagoe.tenant.shell.persistence/initialize-tenant-schema! (fn [arg]
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
                  wagoe.tenant.shell.http/tenant-routes-normalized (fn [service db-ctx cfg]
                                                                        (is (= tenant-service service))
                                                                        (is (= db-context db-ctx))
                                                                        (is (= config cfg))
                                                                        {:api [{:path "/tenants"}]})
                  wagoe.tenant.shell.membership-http/membership-routes-normalized (fn [service]
                                                                                       (is (= membership-service service))
                                                                                       {:api [{:path "/tenants/:tenant-id/memberships"}]})]
      (testing "schema, repositories, services, and routes initialize through their constructors"
        (is (= {:status :initialized}
               (ig/init-key :boundary/tenant-db-schema {:ctx ctx})))
        (is (= tenant-repo
               (ig/init-key :boundary/tenant-repository {:ctx ctx
                                                         :logger logger
                                                         :error-reporter error-reporter})))
        (is (= tenant-service
               (ig/init-key :boundary/tenant-service {:tenant-repository tenant-repo
                                                      :validation-config {:password-policy {:min-length 12}}
                                                      :logger logger
                                                      :metrics-emitter metrics
                                                      :error-reporter error-reporter})))
        (is (= membership-repo
               (ig/init-key :boundary/membership-repository {:ctx ctx
                                                             :logger logger
                                                             :error-reporter error-reporter})))
        (is (= membership-service
               (ig/init-key :boundary/membership-service {:repository membership-repo
                                                          :logger logger
                                                          :metrics-emitter metrics
                                                          :error-reporter error-reporter})))
        (is (= invite-repo
               (ig/init-key :boundary/invite-repository {:ctx ctx
                                                         :logger logger
                                                         :error-reporter error-reporter})))
        (is (= ::invite-service
               (ig/init-key :boundary/invite-service {:repository invite-repo
                                                      :membership-repository membership-repo
                                                      :logger logger
                                                      :metrics-emitter metrics
                                                      :error-reporter error-reporter})))
        (is (= {:api [{:path "/tenants"}]}
               (ig/init-key :boundary/tenant-routes {:tenant-service tenant-service
                                                     :db-context db-context
                                                     :config config})))
        (is (= {:api [{:path "/tenants/:tenant-id/memberships"}]}
               (ig/init-key :boundary/membership-routes {:service membership-service})))))))

(deftest ^:unit tenant-http-middleware-builds-injectable-middleware-seq
  ;; BOU-200: platform's http-handler no longer requires the tenant lib; the
  ;; tenant module owns its middleware and the app injects it via :extra-middleware.
  ;; The entries are (fn [handler] ...) built lazily, so absent services simply
  ;; contribute nothing — building the seq must not invoke the wrap-* fns.
  (testing "both services present -> tenant then membership middleware (2 fns)"
    (let [mw (ig/init-key :boundary/tenant-http-middleware
                          {:tenant-service ::ts :membership-service ::ms :db-context ::db})]
      (is (= 2 (count mw)))
      (is (every? fn? mw))))
  (testing "no services -> empty seq (platform pipeline gets no tenant middleware)"
    (is (empty? (ig/init-key :boundary/tenant-http-middleware {}))))
  (testing "tenant needs db-context; membership stands alone"
    (is (empty? (ig/init-key :boundary/tenant-http-middleware {:tenant-service ::ts})))
    (is (= 1 (count (ig/init-key :boundary/tenant-http-middleware
                                 {:tenant-service ::ts :db-context ::db}))))
    (is (= 1 (count (ig/init-key :boundary/tenant-http-middleware
                                 {:membership-service ::ms}))))))
