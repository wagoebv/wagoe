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
