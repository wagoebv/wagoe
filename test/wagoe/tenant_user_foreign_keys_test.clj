(ns wagoe.tenant-user-foreign-keys-test
  "A user still in a tenant cannot be hard-deleted once `migrate up` has run
   (BOU-611) or the app has booted (BOU-612): the tenant tables reference
   auth_users, and the refused delete answers :hard-deletion-not-allowed."
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [support.embedded-pg :as epg]
            [wagoe.platform.shell.adapters.database.factory :as factory]
            [wagoe.platform.shell.database.migrations :as migrations]
            [wagoe.tenant.ports :as tenant-ports]
            [wagoe.tenant.shell.module-wiring]
            [wagoe.tenant.shell.persistence :as tenant-persistence]
            [wagoe.tenant.shell.user-foreign-keys :as fks]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.module-wiring]
            [wagoe.user.shell.persistence :as user-persistence]
            [wagoe.user.shell.service :as user-service]))

(defn- h2-ctx [label]
  (factory/db-context (factory/h2-config (str "mem:tenant_fk_" label "_" (System/nanoTime) ";DB_CLOSE_DELAY=-1"))))

(defn- user-svc [ctx]
  (user-service/->UserService (user-persistence/create-user-repository ctx) nil nil {} nil))

(defn- engines []
  [[:postgresql (fn [] (let [pg (epg/start!) ctx (epg/db-context pg)]
                         [ctx (fn [] (factory/close-db-context! ctx) (epg/stop! pg))]))]
   [:h2 (fn [] (let [ctx (h2-ctx "engine")] [ctx #(factory/close-db-context! ctx)]))]])

(defn- new-user! [ctx]
  (ports/create-user (user-persistence/create-user-repository ctx)
                     {:email (str "member-" (random-uuid) "@example.com") :name "Member"
                      :password-hash "hash" :role :user :active true}))

(defn- membership!
  ([ctx user-id] (membership! ctx user-id (random-uuid) "active"))
  ([ctx user-id tenant-id status]
   (jdbc/execute! (:datasource ctx)
                  [(str "INSERT INTO tenant_memberships (id, tenant_id, user_id, role, status, invited_at, created_at)"
                        " VALUES (?, ?, ?, 'member', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")
                   (random-uuid) tenant-id user-id status])))

(defn- tenant! [ctx status]
  (let [id (random-uuid)]
    (jdbc/execute! (:datasource ctx)
                   [(str "INSERT INTO tenants (id, slug, name, schema_name, status, created_at, deleted_at)"
                         " VALUES (?, ?, 'Acme', ?, ?, CURRENT_TIMESTAMP, ?)")
                    id (str "t-" id) (str "t_" (System/nanoTime)) status
                    (when (= "deleted" status) (java.sql.Timestamp. (System/currentTimeMillis)))])
    id))

(defn- memberships-of [ctx user-id]
  (mapv :tenant_memberships/status
        (jdbc/execute! (:datasource ctx) ["SELECT status FROM tenant_memberships WHERE user_id = ?" user-id])))

(defn- boot-tenant-schema! [ctx]
  (ig/init-key :wagoe/tenant-db-schema {:ctx ctx :allow-h2? true}))

(defn- validated
  "{constraint convalidated} for the tenant tables' keys to auth_users."
  [ctx]
  (into {} (map (juxt :conname :convalidated))
        (jdbc/execute! (:datasource ctx)
                       [(str "SELECT conname, convalidated FROM pg_constraint"
                             " WHERE contype = 'f' AND confrelid = 'auth_users'::regclass")]
                       {:builder-fn rs/as-unqualified-lower-maps})))

(def ^:private all-valid
  {"fk_tenant_memberships_user" true "fk_tenant_member_invites_user" true})

(defn- with-pg
  "Call `f` with a db-context on a fresh embedded PostgreSQL."
  [f]
  (let [pg  (epg/start!)
        ctx (epg/db-context pg)]
    (try (f ctx) (finally (factory/close-db-context! ctx) (epg/stop! pg)))))

(def ^:private setups
  {"migrate up" #(migrations/migrate-datasource! (:datasource %))
   "boot"       #(do (ig/init-key :wagoe/user-db-schema {:ctx %}) (boot-tenant-schema! %))})

(deftest ^:integration a-member-cannot-be-hard-deleted-after-migrate-up-or-boot
  (doseq [[engine open] (engines) [setup set-up!] setups]
    (testing (str (name engine) ", " setup)
      (let [[ctx close!] (open)]
        (try
          (set-up! ctx)
          (let [user (new-user! ctx)
                svc  (user-svc ctx)]
            (membership! ctx (:id user))
            (let [e (try (ports/permanently-delete-user svc (:id user)) nil
                         (catch clojure.lang.ExceptionInfo e e))]
              (is (= :hard-deletion-not-allowed (:type (ex-data e)))))
            ;; A NOT VALID key refuses the delete too, so check it was finished.
            (when (= :postgresql engine)
              (is (= all-valid (validated ctx)))))
          (finally (close!)))))))

(deftest ^:integration orphaned-memberships-are-named-not-keyed-over
  (let [ctx (h2-ctx "orphan")]
    (try
      ;; What a database looks like that only booted before BOU-612: tables, no keys.
      (user-persistence/initialize-user-schema! ctx)
      (tenant-persistence/initialize-tenant-schema! ctx)
      (membership! ctx (random-uuid))
      (let [e (try (fks/ensure-foreign-keys! ctx) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= {:type :conflict :table "tenant_memberships"} (select-keys (ex-data e) [:type :table]))))
      (jdbc/execute! (:datasource ctx) ["DELETE FROM tenant_memberships"])
      (fks/ensure-foreign-keys! ctx)
      (is (nil? (fks/ensure-foreign-keys! ctx)) "a second run changes nothing")
      (is (thrown? Exception (membership! ctx (random-uuid))) "the key now refuses an unknown user")
      (fks/down {:db {:datasource (:datasource ctx)}})
      (membership! ctx (random-uuid))
      (is (= 1 (count (jdbc/execute! (:datasource ctx) ["SELECT id FROM tenant_memberships"])))
          "down drops the keys")
      (finally (factory/close-db-context! ctx)))))

(deftest ^:integration deleting-a-tenant-frees-its-members-for-hard-delete
  (let [ctx (h2-ctx "deleted")]
    (try
      (migrations/migrate-datasource! (:datasource ctx))
      (let [user      (new-user! ctx)
            tenant-id (tenant! ctx "active")
            repo      (tenant-persistence/create-tenant-repository ctx nil nil)
            svc       (user-svc ctx)]
        (membership! ctx (:id user) tenant-id "active")
        (tenant-ports/update-tenant repo (assoc (tenant-ports/find-tenant-by-id repo tenant-id) :status :suspended))
        (is (= ["active"] (memberships-of ctx (:id user))) "any other update keeps the members")
        (tenant-ports/update-tenant repo (assoc (tenant-ports/find-tenant-by-id repo tenant-id)
                                                :status :deleted :deleted-at (java.time.Instant/now)))
        (is (nil? (tenant-ports/find-tenant-by-id repo tenant-id)) "a deleted tenant is not found")
        (is (empty? (memberships-of ctx (:id user))))
        (ports/permanently-delete-user svc (:id user))
        (is (nil? (ports/find-user-by-id (user-persistence/create-user-repository ctx) (:id user)))))
      (finally (factory/close-db-context! ctx)))))

(deftest ^:integration an-invite-accepted-by-a-deleted-user-is-cleared-not-refused
  (let [ctx (h2-ctx "invite")]
    (try
      (user-persistence/initialize-user-schema! ctx)
      (tenant-persistence/initialize-tenant-schema! ctx)
      (jdbc/execute! (:datasource ctx)
                     [(str "INSERT INTO tenant_member_invites (id, tenant_id, email, role, status, token_hash,"
                           " expires_at, accepted_by_user_id, created_at) VALUES (?, ?, 'a@example.com', 'member',"
                           " 'accepted', ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP)")
                      (random-uuid) (random-uuid) (apply str (repeat 32 "a")) (random-uuid)])
      (fks/ensure-foreign-keys! ctx)
      (is (= [nil] (mapv :tenant_member_invites/accepted_by_user_id
                         (jdbc/execute! (:datasource ctx) ["SELECT accepted_by_user_id FROM tenant_member_invites"]))))
      (finally (factory/close-db-context! ctx)))))

(deftest ^:integration departed-memberships-are-cleared-before-the-key-is-added
  (let [ctx (h2-ctx "departed")]
    (try
      (user-persistence/initialize-user-schema! ctx)
      (tenant-persistence/initialize-tenant-schema! ctx)
      (let [user (new-user! ctx)]
        (membership! ctx (:id user) (tenant! ctx "active") "revoked")
        (membership! ctx (:id user) (tenant! ctx "deleted") "active")
        (membership! ctx (:id user) (tenant! ctx "active") "suspended")
        (fks/ensure-foreign-keys! ctx)
        (is (= ["suspended"] (memberships-of ctx (:id user)))))
      (finally (factory/close-db-context! ctx)))))

(deftest ^:integration a-later-boot-adds-the-keys-once-auth-users-exists
  (let [ctx (h2-ctx "late")]
    (try
      (boot-tenant-schema! ctx)
      (is (some? (membership! ctx (random-uuid))) "no auth_users yet, so no key")
      (jdbc/execute! (:datasource ctx) ["DELETE FROM tenant_memberships"])
      (ig/init-key :wagoe/user-db-schema {:ctx ctx})
      (boot-tenant-schema! ctx)
      (is (thrown? Exception (membership! ctx (random-uuid))) "the next boot keyed it")
      (finally (factory/close-db-context! ctx)))))

(defn- keyed? [ctx table column]
  (some? (#'fks/foreign-key-name ctx table column)))

(deftest ^:integration boot-adds-only-the-keys-it-can-without-changing-rows
  (let [ctx (h2-ctx "boot_careful")]
    (try
      (ig/init-key :wagoe/user-db-schema {:ctx ctx})
      (tenant-persistence/initialize-tenant-schema! ctx)
      (membership! ctx (random-uuid))
      (is (= {:status :initialized} (boot-tenant-schema! ctx)) "an orphan does not stop boot")
      (is (not (keyed? ctx "tenant_memberships" "user_id")) "its table is left to migrate up")
      (is (keyed? ctx "tenant_member_invites" "accepted_by_user_id") "the other table is keyed anyway")
      (is (= 1 (count (jdbc/execute! (:datasource ctx) ["SELECT id FROM tenant_memberships"])))
          "boot deletes nothing")
      (finally (factory/close-db-context! ctx)))))

(deftest ^:integration migrate-up-clears-departed-rows-a-boot-already-keyed
  (let [ctx (h2-ctx "boot_departed")]
    (try
      (ig/init-key :wagoe/user-db-schema {:ctx ctx})
      (tenant-persistence/initialize-tenant-schema! ctx)
      (let [user (new-user! ctx)]
        (membership! ctx (:id user) (tenant! ctx "active") "revoked")
        (boot-tenant-schema! ctx)
        (is (keyed? ctx "tenant_memberships" "user_id"))
        (is (= ["revoked"] (memberships-of ctx (:id user))) "boot keeps it")
        (fks/ensure-foreign-keys! ctx)
        (is (empty? (memberships-of ctx (:id user))) "migrate up clears it, key or not"))
      (finally (factory/close-db-context! ctx)))))

(deftest ^:integration postgresql-finishes-a-half-added-key
  ;; BOU-614
  (with-pg
    (fn [ctx]
      (ig/init-key :wagoe/user-db-schema {:ctx ctx})
      (tenant-persistence/initialize-tenant-schema! ctx)
      (jdbc/execute! (:datasource ctx)
                     [(str "ALTER TABLE tenant_memberships ADD CONSTRAINT fk_tenant_memberships_user"
                           " FOREIGN KEY (user_id) REFERENCES auth_users (id) NOT VALID")])
      (is (= {"fk_tenant_memberships_user" false} (validated ctx)))
      (boot-tenant-schema! ctx)
      (is (= all-valid (validated ctx))))))

(deftest ^:integration a-busy-auth-users-leaves-the-key-for-the-next-boot
  ;; Boot gives up on a held lock rather than queue logins behind it (BOU-614).
  (with-pg
    (fn [ctx]
      (ig/init-key :wagoe/user-db-schema {:ctx ctx})
      (tenant-persistence/initialize-tenant-schema! ctx)
      (with-redefs [fks/lock-timeout "100ms"]
        (with-open [^java.sql.Connection writer (jdbc/get-connection (:datasource ctx))]
          (.setAutoCommit writer false)
          (jdbc/execute! writer ["LOCK TABLE auth_users IN ROW EXCLUSIVE MODE"])
          (let [left-off (deref (future (fks/add-clean-foreign-keys! ctx)) 20000 ::hung)]
            (is (not= ::hung left-off) "boot waited on the lock instead of giving up")
            (is (= #{"tenant_memberships" "tenant_member_invites"} (set (map :table left-off)))))
          (.rollback writer)))
      (is (empty? (fks/add-clean-foreign-keys! ctx)) "the next boot adds them")
      (is (= all-valid (validated ctx))))))
