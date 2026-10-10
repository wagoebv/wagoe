(ns wagoe.tenant.shell.user-foreign-keys
  "Foreign keys from the tenant tables to auth_users (BOU-611). Without them a
   user still in a tenant could be hard-deleted, leaving memberships that point
   at nobody. Idempotent; skipped where auth_users is absent (no user module)
   or the engine cannot add a foreign key to a table (SQLite, which refuses
   tenancy anyway)."
  (:require [clojure.string :as str]
            [wagoe.platform.database :as db]
            [wagoe.platform.ports.database :as protocols])
  (:import [java.sql Connection]
           [javax.sql DataSource]))

(def ^:private references
  "[table column constraint-name on-delete departed]: each references auth_users(id).
   An accepted invite is history, not membership, so it must not block a delete.
   `departed` deletes rows that revoking or deleting a tenant now deletes, but
   which older versions kept, before the key would make them block a delete."
  [["tenant_memberships" "user_id" "fk_tenant_memberships_user" nil
    (str "DELETE FROM tenant_memberships WHERE status = 'revoked'"
         " OR tenant_id IN (SELECT id FROM tenants WHERE status = 'deleted')")]
   ["tenant_member_invites" "accepted_by_user_id" "fk_tenant_member_invites_user" "SET NULL" nil]])

(defn- auth-users-key-name
  "The name of `table`'s (as spelled) key from `column` to auth_users, or nil."
  [^Connection c table column]
  (with-open [rs (.getImportedKeys (.getMetaData c) (.getCatalog c) nil table)]
    (loop []
      (when (.next rs)
        (if (and (= column (str/lower-case (.getString rs "FKCOLUMN_NAME")))
                 (= "auth_users" (str/lower-case (.getString rs "PKTABLE_NAME"))))
          (.getString rs "FK_NAME")
          (recur))))))

(defn- foreign-key-name
  "The name of the key from `table`'s `column` to auth_users, from JDBC
   metadata, or nil when there is none."
  [ctx table column]
  (let [probe (fn [c] (some #(auth-users-key-name c % column)
                            (distinct [table (str/upper-case table)])))
        ds    (:datasource ctx)]
    (if (instance? Connection ds)
      (probe ds)
      (with-open [c (.getConnection ^DataSource ds)] (probe c)))))

(defn- orphans
  "Up to five values of `table`.`column` that name no user."
  [ctx table column]
  (mapv (comp val first)
        (db/execute-query! ctx [(str "SELECT DISTINCT " column " FROM " table " t WHERE " column
                                     " IS NOT NULL AND NOT EXISTS (SELECT 1 FROM auth_users u"
                                     " WHERE u.id = t." column ") LIMIT 5")])))

(defn ensure-foreign-keys!
  "Give each tenant table's user column a foreign key to auth_users, unless it
   has one. Refuses, naming them, rows that point at no user."
  [ctx]
  (when (and (db/table-exists? ctx :auth_users)
             (contains? (protocols/capabilities (:adapter ctx)) :alter-foreign-key))
    (doseq [[table column constraint on-delete departed] references
            :when (and (db/table-exists? ctx table) (not (foreign-key-name ctx table column)))]
      (when departed (db/execute-update! ctx [departed]))
      (when-let [found (not-empty (orphans ctx table column))]
        (throw (ex-info (str table " has rows whose " column " names no user, so it cannot take a"
                             " foreign key to auth_users. Delete them, then run again. The first: "
                             (pr-str found))
                        {:type :conflict :table table :column column :orphans found})))
      (db/execute-ddl! ctx (str "ALTER TABLE " table " ADD CONSTRAINT " constraint
                                " FOREIGN KEY (" column ") REFERENCES auth_users (id)"
                                (when on-delete (str " ON DELETE " on-delete)))))))

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
    (doseq [[table column] references
            :let [constraint (foreign-key-name ctx table column)]
            :when constraint]
      (db/execute-ddl! ctx (str "ALTER TABLE " table " DROP " drop-kw " " constraint)))))
