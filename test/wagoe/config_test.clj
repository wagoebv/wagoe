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
   {:boundary/settings {:name "Boundary Test"
                        :version "0.1.0"
                        :user-validation {:password-policy {:min-length 12}}}
    :boundary/http {:port 3000
                    :host "127.0.0.1"
                    :join? false
                    :port-range {:start 3000 :end 3010}}
    :boundary/router {:adapter :reitit}
    :boundary/logging {:provider :no-op}
    :boundary/metrics {:provider :no-op}
    :boundary/error-reporting {:provider :no-op}
    :boundary/cache {:provider :memory}
    :boundary/sqlite {:db "dev-database.db"
                      :pool {:maximum-pool-size 5}}}})

(deftest ^:unit db-spec-selects-active-adapter-test
  (testing "sqlite config is converted to a DB spec"
    (is (= {:adapter :sqlite
            :database-path "dev-database.db"
            :pool {:maximum-pool-size 5}}
           (sut/db-spec (base-config)))))

  (testing "h2 in-memory mode expands to a memory DSN"
    (let [config {:active {:boundary/h2 {:memory true
                                         :pool {:maximum-pool-size 3}}}}]
      (is (= {:adapter :h2
              :database-path "mem:boundary;DB_CLOSE_DELAY=-1"
              :pool {:maximum-pool-size 3}}
             (sut/db-spec config)))))

  (testing "missing adapters fail clearly"
    (let [ex (is (thrown? clojure.lang.ExceptionInfo
                          (sut/db-adapter {:active {:boundary/http {:port 3000}}})))]
      (is (= "No active database adapter found in configuration" (ex-message ex))))))

(deftest ^:unit ig-config-wires-tenant-membership-and-http-components-test
  (let [config (assoc-in (base-config) [:active :boundary/admin] {:enabled? true})
        ig-config (sut/ig-config config)]
    (testing "tenant and membership services are part of the Integrant graph"
      (is (contains? ig-config :boundary/tenant-repository))
      (is (contains? ig-config :boundary/tenant-service))
      (is (contains? ig-config :boundary/membership-repository))
      (is (contains? ig-config :boundary/membership-service))
      (is (contains? ig-config :boundary/invite-repository))
      (is (contains? ig-config :boundary/invite-service))
      (is (contains? ig-config :boundary/membership-routes)))

    (testing "tenant route wiring includes the db-context needed for provisioning"
      (is (= {:tenant-service (ig/ref :boundary/tenant-service)
              :db-context (ig/ref :boundary/db-context)
              :config config}
             (:boundary/tenant-routes ig-config))))

    (testing "http handler receives membership routes and the injected tenant middleware (BOU-200)"
      (is (= (ig/ref :boundary/membership-routes)
             (get-in ig-config [:boundary/http-handler :membership-routes])))
      ;; Tenant/membership middleware is no longer built inside http-handler; it is
      ;; injected via :extra-middleware from the tenant lib's component.
      (is (= (ig/ref :boundary/tenant-http-middleware)
             (get-in ig-config [:boundary/http-handler :extra-middleware])))
      (is (not (contains? (:boundary/http-handler ig-config) :membership-service))))

    (testing "the tenant-http-middleware component wires the services it needs"
      (is (= {:tenant-service (ig/ref :boundary/tenant-service)
              :membership-service (ig/ref :boundary/membership-service)
              :db-context (ig/ref :boundary/db-context)}
             (:boundary/tenant-http-middleware ig-config))))

    (testing "cache-enabled user service wiring keeps the cache dependency"
      (is (= (ig/ref :boundary/cache)
             (get-in ig-config [:boundary/user-service :cache]))))))
