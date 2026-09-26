(ns wagoe.user.shell.login-log-secrets-test
  "A real login must not write the password hash or MFA secrets to the log (BOU-556)."
  (:require [wagoe.platform.shell.adapters.database.h2.core :as h2]
            [wagoe.user.ports :as user-ports]
            [wagoe.user.shell.auth :as auth]
            [wagoe.user.shell.persistence :as persistence]
            [wagoe.user.shell.service :as user-service]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging.test :as log-test]
            [next.jdbc.connection :as connection])
  (:import [com.zaxxer.hikari HikariDataSource]
           [java.util UUID]))

(def ^:private password "Correct-Horse-Battery-1!")
(def ^:private mfa-secret "MFASECRETVALUE7Q3Z")
(def ^:private backup-code "BACKUPCODE-93XK")
(def ^:private mfa-code "924613")

(defn- h2-ctx []
  {:datasource (connection/->pool HikariDataSource
                                  {:jdbcUrl  (str "jdbc:h2:mem:login-log-" (UUID/randomUUID)
                                                  ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
                                   :username "sa"
                                   :password ""})
   :adapter    (h2/new-adapter)})

(defn- logged-text []
  (->> (log-test/the-log)
       (map (fn [{:keys [message throwable]}]
              (str message " " (some-> throwable ex-data pr-str))))
       (str/join "\n")))

(deftest ^:integration ^:security login-does-not-log-secrets
  (let [ctx          (h2-ctx)
        _            (persistence/initialize-user-schema! ctx)
        user-repo    (persistence/create-user-repository ctx)
        session-repo (persistence/create-session-repository ctx)
        audit-repo   (persistence/create-audit-repository ctx {:default-limit 20 :max-limit 100})
        auth-svc     (auth/create-authentication-service user-repo session-repo nil {})
        service      (user-service/create-user-service user-repo session-repo audit-repo {} auth-svc)
        email        (str "log-secrets-" (UUID/randomUUID) "@example.com")
        hash         (auth/hash-password password)]
    (try
      (.create-user user-repo {:email            email
                               :name             "Log Secrets"
                               :role             :user
                               :active           true
                               :password-hash    hash
                               :mfa-enabled      false
                               :mfa-secret       mfa-secret
                               :mfa-backup-codes [backup-code]})
      (with-redefs [auth/get-jwt-secret (constantly "test-secret-minimum-32-characters-long-for-testing")]
        (log-test/with-log
          (let [result (user-ports/authenticate-user service {:email      email
                                                              :password   password
                                                              :ip-address "203.0.113.7"
                                                              :user-agent "test"
                                                              :mfa-code   mfa-code})
                text   (logged-text)]
            (is (:authenticated result) "the login itself succeeds")
            (is (str/includes? text "update-user") "the login's update-user was logged")
            (testing "no secret value reaches the log"
              (is (not (str/includes? text hash)))
              (is (not (str/includes? text mfa-secret)))
              (is (not (str/includes? text backup-code)))
              (is (not (str/includes? text mfa-code)))
              (is (not (str/includes? text password)))))))
      (finally
        (.close ^HikariDataSource (:datasource ctx))))))
