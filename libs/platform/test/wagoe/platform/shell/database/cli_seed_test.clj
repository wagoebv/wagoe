(ns wagoe.platform.shell.database.cli-seed-test
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.platform.shell.database.cli-seed :as cli-seed]
            [wagoe.platform.shell.adapters.database.config :as db-config]))

(deftest ^:unit seeding-is-refused-outside-development
  (testing "development-like environments are allowed"
    (doseq [env ["dev" "development" "test" "local"]]
      (is (cli-seed/seedable? env false) (str env " should be seedable"))))

  (testing "production-like environments are refused"
    (doseq [env ["prod" "production" "acc"]]
      (is (not (cli-seed/seedable? env false)) (str env " must not be seedable"))))

  (testing "an unrecognised environment is refused, not allowed through"
    ;; The reason this is an allowlist: db-reset uses a denylist naming
    ;; prod/acc/production, which lets these past.
    (doseq [env ["staging" "uat" "qa" "preprod" "prod-eu" "prd"]]
      (is (not (cli-seed/seedable? env false)) (str env " must not be seedable"))))

  (testing "--force overrides deliberately"
    (is (cli-seed/seedable? "prod" true))
    (is (cli-seed/seedable? "staging" true))))

(deftest ^:unit guard-uses-the-same-environment-detection-as-the-database
  ;; A guard that asks a different question than the connection is not a guard.
  ;; `clojure -M:prod:seed` sets -Denv=prod (deps.edn :prod alias) and no
  ;; WAG_ENV, so a guard reading WAG_ENV alone saw "dev" and allowed a write to
  ;; the production database.
  (testing "-Denv wins over WAG_ENV, matching the database config"
    (let [prop "env"
          prev (System/getProperty prop)]
      (try
        (System/setProperty prop "prod")
        (with-redefs [db-config/getenv (fn [k] (when (= k "WAG_ENV") "dev"))]
          (is (= "prod" (db-config/detect-environment))
              "detect-environment must prefer -Denv")
          (is (not (cli-seed/seedable? (db-config/detect-environment) false))
              "the guard must refuse when the database would connect to prod"))
        (finally
          (if prev (System/setProperty prop prev) (System/clearProperty prop)))))))
