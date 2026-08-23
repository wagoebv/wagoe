(ns wagoe.admin.shell.admin-persistence-contract-test
  "Contract tests for the admin auto-CRUD persistence boundary.

   The admin service is a generic CRUD engine over other modules' tables. Its
   contract is the case + type conversion at the DB boundary: a kebab-cased
   entity is written to snake_case columns, and rows are read back with
   kebab-case keys and domain types.

   service_test.clj already covers CRUD behaviour, but only with single-word
   fields (email/name/active) that never exercise the `_`<->`-` conversion.
   These tests use MULTI-WORD fields (display_name/is_active/unit_price/
   serial_number) and verify the physical columns via raw SQL, so a regression
   in the case conversion (AGENTS.md Pitfall #1) cannot slip through."
  (:require [wagoe.admin.ports :as ports]
            [wagoe.admin.shell.service :as service]
            [wagoe.admin.shell.schema-repository :as schema-repo]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.platform.database :as db]
            [wagoe.observability.logging.shell.adapters.no-op :as logging-no-op]
            [wagoe.observability.errors.shell.adapters.no-op :as error-reporting-no-op]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

;; =============================================================================
;; Harness
;; =============================================================================

(def ^:private test-db-config
  {:adapter :h2
   :database-path "mem:admin_contract_test;DB_CLOSE_DELAY=-1"
   :pool {:minimum-idle 1 :maximum-pool-size 3}})

(def ^:private admin-config
  {:enabled?          true
   :base-path         "/web/admin"
   :require-role      :admin
   :entity-discovery  {:mode :allowlist :allowlist #{:contract-widgets}}
   :entities          {:contract-widgets {:label           "Widgets"
                                          :list-fields     [:display-name :is-active]
                                          :readonly-fields #{:id :created-at}}}
   :pagination        {:default-page-size 50 :max-page-size 200}})

(defonce ^:dynamic *db-ctx* nil)
(defonce ^:dynamic *admin-service* nil)

(defn- create-table! [db-ctx]
  (db/execute-update!
   db-ctx
   {:raw "CREATE TABLE IF NOT EXISTS contract_widgets (
            id UUID PRIMARY KEY,
            display_name VARCHAR(255) NOT NULL,
            is_active BOOLEAN NOT NULL,
            unit_price DECIMAL(10,2),
            serial_number VARCHAR(100),
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP,
            deleted_at TIMESTAMP)"}))

(defn- setup! []
  (let [db-ctx          (db-factory/db-context test-db-config)
        logger          (logging-no-op/create-logging-component {})
        error-reporter  (error-reporting-no-op/create-error-reporting-component {})
        schema-provider (schema-repo/create-schema-repository db-ctx admin-config)
        admin-service   (service/create-admin-service db-ctx schema-provider logger error-reporter admin-config)]
    (create-table! db-ctx)
    (alter-var-root #'*db-ctx* (constantly db-ctx))
    (alter-var-root #'*admin-service* (constantly admin-service))))

(defn- teardown! []
  (when *db-ctx*
    (db/execute-update! *db-ctx* {:raw "DROP TABLE IF EXISTS contract_widgets"})
    (db-factory/close-db-context! *db-ctx*)
    (alter-var-root #'*db-ctx* (constantly nil))
    (alter-var-root #'*admin-service* (constantly nil))))

(use-fixtures :once (fn [f] (setup!) (f) (teardown!)))
(use-fixtures :each (fn [f]
                      (when *db-ctx*
                        (db/execute-update! *db-ctx* {:raw "DELETE FROM contract_widgets"}))
                      (f)))

(defn- raw-row
  "Read a row straight from the datasource, bypassing the admin read path, so we
   see the physical snake_case columns as stored."
  [id]
  (jdbc/execute-one! (:datasource *db-ctx*)
                     ["SELECT display_name, is_active, unit_price, serial_number
                         FROM contract_widgets WHERE id = ?" id]
                     {:builder-fn rs/as-unqualified-lower-maps}))

(defn- widget []
  {:display-name  "Widget Alpha"
   :is-active     true
   :unit-price    19.99M
   :serial-number "SN-0001"})

;; =============================================================================
;; Case-conversion boundary
;; =============================================================================

(deftest ^:contract create-entity-writes-snake-case-columns
  (testing "kebab entity keys are persisted to snake_case columns"
    (let [created (ports/create-entity *admin-service* :contract-widgets (widget))
          row     (raw-row (:id created))]
      (is (some? row) "row exists under snake_case columns")
      (is (= "Widget Alpha" (:display_name row)) "display-name -> display_name")
      (is (true? (:is_active row)) "is-active -> is_active")
      (is (= "SN-0001" (:serial_number row)) "serial-number -> serial_number")
      (is (some? (:unit_price row)) "unit-price -> unit_price"))))

(deftest ^:contract get-entity-returns-kebab-case-keys
  (testing "rows are read back with kebab-case keys, never snake_case"
    (let [created   (ports/create-entity *admin-service* :contract-widgets (widget))
          retrieved (ports/get-entity *admin-service* :contract-widgets (:id created))]
      (is (some? retrieved))
      (is (contains? retrieved :display-name) "kebab key present")
      (is (contains? retrieved :is-active))
      (is (contains? retrieved :serial-number))
      (is (not (contains? retrieved :display_name)) "no snake key leaks to the domain")
      (is (not (contains? retrieved :is_active)))
      (is (not (contains? retrieved :serial_number))))))

(deftest ^:contract round-trip-preserves-values-and-types
  (testing "create -> get preserves values and domain types"
    (let [created   (ports/create-entity *admin-service* :contract-widgets (widget))
          retrieved (ports/get-entity *admin-service* :contract-widgets (:id created))]
      (is (= "Widget Alpha" (:display-name retrieved)) "string value fidelity")
      (is (= "SN-0001" (:serial-number retrieved)))
      (is (= true (:is-active retrieved)) "boolean comes back as a real boolean")
      (is (uuid? (:id retrieved)) "id round-trips as a UUID")
      (is (some? (:created-at retrieved)) "created-at populated"))))

(deftest ^:contract update-entity-converts-multiword-field
  (testing "update-entity writes kebab changes to snake_case columns"
    (let [created (ports/create-entity *admin-service* :contract-widgets (widget))
          _       (ports/update-entity *admin-service* :contract-widgets (:id created)
                                       {:display-name "Widget Beta" :is-active false})
          row     (raw-row (:id created))]
      (is (= "Widget Beta" (:display_name row)) "updated display_name persisted")
      (is (false? (:is_active row)) "updated is_active persisted")
      (let [retrieved (ports/get-entity *admin-service* :contract-widgets (:id created))]
        (is (= "Widget Beta" (:display-name retrieved)) "read back under kebab key")
        (is (= false (:is-active retrieved)))))))
