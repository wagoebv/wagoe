(ns wagoe.database-adapter-surface-test
  "Every method of `wagoe.platform.ports.database/DBAdapter`, against every
   adapter that implements it.

   The adapter tests that existed drove H2, and two assertions of SQLite. So
   PostgreSQL's and MySQL's `build-where`, `boolean->db`, `table-exists?` and
   `get-table-info` had never run in this repository, while the four adapter
   directories were near-copies of each other — the shape where a fix lands in
   one fork and not its neighbours.

   This is the safety net BOU-367 refactors behind: it must pass identically
   before and after the adapters are collapsed."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [support.embedded-pg :as epg]
            [wagoe.platform.shell.adapters.database.common.adapter :as adapter]
            [wagoe.platform.ports.database :as protocols]
            [wagoe.platform.shell.adapters.database.factory :as factory]
            [wagoe.platform.shell.adapters.database.h2.core :as h2]
            [wagoe.platform.shell.adapters.database.mysql.core :as mysql]
            [wagoe.platform.shell.adapters.database.postgresql.core :as postgresql]
            [wagoe.platform.shell.adapters.database.sqlite.core :as sqlite]
            [wagoe.platform.shell.adapters.database.utils.schema :as schema-utils]))

;; =============================================================================
;; The table
;; =============================================================================

(def ^:private adapters
  "One row per adapter, naming what the port says may differ between engines.

   `:booleans` and `:string-match` are the two decisions the whole of each
   `query.clj` turned on; they are stated here so a change to one adapter that
   should have been four is a failure rather than a silent divergence."
  [{:label "h2"         :make h2/new-adapter
    :dialect :ansi      :driver "org.h2.Driver"
    :booleans :native   :string-match :like}
   {:label "sqlite"     :make sqlite/new-adapter
    :dialect :sqlite    :driver "org.sqlite.JDBC"
    :booleans :int      :string-match :like}
   {:label "postgresql" :make postgresql/new-adapter
    :dialect nil        :driver "org.postgresql.Driver"
    :booleans :native   :string-match :ilike}
   {:label "mysql"      :make mysql/new-adapter
    :dialect :mysql     :driver "com.mysql.cj.jdbc.Driver"
    :booleans :int      :string-match :like}])

(defn- expect-true [{:keys [booleans]}] (if (= :int booleans) 1 true))
(defn- expect-false [{:keys [booleans]}] (if (= :int booleans) 0 false))

;; =============================================================================
;; Pure surface — no database required, so all four always run
;; =============================================================================

(deftest ^:unit every-adapter-names-its-dialect-and-driver
  (doseq [{:keys [label make dialect driver]} adapters]
    (testing label
      (let [adapter (make)]
        (is (= dialect (protocols/dialect adapter)))
        (is (= driver (protocols/jdbc-driver adapter)))))))

(deftest ^:unit every-adapter-offers-pool-defaults
  (doseq [{:keys [label make]} adapters]
    (testing label
      (let [defaults (protocols/pool-defaults (make))]
        (is (map? defaults))
        (is (pos-int? (:maximum-pool-size defaults))
            "a pool with no maximum is not a pool")))))

(deftest ^:unit every-adapter-builds-a-jdbc-url-its-own-driver-would-accept
  (doseq [[label config prefix]
          [["h2"         {:adapter :h2 :database-path "mem:surface"}      "jdbc:h2:"]
           ["sqlite"     {:adapter :sqlite :database-path "/tmp/s.db"}    "jdbc:sqlite:"]
           ["postgresql" {:adapter :postgresql :host "localhost"
                          :port 5432 :name "db"}                          "jdbc:postgresql:"]
           ["mysql"      {:adapter :mysql :host "localhost"
                          :port 3306 :name "db"}                          "jdbc:mysql:"]]]
    (testing label
      (let [adapter (factory/create-adapter config)
            url (protocols/jdbc-url adapter config)]
        (is (string? url))
        (is (str/starts-with? url prefix)
            (str label " produced " url))))))

