(ns wagoe.search.shell.trigram
  "pg_trgm, which suggestions on PostgreSQL use. Creating it can take a
   privilege the migration user lacks; suggestions then use LIKE (BOU-661)."
  (:require [clojure.tools.logging :as log]
            [next.jdbc :as jdbc]
            [wagoe.platform.database :as db]))

(defn installed?
  "Whether pg_trgm is installed in this PostgreSQL database."
  [connectable]
  (some? (jdbc/execute-one! connectable ["SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm'"])))

(defn up
  "Migratus entry point. The extension stays on down: something else may use it."
  [config]
  (let [conn (db/migration-connectable config)]
    (when (= :postgresql (db/engine-of conn))
      (try
        (jdbc/execute! conn ["CREATE EXTENSION IF NOT EXISTS pg_trgm"])
        (catch java.sql.SQLException e
          (log/warn "pg_trgm not created; search suggestions use LIKE until a superuser runs CREATE EXTENSION pg_trgm"
                    {:error (db/loggable-message e)}))))))
