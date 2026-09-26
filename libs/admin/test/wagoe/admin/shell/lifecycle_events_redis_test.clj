(ns wagoe.admin.shell.lifecycle-events-redis-test
  "Admin lifecycle events over the Redis bus, with the column types the
   scaffolder generates: a TIMESTAMP WITH TIME ZONE reads back as an
   OffsetDateTime, a PostgreSQL jsonb as a driver PGobject. Transit could
   encode neither, so every event was lost (BOU-492).

   Needs Redis on localhost:6379; skipped with a message without it. Start it
   with `bb test:services up`."
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [wagoe.admin.ports :as ports]
            [wagoe.admin.shell.module-wiring]
            [wagoe.admin.shell.schema-repository :as schema-repo]
            [wagoe.admin.test.embedded-pg :as epg]
            [wagoe.events.ports :as events]
            [wagoe.events.shell.module-wiring]
            [wagoe.observability.errors.shell.adapters.no-op :as error-reporting-no-op]
            [wagoe.observability.logging.shell.adapters.no-op :as logging-no-op]
            [wagoe.platform.database :as db]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]))

(defn- redis-up? []
  (try
    (with-open [s (java.net.Socket.)]
      (.connect s (java.net.InetSocketAddress. "localhost" 6379) 300)
      true)
    (catch Exception _ false)))

(def ^:private admin-config
  {:enabled?         true
   :base-path        "/web/admin"
   :require-role     :admin
   :entity-discovery {:mode :allowlist :allowlist #{:widgets}}
   :entities         {:widgets {:label "Widgets"}}})

(def ^:private ddl
  {:h2         (str "CREATE TABLE widgets (id UUID PRIMARY KEY, name VARCHAR(50) NOT NULL,"
                    " meta JSON DEFAULT JSON '{\"a\": 1}',"
                    " created_at TIMESTAMP WITH TIME ZONE, updated_at TIMESTAMP WITH TIME ZONE)")
   :postgresql (str "CREATE TABLE widgets (id UUID PRIMARY KEY, name VARCHAR(50) NOT NULL,"
                    " meta JSONB DEFAULT '{\"a\": 1}'::jsonb,"
                    " created_at TIMESTAMP WITH TIME ZONE, updated_at TIMESTAMP WITH TIME ZONE)")})

(defn- received-types
  "Create, update and delete one widget; the event types a Redis subscriber
   receives."
  [ctx backend]
  (db/execute-update! ctx {:raw "DROP TABLE IF EXISTS widgets"})
  (db/execute-update! ctx {:raw (ddl backend)})
  (let [bus  (ig/init-key :wagoe/events {:provider :redis
                                         :prefix   (str "bou492-" (random-uuid) ":")})
        seen (atom [])]
    (try
      (events/subscribe! bus :admin #(swap! seen conj (:type %)))
      (let [svc (ig/init-key :wagoe/admin-service
                             {:db-ctx          ctx
                              :schema-provider (schema-repo/create-schema-repository ctx admin-config)
                              :logger          (logging-no-op/create-logging-component {})
                              :error-reporter  (error-reporting-no-op/create-error-reporting-component {})
                              :config          admin-config
                              :event-publisher bus})
            rec (ports/create-entity svc :widgets {:name "w1"})]
        (ports/update-entity svc :widgets (:id rec) {:name "w2"})
        (ports/delete-entity svc :widgets (:id rec))
        (loop [n 0]
          (when (and (< (count @seen) 3) (< n 250))
            (Thread/sleep 20)
            (recur (inc n))))
        @seen)
      (finally
        (ig/halt-key! :wagoe/events bus)))))

(deftest ^:integration lifecycle-events-reach-a-redis-subscriber
  (if-not (redis-up?)
    (is (not (redis-up?))
        "Redis not reachable on localhost:6379 — skipped. Start it with `bb test:services up`.")
    (let [pg (epg/start!)]
      (try
        (doseq [[backend ctx] {:h2         (db-factory/db-context
                                            {:adapter :h2 :database-path "mem:bou492redis;DB_CLOSE_DELAY=-1"})
                               :postgresql (epg/db-context pg)}]
          (testing (name backend)
            (try
              (is (= [:admin/entity-created :admin/entity-updated :admin/entity-deleted]
                     (received-types ctx backend)))
              (finally
                (db/execute-update! ctx {:raw "DROP TABLE IF EXISTS widgets"})
                (db-factory/close-db-context! ctx)))))
        (finally (epg/stop! pg))))))
