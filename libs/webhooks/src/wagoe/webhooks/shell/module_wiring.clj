(ns wagoe.webhooks.shell.module-wiring
  "Integrant wiring for outbound webhooks.

   :wagoe/webhooks                 the `IWebhooks` service
   :wagoe.webhooks/db-schema       creates the tables when `migrate up` has not
   :wagoe.webhooks/job-handlers    `{:webhooks/deliver handler}` for the job registry
   :wagoe.webhooks/subscriber      listens on the bus `:topics` and dispatches

   Needs the jobs module; `:topics` needs the events module. Events published
   through the outbox reach the bus only after their transaction commits, so a
   rolled-back change sends no webhook."
  (:require [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [malli.core :as m]
            [malli.error :as me]
            [wagoe.events.ports :as events]
            [wagoe.webhooks.ports :as ports]
            [wagoe.webhooks.schema :as schema]
            [wagoe.webhooks.shell.http-transport :as http-transport]
            [wagoe.webhooks.shell.persistence :as persistence]
            [wagoe.webhooks.shell.service :as service]))

(defmethod ig/init-key :wagoe.webhooks/db-schema [_ {:keys [ctx]}]
  (persistence/create-tables! ctx)
  {:status :initialized})

(defmethod ig/init-key :wagoe/webhooks
  [_ {:keys [db-ctx job-queue idempotency settings]}]
  (let [settings (update settings :encryption-key #(or % (System/getenv "JWT_SECRET")))]
    (log/info "Webhooks: initializing" {:queue (:queue settings) :require-https? (:require-https? settings)})
    (service/create-service {:store       (persistence/create-store db-ctx)
                             :transport   (http-transport/create-transport settings)
                             :job-queue   job-queue
                             :idempotency idempotency}
                            settings)))

(defmethod ig/init-key :wagoe.webhooks/job-handlers [_ {:keys [service]}]
  {service/job-type #(ports/deliver! service %)})

(defmethod ig/init-key :wagoe.webhooks/subscriber [_ {:keys [service bus topics]}]
  (log/info "Webhooks: listening" {:topics (vec topics)})
  {:bus  bus
   :subs (mapv (fn [topic]
                 (events/subscribe! bus topic #(ports/dispatch! service topic %)))
               topics)})

(defmethod ig/halt-key! :wagoe.webhooks/subscriber [_ {:keys [bus subs]}]
  (doseq [s subs] (events/unsubscribe! bus s)))

(defn- refuse! [message]
  (throw (ex-info message {:type :configuration-error :module :wagoe/webhooks})))

(defn ig-config
  "This module's Integrant entries. HTTPS is required outside dev and test
   unless `:require-https?` says otherwise."
  [settings {:keys [enabled config]}]
  (let [settings (dissoc (or settings {}) :enabled?)
        enabled  (or enabled #{})
        topics   (seq (:topics settings))
        profile  (:wagoe/profile config)]
    (when-not (m/validate schema/Settings settings)
      (refuse! (str ":wagoe/webhooks settings are invalid: "
                    (pr-str (me/humanize (m/explain schema/Settings settings))))))
    (when-not (contains? enabled :wagoe/jobs)
      (refuse! ":wagoe/webhooks needs :wagoe/jobs: every delivery is a job."))
    (when (and topics (not (contains? enabled :wagoe/events)))
      (refuse! ":wagoe/webhooks :topics needs :wagoe/events, which publishes them."))
    (let [settings (merge service/defaults
                          {:require-https? (not (contains? #{:dev :test :local} profile))}
                          settings)]
      (cond-> {:components
               {:wagoe.webhooks/db-schema    {:ctx (ig/ref :wagoe/db-context)}
                :wagoe/webhooks              {:db-ctx      (ig/ref :wagoe/db-context)
                                              :schema      (ig/ref :wagoe.webhooks/db-schema)
                                              :job-queue   (ig/ref :wagoe/job-queue)
                                              :idempotency (ig/ref :wagoe/idempotency-store)
                                              :settings    settings}
                :wagoe.webhooks/job-handlers {:service (ig/ref :wagoe/webhooks)}}
               :job-handlers [(ig/ref :wagoe.webhooks/job-handlers)]}
        topics
        (assoc-in [:components :wagoe.webhooks/subscriber]
                  {:service (ig/ref :wagoe/webhooks)
                   :bus     (ig/ref :wagoe/events)
                   :topics  (vec topics)})))))
