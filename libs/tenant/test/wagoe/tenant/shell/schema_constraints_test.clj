(ns wagoe.tenant.shell.schema-constraints-test
  "The uniqueness the tenant tables promise is enforced by the database.

   It used to come from two migrations; since they stopped creating the tables
   (BOU-576), boot DDL is the only source, and it created none of it. Checked
   on a fresh database and on one whose tables already exist, on every engine
   the DDL runs on."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [support.embedded-pg :as epg]
            [wagoe.platform.shell.adapters.database.factory :as factory]
            [wagoe.tenant.ports :as ports]
            [wagoe.tenant.shell.invite-persistence :as invite-persistence]
            [wagoe.tenant.shell.membership-persistence :as membership-persistence]
            [wagoe.tenant.shell.persistence :as sut])
  (:import [java.time Instant]
           [java.util.concurrent CountDownLatch]))

(defn- backends
  "[label open] — `open` returns [ctx close!] on a fresh, empty database."
  []
  [["h2" (fn []
           (let [ctx (factory/db-context
                      (factory/h2-config (str "mem:tenant_uniq_" (System/nanoTime) ";DB_CLOSE_DELAY=-1")))]
             [ctx #(factory/close-db-context! ctx)]))]
   ["sqlite" (fn []
               (let [path (str (System/getProperty "java.io.tmpdir") "/tenant-uniq-" (System/nanoTime) ".db")
                     ctx  (factory/db-context (factory/sqlite-config path))]
                 [ctx (fn []
                        (factory/close-db-context! ctx)
                        (.delete (java.io.File. path)))]))]
   ["postgresql" (fn []
                   (let [pg  (epg/start!)
                         ctx (epg/db-context pg)]
                     [ctx (fn []
                            (factory/close-db-context! ctx)
                            (epg/stop! pg))]))]])

(defn- refused? [ds sql-params]
  (try (jdbc/execute! ds sql-params) false
       (catch java.sql.SQLException _ true)))

(def ^:private now "2026-01-01T00:00:00Z")
(def ^:private ts (java.sql.Timestamp/from (Instant/parse now)))

(defn- tenant-row [slug schema-name]
  ["INSERT INTO tenants (id, slug, name, schema_name, status, created_at) VALUES (?, ?, ?, ?, 'active', ?)"
   (str (random-uuid)) slug slug schema-name now])

(defn- membership-row [tenant-id user-id]
  ["INSERT INTO tenant_memberships (id, tenant_id, user_id, role, status, invited_at, created_at)
    VALUES (?, ?, ?, 'member', 'invited', ?, ?)"
   (str (random-uuid)) tenant-id user-id ts ts])

(defn- invite-row [token-hash]
  ["INSERT INTO tenant_member_invites (id, tenant_id, email, role, status, token_hash, expires_at, created_at)
    VALUES (?, ?, 'a@example.test', 'member', 'pending', ?, ?, ?)"
   (str (random-uuid)) (str (random-uuid)) token-hash ts ts])

(defn- index-names
  "Every index the engine reports on `table`, lower-cased."
  [ds table]
  (with-open [c (jdbc/get-connection ds)]
    (let [md (.getMetaData c)]
      (into #{}
            (mapcat (fn [t]
                      (with-open [rs (.getIndexInfo md nil nil t false false)]
                        (loop [acc []]
                          (if (.next rs)
                            (recur (conj acc (some-> (.getString rs "INDEX_NAME") str/lower-case)))
                            acc)))))
            [table (str/upper-case table)]))))

(deftest ^:integration boot-ddl-enforces-tenant-uniqueness
  (doseq [[label open] (backends)]
    (testing label
      (let [[{:keys [datasource] :as ctx} close!] (open)]
        (try
          (sut/initialize-tenant-schema! ctx)
          (testing "and running it again is harmless"
            (is (nil? (sut/initialize-tenant-schema! ctx))))

          (testing "a slug, and a schema name, belong to one tenant"
            (jdbc/execute! datasource (tenant-row "acme" "tenant_acme"))
            (is (refused? datasource (tenant-row "acme" "tenant_other")))
            (is (refused? datasource (tenant-row "other" "tenant_acme"))))

          (testing "a user has one membership per tenant"
            (let [t1 (str (random-uuid)) t2 (str (random-uuid)) u1 (str (random-uuid))]
              (jdbc/execute! datasource (membership-row t1 u1))
              (is (refused? datasource (membership-row t1 u1)))
              (is (not (refused? datasource (membership-row t2 u1))))))

          (testing "an invite token hash is unique"
            (jdbc/execute! datasource (invite-row "hash-one-0123456789-0123456789-01"))
            (is (refused? datasource (invite-row "hash-one-0123456789-0123456789-01"))))

          (testing "invites are indexed by email"
            (is (contains? (index-names datasource "tenant_member_invites")
                           "idx_tenant_member_invites_email")))
          (finally (close!)))))))

(deftest ^:integration duplicates-already-present-stop-the-boot-and-say-where
  (doseq [[label open] (backends)]
    (testing label
      (let [[{:keys [datasource] :as ctx} close!] (open)]
        (try
          ;; The table as boot created it before the index existed.
          (jdbc/execute! datasource ["CREATE TABLE tenants (id VARCHAR(36) PRIMARY KEY, slug VARCHAR(100),
                                        name VARCHAR(255), schema_name VARCHAR(63), status VARCHAR(20),
                                        settings TEXT, created_at VARCHAR(40), updated_at VARCHAR(40),
                                        deleted_at VARCHAR(40))"])
          (jdbc/execute! datasource (tenant-row "acme" "tenant_acme"))
          (jdbc/execute! datasource (tenant-row "acme" "tenant_acme"))
          (let [e (is (thrown? clojure.lang.ExceptionInfo (sut/initialize-tenant-schema! ctx)))]
            (is (str/includes? (ex-message e) "tenants"))
            (is (str/includes? (ex-message e) "slug")))
          (finally (close!)))))))

(deftest ^:integration a-postgresql-database-the-old-migrations-built-still-boots
  ;; Those migrations declared the membership uniqueness as a constraint.
  (let [pg  (epg/start!)
        ctx (epg/db-context pg)]
    (try
      (jdbc/execute! (:datasource ctx)
                     ["CREATE TABLE public.tenant_memberships (
                         id VARCHAR(255) NOT NULL PRIMARY KEY, tenant_id VARCHAR(255) NOT NULL,
                         user_id VARCHAR(255) NOT NULL, role VARCHAR(255) NOT NULL,
                         status VARCHAR(255) NOT NULL, invited_at TIMESTAMP WITH TIME ZONE NOT NULL,
                         accepted_at TIMESTAMP WITH TIME ZONE, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                         updated_at TIMESTAMP WITH TIME ZONE, UNIQUE (tenant_id, user_id))"])
      (is (nil? (sut/initialize-tenant-schema! ctx)))
      (finally
        (factory/close-db-context! ctx)
        (epg/stop! pg)))))

(defn- membership [tenant-id user-id]
  {:id (random-uuid) :tenant-id tenant-id :user-id user-id :role :member :status :invited
   :invited-at (Instant/now) :created-at (Instant/now)})

(deftest ^:integration concurrent-duplicate-memberships-leave-one-row
  ;; The service checks before it inserts, so two requests can both pass the
  ;; check; the database is what keeps the second out.
  (doseq [[label open] (remove #(= "sqlite" (first %)) (backends))]
    (testing label
      (let [[ctx close!] (open)]
        (try
          (sut/initialize-tenant-schema! ctx)
          (let [repo      (membership-persistence/create-membership-repository ctx nil nil)
                tenant-id (random-uuid)
                user-id   (random-uuid)
                start     (CountDownLatch. 1)
                attempts  (doall (for [_ (range 4)]
                                   (future
                                     (.await start)
                                     (try (ports/create-membership repo (membership tenant-id user-id)) :ok
                                          (catch Exception _ :refused)))))]
            (.countDown start)
            (is (= {:ok 1 :refused 3} (frequencies (map deref attempts))))
            (is (= 1 (count (ports/find-memberships-by-tenant repo tenant-id {:limit 10 :offset 0})))))
          (finally (close!)))))))

(deftest ^:integration the-invite-lookup-by-email-uses-its-index
  (let [pg  (epg/start!)
        ctx (epg/db-context pg)]
    (try
      (sut/initialize-tenant-schema! ctx)
      (let [repo      (invite-persistence/create-invite-repository ctx nil nil)
            tenant-id (random-uuid)
            invite    {:id (random-uuid) :tenant-id tenant-id :email "a@example.test" :role :member
                       :status :pending :token-hash (str (random-uuid) (random-uuid))
                       :expires-at (Instant/now) :created-at (Instant/now)}]
        (ports/create-invite repo invite)
        (is (= (:id invite)
               (:id (ports/find-pending-invite-by-email-and-tenant repo tenant-id "a@example.test"))))
        (testing "the planner can answer an email lookup from the index"
          (with-open [c (jdbc/get-connection (:datasource ctx))]
            (jdbc/execute! c ["SET enable_seqscan = off"])
            (let [plan (str/join "\n" (map (comp first vals)
                                           (jdbc/execute! c ["EXPLAIN SELECT * FROM public.tenant_member_invites WHERE email = ?"
                                                             "a@example.test"])))]
              (is (str/includes? plan "idx_tenant_member_invites_email") plan)))))
      (finally
        (factory/close-db-context! ctx)
        (epg/stop! pg)))))
