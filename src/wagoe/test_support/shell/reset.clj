(ns wagoe.test-support.shell.reset
  "Side-effecting reset of the test database.
   Safe to call only in the :test profile."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [next.jdbc.sql :as sql]
            [clojure.tools.logging :as log]
            [wagoe.test-support.core :as core]
            [wagoe.tenant.ports :as tenant-ports]
            [wagoe.user.core.group :as group]
            [wagoe.user.ports :as user-ports]))

(def ^:private tables-in-truncation-order
  ;; Order does not matter since we disable referential integrity,
  ;; but listing child tables first documents the foreign-key graph.
  ;; Names match the real production tables created by
  ;; `wagoe.user.shell.persistence/initialize-user-schema!` and
  ;; `wagoe.tenant.shell.persistence/initialize-tenant-schema!`.
  ["user_sessions"
   "user_audit_log"
   "user_group_grants"
   "user_group_roles"
   "user_roles"
   "user_group_members"
   "tenant_memberships"
   "tenant_member_invites"
   "users"
   "auth_users"
   "tenants"
   "e2e_order_lines"
   "e2e_orders"
   "e2e_ticket_labels"
   "e2e_ticket_watchers"
   "e2e_tickets"
   "e2e_products"])

(def ^:private fixture-tables
  "Tables no module owns, for admin e2e tests: an order whose lines have a
   has-many :min (:e2e-orders in resources/conf/test/config.edn, BOU-570),
   tickets whose customer is a combobox (BOU-754), and two join tables:
   labels a ticket has, few, and customers watching it, many (BOU-778),
   and a product with a price and a file (BOU-798)."
  ["CREATE TABLE IF NOT EXISTS e2e_orders (
      id UUID PRIMARY KEY,
      number VARCHAR(50) NOT NULL,
      status VARCHAR(20) DEFAULT 'open' NOT NULL)"
   "CREATE TABLE IF NOT EXISTS e2e_order_lines (
      id UUID PRIMARY KEY,
      e2e_order_id UUID NOT NULL REFERENCES e2e_orders(id),
      description VARCHAR(255) NOT NULL,
      qty INT DEFAULT 1 NOT NULL)"
   ;; More customers than a select holds, so a ticket's customer is a
   ;; combobox (BOU-754).
   "CREATE TABLE IF NOT EXISTS e2e_customers (
      id UUID PRIMARY KEY,
      name VARCHAR(100) NOT NULL)"
   "CREATE TABLE IF NOT EXISTS e2e_tickets (
      id UUID PRIMARY KEY,
      customer_id UUID NOT NULL REFERENCES e2e_customers(id),
      subject VARCHAR(255) NOT NULL)"
   "CREATE TABLE IF NOT EXISTS e2e_labels (
      id UUID PRIMARY KEY,
      name VARCHAR(50) NOT NULL)"
   "CREATE TABLE IF NOT EXISTS e2e_ticket_labels (
      e2e_ticket_id UUID NOT NULL REFERENCES e2e_tickets(id),
      e2e_label_id UUID NOT NULL REFERENCES e2e_labels(id),
      PRIMARY KEY (e2e_ticket_id, e2e_label_id))"
   "CREATE TABLE IF NOT EXISTS e2e_ticket_watchers (
      id UUID PRIMARY KEY,
      e2e_ticket_id UUID NOT NULL REFERENCES e2e_tickets(id),
      e2e_customer_id UUID NOT NULL REFERENCES e2e_customers(id),
      UNIQUE (e2e_ticket_id, e2e_customer_id))"
   "CREATE TABLE IF NOT EXISTS e2e_products (
      id UUID PRIMARY KEY,
      name VARCHAR(100) NOT NULL,
      price DECIMAL(10,2),
      datasheet BYTEA)"])

(defn- seed-customers!
  "One more customer than :max-page-size (200) in the test config. Tests only
   read them, so they are seeded once and never truncated."
  [ds]
  (when (zero? (:n (jdbc/execute-one! ds ["SELECT COUNT(*) AS n FROM e2e_customers"]
                                      {:builder-fn rs/as-unqualified-lower-maps})))
    (sql/insert-multi! ds :e2e_customers [:id :name]
                       (for [i (range 1 202)] [(random-uuid) (format "Customer %03d" i)]))))

(defn- seed-labels!
  "Three labels: few enough for checkboxes. Seeded once, like the customers."
  [ds]
  (when (zero? (:n (jdbc/execute-one! ds ["SELECT COUNT(*) AS n FROM e2e_labels"]
                                      {:builder-fn rs/as-unqualified-lower-maps})))
    (sql/insert-multi! ds :e2e_labels [:id :name]
                       (for [n ["Bug" "Feature" "Question"]] [(random-uuid) n]))))

