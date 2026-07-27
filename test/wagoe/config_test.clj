(ns wagoe.config-test
  (:require [wagoe.config :as sut]
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
   {:wagoe/settings {:name "Boundary Test"
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
              :database-path "mem:boundary;DB_CLOSE_DELAY=-1"
              :pool {:maximum-pool-size 3}}
             (sut/db-spec config)))))

  (testing "missing adapters fail clearly"
    (let [ex (is (thrown? clojure.lang.ExceptionInfo
                          (sut/db-adapter {:active {:wagoe/http {:port 3000}}})))]
      (is (= "No active database adapter found in configuration" (ex-message ex))))))

(deftest ^:unit ig-config-wires-tenant-membership-and-http-components-test
  (let [config (assoc-in (base-config) [:active :wagoe/admin] {:enabled? true})
        ig-config (sut/ig-config config)]
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
