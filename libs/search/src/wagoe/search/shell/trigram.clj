(ns wagoe.search.shell.trigram
  "pg_trgm, which suggestions on PostgreSQL use. Creating it can take a
   privilege the migration user lacks; suggestions then use LIKE (BOU-661)."
  (:require [clojure.tools.logging :as log]
            [next.jdbc :as jdbc]
            [wagoe.platform.database :as db]))

(def index-name "idx_search_documents_content_trgm")

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

(defn index-up
  "Migratus entry point: the trigram index suggestions use. Outside a
   transaction, so the table is locked for the build and nothing else."
  [config]
  (let [conn (db/migration-connectable config)]
    (when (and (= :postgresql (db/engine-of conn)) (installed? conn))
      (try
        (jdbc/execute! conn [(str "CREATE INDEX IF NOT EXISTS " index-name
                                  " ON search_documents USING GIN (content_all gin_trgm_ops)")])
        (catch java.sql.SQLException e
          ;; pg_trgm in a schema off the search_path: gin_trgm_ops is not found.
          (log/warn "Trigram index not created; search suggestions read every row"
                    {:error (db/loggable-message e)}))))))

(defn index-down
  "Migratus entry point."
  [config]
  (db/drop-index-if-exists! (db/context-of (db/migration-connectable config))
                            index-name :search_documents))
