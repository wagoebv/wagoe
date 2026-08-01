(ns wagoe.platform.shell.adapters.database.disposable-env-test
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.platform.shell.adapters.database.config :as db-config]))

(deftest ^:unit disposable-environment-is-an-allowlist
  (testing "development-like environments are disposable"
    (doseq [env ["dev" "development" "test" "local"]]
      (is (db-config/disposable-environment? env) (str env " should be disposable"))))

  (testing "production-like environments are not"
    (doseq [env ["prod" "production" "acc"]]
      (is (not (db-config/disposable-environment? env)) (str env " must not be disposable"))))

  (testing "an unrecognised environment is refused, not allowed through"
    ;; This is why it is an allowlist. bb db:reset previously used a denylist
    ;; naming prod/acc/production, so every name below reached a database drop
    ;; — and the confirmation prompt then said "dev" regardless (BOU-258).
    (doseq [env ["staging" "uat" "qa" "preprod" "prod-eu" "prd" ""]]
      (is (not (db-config/disposable-environment? env)) (str env " must not be disposable")))))

(deftest ^:unit destructive-commands-resolve-env-the-way-the-connection-does
  ;; A guard that asks a narrower question than the connection is not a guard.
  ;; `clojure -M:prod:migrate reset` sets -Denv=prod and no WAG_ENV, so a check
  ;; reading WAG_ENV alone saw "dev" and allowed a drop of the production
  ;; database.
  (testing "-Denv wins over WAG_ENV, and the guard refuses on the result"
    (let [prop "env"
          prev (System/getProperty prop)]
      (try
        (System/setProperty prop "prod")
        (with-redefs [db-config/getenv (fn [k] (when (= k "WAG_ENV") "dev"))]
          (is (= "prod" (db-config/detect-environment)))
          (is (not (db-config/disposable-environment? (db-config/detect-environment)))))
        (finally
          (if prev (System/setProperty prop prev) (System/clearProperty prop))))))

  (testing "ENV and ENVIRONMENT are in the chain too"
    ;; The :test alias sets -Denv=test, which detect-environment checks *first* —
    ;; so the property has to be cleared to observe the env-var rungs at all.
    ;; That the property wins is itself the behaviour the guard depends on.
    (let [prop "env"
          prev (System/getProperty prop)]
      (try
        (System/clearProperty prop)
        (doseq [k ["ENV" "ENVIRONMENT"]]
          (with-redefs [db-config/getenv (fn [key] (when (= key k) "staging"))]
            (is (= "staging" (db-config/detect-environment)) (str k " should be consulted"))
            (is (not (db-config/disposable-environment? (db-config/detect-environment))))))
        (finally
          (when prev (System/setProperty prop prev)))))))
