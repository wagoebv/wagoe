(ns wagoe.push.shell.token-collation
  "Migratus entry points giving `push_device_tokens.token` a binary collation
   on MySQL. Its default is case-insensitive, so two tokens differing only in
   case were one device, and marking one invalid marked both."
  (:require [wagoe.platform.database :as db]))

(defn up
  "Migratus entry point."
  [config]
  (db/ensure-binary-collation! (db/context-of (db/migration-connectable config))
                               :push_device_tokens :token "VARCHAR(512)" true))

(defn down
  "Nothing: a case-insensitive token is the bug this fixed."
  [_config])
