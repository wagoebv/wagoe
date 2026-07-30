(ns verify-schema
  "Quick verification script for user schema initialization."
  (:require [wagoe.platform.shell.adapters.database.factory :as dbf]
            ;[wagoe.platform.shell.adapters.database.core :as db]
            [wagoe.user.shell.persistence :as user-persistence]))

(defn verify-schema-creation
  "Verify that user schema creation works with SQLite."
  []
  (println "\n=== User Schema Verification ===\n")

  ;; Create a temporary SQLite database
  (let [db-path "dev/test-schema.db"
        db-config {:adapter :sqlite
                   :database-path db-path}
        ctx (dbf/db-context db-config)]

    (try
      (println "1. Created database context for SQLite")
      (println (str "   Database: " db-path))

      ;; Initialize user schema
      (user-persistence/initialize-user-schema! ctx)
      (println "\n2. Initialized user schema successfully")

      ;; Check if tables exist
      (let [adapter (:adapter ctx)
            datasource (:datasource ctx)
            users-exists? (.table-exists? adapter datasource "users")
            sessions-exists? (.table-exists? adapter datasource "user_sessions")]

        (println "\n3. Table existence check:")
        (println (str "   users table: " (if users-exists? "✓ EXISTS" "✗ MISSING")))
        (println (str "   user_sessions table: " (if sessions-exists? "✓ EXISTS" "✗ MISSING")))

        (when (and users-exists? sessions-exists?)
          (println "\n✓ Schema verification PASSED"))

        (when-not (and users-exists? sessions-exists?)
          (println "\n✗ Schema verification FAILED")))

      (finally
        (dbf/close-db-context! ctx)
        (println "\n4. Closed database context")))))

(comment
  ;; Run verification from REPL
  (verify-schema-creation)
  ;; Clean up test database
  (require '[clojure.java.io :as io])
  (io/delete-file "dev/test-schema.db" true))
