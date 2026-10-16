(ns wagoe.push.shell.platform-index
  "Migratus entry points for the index broadcast pages by: platform, active, id (BOU-683)."
  (:require [wagoe.platform.database :as db]))

(defn up
  "Migratus entry point."
  [config]
  (let [ctx (db/context-of (db/migration-connectable config))]
    (db/create-index-if-not-exists! ctx "idx_push_devices_platform_id" :push_device_tokens [:platform :active :id])
    (db/drop-index-if-exists! ctx "idx_push_devices_platform" :push_device_tokens)))

(defn down
  "Migratus entry point."
  [config]
  (let [ctx (db/context-of (db/migration-connectable config))]
    (db/create-index-if-not-exists! ctx "idx_push_devices_platform" :push_device_tokens [:platform :active])
    (db/drop-index-if-exists! ctx "idx_push_devices_platform_id" :push_device_tokens)))
