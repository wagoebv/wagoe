(ns wagoe.platform.shell.adapters.database.key-columns-test
  "A refused key over several columns names them where the driver's message
   does not: PostgreSQL's Detail in another language, MySQL's key name. And
   never a key on another table than the statement's (BOU-721)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [support.embedded-pg :as epg]
            [wagoe.platform.database :as db]
            [wagoe.platform.shell.adapters.database.common.adapter :as adapter]
            [wagoe.platform.shell.adapters.database.common.introspection :as introspection]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.platform.shell.adapters.database.mysql.core :as mysql-adapter])
  (:import [java.lang.reflect InvocationHandler InvocationTargetException Method Proxy]
           [java.sql Connection PreparedStatement SQLException]
           [javax.sql DataSource]
           [org.postgresql.util PSQLException ServerErrorMessage]))

(def ^:private ^:dynamic *pg* nil)
(def ^:private ^:dynamic *server* nil)

(defn- create-tables! [ctx]
  (doseq [sql ["CREATE SCHEMA km_tenant"
               "CREATE TABLE km_lines (id INT PRIMARY KEY, invoice_id INT, position INT, CONSTRAINT uq_km_lines UNIQUE (invoice_id, position))"
               ;; The same names in another schema, the key's columns the other way round.
               "CREATE TABLE km_tenant.km_lines (id INT PRIMARY KEY, invoice_id INT, position INT, CONSTRAINT uq_km_lines UNIQUE (position, invoice_id))"
               "CREATE TABLE km_emails (id INT PRIMARY KEY, email VARCHAR(50))"
               "CREATE UNIQUE INDEX uq_km_emails_lower ON km_emails (lower(email))"
               ;; The rows each test's insert collides with.
               "INSERT INTO km_lines VALUES (1, 1, 2)"
               "INSERT INTO km_tenant.km_lines VALUES (1, 1, 2)"
               "INSERT INTO km_emails VALUES (1, 'a@b.c')"]]
    (db/execute-update! ctx {:raw sql})))

(use-fixtures :once
  (fn [f]
    (let [pg  (epg/start!)
          ctx (epg/db-context pg)]
      (try
        (create-tables! ctx)
        (binding [*pg* ctx *server* pg] (f))
        (finally
          (db-factory/close-db-context! ctx)
          (epg/stop! pg))))))

(defn- delegating
  "`real` behind `iface`, each call passed to `(around method-name args call)`."
  [^Class iface real around]
  (Proxy/newProxyInstance
   (.getClassLoader iface) (into-array Class [iface])
   (reify InvocationHandler
     (invoke [_ _ m args]
       (around (.getName ^Method m) args
               #(try (.invoke ^Method m real args)
                     (catch InvocationTargetException e (throw (.getCause e)))))))))

(defn- refusing
  "`ds`, on which a statement writing `table` that the database refuses throws
   `(replace e)` in place of its exception `e`. The statement still runs, so a
   transaction it is in is aborted as it would be."
  [^DataSource ds table replace]
  (let [statement (fn [ps]
                    (delegating PreparedStatement ps
                                (fn [method _ call]
                                  (if (str/starts-with? method "execute")
                                    (try (call) (catch SQLException e (throw (replace e))))
                                    (call)))))
        connection (fn [conn]
                     (delegating Connection conn
                                 (fn [method args call]
                                   (if (and (= "prepareStatement" method)
                                            (str/includes? (str/lower-case (str (first args))) table))
                                     (statement (call))
                                     (call)))))]
    (reify DataSource
      (getConnection [_] (connection (.getConnection ds))))))

(defn- in-german
  "PostgreSQL's refusal `e` as a server with lc_messages=de_DE reports it: the
   same names, the Detail in German."
  [^PSQLException e]
  (let [m (.getServerErrorMessage e)]
    (PSQLException.
     (ServerErrorMessage.
      (str "SFEHLER\u0000VERROR\u0000C23505"
           "\u0000Mdoppelter Schlüsselwert verletzt Unique-Constraint »" (.getConstraint m) "«"
           "\u0000DSchlüssel »(invoice_id, position)=(1, 2)« existiert bereits."
           "\u0000s" (.getSchema m) "\u0000t" (.getTable m) "\u0000n" (.getConstraint m)
           "\u0000Rerrmsg\u0000")))))

(defn- thrown [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e e)))

(defn- german-ctx [table]
  (update *pg* :datasource refusing table in-german))

