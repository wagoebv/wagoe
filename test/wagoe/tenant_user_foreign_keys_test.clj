(ns wagoe.tenant-user-foreign-keys-test
  "A user still in a tenant cannot be hard-deleted once `migrate up` has run
   (BOU-611): the tenant tables reference auth_users, and the refused delete
   answers :hard-deletion-not-allowed."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [support.embedded-pg :as epg]
            [wagoe.platform.shell.adapters.database.factory :as factory]
            [wagoe.platform.shell.database.migrations :as migrations]
            [wagoe.tenant.ports :as tenant-ports]
            [wagoe.tenant.shell.persistence :as tenant-persistence]
            [wagoe.tenant.shell.user-foreign-keys :as fks]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.persistence :as user-persistence]
            [wagoe.user.shell.service :as user-service]))

(defn- engines []
  [[:postgresql (fn [] (let [pg (epg/start!) ctx (epg/db-context pg)]
                         [ctx (fn [] (factory/close-db-context! ctx) (epg/stop! pg))]))]
   [:h2 (fn [] (let [ctx (factory/db-context
                          (factory/h2-config (str "mem:tenant_fk_" (System/nanoTime) ";DB_CLOSE_DELAY=-1")))]
                 [ctx #(factory/close-db-context! ctx)]))]])

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
                   [(str "INSERT INTO tenants (id, slug, name, schema_name, status, created_at)"
                         " VALUES (?, ?, 'Acme', ?, ?, CURRENT_TIMESTAMP)")
                    id (str "t-" id) (str "t_" (System/nanoTime)) status])
    id))

(defn- memberships-of [ctx user-id]
  (mapv :tenant_memberships/status
        (jdbc/execute! (:datasource ctx) ["SELECT status FROM tenant_memberships WHERE user_id = ?" user-id])))

(defn- h2-ctx [label]
  (factory/db-context (factory/h2-config (str "mem:tenant_fk_" label "_" (System/nanoTime) ";DB_CLOSE_DELAY=-1"))))

(deftest ^:integration a-member-cannot-be-hard-deleted-after-migrate-up
  (doseq [[engine open] (engines)]
    (testing (name engine)
      (let [[ctx close!] (open)]
        (try
          (migrations/migrate-datasource! (:datasource ctx))
          (let [user (new-user! ctx)
                svc  (user-service/->UserService (user-persistence/create-user-repository ctx) nil nil {} nil nil)]
            (membership! ctx (:id user))
            (let [e (try (ports/permanently-delete-user svc (:id user)) nil
                         (catch clojure.lang.ExceptionInfo e e))]
              (is (= :hard-deletion-not-allowed (:type (ex-data e))))))
          (finally (close!)))))))

(deftest ^:integration orphaned-memberships-are-named-not-keyed-over
  (let [ctx (h2-ctx "orphan")]
    (try
      ;; What a database looks like that only ever booted: tables, no keys.
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
            svc       (user-service/->UserService (user-persistence/create-user-repository ctx) nil nil {} nil nil)]
        (membership! ctx (:id user) tenant-id "active")
        (tenant-ports/soft-delete-tenant repo (assoc (tenant-ports/find-tenant-by-id repo tenant-id)
                                                     :status :deleted :deleted-at (java.time.Instant/now)))
        (is (nil? (tenant-ports/find-tenant-by-id repo tenant-id)) "a deleted tenant is not found")
        (is (empty? (memberships-of ctx (:id user))))
        (ports/permanently-delete-user svc (:id user))
        (is (nil? (ports/find-user-by-id (user-persistence/create-user-repository ctx) (:id user)))))
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