(deftest ^:unit booleans-round-trip-through-every-adapter
  (doseq [{:keys [label make] :as row} adapters]
    (testing label
      (let [adapter (make)]
        (is (= (expect-true row) (protocols/boolean->db adapter true)))
        (is (= (expect-false row) (protocols/boolean->db adapter false)))
        (is (true? (protocols/db->boolean adapter (protocols/boolean->db adapter true))))
        (is (false? (protocols/db->boolean adapter (protocols/boolean->db adapter false))))))))

(deftest ^:unit build-where-agrees-on-everything-but-the-two-decisions
  (doseq [{:keys [label make string-match] :as row} adapters]
    (testing label
      (let [adapter (make)]

        (testing "no filters produces no clause"
          (is (nil? (protocols/build-where adapter {})))
          (is (nil? (protocols/build-where adapter nil))))

        (testing "a nil value is not a filter"
          (is (nil? (protocols/build-where adapter {:name nil}))))

        (testing "a string is a containment match, in this adapter's operator"
          (is (= [string-match :name "%jo%"]
                 (protocols/build-where adapter {:name "jo"}))))

        (testing "a vector is an IN"
          (is (= [:in :role [:admin :user]]
                 (protocols/build-where adapter {:role [:admin :user]}))))

        (testing "a boolean uses this adapter's representation"
          (is (= [:= :active (expect-true row)]
                 (protocols/build-where adapter {:active true}))))

        (testing "anything else is equality"
          (is (= [:= :count 3] (protocols/build-where adapter {:count 3}))))

        (testing "a single filter is the condition itself, several are an :and"
          (let [clause (protocols/build-where adapter {:name "jo" :count 3})]
            (is (= :and (first clause)))
            (is (= 2 (count (rest clause))))))))))

(deftest ^:unit the-schema-builder-gets-each-engine-its-own-column-types
  (testing "what `dialect` answers is what the DDL is generated from"
    (let [ddl-for (fn [make]
                    (schema-utils/generate-table-ddl
                     {:adapter (make)} "probe"
                     [:map [:id :uuid] [:label :string] [:flag :boolean]]))]

      (testing "sqlite"
        (let [ddl (ddl-for sqlite/new-adapter)]
          ;; It answered nil, which `(or (dialect a) :postgresql)` read as
          ;; PostgreSQL, so SQLite was handed UUID and VARCHAR (BOU-430).
          (is (str/includes? ddl "CHAR(36)"))
          (is (str/includes? ddl "TEXT"))
          (is (not (str/includes? ddl "VARCHAR(255)")))))

      (testing "postgresql — nil still means PostgreSQL, and only PostgreSQL"
        (let [ddl (ddl-for postgresql/new-adapter)]
          (is (str/includes? ddl "UUID"))
          (is (str/includes? ddl "VARCHAR(255)"))))

      (testing "mysql"
        (let [ddl (ddl-for mysql/new-adapter)]
          (is (str/includes? ddl "CHAR(36)"))
          (is (str/includes? ddl "TINYINT(1)"))))

      (testing "h2"
        (let [ddl (ddl-for h2/new-adapter)]
          (is (str/includes? ddl "UUID"))
          (is (str/includes? ddl "BOOLEAN")))))))

;; =============================================================================
;; Live surface — the methods that need a database
;; =============================================================================

(def ^:private mysql-port
  (parse-long (or (System/getenv "WAGOE_TEST_MYSQL_PORT") "3306")))

(defn- mysql-config []
  (factory/mysql-config "127.0.0.1" mysql-port
                        (or (System/getenv "WAGOE_TEST_MYSQL_DB") "audience")
                        "root"
                        (or (System/getenv "WAGOE_TEST_MYSQL_PASSWORD") "probe")))

(defonce ^:private mysql-up?
  (delay (try
           (with-open [c (jdbc/get-connection
                          (jdbc/get-datasource
                           {:dbtype "mysql" :host "127.0.0.1" :port mysql-port
                            :dbname (or (System/getenv "WAGOE_TEST_MYSQL_DB") "audience")
                            :user "root"
                            :password (or (System/getenv "WAGOE_TEST_MYSQL_PASSWORD") "probe")}))]
             (some? (.getMetaData c)))
           (catch Exception _ false))))

