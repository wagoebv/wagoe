(ns wagoe.platform.shell.database.cli-seed-test
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.platform.shell.database.cli-seed :as cli-seed]
            [wagoe.platform.shell.adapters.database.config :as db-config]))

(deftest ^:unit seeding-is-refused-outside-development
  ;; The profiles db:reset allows, aliases resolved: a reset in acc is useless
  ;; if acc cannot be seeded afterwards (BOU-588).
  (testing "the profiles a reset allows are allowed, by any alias"
    (doseq [env ["dev" "development" "test" "testing" "acc" "acceptance" "DEV"]]
      (is (cli-seed/seedable? env false) (str env " should be seedable"))))

  (testing "production is refused"
    (doseq [env ["prod" "production"]]
      (is (not (cli-seed/seedable? env false)) (str env " must not be seedable"))))

  (testing "an unrecognised environment is refused, not allowed through"
    ;; An allowlist: "local" is not a profile the config loader knows.
    (doseq [env ["staging" "uat" "qa" "preprod" "prod-eu" "prd" "local" ""]]
      (is (not (cli-seed/seedable? env false)) (str env " must not be seedable"))))

  (testing "--force overrides deliberately"
    (is (cli-seed/seedable? "prod" true))
    (is (cli-seed/seedable? "staging" true))))

(deftest ^:unit guard-uses-the-same-environment-detection-as-the-database
  ;; A guard that asks a different question than the connection is not a guard.
  (with-redefs [db-config/getenv {"WAG_ENV" "production"}]
    (is (not (cli-seed/seedable? (db-config/detect-environment) false))
        "the guard must refuse when the database would connect to prod")))
