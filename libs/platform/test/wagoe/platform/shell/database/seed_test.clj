(ns wagoe.platform.shell.database.seed-test
  "Seeding a real database: rows without ids or timestamps, and children that
   name their parent by a symbolic id (BOU-588)."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [wagoe.platform.shell.database.seed :as sut])
  (:import [java.io File]))

(def ^:private ddl
  ;; What `bb scaffold generate` writes for an invoice and its line items.
  ["CREATE TABLE invoices (
      id UUID PRIMARY KEY,
      number VARCHAR(255) NOT NULL UNIQUE,
      status VARCHAR(50) DEFAULT 'entered' NOT NULL,
      created_at TIMESTAMP WITH TIME ZONE NOT NULL,
      updated_at TIMESTAMP WITH TIME ZONE)"
   "CREATE TABLE invoice_line_items (
      id UUID PRIMARY KEY,
      invoice_id UUID NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,
      description VARCHAR(255) NOT NULL,
      created_at TIMESTAMP WITH TIME ZONE NOT NULL,
      updated_at TIMESTAMP WITH TIME ZONE)"])

(def ^:private seed
  [[:invoices [{:id :invoice/acme :number "INV-1" :status :entered}
               {:id :invoice/globex :number "INV-2" :status :paid}
               {:number "INV-3" :status :entered}]]
   [:invoice-line-items [{:invoice-id :invoice/acme :description "Consulting"}
                         {:invoice-id :invoice/acme :description "Travel"}
                         {:invoice-id :invoice/globex :description "Hosting"}]]])

(defn- h2 []
  (jdbc/get-datasource {:jdbcUrl (str "jdbc:h2:mem:seed-" (random-uuid) ";DB_CLOSE_DELAY=-1")}))

(defn- sqlite []
  (let [f (File/createTempFile "seed-test" ".db")]
    (.deleteOnExit f)
    (jdbc/get-datasource {:jdbcUrl (str "jdbc:sqlite:" (.getPath f) "?foreign_keys=on")})))

(defn- query [ds sql]
  (jdbc/execute! ds [sql] {:builder-fn rs/as-unqualified-lower-maps}))

(deftest ^:integration a-seed-without-ids-or-timestamps-seeds
  (doseq [[db ds] [["h2" (h2)] ["sqlite" (sqlite)]]]
    (testing db
      (doseq [s ddl] (jdbc/execute! ds [s]))
      (let [result (sut/seed! ds seed)]
        (is (nil? (:error result)) (pr-str result))
        (is (= 6 (get-in result [:ok :rows]))))
      (let [invoices (query ds "SELECT id, number, status, created_at, updated_at FROM invoices ORDER BY number")
            items    (query ds "SELECT i.number, l.description FROM invoice_line_items l
                                JOIN invoices i ON i.id = l.invoice_id ORDER BY l.description")]
        (is (every? (comp some? :id) invoices))
        (is (= 3 (count (distinct (map (comp str :id) invoices)))))
        (is (every? (comp some? :created_at) invoices))
        (is (every? (comp some? :updated_at) invoices))
        (is (= ["entered" "paid" "entered"] (map :status invoices)))
        (is (= [["INV-1" "Consulting"] ["INV-2" "Hosting"] ["INV-1" "Travel"]]
               (map (juxt :number :description) items))
            "each child hangs off the parent its symbolic id named")))))

(deftest ^:integration explicit-ids-still-seed
  (let [ds (h2)
        id "11111111-0000-4000-8000-000000000001"]
    (doseq [s ddl] (jdbc/execute! ds [s]))
    (is (nil? (:error (sut/seed! ds [[:invoices [{:id id :number "INV-1" :created-at "2026-09-01T09:00:00Z"}]]
                                     [:invoice-line-items [{:id "22222222-0000-4000-8000-000000000001"
                                                            :invoice-id id :description "x"
                                                            :created-at "2026-09-01T09:00:00Z"}]]]))))
    (is (= id (str (:invoice_id (first (query ds "SELECT invoice_id FROM invoice_line_items"))))))))

(deftest ^:integration a-refused-seed-inserts-nothing
  (let [ds (h2)]
    (doseq [s ddl] (jdbc/execute! ds [s]))
    (is (= :validation-error
           (get-in (sut/seed! ds [[:invoices [{:id :invoice/acme :number "INV-1"}]]
                                  [:invoice-line-items [{:invoice-id :invoice/acmee :description "x"}]]])
                   [:error :type])))
    (is (empty? (query ds "SELECT * FROM invoices")))))

(deftest ^:integration an-application-without-an-admin-is-told
  (let [ds (h2)]
    (is (some? (io/resource "wagoe/boot-tables/user.edn")) "the user module is on this classpath")
    (is (sut/admin-missing? ds) "no users table: a reset dropped it")
    (jdbc/execute! ds ["CREATE TABLE users (id UUID PRIMARY KEY, role VARCHAR(50))"])
    (is (sut/admin-missing? ds) "an empty users table")
    (jdbc/execute! ds ["INSERT INTO users VALUES (RANDOM_UUID(), 'user')"])
    (is (sut/admin-missing? ds) "users, but no admin")
    (jdbc/execute! ds ["INSERT INTO users VALUES (RANDOM_UUID(), 'admin')"])
    (is (not (sut/admin-missing? ds)))))