(defn create-fixture-tables!
  "Create the e2e fixture tables if they are missing. Called when the reset
   route is mounted, before the admin first reads their config."
  [ds]
  (doseq [ddl fixture-tables]
    (jdbc/execute! ds [ddl]))
  (seed-customers! ds)
  (seed-labels! ds))

(defn- engine [tx]
  (let [product (.getDatabaseProductName (.getMetaData ^java.sql.Connection tx))]
    (cond (re-find #"(?i)postgres" product) :postgresql
          (re-find #"(?i)sqlite" product)   :sqlite
          (re-find #"(?i)mysql" product)    :mysql
          :else                             :h2)))

(defn- existing-tables [tx]
  (let [md (.getMetaData ^java.sql.Connection tx)]
    (with-open [rs (.getTables md nil nil "%" (into-array String ["TABLE"]))]
      (loop [acc #{}]
        (if (.next rs) (recur (conj acc (.toLowerCase (.getString rs "TABLE_NAME")))) acc)))))

(defn truncate-all!
  "Empties every table the e2e suite might touch, with each engine's way of
   setting foreign keys aside: the suite runs on H2 by default, and on the
   other engines to catch SQL only H2 accepts."
  [ds]
  (create-fixture-tables! ds)
  (jdbc/with-transaction [tx ds]
    (let [eng    (engine tx)
          tables (filter (existing-tables tx) tables-in-truncation-order)
          [off on empty!] (case eng
                            :h2         ["SET REFERENTIAL_INTEGRITY FALSE" "SET REFERENTIAL_INTEGRITY TRUE" #(str "TRUNCATE TABLE " %)]
                            :mysql      ["SET FOREIGN_KEY_CHECKS = 0" "SET FOREIGN_KEY_CHECKS = 1" #(str "DELETE FROM " %)]
                            :sqlite     ["PRAGMA defer_foreign_keys = ON" nil #(str "DELETE FROM " %)]
                            :postgresql [nil nil nil])]
      (when off (jdbc/execute! tx [off]))
      (try
        (if empty!
          (doseq [t tables] (jdbc/execute! tx [(empty! t)]))
          (jdbc/execute! tx [(str "TRUNCATE TABLE " (str/join ", " tables) " CASCADE")]))
        ;; Everyone stays: the user module creates it only at boot.
        (jdbc/execute! tx ["DELETE FROM user_groups WHERE id <> ?" (str group/everyone-id)])
        (finally
          (when on (jdbc/execute! tx [on]))))))
  (log/debug "test-support: truncated all tables"))

(defn seed-baseline!
  "Creates the baseline test fixture described by
   `wagoe.test-support.core/baseline-seed-spec` by calling the real
   production services: the tenant is created via
   `wagoe.tenant.ports/create-new-tenant` and the users are registered via
   `wagoe.user.ports/register-user`. Going through the production write
   path is deliberate: any schema drift between the seed spec and what the
   services actually accept will surface here as a contract test failure.

   Arguments:
     {:user-service   instance implementing wagoe.user.ports/IUserService
      :tenant-service instance implementing wagoe.tenant.ports/ITenantService}

   Returns:
     {:tenant <tenant-entity>
      :admin  <user-entity exactly as register-user returned it, with the
               plain :password re-attached for test consumers>
      :user   <ditto>}

   Note: we deliberately do NOT dissoc :password-hash here. The production
   `register-user` is contractually required to strip it on the success path,
   and the contract test in `reset-test` asserts that. Stripping it defensively
   here would neuter that assertion."
  [{:keys [user-service tenant-service]}]
  (let [spec            (core/baseline-seed-spec)
        tenant-input    (select-keys (:tenant spec) [:slug :name])
        ;; Tenancy is off on an engine it does not run on
        tenant          (when tenant-service
                          (tenant-ports/create-new-tenant tenant-service tenant-input))
        admin-spec      (:admin spec)
        user-spec       (:user spec)
        created-admin   (user-ports/register-user user-service admin-spec)
        created-user    (user-ports/register-user user-service user-spec)]
    (log/debug "test-support: seeded baseline tenant + users"
               {:tenant-id (:id tenant)
                :admin-id  (:id created-admin)
                :user-id   (:id created-user)})
    {:tenant tenant
     :admin  (assoc created-admin :password (:password admin-spec))
     :user   (assoc created-user  :password (:password user-spec))}))
