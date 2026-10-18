(ns wagoe.tenant.shell.user-foreign-keys
  "Foreign keys from the tenant tables to auth_users (BOU-611). Without them a
   user still in a tenant could be hard-deleted, leaving memberships that point
   at nobody. Idempotent; skipped where auth_users is absent (no user module)
   or the engine cannot add a foreign key to a table (SQLite, which refuses
   tenancy anyway)."
  (:require [wagoe.core.utils.text :as text]
            [wagoe.platform.database :as db]
            [wagoe.platform.ports.database :as protocols])
  (:import [java.sql Connection]
           [javax.sql DataSource]))

(def ^:private references
  "Each references auth_users(id). An accepted invite is history, not
   membership, so it must not block a delete."
  [{:table "tenant_memberships" :column "user_id" :constraint "fk_tenant_memberships_user"}
   {:table "tenant_member_invites" :column "accepted_by_user_id"
    :constraint "fk_tenant_member_invites_user" :on-delete "SET NULL"}])

(def ^:private departed-memberships
  "Rows revoking a member or deleting a tenant now deletes, but older versions
   kept. Under the key they would block their user's hard delete."
  (str "DELETE FROM tenant_memberships WHERE status = 'revoked'"
       " OR tenant_id IN (SELECT id FROM tenants WHERE deleted_at IS NOT NULL)"))

(defn- auth-users-key-name
  "The name of `table`'s (as spelled) key from `column` to auth_users, or nil."
  [^Connection c table column]
  (with-open [rs (.getImportedKeys (.getMetaData c) (.getCatalog c) nil table)]
    (loop []
      (when (.next rs)
        (if (and (= column (text/lower-case (.getString rs "FKCOLUMN_NAME")))
                 (= "auth_users" (text/lower-case (.getString rs "PKTABLE_NAME"))))
          (.getString rs "FK_NAME")
          (recur))))))

(defn- foreign-key-name
  "The name of the key from `table`'s `column` to auth_users, from JDBC
   metadata, or nil when there is none."
  [ctx table column]
  (let [probe (fn [c] (some #(auth-users-key-name c % column)
                            (distinct [table (text/upper-case table)])))
        ds    (:datasource ctx)]
    (if (instance? Connection ds)
      (probe ds)
      (with-open [c (.getConnection ^DataSource ds)] (probe c)))))

(defn- postgresql? [ctx] (= :postgresql (protocols/engine (:adapter ctx))))

(defn- quoted [ctx ident] (db/quote-identifier (protocols/engine (:adapter ctx)) ident))

(defn- existing-key
  "{:name :valid?} of the key from `table`'s `column` to auth_users, or nil; a
   key outside PostgreSQL is always valid."
  [ctx table column]
  (if (postgresql? ctx)
    (when-let [{conname :name valid :valid} (db/execute-one!
                                     ctx [(str "SELECT c.conname AS name, c.convalidated AS valid"
                                               " FROM pg_constraint c JOIN pg_attribute a"
                                               " ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)"
                                               " WHERE c.contype = 'f' AND c.confrelid = to_regclass('auth_users')"
                                               " AND c.conrelid = to_regclass(?) AND a.attname = ?")
                                          table column])]
      {:name conname :valid? valid})
    (when-let [n (foreign-key-name ctx table column)]
      {:name n :valid? true})))

(def ^:private lock-timeout
  "The ADD's wait for its lock; longer would queue later auth_users writes."
  "5s")

(defn- orphaned
  "WHERE clause matching `column` values that name no user."
  [column]
  (str " WHERE " column " IS NOT NULL AND NOT EXISTS (SELECT 1 FROM auth_users u"
       " WHERE u.id = t." column ")"))

(defn- orphans
  "Up to five values of `table`.`column` that name no user."
  [ctx table column]
  (mapv (comp val first)
        (db/execute-query! ctx [(str "SELECT DISTINCT " column " FROM " table " t"
                                     (orphaned column) " LIMIT 5")])))

