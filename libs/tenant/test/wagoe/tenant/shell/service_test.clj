(ns wagoe.tenant.shell.service-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [wagoe.tenant.shell.service :as sut]
            [wagoe.tenant.ports :as ports]
            [wagoe.observability.errors.ports :as error-ports]
            [wagoe.observability.logging.ports :as logging-ports]
            [wagoe.observability.metrics.ports :as metrics-ports])
  (:import (java.util UUID)))

(def ^:dynamic *tenant-repository* nil)

;; Mock observability services
(def mock-logger
  (reify
    logging-ports/ILogger
    (log* [_ _level _message _context _exception] nil)
    (trace [_ _message] nil)
    (trace [_ _message _context] nil)
    (debug [_ _message] nil)
    (debug [_ _message _context] nil)
    (info [_ _message] nil)
    (info [_ _message _context] nil)
    (warn [_ _message] nil)
    (warn [_ _message _context] nil)
    (warn [_ _message _context _exception] nil)
    (error [_ _message] nil)
    (error [_ _message _context] nil)
    (error [_ _message _context _exception] nil)
    (fatal [_ _message] nil)
    (fatal [_ _message _context] nil)
    (fatal [_ _message _context _exception] nil)
    Object
    (toString [_] "MockLogger")))

(def mock-metrics-emitter
  (reify
    wagoe.observability.metrics.ports/IMetricsEmitter
    (inc-counter! [_ _metric-handle] nil)
    (inc-counter! [_ _metric-handle _value] nil)
    (inc-counter! [_ _metric-handle _value _tags] nil)
    (set-gauge! [_ _metric-handle _value] nil)
    (set-gauge! [_ _metric-handle _value _tags] nil)
    (observe-histogram! [_ _metric-handle _value] nil)
    (observe-histogram! [_ _metric-handle _value _tags] nil)
    (observe-summary! [_ _metric-handle _value] nil)
    (observe-summary! [_ _metric-handle _value _tags] nil)
    (time-histogram! [_ _metric-handle f] (f))
    (time-histogram! [_ _metric-handle _tags f] (f))
    (time-summary! [_ _metric-handle f] (f))
    (time-summary! [_ _metric-handle _tags f] (f))
    Object
    (toString [_] "MockMetricsEmitter")))

(def mock-error-reporter
  (reify
    error-ports/IErrorContext
    (add-breadcrumb! [_ _breadcrumb] nil)
    (with-context [_ _context-map f] (f))
    (clear-breadcrumbs! [_] nil)
    (set-user! [_ _user-info] nil)
    (set-tags! [_ _tags] nil)
    (set-extra! [_ _extra] nil)
    (current-context [_] {})
    Object
    (toString [_] "MockErrorReporter")))

