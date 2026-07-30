(ns wagoe.user.shell.service-logout-audit-test
  (:require [wagoe.platform.shell.adapters.database.h2.core :as h2]
            [wagoe.user.ports :as user-ports]
            [wagoe.user.shell.persistence :as persistence]
            [wagoe.user.shell.service :as user-service]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc.connection :as connection])
  (:import [com.zaxxer.hikari HikariDataSource]
           [java.time Instant]
           [java.util UUID]))

(def test-db-context (atom nil))
(def test-user-repository (atom nil))
(def test-session-repository (atom nil))
(def test-audit-repository (atom nil))
(def test-service (atom nil))

(defn setup-test-db []
  (let [^HikariDataSource datasource (connection/->pool
                                      com.zaxxer.hikari.HikariDataSource
                                      {:jdbcUrl (str "jdbc:h2:mem:logout-audit-" (UUID/randomUUID) ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
                                       :username "sa"
                                       :password ""})
        db-ctx {:datasource datasource
                :adapter (h2/new-adapter)}]
    (reset! test-db-context db-ctx)
    (persistence/initialize-user-schema! db-ctx)
    (reset! test-user-repository (persistence/create-user-repository db-ctx))
    (reset! test-session-repository (persistence/create-session-repository db-ctx))
    (reset! test-audit-repository (persistence/create-audit-repository db-ctx {:default-limit 20 :max-limit 100}))
    (reset! test-service (user-service/create-user-service
                          @test-user-repository
                          @test-session-repository
                          @test-audit-repository
                          {}
                          nil))))

(defn teardown-test-db []
  (when-let [db-ctx @test-db-context]
    (when-let [^HikariDataSource datasource (:datasource db-ctx)]
      (.close datasource)))
  (reset! test-service nil)
  (reset! test-audit-repository nil)
  (reset! test-session-repository nil)
  (reset! test-user-repository nil)
  (reset! test-db-context nil))

(use-fixtures :once (fn [f] (setup-test-db) (try (f) (finally (teardown-test-db)))))

(deftest ^:integration logout-creates-audit-entry-test
  (testing "logout writes a :logout audit record for the session user"
    (let [service @test-service
          user-repo @test-user-repository
          session-repo @test-session-repository
          audit-repo @test-audit-repository
          email (str "logout-audit-" (UUID/randomUUID) "@example.com")
          created-user (.create-user user-repo {:email email
                                                :name "Logout Audit User"
                                                :role :user
                                                :active true
                                                :password-hash "bcrypt+sha512$test"})
          session-token (str "session-" (UUID/randomUUID))
          created-session (.create-session session-repo {:user-id (:id created-user)
                                                         :session-token session-token
                                                         :expires-at (.plusSeconds (Instant/now) 3600)
                                                         :ip-address "203.0.113.10"
                                                         :user-agent "JUnit/Test Browser"})
          logout-result (user-ports/logout-user service session-token)
          audit-result (.find-audit-logs audit-repo {:filter-action :logout
                                                     :limit 10
                                                     :offset 0})
          matching-entry (some #(when (= (:actor-id %) (:id created-user)) %) (:audit-logs audit-result))]
      (is (= {:invalidated true
              :session-id (:id created-session)}
             logout-result))
      (is (some? matching-entry))
      (is (= :logout (:action matching-entry)))
      (is (= (:email created-user) (:actor-email matching-entry)))
      (is (= (:email created-user) (:target-user-email matching-entry)))
      (is (= "203.0.113.10" (:ip-address matching-entry)))
      (is (= "JUnit/Test Browser" (:user-agent matching-entry)))
      (is (= :success (:result matching-entry))))))