(defn- live-backends
  "Each is [label open], where `open` returns [db-context close!].

   Every one goes through `factory/db-context`, so the JDBC URL, the pool
   defaults and `init-connection!` are exercised by building it rather than
   asserted in isolation."
  []
  (cond-> [["h2" (fn []
                   (let [ctx (factory/db-context
                              (factory/h2-config (str "mem:surface_" (System/nanoTime)
                                                      ";DB_CLOSE_DELAY=-1")))]
                     [ctx #(factory/close-db-context! ctx)]))]
           ["sqlite" (fn []
                       (let [path (str (System/getProperty "java.io.tmpdir")
                                       "/surface-" (System/nanoTime) ".db")
                             ctx (factory/db-context (factory/sqlite-config path))]
                         [ctx (fn []
                                (factory/close-db-context! ctx)
                                (.delete (java.io.File. path)))]))]
           ["postgresql" (fn []
                           (let [pg (epg/start!)
                                 ctx (epg/db-context pg)]
                             [ctx (fn []
                                    (factory/close-db-context! ctx)
                                    (epg/stop! pg))]))]]
    @mysql-up?
    (conj ["mysql" (fn []
                     (let [ctx (factory/db-context (mysql-config))]
                       [ctx #(factory/close-db-context! ctx)]))])))

(def ^:private probe-ddl
  "CREATE TABLE surface_probe (
     id VARCHAR(64) NOT NULL PRIMARY KEY,
     label VARCHAR(255),
     amount INTEGER)")

(deftest ^:integration table-exists-and-get-table-info-answer-on-every-engine
  (doseq [[label open] (live-backends)]
    (testing label
      (let [[{:keys [adapter datasource]} close!] (open)]
        (try
          (jdbc/execute! datasource ["DROP TABLE IF EXISTS surface_probe"])

          (testing "a table nobody created does not exist"
            (is (false? (boolean (protocols/table-exists? adapter datasource :surface_probe)))))

          (jdbc/execute! datasource [probe-ddl])

          (testing "a table that was created does"
            (is (true? (boolean (protocols/table-exists? adapter datasource :surface_probe))))
            (is (false? (boolean (protocols/table-exists? adapter datasource :no_such_table)))))

          (testing "get-table-info returns the documented shape, one entry per column"
            (let [info (protocols/get-table-info adapter datasource :surface_probe)
                  by-name (into {} (map (juxt #(str/lower-case (:name %)) identity) info))]
              (is (= #{"id" "label" "amount"} (set (keys by-name))))
              (doseq [col info]
                (is (string? (:name col)))
                (is (string? (:type col)))
                (is (boolean? (:not-null col)))
                (is (boolean? (:primary-key col))))
              (is (true? (:primary-key (by-name "id"))))
              (is (false? (:primary-key (by-name "label"))))
              (is (true? (:not-null (by-name "id"))))))

          (finally
            (try (jdbc/execute! datasource ["DROP TABLE IF EXISTS surface_probe"])
                 (catch Exception _ nil))
            (close!)))))))

(def ^:private generated-probe-ddl
  "A column the database numbers and one it computes, per engine. SQLite's
   INTEGER PRIMARY KEY is the rowid, which it numbers itself."
  {"h2"         (str "CREATE TABLE generated_probe (id VARCHAR(64) PRIMARY KEY,"
                     " seq BIGINT GENERATED ALWAYS AS IDENTITY,"
                     " len INT GENERATED ALWAYS AS (CHAR_LENGTH(id)) NOT NULL,"
                     " plain INT NOT NULL)")
   "postgresql" (str "CREATE TABLE generated_probe (id VARCHAR(64) PRIMARY KEY,"
                     " seq BIGINT GENERATED ALWAYS AS IDENTITY,"
                     " len INT GENERATED ALWAYS AS (length(id)) STORED NOT NULL,"
                     " plain INT NOT NULL)")
   "mysql"      (str "CREATE TABLE generated_probe (id VARCHAR(64) PRIMARY KEY,"
                     " seq BIGINT NOT NULL AUTO_INCREMENT UNIQUE,"
                     " len INT AS (LENGTH(id)) STORED NOT NULL,"
                     " plain INT NOT NULL)")
   "sqlite"     (str "CREATE TABLE generated_probe (seq INTEGER PRIMARY KEY,"
                     " id VARCHAR(64) NOT NULL,"
                     " len INT GENERATED ALWAYS AS (length(id)) STORED NOT NULL,"
                     " plain INT NOT NULL)")})

(deftest ^:integration get-table-info-marks-columns-the-database-fills
  ;; Their column_default is NULL, so without :generated a caller took an
  ;; identity column for one an INSERT must supply (PR #568 review).
  (doseq [[label open] (live-backends)]
    (testing label
      (let [[{:keys [adapter datasource]} close!] (open)]
        (try
          (jdbc/execute! datasource ["DROP TABLE IF EXISTS generated_probe"])
          (jdbc/execute! datasource [(generated-probe-ddl label)])
          (let [info    (protocols/get-table-info adapter datasource :generated_probe)
                by-name (into {} (map (juxt :name identity) info))]
            (is (every? (comp boolean? :generated) info))
            (is (true? (:generated (by-name "seq"))) "identity / auto-increment / rowid")
            (is (false? (:generated (by-name "plain"))))
            ;; PRAGMA table_info leaves generated columns out on SQLite.
            (when-let [len (by-name "len")]
              (is (true? (:generated len)) "computed")))
          (finally
            (try (jdbc/execute! datasource ["DROP TABLE IF EXISTS generated_probe"])
                 (catch Exception _ nil))
            (close!)))))))

(deftest ^:integration column-names-come-back-lower-case-on-every-engine
  (testing "the port promises one shape, so a caller can compare without guessing"
    (doseq [[label open] (live-backends)]
      (testing label
        (let [[{:keys [adapter datasource]} close!] (open)]
          (try
            (jdbc/execute! datasource ["DROP TABLE IF EXISTS surface_probe"])
            (jdbc/execute! datasource [probe-ddl])
            (let [names (map :name (protocols/get-table-info adapter datasource :surface_probe))]
              (is (= #{"id" "label" "amount"} (set names))))
            (finally
              (try (jdbc/execute! datasource ["DROP TABLE IF EXISTS surface_probe"])
                   (catch Exception _ nil))
              (close!))))))))

(deftest ^:integration a-failing-session-statement-does-not-cancel-the-rest
  (testing "MySQL asks for a sql_mode, a timezone and a charset, in that order"
    ;; They used to share one try: MySQL 8 rejects NO_AUTO_CREATE_USER, so the
    ;; timezone and charset after it never ran and sessions stayed on the
    ;; server's local time (BOU-367).
    (if @mysql-up?
      (let [ctx (factory/db-context (mysql-config))]
        (try
          (let [q (fn [sql] (first (jdbc/execute! (:datasource ctx) [sql]
                                                  {:builder-fn rs/as-unqualified-lower-maps})))]
            (is (= "+00:00" (:tz (q "SELECT @@session.time_zone AS tz"))))
            (is (str/includes? (:m (q "SELECT @@session.sql_mode AS m"))
                               "STRICT_TRANS_TABLES")))
          (finally (factory/close-db-context! ctx))))
      (println "  note: MySQL not reachable — session-statement ordering unverified")))

  (testing "a statement this engine rejects does not stop the ones after it"
    (let [ran (atom [])
          ds (reify javax.sql.DataSource)]
      (with-redefs [jdbc/execute! (fn [_ statement]
                                    (swap! ran conj (first statement))
                                    (when (= "BOOM" (first statement))
                                      (throw (ex-info "rejected" {}))))]
        (adapter/run-session-statements! "probe" ds [["FIRST"] ["BOOM"] ["LAST"]]))
      (is (= ["FIRST" "BOOM" "LAST"] @ran)))))

(deftest ^:integration the-sweep-says-out-loud-which-engines-it-reached
  (let [reached (set (map first (live-backends)))]
    (is (contains? reached "h2"))
    (is (contains? reached "sqlite"))
    (is (contains? reached "postgresql"))
    (is (or (contains? reached "mysql") (not @mysql-up?))
        "MySQL was reachable but the sweep skipped it")
    (when-not (contains? reached "mysql")
      (println "  note: MySQL not reachable on port" mysql-port
               "— three of four engines swept. Set WAGOE_TEST_MYSQL_PORT."))))