(defrecord MockTenantRepository [state schema-error]
  ports/ITenantRepository

  (find-tenant-by-id [_ tenant-id]
    (get @state tenant-id))

  (find-tenant-by-slug [_ slug]
    (->> @state
         vals
         (filter #(= slug (:slug %)))
         first))

  (find-all-tenants [_ _options]
    (vals @state))

  (create-tenant [_ tenant-entity]
    (swap! state assoc (:id tenant-entity) tenant-entity)
    tenant-entity)

  (update-tenant [_ tenant-entity]
    (swap! state assoc (:id tenant-entity) tenant-entity)
    tenant-entity)

  (delete-tenant [_ tenant-id]
    (swap! state dissoc tenant-id)
    nil)

  (tenant-slug-exists? [_ slug]
    (boolean (some #(= slug (:slug %)) (vals @state))))

  (create-tenant-schema [_ _schema-name]
    (when schema-error
      (throw schema-error))
    nil)

  (drop-tenant-schema [_ _schema-name]
    nil))

(defn setup-mock-repository []
  (alter-var-root #'*tenant-repository*
                  (constantly (->MockTenantRepository (atom {}) nil))))

(defn teardown-mock-repository []
  (alter-var-root #'*tenant-repository* (constantly nil)))

(use-fixtures :each
  (fn [f]
    (setup-mock-repository)
    (f)
    (teardown-mock-repository)))

(deftest ^:unit create-new-tenant-test
  (testing "creates new tenant successfully"
    (let [service (sut/create-tenant-service *tenant-repository* {} mock-logger mock-metrics-emitter mock-error-reporter)
          result (ports/create-new-tenant service
                                          {:slug "acme-corp"
                                           :name "ACME Corporation"
                                           :settings {:features {:mfa-enabled true}}})]
      (is (uuid? (:id result)))
      (is (= "acme-corp" (:slug result)))
      (is (= "ACME Corporation" (:name result)))
      (is (= "tenant_acme_corp" (:schema-name result)))
      (is (= :active (:status result)))))

  (testing "rejects duplicate slug"
    (let [service (sut/create-tenant-service *tenant-repository* {} mock-logger mock-metrics-emitter mock-error-reporter)]
      (ports/create-new-tenant service {:slug "duplicate-test" :name "First"})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Tenant slug already exists"
                            (ports/create-new-tenant service {:slug "duplicate-test" :name "Duplicate"})))))

  (testing "allows tenant creation when schema provisioning is not supported"
    (let [repository (->MockTenantRepository (atom {})
                                             (ex-info "Tenant provisioning requires PostgreSQL database"
                                                      {:type :not-supported
                                                       :dialect :h2}))
          service (sut/create-tenant-service repository {} mock-logger mock-metrics-emitter mock-error-reporter)
          result (ports/create-new-tenant service {:slug "h2-test" :name "H2 Test"})]
      (is (uuid? (:id result)))
      (is (= "h2-test" (:slug result)))))

  (testing "rolls back tenant creation for real schema provisioning failures"
    (let [repository (->MockTenantRepository (atom {})
                                             (ex-info "Boom" {:type :provisioning-error}))
          service (sut/create-tenant-service repository {} mock-logger mock-metrics-emitter mock-error-reporter)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Failed to create tenant schema"
                            (ports/create-new-tenant service {:slug "broken" :name "Broken"})))
      (is (empty? (ports/find-all-tenants repository {:limit 100}))))))

(deftest ^:unit get-tenant-test
  (testing "retrieves existing tenant by ID"
    (let [service (sut/create-tenant-service *tenant-repository* {} mock-logger mock-metrics-emitter mock-error-reporter)
          created (ports/create-new-tenant service {:slug "test-tenant" :name "Test"})
          retrieved (ports/get-tenant service (:id created))]
      (is (= created retrieved))))

  (testing "throws error for non-existent tenant"
    (let [service (sut/create-tenant-service *tenant-repository* {} mock-logger mock-metrics-emitter mock-error-reporter)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Tenant not found"
                            (ports/get-tenant service (UUID/randomUUID))))))

  (testing "retrieves existing tenant by slug"
    (let [service (sut/create-tenant-service *tenant-repository* {} mock-logger mock-metrics-emitter mock-error-reporter)
          created (ports/create-new-tenant service {:slug "slug-test" :name "Slug Test"})]
      (is (= created (ports/get-tenant-by-slug service "slug-test")))))

  (testing "throws error for unknown slug"
    (let [service (sut/create-tenant-service *tenant-repository* {} mock-logger mock-metrics-emitter mock-error-reporter)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Tenant not found"
                            (ports/get-tenant-by-slug service "missing-slug"))))))

(deftest ^:unit update-existing-tenant-test
  (testing "updates tenant name and status"
    (let [service (sut/create-tenant-service *tenant-repository* {} mock-logger mock-metrics-emitter mock-error-reporter)
          created (ports/create-new-tenant service {:slug "update-test" :name "Old Name"})
          updated (ports/update-existing-tenant service (:id created) {:name "New Name" :status :suspended})]
      (is (= "New Name" (:name updated)))
      (is (= :suspended (:status updated)))))

  (testing "rejects updates for missing tenants"
    (let [service (sut/create-tenant-service *tenant-repository* {} mock-logger mock-metrics-emitter mock-error-reporter)
          ex (is (thrown? clojure.lang.ExceptionInfo
                          (ports/update-existing-tenant service (UUID/randomUUID) {:name "Nope"})))]
      (is (= :not-found (:type (ex-data ex))))))

  (testing "rejects invalid statuses"
    (let [service (sut/create-tenant-service *tenant-repository* {} mock-logger mock-metrics-emitter mock-error-reporter)
          created (ports/create-new-tenant service {:slug "bad-status" :name "Bad Status"})
          ex (is (thrown? clojure.lang.ExceptionInfo
                          (ports/update-existing-tenant service (:id created) {:status :archived})))]
      (is (= :validation-error (:type (ex-data ex)))))))

(deftest ^:unit suspend-and-activate-tenant-test
  (testing "suspends active tenant"
    (let [service (sut/create-tenant-service *tenant-repository* {} mock-logger mock-metrics-emitter mock-error-reporter)
          created (ports/create-new-tenant service {:slug "suspend-test" :name "Test"})
          suspended (ports/suspend-tenant service (:id created))]
      (is (= :suspended (:status suspended)))))

  (testing "activates suspended tenant"
    (let [service (sut/create-tenant-service *tenant-repository* {} mock-logger mock-metrics-emitter mock-error-reporter)
          created (ports/create-new-tenant service {:slug "activate-test" :name "Test"})
          _ (ports/suspend-tenant service (:id created))
          activated (ports/activate-tenant service (:id created))]
      (is (= :active (:status activated))))))

(deftest ^:unit delete-existing-tenant-test
  (testing "soft deletes tenant"
    (let [service (sut/create-tenant-service *tenant-repository* {} mock-logger mock-metrics-emitter mock-error-reporter)
          created (ports/create-new-tenant service {:slug "delete-test" :name "Test"})]
      (ports/delete-existing-tenant service (:id created))
      (let [retrieved (ports/get-tenant service (:id created))]
        (is (= :deleted (:status retrieved)))
        (is (some? (:deleted-at retrieved))))))

  (testing "rejects deleting an unknown tenant"
    (let [service (sut/create-tenant-service *tenant-repository* {} mock-logger mock-metrics-emitter mock-error-reporter)
          ex (is (thrown? clojure.lang.ExceptionInfo
                          (ports/delete-existing-tenant service (UUID/randomUUID))))]
      (is (= :not-found (:type (ex-data ex))))))

  (testing "rejects deleting an already deleted tenant"
    (let [service (sut/create-tenant-service *tenant-repository* {} mock-logger mock-metrics-emitter mock-error-reporter)
          created (ports/create-new-tenant service {:slug "delete-twice" :name "Delete Twice"})]
      (ports/delete-existing-tenant service (:id created))
      (let [ex (is (thrown? clojure.lang.ExceptionInfo
                            (ports/delete-existing-tenant service (:id created))))]
        (is (= :validation-error (:type (ex-data ex))))))))
