(ns wagoe.tools.doctor-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.tools.doctor :as doctor]))

;; =============================================================================
;; extract-env-refs
;; =============================================================================

(deftest ^:unit extract-env-refs-test
  (testing "extracts #env VAR references"
    (is (= #{"POSTGRES_HOST" "POSTGRES_PORT"}
           (doctor/extract-env-refs
            ":host #env POSTGRES_HOST\n:port #env POSTGRES_PORT"))))

  (testing "ignores #env inside #or"
    ;; extract-env-refs finds ALL #env refs; extract-or-defaults finds the protected ones
    (is (= #{"POSTGRES_HOST"}
           (doctor/extract-env-refs ":host #or [#env POSTGRES_HOST \"localhost\"]"))))

  (testing "returns empty set for no env refs"
    (is (= #{} (doctor/extract-env-refs ":host \"localhost\"")))))

;; =============================================================================
;; extract-or-defaults
;; =============================================================================

(deftest ^:unit extract-or-defaults-test
  (testing "extracts env vars with #or defaults"
    (is (= #{"POSTGRES_HOST"}
           (doctor/extract-or-defaults ":host #or [#env POSTGRES_HOST \"localhost\"]"))))

  (testing "does not match bare #env without #or"
    (is (= #{} (doctor/extract-or-defaults ":host #env POSTGRES_HOST"))))

  (testing "handles multiple #or defaults"
    (is (= #{"HOST" "PORT"}
           (doctor/extract-or-defaults
            (str ":host #or [#env HOST \"localhost\"]\n"
                 ":port #or [#env PORT 5432]"))))))

;; =============================================================================
;; extract-fallback-env-refs
;; =============================================================================

(deftest ^:unit extract-fallback-env-refs-test
  (testing "extracts env refs inside :fallback blocks"
    (is (= #{"ANTHROPIC_API_KEY"}
           (doctor/extract-fallback-env-refs
            ":fallback {:provider :anthropic :api-key #env ANTHROPIC_API_KEY}"))))

  (testing "returns empty set when no fallback blocks"
    (is (= #{} (doctor/extract-fallback-env-refs ":provider :ollama :api-key #env API_KEY"))))

  (testing "handles nested braces in fallback"
    (is (= #{"FALLBACK_KEY"}
           (doctor/extract-fallback-env-refs
            ":fallback {:provider :anthropic :opts {:key #env FALLBACK_KEY}}")))))

;; =============================================================================
;; check-env-refs
;; =============================================================================

(deftest ^:unit check-env-refs-test
  (testing "passes when all unprotected env vars are set"
    (let [result (doctor/check-env-refs "#env SECRET_KEY" {"SECRET_KEY" "val"})]
      (is (= :pass (:level (first result))))))

  (testing "passes when env var has #or default"
    (let [result (doctor/check-env-refs "#or [#env HOST \"localhost\"]" {})]
      (is (= :pass (:level (first result))))))

  (testing "errors when unprotected env var is missing"
    (let [result (doctor/check-env-refs "#env SECRET_KEY" {})]
      (is (= :error (:level (first result))))
      (is (re-find #"SECRET_KEY" (:msg (first result))))))

  (testing "warns (not errors) for missing fallback env vars"
    (let [config ":provider :ollama\n:fallback {:provider :anthropic :api-key #env ANTHROPIC_API_KEY}"
          result (doctor/check-env-refs config {})]
      (is (= :warn (:level (first result))))
      (is (re-find #"ANTHROPIC_API_KEY" (:msg (first result))))))

  (testing "errors on required but warns on fallback when both are missing"
    (let [config (str "#env REQUIRED_KEY\n"
                      ":fallback {:api-key #env FALLBACK_KEY}")
          result (doctor/check-env-refs config {})]
      (is (= 2 (count result)))
      (is (= :error (:level (first result))))
      (is (= :warn (:level (second result)))))))

;; =============================================================================
;; check-providers
;; =============================================================================

(deftest ^:unit check-providers-test
  (testing "passes for known providers"
    (let [result (doctor/check-providers {:boundary/logging {:provider :slf4j}})]
      (is (= :pass (:level (first result))))))

  (testing "errors for unknown provider"
    (let [result (doctor/check-providers {:boundary/logging {:provider :banana}})]
      (is (= :error (:level (first result))))
      (is (re-find #"unknown provider" (:msg (first result))))))

  (testing "passes when no providers are configured"
    (let [result (doctor/check-providers {:boundary/settings {:name "test"}})]
      (is (= :pass (:level (first result))))))

  (testing "checks multiple providers"
    (let [result (doctor/check-providers
                  {:boundary/logging  {:provider :slf4j}
                   :boundary/cache    {:provider :redis}
                   :boundary/metrics  {:provider :prometheus}})]
      (is (= 1 (count result)))
      (is (= :pass (:level (first result)))))))

;; =============================================================================
;; check-jwt-secret
;; =============================================================================

(deftest ^:unit check-jwt-secret-test
  (testing "passes when user module not active"
    (let [result (doctor/check-jwt-secret {:boundary/settings {:name "test"}} {})]
      (is (= :pass (:level (first result))))))

  (testing "passes when user module active and JWT_SECRET set"
    (let [result (doctor/check-jwt-secret
                  {:boundary/user-auth {:enabled? true}}
                  {"JWT_SECRET" "secret-key"})]
      (is (= :pass (:level (first result))))))

  (testing "errors when user module active but JWT_SECRET missing"
    (let [result (doctor/check-jwt-secret
                  {:boundary/user-auth {:enabled? true}}
                  {})]
      (is (= :error (:level (first result)))))))

;; =============================================================================
;; check-admin-parity
;; =============================================================================

(deftest ^:unit check-admin-parity-test
  (testing "passes when files match"
    (let [dev-files  [(java.io.File. "users.edn") (java.io.File. "tenants.edn")]
          test-files [(java.io.File. "users.edn") (java.io.File. "tenants.edn")]
          result     (doctor/check-admin-parity dev-files test-files)]
      (is (= :pass (:level (first result))))))

  (testing "warns on dev-only files"
    (let [dev-files  [(java.io.File. "users.edn") (java.io.File. "products.edn")]
          test-files [(java.io.File. "users.edn")]
          result     (doctor/check-admin-parity dev-files test-files)]
      (is (= :warn (:level (first result))))
      (is (re-find #"products.edn" (:msg (first result))))))

  (testing "warns on test-only files"
    (let [dev-files  [(java.io.File. "users.edn")]
          test-files [(java.io.File. "users.edn") (java.io.File. "extra.edn")]
          result     (doctor/check-admin-parity dev-files test-files)]
      (is (= :warn (:level (first result))))
      (is (re-find #"extra.edn" (:msg (first result)))))))

;; =============================================================================
;; check-prod-placeholders
;; =============================================================================

(deftest ^:unit check-prod-placeholders-test
  (testing "skips non-production environments"
    (let [result (doctor/check-prod-placeholders "company.com" "dev")]
      (is (= :pass (:level (first result))))))

  (testing "errors on company.com in prod"
    (let [result (doctor/check-prod-placeholders "email: admin@company.com" "prod")]
      (is (= :error (:level (first result))))))

  (testing "errors on TODO in acc"
    (let [result (doctor/check-prod-placeholders ":api-key \"TODO\"" "acc")]
      (is (= :error (:level (first result))))))

  (testing "passes for clean prod config"
    (let [result (doctor/check-prod-placeholders ":host #env POSTGRES_HOST" "prod")]
      (is (= :pass (:level (first result)))))))

;; =============================================================================
;; check-reset-endpoint-flag
;; =============================================================================

(deftest ^:unit reset-endpoint-flag-must-not-be-enabled-in-prod
  (testing "errors when :test/reset-endpoint-enabled? is true in prod profile"
    (let [result (doctor/check-reset-endpoint-flag
                  {:test/reset-endpoint-enabled? true
                   :active {:boundary/settings {:name "app"}}}
                  "prod")]
      (is (= :error (:level (first result))))
      (is (re-find #"reset-endpoint-enabled\?" (:msg (first result))))
      (is (re-find #"prod" (:msg (first result))))))

  (testing "errors in acc profile as well"
    (let [result (doctor/check-reset-endpoint-flag
                  {:test/reset-endpoint-enabled? true}
                  "acc")]
      (is (= :error (:level (first result))))
      (is (re-find #"acc" (:msg (first result)))))))

(deftest ^:unit reset-endpoint-flag-may-be-enabled-in-test
  (testing "passes when flag is true in test profile"
    (let [result (doctor/check-reset-endpoint-flag
                  {:test/reset-endpoint-enabled? true}
                  "test")]
      (is (= :pass (:level (first result))))))

  (testing "passes when flag is true in dev profile"
    (let [result (doctor/check-reset-endpoint-flag
                  {:test/reset-endpoint-enabled? true}
                  "dev")]
      (is (= :pass (:level (first result)))))))

(deftest ^:unit reset-endpoint-flag-absent-in-prod-is-fine
  (testing "passes when flag is absent in prod"
    (let [result (doctor/check-reset-endpoint-flag
                  {:active {:boundary/settings {:name "app"}}}
                  "prod")]
      (is (= :pass (:level (first result))))))

  (testing "passes when flag is explicitly false in prod"
    (let [result (doctor/check-reset-endpoint-flag
                  {:test/reset-endpoint-enabled? false}
                  "prod")]
      (is (= :pass (:level (first result)))))))

;; =============================================================================
;; check-wiring-requires
;; =============================================================================

(deftest ^:unit check-wiring-requires-test
  (testing "passes when all modules are wired"
    (let [wiring "(ns ... (:require [wagoe.admin.shell.module-wiring]))"
          config {:boundary/admin {:enabled? true}}
          result (doctor/check-wiring-requires wiring config)]
      (is (= :pass (:level (first result))))))

  (testing "warns on missing module-wiring"
    (let [wiring "(ns ... (:require [wagoe.admin.shell.module-wiring]))"
          config {:boundary/admin   {:enabled? true}
                  :boundary/search  {:enabled? true}}
          result (doctor/check-wiring-requires wiring config)]
      (is (= :warn (:level (first result))))
      (is (re-find #"search" (:msg (first result))))))

  (testing "excludes infrastructure keys"
    (let [wiring "(ns ...)"
          config {:boundary/postgresql {:host "localhost"}
                  :boundary/settings   {:name "test"}
                  :boundary/logging    {:provider :slf4j}}
          result (doctor/check-wiring-requires wiring config)]
      (is (= :pass (:level (first result)))))))

;; =============================================================================
;; extract-active-section
;; =============================================================================

(deftest ^:unit extract-active-section-test
  (testing "extracts only active section text"
    (let [config (str ":active\n"
                      "{:boundary/settings {:name \"test\"}\n"
                      " :host #env ACTIVE_HOST}\n"
                      "\n"
                      ":inactive\n"
                      "{:host #env INACTIVE_HOST}")]
      ;; The active section should contain ACTIVE_HOST but not INACTIVE_HOST
      (is (re-find #"ACTIVE_HOST"
                   (#'wagoe.tools.doctor/extract-active-section config)))
      (is (not (re-find #"INACTIVE_HOST"
                        (#'wagoe.tools.doctor/extract-active-section config))))))

  (testing "skips :active in comments"
    (let [config (str ";; :active section below\n"
                      ":active\n"
                      "{:host #env REAL_HOST}\n"
                      "\n"
                      ":inactive\n"
                      "{:host #env INACTIVE_HOST}")]
      (is (re-find #"REAL_HOST"
                   (#'wagoe.tools.doctor/extract-active-section config)))
      (is (not (re-find #"INACTIVE_HOST"
                        (#'wagoe.tools.doctor/extract-active-section config)))))))

(deftest ^:unit check-upgrade-wiring-flags-tenant-without-http-middleware
  (testing "BOU-200 silent case: tenant-service wired, tenant-http-middleware absent"
    (let [results (doctor/check-upgrade-wiring
                   "(ig/ref :boundary/tenant-service) (ig/ref :boundary/membership-service)")]
      (is (= [:warn] (map :level results)))
      (is (re-find #"tenant-http-middleware" (:msg (first results))))))

  (testing "tenant-service + tenant-http-middleware both wired passes"
    (let [results (doctor/check-upgrade-wiring
                   (str "(ig/ref :boundary/tenant-service)\n"
                        ":boundary/tenant-http-middleware {:tenant-service (ig/ref :boundary/tenant-service)}"))]
      (is (= [:pass] (map :level results)))))

  (testing "tenant signalled by :tenant-routes alone (no service key) still warns"
    (let [results (doctor/check-upgrade-wiring "(ig/ref :boundary/tenant-routes)")]
      (is (= [:warn] (map :level results)))))

  (testing "no tenant module at all passes"
    (is (= [:pass] (map :level (doctor/check-upgrade-wiring "(ns app.config)"))))))

(deftest ^:unit check-upgrade-wiring-flags-relocated-namespaces
  (testing "pre-BOU-198 platform tenant middleware require is an error with the new ns in the fix"
    (let [results (doctor/check-upgrade-wiring
                   "(:require [wagoe.platform.shell.interfaces.http.tenant-middleware :as mw])")]
      (is (= [:error] (map :level results)))
      (is (re-find #"wagoe\.tenant\.shell\.tenant-middleware" (:fix (first results))))))

  (testing "old membership-middleware ns is also flagged"
    (let [results (doctor/check-upgrade-wiring
                   "(:require [wagoe.platform.shell.interfaces.http.membership-middleware :as mmw])")]
      (is (= [:error] (map :level results)))))

  (testing "stale ns AND missing middleware pair reports both"
    (let [results (doctor/check-upgrade-wiring
                   (str "(:require [wagoe.platform.shell.interfaces.http.tenant-middleware :as mw])\n"
                        "(ig/ref :boundary/tenant-service)"))]
      (is (= #{:error :warn} (set (map :level results))))))

  (testing "new tenant-lib ns does not trip the relocated check"
    (is (= [:pass] (map :level (doctor/check-upgrade-wiring
                                "(:require [wagoe.tenant.shell.tenant-middleware :as mw])"))))))
