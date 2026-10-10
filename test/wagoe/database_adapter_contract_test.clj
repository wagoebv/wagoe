(ns wagoe.database-adapter-contract-test
  "What the four database engines must agree on, run on all four (ADR-039).

   `database-adapter-surface-test` checks each `DBAdapter` method answers.
   This checks the behaviour behind the answers: a value written comes back,
   strings match regardless of case, a rolled-back write is gone, a refused key
   is a :conflict, and each claimed capability works while each unclaimed one
   does not.

   A case an engine cannot pass yet is in `known-divergences`, with its reason
   and ticket. Everything unlisted must pass, and a listed case that passes
   fails too, so the list only shrinks. MySQL needs a server:
   `bb test:services up`. Without one this fails rather than passing on three."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [support.embedded-pg :as epg]
            [wagoe.core.utils.type-conversion :as tc]
            [wagoe.platform.database :as db]
            [wagoe.platform.ports.database :as protocols]
            [wagoe.platform.shell.adapters.database.factory :as factory])
  (:import [java.time Instant]
           [java.util Locale UUID]))

;; =============================================================================
;; Engines
;; =============================================================================

(def ^:private mysql-port
  (parse-long (or (System/getenv "WAGOE_TEST_MYSQL_PORT") "3306")))

(def ^:private mysql-password
  (or (System/getenv "WAGOE_TEST_MYSQL_PASSWORD") "probe"))

(defn- mysql-server []
  (jdbc/get-datasource {:dbtype "mysql" :host "127.0.0.1" :port mysql-port
                        :user "root" :password mysql-password}))

(defonce ^:private mysql-up?
  (delay (try (with-open [c (jdbc/get-connection (mysql-server))]
                (some? (.getMetaData c)))
              (catch Exception _ false))))

