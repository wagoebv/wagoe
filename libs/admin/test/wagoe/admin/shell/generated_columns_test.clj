(ns wagoe.admin.shell.generated-columns-test
  "A column the database refuses a value for — computed, or an identity
   GENERATED ALWAYS — is left out of the admin's INSERT and UPDATE, even when
   the config marks it editable. On the engine WAGOE_TEST_DB names."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [support.test-db :as test-db]
            [wagoe.admin.ports :as ports]
            [wagoe.admin.shell.schema-repository :as schema-repo]
            [wagoe.admin.shell.service :as service]
            [wagoe.observability.errors.shell.adapters.no-op :as error-reporting-no-op]
            [wagoe.observability.logging.shell.adapters.no-op :as logging-no-op]
            [wagoe.platform.database :as db]))

(def ^:private tickets-ddl
  "MySQL has no identity column; its AUTO_INCREMENT takes a value."
  {:h2    (str "CREATE TABLE tickets (id VARCHAR(36) PRIMARY KEY, title VARCHAR(50) NOT NULL,"
               " seq BIGINT GENERATED ALWAYS AS IDENTITY,"
               " len INT GENERATED ALWAYS AS (CHAR_LENGTH(title)))")
   :pg    (str "CREATE TABLE tickets (id VARCHAR(36) PRIMARY KEY, title VARCHAR(50) NOT NULL,"
               " seq BIGINT GENERATED ALWAYS AS IDENTITY,"
               " len INT GENERATED ALWAYS AS (length(title)) STORED)")
   :mysql (str "CREATE TABLE tickets (id VARCHAR(36) PRIMARY KEY, title VARCHAR(50) NOT NULL,"
               " seq BIGINT NOT NULL AUTO_INCREMENT UNIQUE,"
               " len INT AS (CHAR_LENGTH(title)) STORED)")})

(def ^:private config
  {:entity-discovery {:mode :allowlist :allowlist #{:tickets}}
   ;; Marks both editable, as a hand-written config may.
   :entities         {:tickets {:readonly-fields #{:id}
                                :editable-fields [:title :seq :len]}}
   :pagination       {:default-page-size 20 :max-page-size 200}})

(def ^:dynamic ^:private *ctx* nil)

(use-fixtures :each
  (fn [f]
    (test-db/with-db "admin_generated"
      (fn [db-ctx]
        (db/execute-update! db-ctx {:raw (tickets-ddl test-db/engine)})
        (binding [*ctx* db-ctx] (f))))))

(defn- system []
  (let [schema (schema-repo/create-schema-repository *ctx* config)]
    {:schema schema
     :svc    (service/create-admin-service *ctx* schema
                                           (logging-no-op/create-logging-component {})
                                           (error-reporting-no-op/create-error-reporting-component {})
                                           config)}))

(deftest ^:integration generated-columns-are-not-written
  (let [{:keys [schema svc]} (system)
        cfg     (ports/get-entity-config schema :tickets)
        always? (not= :mysql test-db/engine)]
    (testing "they are read-only, and off the form"
      (is (contains? (:readonly-fields cfg) :len))
      (is (= always? (contains? (:readonly-fields cfg) :seq)))
      (is (not-any? #{:len} (:editable-fields cfg))))

    (let [created (ports/create-entity svc :tickets {:title "Hello" :seq 7 :len 99})]
      (testing "a create submitting them succeeds, and the database fills them"
        (is (= "Hello" (:title created)))
        (is (= 5 (:len created)))
        (is (some? (:seq created))))

      (testing "so does an edit"
        (let [updated (ports/update-entity svc :tickets (:id created) {:title "Bye!!!" :seq 8 :len 1})]
          (is (= "Bye!!!" (:title updated)))
          (is (= 6 (:len updated)))
          (is (= (if always? (:seq created) 8) (:seq updated))))))))
