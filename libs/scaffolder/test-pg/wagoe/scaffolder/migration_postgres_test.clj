(ns wagoe.scaffolder.migration-postgres-test
  "The generated migration's timestamps, written and read back on PostgreSQL.

   The H2 and SQLite tests beside this one check that the migration runs and
   what column types it creates; neither can see what PostgreSQL does with a
   value. That is where the plain TIMESTAMP audit columns went wrong: connected
   as the platform connects (`stringtype=unspecified`), a `…10:00:50Z` string
   written into one kept only its wall time, and with the JVM in
   Europe/Amsterdam it read back as 08:00:50Z (BOU-522).

   Lives under test-pg/, which only the `:test/pg` alias puts on the
   classpath: it needs embedded PostgreSQL and the platform library, which a
   plain `clojure -M:test` should not resolve. CI runs the scaffolder suite
   with :test/pg, and tools' check_test requires it to."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [wagoe.platform.shell.adapters.database.postgresql.connection :as pg-conn]
            [wagoe.scaffolder.core.generators :as gen]
            [wagoe.scaffolder.core.template :as template])
  (:import [io.zonky.test.db.postgres.embedded EmbeddedPostgres]
           [java.util TimeZone]))

(def ^:private ctx
  (template/build-module-context
   {:module-name "billing"
    :base-ns     "app"
    :entities    [{:name   "Payment"
                   :fields [{:name :reference :type :string}
                            {:name :paid-at   :type :inst}]}]}))

(defn- statements [sql]
  (->> (str/split sql #";")
       (map (fn [s] (->> (str/split-lines s)
                         (remove #(str/starts-with? (str/trim %) "--"))
                         (str/join "\n"))))
       (map str/trim)
       (remove str/blank?)))

(defn- in-jvm-zone
  "Run `f` with the JVM default zone set to `zone-id`. pgjdbc sends the JVM's
   zone as the session TimeZone when it connects, so it must be set before
   the first connection."
  [zone-id f]
  (let [original (TimeZone/getDefault)]
    (try
      (TimeZone/setDefault (TimeZone/getTimeZone ^String zone-id))
      (f)
      (finally (TimeZone/setDefault original)))))

(deftest ^:integration instants-round-trip-on-postgresql-in-a-non-utc-jvm
  (in-jvm-zone
   "Europe/Amsterdam"
   (fn []
     (with-open [pg (.start (EmbeddedPostgres/builder))]
       (let [ds      (jdbc/get-datasource
                      {:jdbcUrl  (pg-conn/build-jdbc-url {:host "localhost" :port (.getPort pg) :name "postgres"})
                       :user     "postgres"
                       :password "postgres"})
             instant (java.time.Instant/parse "2026-09-01T10:00:50Z")]
         (doseq [s (statements (gen/generate-migration-file ctx "20260925000000"))]
           (jdbc/execute! ds [s]))
         ;; Written as the admin writes it: an ISO string carrying `Z`.
         (jdbc/execute! ds ["INSERT INTO payments (id, reference, created_at, updated_at, paid_at) VALUES (?, ?, ?, ?, ?)"
                            (random-uuid) "r1" "2026-09-01T10:00:50Z" "2026-09-01T10:00:50Z" "2026-09-01T10:00:50Z"])
         (let [row (jdbc/execute-one! ds ["SELECT created_at, updated_at, paid_at FROM payments"]
                                      {:builder-fn rs/as-unqualified-lower-maps})]
           (testing "every generated instant column reads back the instant that was written"
             (doseq [c [:created_at :updated_at :paid_at]]
               (is (= instant (.toInstant ^java.sql.Timestamp (get row c))) (name c))))))))))
