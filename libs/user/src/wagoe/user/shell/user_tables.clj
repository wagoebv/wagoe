(ns wagoe.user.shell.user-tables
  "The user library's migration (BOU-611): it runs `initialize-user-schema!`,
   the definition boot uses, so `migrate up` and a first boot give the same
   tables, and a later migration can reference them."
  (:require [wagoe.platform.database :as db]
            [wagoe.user.shell.persistence :as persistence]))

(defn up
  "Migratus entry point."
  [config]
  (persistence/initialize-user-schema! (db/context-of (db/migration-connectable config))))

(defn down
  "Nothing. The tables may predate this migration, created at boot and holding
   data, and the runner calls down to back out a failed up on MySQL and H2."
  [_config])
