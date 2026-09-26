(ns wagoe.scaffolder.migration-executes-test
  "A generated migration has to run, not only read right.

   The scaffolder emits one migration for every database — SQLite in a
   generated project, H2 under the test profile, PostgreSQL in production —
   and until now it was only checked as a string. `:inst` fields were emitted
   as TIMESTAMPTZ, which H2 rejects outright (Unknown data type), and the
   audit columns as TIMESTAMP without a zone, which PostgreSQL stores as local
   wall time: a created_at written as `…10:00:50Z` read back as 08:00:50Z on
   an Amsterdam server (BOU-522)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [wagoe.scaffolder.core.generators :as gen]
            [wagoe.scaffolder.core.template :as template]))

(def ^:private ctx
  (template/build-module-context
   {:module-name "billing"
    :base-ns     "app"
    :entities    [{:name   "Payment"
                   :fields [{:name :reference :type :string :indexed true}
                            {:name :paid-at   :type :inst}
                            {:name :due-on    :type :date}]}]}))

(defn- statements
  "The SQL statements in a migration file, comments dropped."
  [sql]
  (->> (str/split sql #";")
       (map (fn [s] (->> (str/split-lines s)
                         (remove #(str/starts-with? (str/trim %) "--"))
                         (str/join "\n"))))
       (map str/trim)
       (remove str/blank?)))

(defn- run-migration! [ds]
  (doseq [s (statements (gen/generate-migration-file ctx "20260925000000"))]
    (jdbc/execute! ds [s])))

(deftest ^:integration the-generated-migration-runs-on-h2
  (let [ds (jdbc/get-datasource {:jdbcUrl (str "jdbc:h2:mem:mig" (System/nanoTime) ";DB_CLOSE_DELAY=-1")})]
    (run-migration! ds)
    (testing "every instant column is zone-aware, the date column is a plain DATE"
      (let [types (->> (jdbc/execute! ds [(str "SELECT column_name, data_type FROM information_schema.columns"
                                               " WHERE table_name = 'PAYMENTS'")]
                                      {:builder-fn rs/as-unqualified-lower-maps})
                       (map (juxt (comp str/lower-case :column_name) :data_type))
                       (into {}))]
        (doseq [c ["created_at" "updated_at" "deleted_at" "paid_at"]]
          (is (= "TIMESTAMP WITH TIME ZONE" (types c)) c))
        (is (= "DATE" (types "due_on")))))))

(deftest ^:integration the-generated-migration-runs-on-sqlite
  (let [f  (java.io.File/createTempFile "mig" ".db")
        ds (jdbc/get-datasource {:jdbcUrl (str "jdbc:sqlite:" (.getPath f))})]
    (try
      (run-migration! ds)
      (is (= 1 (count (jdbc/execute! ds ["SELECT name FROM sqlite_master WHERE name = 'payments'"]))))
      (finally (.delete f)))))