(deftest ^:integration a-detail-in-another-language-is-read-from-the-catalog
  (let [ctx (german-ctx "km_lines")]
    (is (= {:type :conflict :constraint :unique :fields [:invoice-id :position]}
           (ex-data (thrown #(db/execute-update! ctx ["INSERT INTO km_lines VALUES (2, 1, 2)"])))))
    (testing "in a transaction the refusal aborted"
      (is (= {:type :conflict :constraint :unique :fields [:invoice-id :position]}
             (ex-data (thrown #(db/with-transaction [tx ctx]
                                 (db/execute-update! tx ["INSERT INTO km_lines VALUES (2, 1, 2)"])))))))
    (testing "in the schema the server names"
      (is (= {:type :conflict :constraint :unique :fields [:position :invoice-id]}
             (ex-data (thrown #(db/execute-update! ctx ["INSERT INTO km_tenant.km_lines VALUES (2, 1, 2)"])))))))
  (testing "a key on an expression names no field"
    (is (= {:type :conflict :constraint :unique}
           (ex-data (thrown #(db/execute-update! (german-ctx "km_emails")
                                                 ["INSERT INTO km_emails VALUES (2, 'A@b.c')"])))))))

(defn- millis [f]
  (let [t (System/nanoTime)]
    [(try (f) (catch clojure.lang.ExceptionInfo e e)) (quot (- (System/nanoTime) t) 1000000)]))

(deftest ^:integration a-refusal-in-a-transaction-does-not-wait-for-the-pool
  ;; One connection, held by the transaction: the lookup cannot have another.
  (let [pool (epg/db-context *server* {:pool {:minimum-idle 1 :maximum-pool-size 1
                                              :connection-timeout-ms 10000}})
        ctx  (update pool :datasource refusing "km_lines" in-german)
        in-tx #(db/with-transaction [tx ctx]
                 (db/execute-update! tx ["INSERT INTO km_lines VALUES (2, 1, 2)"]))]
    (try
      (testing "out of connections: no fields, and well before the pool's timeout"
        (let [[ex ms] (millis in-tx)]
          (is (= {:type :conflict :constraint :unique} (ex-data ex)))
          (is (< ms 5000) (str ms " ms"))))
      (testing "once looked up, the columns are remembered"
        (is (= [:invoice-id :position]
               (:fields (ex-data (thrown #(db/execute-update! ctx ["INSERT INTO km_lines VALUES (2, 1, 2)"]))))))
        (let [[ex ms] (millis in-tx)]
          (is (= [:invoice-id :position] (:fields (ex-data ex))))
          (is (< ms 5000) (str ms " ms"))))
      (finally (db-factory/close-db-context! pool)))))

(deftest ^:integration the-catalog-lookup-reads-one-table
  (is (= ["invoice_id" "position"] (introspection/pg-key-columns (:datasource *pg*) "km_lines" "uq_km_lines")))
  (is (= ["position" "invoice_id"] (introspection/pg-key-columns (:datasource *pg*) :km_tenant/km_lines "uq_km_lines")))
  (is (= [] (introspection/pg-key-columns (:datasource *pg*) "km_emails" "uq_km_lines")) "another table's key")
  (is (nil? (introspection/pg-key-columns (:datasource *pg*) "km_emails" "uq_km_emails_lower"))))

(defn- mysql-refusal
  "A ctx whose INSERT into km_lines MySQL refuses with `message`, and whose key
   lookups land in `asked`."
  [message asked]
  {:adapter    (adapter/new-adapter (assoc mysql-adapter/spec
                                           :key-columns (fn [_ table key]
                                                          (swap! asked conj [table key])
                                                          ["invoice_id" "position"])))
   :datasource (refusing (:datasource *pg*) "km_lines"
                         (fn [_] (SQLException. ^String message "23000" (int 1062))))})

(deftest ^:integration a-key-on-another-table-is-not-looked-up
  ;; A trigger, or a value, can make MySQL's message name another table's key.
  (let [asked (atom [])
        ctx   (mysql-refusal "Duplicate entry 'x' for key 'users.uq_users_email_tenant'" asked)
        ex    (thrown #(db/execute-update! ctx ["INSERT INTO km_lines VALUES (3, 1, 2)"]))]
    (is (= {:type :conflict :constraint :unique} (ex-data ex)))
    (is (= [] @asked)))
  (testing "the key after the value, not one the value names"
    (let [asked (atom [])
          ctx   (mysql-refusal "Duplicate entry 'x' for key 'users.email' for key 'km_lines.uq_km_lines'" asked)]
      (is (= {:type :conflict :constraint :unique :fields [:invoice-id :position]}
             (ex-data (thrown #(db/execute-update! ctx ["INSERT INTO km_lines VALUES (3, 1, 2)"])))))
      (is (= [["km_lines" "uq_km_lines"]] @asked)))))
