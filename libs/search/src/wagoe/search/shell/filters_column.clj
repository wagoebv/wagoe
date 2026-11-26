(ns wagoe.search.shell.filters-column
  "Migratus entry points for search_documents.filters."
  (:require [wagoe.platform.database :as db]))

(defn up
  "Migratus entry point."
  [config]
  (db/add-column-if-not-exists! (db/context-of (db/migration-connectable config))
                                :search_documents :filters "TEXT"))

(defn down
  "Migratus entry point."
  [config]
  (db/drop-column-if-exists! (db/context-of (db/migration-connectable config))
                             :search_documents :filters))

(defn binary-up
  "Migratus entry point: a binary collation on MySQL, so tenant ids in the
   filters compare case- and accent-sensitively (BOU-1124)."
  [config]
  (db/ensure-binary-collation! (db/context-of (db/migration-connectable config))
                               :search_documents :filters "TEXT" false))

(defn binary-down
  "Nothing: a case-insensitive tenant match is the bug this fixed."
  [_config])
