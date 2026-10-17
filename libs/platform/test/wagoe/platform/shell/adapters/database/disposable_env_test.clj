(ns wagoe.platform.shell.adapters.database.disposable-env-test
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.platform.shell.adapters.database.config :as db-config]))

(deftest ^:unit disposable-environment-is-an-allowlist
  (testing "the profiles a reset allows are disposable, by any alias (BOU-588)"
    (doseq [env ["dev" "development" "test" "acc" "acceptance"]]
      (is (db-config/disposable-environment? env) (str env " should be disposable")))
    (is (= db-config/resettable-envs db-config/disposable-envs)))

  (testing "production and names the config loader does not know are not"
    (doseq [env ["prod" "production" "local"]]
      (is (not (db-config/disposable-environment? env)) (str env " must not be disposable"))))

  (testing "an unrecognised environment is refused, not allowed through"
    ;; This is why it is an allowlist. bb db:reset previously used a denylist
    ;; naming prod/acc/production, so every name below reached a database drop
    ;; — and the confirmation prompt then said "dev" regardless (BOU-258).
    (doseq [env ["staging" "uat" "qa" "preprod" "prod-eu" "prd" ""]]
      (is (not (db-config/disposable-environment? env)) (str env " must not be disposable")))))

(deftest ^:unit destructive-commands-resolve-env-the-way-the-connection-does
  ;; A guard that asks a narrower question than the connection is not a guard.
  (with-redefs [db-config/getenv {"WAG_ENV" "production"}]
    (is (= "prod" (db-config/detect-environment)))
    (is (not (db-config/disposable-environment? (db-config/detect-environment))))))
