(ns wagoe.seed-mysql-test
  "Seeding on MySQL, which refuses an ISO instant string in strict mode and
   Java-serialises a raw UUID (BOU-657). Needs `bb test:services up`."
  (:require [clojure.test :refer [deftest is]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [support.mysql :as mysql]
            [wagoe.platform.core.database.migration-sql :as migration-sql]
            [wagoe.platform.shell.database.seed :as seed])
  (:import [java.time Instant LocalDateTime ZoneOffset]))

(def ^:private ddl
  ;; What `bb scaffold generate` writes, as `migrate up` rewrites it for MySQL.
  ["CREATE TABLE invoices (
      id UUID PRIMARY KEY,
      number VARCHAR(255) NOT NULL UNIQUE,
      created_at TIMESTAMP WITH TIME ZONE NOT NULL,
      updated_at TIMESTAMP WITH TIME ZONE)"
   "CREATE TABLE invoice_line_items (
      id UUID PRIMARY KEY,
      invoice_id UUID NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,
      description VARCHAR(255) NOT NULL,
      created_at TIMESTAMP WITH TIME ZONE NOT NULL,
      updated_at TIMESTAMP WITH TIME ZONE)"])

(deftest ^:integration a-seed-with-timestamps-seeds-on-mysql
  (mysql/with-database "seed"
    (fn [{:keys [datasource]}]
      (doseq [s (migration-sql/for-mysql ddl)] (jdbc/execute! datasource [s]))
      (let [result (seed/seed! datasource
                               [[:invoices [{:id :invoice/acme :number "INV-1"
                                             :created-at "2026-09-01T09:00:00Z"}
                                            {:number "INV-2"}]]
                                [:invoice-line-items [{:invoice-id :invoice/acme :description "x"}]]])]
        (is (nil? (:error result)) (pr-str result))
        (is (= 3 (get-in result [:ok :rows]))))
      (let [[acme other] (jdbc/execute! datasource ["SELECT id, created_at FROM invoices ORDER BY number"]
                                        {:builder-fn rs/as-unqualified-lower-maps})]
        (is (= (Instant/parse "2026-09-01T09:00:00Z")
               (.toInstant ^LocalDateTime (:created_at acme) ZoneOffset/UTC)))
        (is (some? (:created_at other)) "the filled-in timestamp")
        (is (= [(:id acme)]
               (map :invoice_id (jdbc/execute! datasource ["SELECT invoice_id FROM invoice_line_items"]
                                               {:builder-fn rs/as-unqualified-lower-maps})))
            "the child names its parent's id as text")
        (is (parse-uuid (:id acme)))))))
