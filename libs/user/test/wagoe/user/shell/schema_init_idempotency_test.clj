(ns wagoe.user.shell.schema-init-idempotency-test
  "Regression: initialize-user-schema! must be idempotent across reconnects.

   H2 in-memory databases created with DB_CLOSE_DELAY=-1 keep their data alive
   after a connection pool closes, but when a *new* pool re-opens the same
   named DB, CHECK constraint clauses are reloaded empty and reject even valid
   enum values (`Check constraint invalid: \"chk_...: \"`). This is exactly the
   real-world 're-initialise an existing DB / re-run migrations' path.

   Re-running initialize-user-schema! must heal those constraints so valid
   enum values (e.g. role='admin') still insert successfully."
  (:require [wagoe.platform.shell.adapters.database.h2.core :as h2]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.persistence :as persistence]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc.connection :as connection])
  (:import [com.zaxxer.hikari HikariDataSource]
           [java.util UUID]))

(defn- open-pool
  "Open a fresh Hikari pool for a fixed-name persisted in-memory H2 DB."
  [db-name]
  (connection/->pool
   com.zaxxer.hikari.HikariDataSource
   {:jdbcUrl (str "jdbc:h2:mem:" db-name
                  ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
                  ";DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
    :username "sa"
    :password ""}))

(defn- create-admin!
  [db-ctx]
  (let [repo (persistence/create-user-repository db-ctx)
        email (str "idempotency-" (UUID/randomUUID) "@example.com")
        created (ports/create-user repo
                                   {:email email
                                    :name "Idempotency User"
                                    :role :admin
                                    :active true
                                    :password-hash "x"})]
    [repo email created]))

(deftest ^:integration reinit-after-reconnect-preserves-enum-check-constraints
  (testing "re-running initialize-user-schema! on a re-opened DB still accepts valid enum values"
    (let [db-name (str "schema-idempotency-" (UUID/randomUUID))
          ^HikariDataSource ds1 (open-pool db-name)]
      (try
        ;; First init creates the tables (with named enum CHECK constraints)
        ;; and a valid admin insert succeeds.
        (let [ctx1 {:datasource ds1 :adapter (h2/new-adapter)}]
          (persistence/initialize-user-schema! ctx1)
          (let [[_ _ created] (create-admin! ctx1)]
            (is (some? (:id created)) "admin insert works on the fresh DB")))
        (finally (.close ds1)))

      ;; A NEW pool re-opens the same persisted DB. Without the idempotent
      ;; repair, the reloaded CHECK constraints reject role='admin'.
      (let [^HikariDataSource ds2 (open-pool db-name)]
        (try
          (let [ctx2 {:datasource ds2 :adapter (h2/new-adapter)}]
            (persistence/initialize-user-schema! ctx2)
            (let [[repo email created] (create-admin! ctx2)]
              (is (some? (:id created)) "admin insert works after reconnect + re-init")
              (is (= :admin (:role (ports/find-user-by-email repo email))))
              ;; The re-added constraint must still *enforce* — an out-of-enum
              ;; role has to be rejected, proving repair didn't drop it or make
              ;; it permissive.
              (is (thrown? Exception
                           (ports/create-user
                            repo
                            {:email (str "invalid-" (UUID/randomUUID) "@example.com")
                             :name "Invalid Role User"
                             :role :superuser
                             :active true
                             :password-hash "x"}))
                  "out-of-enum role is still rejected after repair")))
          (finally (.close ds2)))))))

(deftest ^:integration double-init-on-same-connection-is-safe
  (testing "calling initialize-user-schema! twice on the same context still accepts valid enum values"
    (let [db-name (str "schema-idempotency-same-" (UUID/randomUUID))
          ^HikariDataSource ds (open-pool db-name)]
      (try
        (let [ctx {:datasource ds :adapter (h2/new-adapter)}]
          (persistence/initialize-user-schema! ctx)
          (persistence/initialize-user-schema! ctx)
          (let [[_ _ created] (create-admin! ctx)]
            (is (some? (:id created)))))
        (finally (.close ds))))))
