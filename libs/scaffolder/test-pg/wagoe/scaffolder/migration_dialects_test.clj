(ns wagoe.scaffolder.migration-dialects-test
  "What `generate`, `entity` and `field` write to migrations/, run by migratus
   as `bb migrate` runs it, up and down, on every database a project can use.

   The create migrations held CREATE TABLE and CREATE INDEX with no `--;;`
   between them. migratus then sends the file as one statement: PostgreSQL and
   H2 run all of it, SQLite runs the first statement and drops the rest, so the
   indexes were never created (BOU-569). MySQL refused the file outright.

   MySQL needs a server: `bb test:services up` starts one. Without it the sweep
   fails, rather than passing on three databases as if that were all four."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [migratus.core :as migratus]
            [next.jdbc :as jdbc]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.platform.shell.database.migrations :as migrations]
            [wagoe.scaffolder.ports :as ports]
            [wagoe.scaffolder.shell.service :as service])
  (:import [io.zonky.test.db.postgres.embedded EmbeddedPostgres]
           [java.sql DatabaseMetaData]))

(def ^:private mysql-port
  (parse-long (or (System/getenv "WAGOE_TEST_MYSQL_PORT") "3306")))

(defn- mysql-spec [dbname]
  {:dbtype "mysql" :host "127.0.0.1" :port mysql-port :dbname dbname
   :user "root" :password (or (System/getenv "WAGOE_TEST_MYSQL_PASSWORD") "probe")})

(defonce ^:private mysql-up?
  (delay (try (with-open [c (jdbc/get-connection (jdbc/get-datasource (mysql-spec "")))]
                (some? (.getMetaData c)))
              (catch Exception _ false))))

