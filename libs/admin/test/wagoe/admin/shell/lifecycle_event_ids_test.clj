(ns wagoe.admin.shell.lifecycle-event-ids-test
  "An admin event's `:id` is a UUID whichever database stored the row (BOU-579).

   SQLite hands a UUID column back as a string, so a subscriber got a string
   there and a UUID on PostgreSQL, for the same entity."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [integrant.core :as ig]
            [reitit.ring :as ring]
            [wagoe.admin.shell.http :as admin-http]
            [wagoe.admin.shell.schema-repository :as schema-repo]
            [wagoe.admin.test.embedded-pg :as epg]
            [wagoe.events.ports :as events]
            [wagoe.events.shell.module-wiring]
            [wagoe.observability.errors.shell.adapters.no-op :as error-reporting-no-op]
            [wagoe.observability.logging.shell.adapters.no-op :as logging-no-op]
            [wagoe.platform.database :as db]
            [wagoe.platform.shell.adapters.database.factory :as db-factory])
  (:import [java.io File]))

(def ^:private admin-config
  {:enabled?         true
   :base-path        "/web/admin"
   :require-role     :admin
   :entity-discovery {:mode :allowlist :allowlist #{:invoices}}
   :entities         {:invoices {:label "Invoices"}}})

(def ^:private admin-user
  {:id #uuid "00000000-0000-0000-0000-000000000001" :email "admin@example.com"
   :name "Admin" :role :admin :active true})

(defonce ^:private backends (atom {}))

(defn- with-backends [f]
  (let [sqlite-file (File/createTempFile "bou579" ".db")
        pg          (epg/start!)
        ctxs        {:sqlite     (db-factory/db-context {:adapter :sqlite :database-path (.getPath sqlite-file)})
                     :postgresql (epg/db-context pg)}]
    (try
      (doseq [ctx (vals ctxs)]
        ;; The column the scaffolder writes for an id.
        (db/execute-update! ctx {:raw "CREATE TABLE invoices (id UUID PRIMARY KEY,
                                                             number VARCHAR(50) NOT NULL,
                                                             created_at TIMESTAMP,
                                                             updated_at TIMESTAMP)"}))
      (reset! backends ctxs)
      (f)
      (finally
        (doseq [ctx (vals ctxs)] (db-factory/close-db-context! ctx))
        (epg/stop! pg)
        (.delete sqlite-file)
        (reset! backends {})))))

(use-fixtures :once with-backends)

(defn- handler [db-ctx bus]
  (let [provider (schema-repo/create-schema-repository db-ctx admin-config)
        service  (ig/init-key :wagoe/admin-service
                              {:db-ctx          db-ctx
                               :schema-provider provider
                               :logger          (logging-no-op/create-logging-component {})
                               :error-reporter  (error-reporting-no-op/create-error-reporting-component {})
                               :config          admin-config
                               :event-publisher bus})
        app      (ring/ring-handler
                  (ring/router [["/web/admin" (admin-http/web-routes service provider admin-config nil)]]
                               {:conflicts nil}))]
    (fn [method uri & [form]]
      (app {:request-method method :uri uri :user admin-user
            :headers {} :query-params {} :form-params (or form {})}))))

(defn- await-event [seen type]
  (loop [n 0]
    (or (some #(when (= type (:type %)) %) @seen)
        (when (< n 100) (Thread/sleep 20) (recur (inc n))))))

(deftest ^:integration every-event-id-is-a-uuid-on-every-database
  (doseq [[engine db-ctx] @backends]
    (testing (name engine)
      (let [bus  (ig/init-key :wagoe/events {:provider :memory})
            seen (atom [])]
        (try
          (events/subscribe! bus :admin #(swap! seen conj %))
          (let [request (handler db-ctx bus)
                _       (request :post "/web/admin/invoices" {"number" "INV-1"})
                id      (:id (db/execute-one! db-ctx {:select [:id] :from [:invoices]
                                                      :where [:= :number "INV-1"]}))]
            (request :put (str "/web/admin/invoices/" id) {"number" "INV-2"})
            (request :delete (str "/web/admin/invoices/" id))
            (doseq [type [:admin/entity-created :admin/entity-updated :admin/entity-deleted]]
              (let [event-id (get-in (await-event seen type) [:payload :id])]
                (is (uuid? event-id) (str type " carried " (pr-str event-id)))
                (is (= (str id) (str event-id)) (str type " named another row")))))
          (finally (ig/halt-key! :wagoe/events bus)))))))