(defn- add-key!
  "Add the key, or finish one `missing-keys` found unvalidated. On PostgreSQL
   NOT VALID, in a transaction of its own with a lock timeout, then VALIDATE: a
   plain ADD scans under a lock that blocks writes to auth_users (BOU-614)."
  [ctx {:keys [table column constraint on-delete existing]}]
  (let [pg? (postgresql? ctx)
        add (str "ALTER TABLE " table " ADD CONSTRAINT " constraint
                 " FOREIGN KEY (" column ") REFERENCES auth_users (id)"
                 (when on-delete (str " ON DELETE " on-delete))
                 (when pg? " NOT VALID"))
        k   (or existing
                (try
                  (if pg?
                    (db/with-transaction [tx ctx]
                      (db/execute-ddl! tx (str "SET LOCAL lock_timeout = '" lock-timeout "'"))
                      (db/execute-ddl! tx add))
                    (db/execute-ddl! ctx add))
                  constraint
                  ;; Replicas boot together; one losing the race finds the key there.
                  (catch Exception e
                    (or (:name (existing-key ctx table column)) (throw e)))))]
    (when pg?
      (db/execute-ddl! ctx (str "ALTER TABLE " table " VALIDATE CONSTRAINT " (quoted ctx k))))))

(defn- missing-keys
  "The references still to key, a PostgreSQL key never validated carrying its
   name as `:existing`. None where they do not apply."
  [ctx]
  (when (and (contains? (protocols/capabilities (:adapter ctx)) :alter-foreign-key)
             (db/table-exists? ctx :auth_users))
    (into []
          (keep (fn [{:keys [table column] :as reference}]
                  (when (db/table-exists? ctx table)
                    (let [{k :name valid? :valid?} (existing-key ctx table column)]
                      (cond (nil? k)     reference
                            (not valid?) (assoc reference :existing k))))))
          references)))

(defn ensure-foreign-keys!
  "`migrate up`: clear departed memberships, set NULL what an ON DELETE SET NULL
   key would have, refuse naming them any other rows that point at no user, and
   add the missing keys."
  [ctx]
  (when (db/table-exists? ctx :tenant_memberships)
    (db/execute-update! ctx [departed-memberships]))
  (doseq [{:keys [table column on-delete] :as reference} (missing-keys ctx)]
    (when (= "SET NULL" on-delete)
      (db/execute-update! ctx [(str "UPDATE " table " t SET " column " = NULL" (orphaned column))]))
    (when-let [found (not-empty (orphans ctx table column))]
      (throw (ex-info (str table " has rows whose " column " names no user, so it cannot take a"
                           " foreign key to auth_users. Delete them, then run again. The first: "
                           (pr-str found))
                      {:type :conflict :table table :column column :orphans found})))
    (add-key! ctx reference)))

(defn add-clean-foreign-keys!
  "Boot: add each missing key whose table has no row pointing at no user, and
   change no rows. Returns the keys left off, each naming why."
  [ctx]
  (into []
        (keep (fn [{:keys [table column] :as reference}]
                (try
                  (if-let [found (not-empty (orphans ctx table column))]
                    {:table table :column column :orphans found}
                    (do (add-key! ctx reference) nil))
                  (catch Exception e
                    {:table table :column column :error (ex-message e)}))))
        (missing-keys ctx)))

(defn up
  "Migratus entry point."
  [config]
  (ensure-foreign-keys! (db/context-of (db/migration-connectable config))))

(defn down
  "Migratus entry point."
  [config]
  (let [ctx (db/context-of (db/migration-connectable config))
        drop-kw (if (= :mysql (protocols/engine (:adapter ctx))) "FOREIGN KEY" "CONSTRAINT")]
    ;; The name found, not the one `up` would give: a key made before up ran
    ;; may carry another, and dropping a name that is not there fails.
    (doseq [{:keys [table column]} references
            :let [k (:name (existing-key ctx table column))]
            :when k]
      (db/execute-ddl! ctx (str "ALTER TABLE " table " DROP " drop-kw " " (quoted ctx k))))))
