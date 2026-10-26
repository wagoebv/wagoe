(ns wagoe.push.shell.callback-id
  "Migratus entry points for push_analytics_events.callback_id (BOU-849)."
  (:require [wagoe.platform.database :as db]))

(defn up
  "Migratus entry point."
  [config]
  (let [ctx (db/context-of (db/migration-connectable config))]
    ;; No read of the columns first: H2 in PostgreSQL mode has no lower-case
    ;; information_schema. A column already there counts as done.
    (db/add-columns-if-not-exist! ctx :push_analytics_events [[:callback_id "VARCHAR(255)"]] #{})
    (db/create-index-if-not-exists! ctx "uk_push_analytics_callback" :push_analytics_events
                                    [:callback_id :event_type] {:unique? true})))

(defn down
  "Migratus entry point."
  [config]
  (let [ctx (db/context-of (db/migration-connectable config))]
    (db/drop-index-if-exists! ctx "uk_push_analytics_callback" :push_analytics_events)
    (db/drop-column-if-exists! ctx :push_analytics_events :callback_id)))