(defn- engines
  "Each is [engine open], `open` returning [db-context close!]. MySQL gets a
   fresh database, so runs do not share tables."
  []
  (cond->
   [[:h2 (fn []
           (let [ctx (factory/db-context
                      (factory/h2-config (str "mem:contract_" (System/nanoTime) ";DB_CLOSE_DELAY=-1")))]
             [ctx #(factory/close-db-context! ctx)]))]
    [:sqlite (fn []
               (let [path (str (System/getProperty "java.io.tmpdir") "/contract-" (System/nanoTime) ".db")
                     ctx  (factory/db-context (factory/sqlite-config path))]
                 [ctx (fn [] (factory/close-db-context! ctx) (.delete (java.io.File. path)))]))]
    [:postgresql (fn []
                   (let [pg (epg/start!) ctx (epg/db-context pg)]
                     [ctx (fn [] (factory/close-db-context! ctx) (epg/stop! pg))]))]]
    @mysql-up?
    (conj [:mysql (fn []
                    (let [dbname (str "contract_" (System/nanoTime))]
                      (jdbc/execute! (mysql-server) [(str "CREATE DATABASE " dbname)])
                      (let [ctx (factory/db-context
                                 (factory/mysql-config "127.0.0.1" mysql-port dbname "root" mysql-password))]
                        [ctx (fn []
                               (factory/close-db-context! ctx)
                               (jdbc/execute! (mysql-server) [(str "DROP DATABASE " dbname)]))])))])))

;; =============================================================================
;; Known divergences: [engine case] -> why, and which ticket closes it
;; =============================================================================

(def ^:private known-divergences
  {[:mysql :round-trip/uuid]
   {:ticket "BOU-574" :reason "a UUID parameter is Java-serialised, not written as text"}
   [:mysql :round-trip/instant]
   {:ticket "BOU-574" :reason "instants are written as ISO strings, which strict mode refuses"}
   [:mysql :round-trip/true]
   {:ticket "BOU-574" :reason "TINYINT(1) reads back as a Boolean, and db->boolean expects an int"}
   [:mysql :conflicts/unique-key]
   {:ticket "BOU-574" :reason "the constraint reader finds no field in MySQL's message"}})

;; =============================================================================
;; Cases. Each is (fn [ctx]) -> nil when the engine conforms, or what it did.
;; =============================================================================

(defn- adapter [ctx] (:adapter ctx))

(defn- ddl! [ctx sql] (db/execute-update! ctx {:raw sql}))

(defn- attempt
  "nil when `f` returns, the exception's message when it throws."
  [f]
  (try (f) nil (catch Exception e (or (ex-message e) (str (class e))))))

(defn- round-trip
  "Write `value` into a `logical` column, read it back and decode it."
  [ctx logical value encode decode]
  (let [table (str "rt_" (name logical))]
    (ddl! ctx (str "DROP TABLE IF EXISTS " table))
    (ddl! ctx (str "CREATE TABLE " table " (k INTEGER NOT NULL PRIMARY KEY, v "
                   (protocols/column-type (adapter ctx) logical) ")"))
    (db/execute-update! ctx {:insert-into (keyword table) :values [{:k 1 :v (encode value)}]})
    (let [row (first (db/execute-query! ctx {:select [:v] :from [(keyword table)]}))
          raw (val (first row))
          out (decode raw)]
      (when-not (= value out)
        (str "wrote " (pr-str value) ", read " (pr-str raw) " (" (some-> raw class .getName) ")"
             ", decoded " (pr-str out))))))

(defn- json-decode [raw]
  (when raw
    (json/parse-string (if (string? raw) raw (str (or (try (.getValue raw) (catch Exception _ nil)) raw))))))

(def ^:private round-trips
  {:uuid      [:uuid (UUID/randomUUID) identity tc/string->uuid]
   :instant   [:instant (Instant/ofEpochMilli 1767225600123) identity tc/string->instant]
   :true      [:boolean true ::adapter-boolean ::adapter-boolean]
   :false     [:boolean false ::adapter-boolean ::adapter-boolean]
   :unicode   [:text "Zoë — 東京 🚀" identity identity]
   :long-text [:text (apply str (repeat 70000 "x")) identity identity]
   :json      [:json {"a" [1 2] "b" "ü"} json/generate-string json-decode]
   :nil-uuid  [:uuid nil identity tc/string->uuid]})

(defn- round-trip-case [k]
  (fn [ctx]
    (let [[logical value encode decode] (round-trips k)
          a (adapter ctx)
          [encode decode] (if (= ::adapter-boolean encode)
                            [#(protocols/boolean->db a %) #(protocols/db->boolean a %)]
                            [encode decode])]
      (round-trip ctx logical value encode decode))))

(defn- strings-ignore-case [ctx]
  (ddl! ctx "DROP TABLE IF EXISTS people")
  (ddl! ctx "CREATE TABLE people (id INTEGER NOT NULL PRIMARY KEY, name VARCHAR(50))")
  (db/execute-update! ctx {:insert-into :people
                           :values [{:id 1 :name "John"} {:id 2 :name "johnny"} {:id 3 :name "Mary"}]})
  (let [ids (fn [where] (set (map #(val (first %))
                                  (db/execute-query! ctx {:select [:id] :from [:people] :where where}))))
        a   (adapter ctx)
        got {:build-where (ids (protocols/build-where a {:name "JOHN"}))
             :like        (ids (protocols/like a :name "%OHN%"))}]
    (when-not (= {:build-where #{1 2} :like #{1 2}} got)
      (str "matched " (pr-str got)))))

(defn- strings-ignore-unicode-case [ctx]
  (ddl! ctx "DROP TABLE IF EXISTS names_u")
  (ddl! ctx "CREATE TABLE names_u (id INTEGER NOT NULL PRIMARY KEY, name VARCHAR(50))")
  (db/execute-update! ctx {:insert-into :names_u :values [{:id 1 :name "Élodie"}]})
  (let [a   (adapter ctx)
        hit (fn [where] (seq (db/execute-query! ctx {:select [:id] :from [:names_u] :where where})))
        got {:build-where (boolean (hit (protocols/build-where a {:name "ÉLODIE"})))
             :like        (boolean (hit (protocols/like a :name "%élodie%")))}]
    (when-not (= {:build-where true :like true} got)
      (str "a stored \"Élodie\" matched " (pr-str got)))))

(defn- strings-ignore-case-in-any-locale
  "A Turkish JVM lowers \"ID\" to \"ıd\"; the database lowers it to \"id\". The
   match must not depend on which one folds the pattern."
  [ctx]
  (ddl! ctx "DROP TABLE IF EXISTS codes_tr")
  (ddl! ctx "CREATE TABLE codes_tr (id INTEGER NOT NULL PRIMARY KEY, code VARCHAR(20))")
  (db/execute-update! ctx {:insert-into :codes_tr :values [{:id 1 :code "ID-7"}]})
  (let [default (Locale/getDefault)]
    (try
      (Locale/setDefault (Locale/forLanguageTag "tr-TR"))
      (let [a   (adapter ctx)
            hit (fn [where] (seq (db/execute-query! ctx {:select [:id] :from [:codes_tr] :where where})))]
        (when-not (and (hit (protocols/build-where a {:code "ID"}))
                       (hit (protocols/like a :code "%ID%")))
          "\"ID\" did not match a stored \"ID-7\" under a Turkish locale"))
      (finally (Locale/setDefault default)))))

(def ^:private session-setting
  "Per engine, a setting Wagoe gives each connection, and what it reads."
  {:sqlite     ["PRAGMA foreign_keys" "1"]
   :mysql      ["SELECT @@session.time_zone" "+00:00"]
   :h2         ["SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS WHERE SETTING_NAME = 'TIME ZONE'" "UTC"]
   :postgresql ["SHOW statement_timeout" "30s"]})

(defn- every-connection-is-set-up
  "Held at once, so the pool has to open several (BOU-606: the settings reached
   whichever connection ran them first, and no other)."
  [ctx]
  (let [[sql expected] (session-setting (protocols/engine (adapter ctx)))
        conns          (doall (repeatedly 3 #(jdbc/get-connection (:datasource ctx))))]
    (try
      (let [seen (mapv #(str (val (first (jdbc/execute-one! % [sql])))) conns)]
        (when-not (every? #{expected} seen)
          (str sql " on three connections: " (pr-str seen))))
      (finally (run! #(.close ^java.sql.Connection %) conns)))))

(defn- index-twice
  "An index created twice is created once, on every engine, with or without
   IF NOT EXISTS (BOU-607)."
  [ctx]
  (ddl! ctx "DROP TABLE IF EXISTS idx_twice")
  (ddl! ctx "CREATE TABLE idx_twice (id INTEGER NOT NULL PRIMARY KEY, code VARCHAR(20))")
  (attempt #(dotimes [_ 2]
              (db/create-index-if-not-exists! ctx "uk_idx_twice_code" :idx_twice [:code] {:unique? true}))))

(defn- unique-index-on-duplicates
  "A unique index the rows break is a :conflict naming the table, not a raw
   driver error."
  [ctx]
  (ddl! ctx "DROP TABLE IF EXISTS idx_dups")
  (ddl! ctx "CREATE TABLE idx_dups (id INTEGER NOT NULL PRIMARY KEY, code VARCHAR(20))")
  (db/execute-update! ctx {:insert-into :idx_dups :values [{:id 1 :code "A"} {:id 2 :code "A"}]})
  (let [data (try (db/create-index-if-not-exists! ctx "uk_idx_dups_code" :idx_dups [:code] {:unique? true})
                  nil
                  (catch Exception e (ex-data e)))]
    (when-not (= {:type :conflict :table "idx_dups"} (select-keys data [:type :table]))
      (str "a unique index over duplicates gave " (pr-str (select-keys data [:type :table]))))))

(defn- rollback-undoes [ctx]
  (ddl! ctx "DROP TABLE IF EXISTS tx_probe")
  (ddl! ctx "CREATE TABLE tx_probe (id INTEGER NOT NULL PRIMARY KEY)")
  (attempt #(db/with-transaction [tx ctx]
              (db/execute-update! tx {:insert-into :tx_probe :values [{:id 1}]})
              (throw (ex-info "roll back" {:type :internal-error}))))
  (db/with-transaction [tx ctx]
    (db/execute-update! tx {:insert-into :tx_probe :values [{:id 2}]}))
  (let [ids (set (map #(val (first %)) (db/execute-query! ctx {:select [:id] :from [:tx_probe]})))]
    (when-not (= #{2} ids) (str "after one rollback and one commit, rows " (pr-str ids)))))

(defn- refused-key-is-a-conflict [ctx]
  (ddl! ctx "DROP TABLE IF EXISTS codes")
  (ddl! ctx "CREATE TABLE codes (id INTEGER NOT NULL PRIMARY KEY, code VARCHAR(20) NOT NULL UNIQUE)")
  (db/execute-update! ctx {:insert-into :codes :values [{:id 1 :code "A"}]})
  (let [data (try (db/execute-update! ctx {:insert-into :codes :values [{:id 2 :code "A"}]})
                  nil
                  (catch Exception e (ex-data e)))]
    (when-not (= {:type :conflict :constraint :unique :field :code}
                 (select-keys data [:type :constraint :field]))
      (str "a duplicate code gave " (pr-str (select-keys data [:type :constraint :field]))))))

;; Each probe returns nil when the database honours the capability.
(def ^:private capability-probes
  {:transactional-ddl
   (fn [ctx]
     (ddl! ctx "DROP TABLE IF EXISTS ddl_probe")
     (attempt #(db/with-transaction [tx ctx]
                 (ddl! tx "CREATE TABLE ddl_probe (id INTEGER)")
                 (throw (ex-info "roll back" {:type :internal-error}))))
     (when (db/table-exists? ctx :ddl_probe) "a rolled-back CREATE TABLE stayed"))

   :index-if-not-exists
   (fn [ctx]
     (ddl! ctx "DROP TABLE IF EXISTS ix_probe")
     (ddl! ctx "CREATE TABLE ix_probe (id INTEGER, c INTEGER)")
     (attempt #(dotimes [_ 2] (ddl! ctx "CREATE INDEX IF NOT EXISTS ix_probe_c ON ix_probe (c)"))))

   :column-if-not-exists
   (fn [ctx]
     (ddl! ctx "DROP TABLE IF EXISTS col_probe")
     (ddl! ctx "CREATE TABLE col_probe (id INTEGER)")
     (attempt #(dotimes [_ 2] (ddl! ctx "ALTER TABLE col_probe ADD COLUMN IF NOT EXISTS c INTEGER"))))

   :on-conflict
   (fn [ctx]
     (ddl! ctx "DROP TABLE IF EXISTS upsert_probe")
     (ddl! ctx "CREATE TABLE upsert_probe (id INTEGER NOT NULL PRIMARY KEY, n INTEGER)")
     (or (attempt #(dotimes [i 2]
                     (ddl! ctx (str "INSERT INTO upsert_probe (id, n) VALUES (1, " i ")"
                                    " ON CONFLICT (id) DO UPDATE SET n = excluded.n"))))
         (let [n (val (first (first (db/execute-query! ctx {:select [:n] :from [:upsert_probe]}))))]
           (when-not (= 1 n) (str "n is " n " after the upsert")))))

   :row-locks
   (fn [ctx]
     (ddl! ctx "DROP TABLE IF EXISTS lock_probe")
     (ddl! ctx "CREATE TABLE lock_probe (id INTEGER NOT NULL PRIMARY KEY)")
     (attempt #(db/with-transaction [tx ctx]
                 (db/execute-query! tx {:raw "SELECT id FROM lock_probe WHERE id = 1 FOR UPDATE"}))))

   :full-text
   (fn [ctx]
     (attempt #(db/execute-query! ctx {:raw "SELECT ts_rank(to_tsvector('simple', 'a b'), to_tsquery('simple', 'a')) AS r"})))

   :alter-foreign-key
   (fn [ctx]
     (ddl! ctx "DROP TABLE IF EXISTS fk_child")
     (ddl! ctx "DROP TABLE IF EXISTS fk_parent")
     (ddl! ctx "CREATE TABLE fk_parent (id INTEGER NOT NULL PRIMARY KEY)")
     (ddl! ctx "CREATE TABLE fk_child (id INTEGER NOT NULL PRIMARY KEY, parent_id INTEGER)")
     (attempt #(ddl! ctx "ALTER TABLE fk_child ADD CONSTRAINT fk_child_parent FOREIGN KEY (parent_id) REFERENCES fk_parent (id)")))

   :schemas
   (fn [ctx]
     (let [schema (str "contract_" (System/nanoTime))]
       (attempt #(db/with-transaction [tx ctx]
                   (ddl! tx (str "CREATE SCHEMA " schema))
                   (ddl! tx (str "SET LOCAL search_path TO " schema))
                   (ddl! tx "CREATE TABLE in_schema (id INTEGER)")
                   (db/execute-query! tx {:raw (str "SELECT id FROM " schema ".in_schema")})))))})

(def ^:private cases
  "Case name -> (fn [ctx]) returning nil when the engine conforms."
  (merge
   (into {} (for [k (keys round-trips)] [(keyword "round-trip" (name k)) (round-trip-case k)]))
   {:strings/ignore-case       strings-ignore-case
    :strings/any-locale        strings-ignore-case-in-any-locale
    :strings/unicode           strings-ignore-unicode-case
    :connections/session       every-connection-is-set-up
    :ddl/index-twice           index-twice
    :ddl/unique-over-duplicates unique-index-on-duplicates
    :transactions/rollback     rollback-undoes
    :conflicts/unique-key      refused-key-is-a-conflict}))

;; =============================================================================
;; The sweep
;; =============================================================================

(defn- check
  "Assert `failure` (nil when it passed) against the divergence list."
  [engine case-name failure]
  (if-let [{:keys [reason ticket]} (known-divergences [engine case-name])]
    (is (some? failure)
        (str engine " " case-name " now passes: remove it from known-divergences (" ticket ", " reason ")"))
    (is (nil? failure) (str engine " " case-name ": " failure))))

(deftest ^:integration every-engine-is-swept
  (is (= 4 (count (engines)))
      (str "MySQL is not reachable on 127.0.0.1:" mysql-port "; start it with `bb test:services up`")))

(deftest ^:integration the-engines-agree
  (doseq [[engine open] (engines)]
    (testing (name engine)
      (let [[ctx close!] (open)]
        (try
          (is (= engine (protocols/engine (adapter ctx))))
          (is (= engine (db/engine-of (:datasource ctx))) "engine-of reads the driver the same way")
          (doseq [[case-name f] (sort-by key cases)]
            (check engine case-name (try (f ctx) (catch Exception e (str "threw " (ex-message e))))))
          (finally (close!)))))))

(deftest ^:integration capabilities-are-claimed-exactly-when-honoured
  (doseq [[engine open] (engines)]
    (testing (name engine)
      (let [[ctx close!] (open)]
        (try
          (let [claimed (protocols/capabilities (adapter ctx))]
            (is (every? protocols/capability-keywords claimed) (pr-str claimed))
            (is (= protocols/capability-keywords (set (keys capability-probes)))
                "every capability has a probe")
            (doseq [[capability probe] (sort-by key capability-probes)]
              (let [failure (try (probe ctx) (catch Exception e (str "threw " (ex-message e))))]
                (if (claimed capability)
                  (is (nil? failure) (str engine " claims " capability " but: " failure))
                  (is (some? failure) (str engine " honours " capability " but does not claim it"))))))
          (finally (close!)))))))
