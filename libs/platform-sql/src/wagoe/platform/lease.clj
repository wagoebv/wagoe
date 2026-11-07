(ns wagoe.platform.lease
  "A lease row that replicas take turns holding, timed by the database clock
   so replica clock skew cannot split it. A holder paused past `lease-ms` may
   overlap the next one until it notices; callers whose work must not run twice
   guard it separately."
  (:require [next.jdbc :as jdbc]
            [wagoe.platform.database :as db]
            [wagoe.platform.ports.database :as db-ports]))

(defn- table-name [table]
  (let [t (name table)]
    (when-not (re-matches #"[a-z_][a-z0-9_]*" t)
      (throw (ex-info (str "Lease table must be a plain identifier, not " (pr-str t))
                      {:type :validation-error :table table})))
    t))

(defn create-table!
  "Create `table` and its row for `lease-name` if absent. Safe to call from
   every replica at boot."
  [ds table lease-name]
  (let [t       (table-name table)
        seeded? #(jdbc/execute-one! ds [(str "SELECT name FROM " t " WHERE name = ?") lease-name])]
    (jdbc/execute! ds [(str "CREATE TABLE IF NOT EXISTS " t " (
                               name     VARCHAR(64) PRIMARY KEY,
                               owner    VARCHAR(64) NULL,
                               until_ms BIGINT NOT NULL)")])
    (when-not (seeded?)
      (try
        (jdbc/execute-one! ds [(str "INSERT INTO " t " (name, owner, until_ms) VALUES (?, NULL, 0)") lease-name])
        ;; Another replica seeded it first. Anything else would leave no row,
        ;; and a lease nobody can claim stops its holder's work, silently.
        (catch java.sql.SQLException e
          (when-not (seeded?) (throw e)))))))

(defn create-lease
  "The lease `lease-name` in `table` on `ds`. Call `create-table!` first."
  [ds table lease-name]
  (let [t   (table-name table)
        now (db-ports/now-ms-sql (:adapter (db/context-of ds)))]
    {:ds          ds
     :lease-name  lease-name
     :claim-sql   (str "UPDATE " t " SET owner = ?, until_ms = " now " + ?
                        WHERE name = ? AND (until_ms < " now " OR owner = ?)")
     :release-sql (str "UPDATE " t " SET until_ms = 0 WHERE name = ? AND owner = ?")}))

(defn claim!
  "Take the lease for `owner`, or renew it, for `lease-ms`. True when held.
   `owner` is at most 36 characters: older tables were made with that width."
  [{:keys [ds lease-name claim-sql]} owner lease-ms]
  (= 1 (::jdbc/update-count (jdbc/execute-one! ds [claim-sql owner lease-ms lease-name owner]))))

(defn release!
  "Hand the lease on, if `owner` holds it."
  [{:keys [ds lease-name release-sql]} owner]
  (jdbc/execute-one! ds [release-sql lease-name owner])
  nil)
