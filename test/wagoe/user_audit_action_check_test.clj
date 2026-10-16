(ns wagoe.user-audit-action-check-test
  "A database created before `:permanent-delete` checks user_audit_log's action
   against the old list, and refused the entry a permanent delete writes; the
   service only warns about a failed audit write (BOU-682). Boot and `migrate
   up` widen the check, on every engine. H2 re-applies its checks at boot.

   Needs MySQL: `bb test:services up`."
  (:require [clojure.test :refer [deftest is testing]]
            [support.embedded-pg :as epg]
            [support.mysql :as mysql]
            [wagoe.platform.database :as db]
            [wagoe.platform.shell.adapters.database.factory :as factory]
            [wagoe.user.core.audit :as audit]
            [wagoe.user.ports :as ports]
            [wagoe.user.schema :as user-schema]
            [wagoe.user.shell.persistence :as persistence]))

(def ^:private old-audit-log
  "UserAuditLog as it was before `:permanent-delete`."
  (mapv #(if (and (vector? %) (= :action (first %)))
           [:action (into [:enum] (remove #{:permanent-delete}) user-schema/audit-actions)]
           %)
        user-schema/UserAuditLog))

(defn- entry [entry-fn]
  (entry-fn (random-uuid) "admin@example.com" (random-uuid) "gone@example.com" nil nil))

(defn- check-widened [ctx]
  (db/initialize-tables-from-schemas! ctx {"user_audit_log" old-audit-log})
  (let [repo (persistence/create-audit-repository ctx {})]
    (ports/create-audit-log repo (entry audit/delete-user-audit-entry))
    (is (thrown? Exception (ports/create-audit-log repo (entry audit/permanently-delete-user-audit-entry)))
        "the old check refuses it, or this proves nothing")
    (dotimes [_ 2] (persistence/initialize-user-schema! ctx))
    (ports/create-audit-log repo (entry audit/permanently-delete-user-audit-entry))
    (is (= #{:delete :permanent-delete}
           (set (map :action (:audit-logs (ports/find-audit-logs repo {:limit 10})))))
        "the earlier entry is kept, and the new one written")
    (is (thrown? Exception (ports/create-audit-log repo (assoc (entry audit/delete-user-audit-entry)
                                                               :action :no-such-action)))
        "and the check still checks")))

(deftest ^:integration the-audit-action-check-takes-permanent-delete
  (testing "sqlite"
    (let [f   (doto (java.io.File/createTempFile "audit-check-" ".db") .deleteOnExit)
          ctx (factory/db-context (factory/sqlite-config (str f)))]
      (try (check-widened ctx) (finally (factory/close-db-context! ctx)))))
  (testing "h2"
    (let [ctx (factory/db-context (factory/h2-config (str "mem:audit_check_" (System/nanoTime) ";DB_CLOSE_DELAY=-1")))]
      (try (check-widened ctx) (finally (factory/close-db-context! ctx)))))
  (testing "postgresql"
    (let [pg  (epg/start!)
          ctx (epg/db-context pg)]
      (try (check-widened ctx)
           (finally (factory/close-db-context! ctx) (epg/stop! pg)))))
  (testing "mysql"
    (let [up? (try (with-open [c (.getConnection ^javax.sql.DataSource (mysql/server))] (some? c))
                   (catch Exception _ false))]
      (is up? "MySQL is not reachable; start it with `bb test:services up`")
      (when up? (mysql/with-database "audit_check" check-widened)))))