(defn- backends
  "[label make], where `make` returns [datasource stop!]."
  []
  (cond->
   ;; In the mode the platform opens H2 in, as a project does.
   [["h2" (fn [] (let [ctx (db-factory/db-context {:adapter :h2 :database-path (str "mem:dialects" (System/nanoTime))
                                                   :pool {:minimum-idle 1 :maximum-pool-size 2}})]
                   [(:datasource ctx) #(db-factory/close-db-context! ctx)]))]
    ["sqlite" (fn [] (let [f (java.io.File/createTempFile "dialects" ".db")]
                       [(jdbc/get-datasource {:jdbcUrl (str "jdbc:sqlite:" (.getPath f))})
                        #(.delete f)]))]
    ["postgresql" (fn [] (let [pg (.start (EmbeddedPostgres/builder))]
                           [(.getPostgresDatabase pg) #(.close pg)]))]]
    @mysql-up?
    (conj ["mysql" (fn [] (let [db     (str "scaffolder_" (System/nanoTime))
                                server (jdbc/get-datasource (mysql-spec ""))]
                            (jdbc/execute! server [(str "CREATE DATABASE " db)])
                            [(jdbc/get-datasource (mysql-spec db))
                             #(jdbc/execute! server [(str "DROP DATABASE " db)])]))])))

(def ^:private svc (service/create-scaffolder-service))

(defn- scaffold!
  "A module the way a user builds one: generate, then entity, then field."
  []
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "wagoe-dialects" (make-array java.nio.file.attribute.FileAttribute 0)))
        ok  (fn [r] (assert (:success r) (pr-str (:errors r))) r)]
    (ok (ports/generate-module svc {:module-name "billing" :base-ns "dialects"
                                    :entities [{:name "Invoice"
                                                :fields [{:name :number :type :string :required true :unique true}
                                                         {:name :reference :type :string :indexed true}
                                                         {:name :issued-at :type :inst :default "2026-01-01T00:00:00Z"}
                                                         {:name :notes :type :text :default "it's"}
                                                         {:name :data :type :json :required false}
                                                         {:name :status :type :enum :enum-values [:open :paid]}]}]
                                    :output-dir (.getPath dir)}))
    ;; The seconds tick between ids; entity and field would otherwise wait.
    (ok (ports/add-entity svc {:module-name "billing" :base-ns "dialects" :output-dir (.getPath dir)
                               :entity {:name "InvoiceLine" :belongs-to "Invoice"
                                        :fields [{:name :description :type :string}]}}))
    (ok (ports/add-field svc {:module-name "billing" :base-ns "dialects" :entity "Invoice"
                              :output-dir (.getPath dir)
                              :field {:name :code :type :string :required false :indexed true}}))
    (ok (ports/add-field svc {:module-name "billing" :base-ns "dialects" :entity "Invoice"
                              :output-dir (.getPath dir)
                              :field {:name :remark :type :text :required false :default "none"}}))
    ;; Copied under target/: migratus takes only a relative path.
    (let [copy (str "target/dialects-" (System/nanoTime))]
      (doseq [^java.io.File m (.listFiles (io/file dir "migrations"))]
        (io/make-parents (io/file copy (.getName m)))
        (io/copy m (io/file copy (.getName m))))
      copy)))

(defn- metadata-name
  "`s` as this engine stores an unquoted identifier."
  [^DatabaseMetaData md s]
  (if (.storesUpperCaseIdentifiers md) (str/upper-case s) s))

(defn- indexes [ds table]
  (with-open [c (jdbc/get-connection ds)]
    (let [md (.getMetaData c)]
      (with-open [rs (.getIndexInfo md (.getCatalog c) nil (metadata-name md table) false false)]
        (set (for [row (resultset-seq rs) :let [n (:index_name row)] :when n]
               (str/lower-case n)))))))

(defn- parents-of [ds table]
  (with-open [c (jdbc/get-connection ds)]
    (let [md (.getMetaData c)]
      (with-open [rs (.getImportedKeys md (.getCatalog c) nil (metadata-name md table))]
        (set (map (comp str/lower-case :pktable_name) (resultset-seq rs)))))))

(defn- tables [ds]
  (with-open [c (jdbc/get-connection ds)]
    (let [md (.getMetaData c)]
      (with-open [rs (.getTables md (.getCatalog c) nil "%" (into-array String ["TABLE"]))]
        (set (map (comp str/lower-case :table_name) (resultset-seq rs)))))))

(deftest ^:unit mysql-cannot-index-text-or-json-so-neither-is-offered
  (doseq [type [:text :json] flag [:unique :indexed]]
    (let [r (ports/generate-module svc {:module-name "billing" :base-ns "dialects" :dry-run true
                                        :entities [{:name "Invoice" :fields [{:name :x :type type flag true}]}]})]
      (is (false? (:success r)) (str type flag))
      (is (re-find #"string" (str (:errors r))) (pr-str (:errors r))))))

(deftest ^:integration the-sweep-covers-every-database
  (is (= 4 (count (backends)))
      (str "MySQL is not reachable on 127.0.0.1:" mysql-port
           ", so this run migrated " (count (backends)) " databases, not four.\n"
           "  Start it with `bb test:services up`, or point at another port with WAGOE_TEST_MYSQL_PORT.")))

(deftest ^:integration generated-migrations-run-up-and-down-everywhere
  (let [dir (scaffold!)]
    (doseq [[label make] (backends)]
      (testing label
        (let [[ds stop!] (make)
              config     (migrations/migratus-config ds dir)]
          (try
            (migratus/migrate config)
            (testing "every table and every index is there"
              (is (every? (tables ds) ["invoices" "invoice_lines"]))
              (is (every? (indexes ds "invoices")
                          ["idx_invoices_created_at" "idx_invoices_reference" "idx_invoices_code"]))
              (is (every? (indexes ds "invoice_lines")
                          ["idx_invoice_lines_created_at" "idx_invoice_lines_invoice_id"])))
            (testing "the line's foreign key is there"
              (is (= #{"invoices"} (parents-of ds "invoice_lines"))))
            (testing "the down migrations undo it all"
              ;; By id: `rollback` takes the latest `applied`, which MySQL keeps
              ;; to the second, so three migrations applied at once tie.
              (migratus/rollback-until-just-after config 0)
              (is (not-any? (tables ds) ["invoices" "invoice_lines"])))
            (finally (stop!))))))
    (run! io/delete-file (reverse (file-seq (io/file dir))))))
