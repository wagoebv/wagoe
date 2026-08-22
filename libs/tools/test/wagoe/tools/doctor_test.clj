(ns wagoe.tools.doctor-test
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
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
    (let [result (doctor/check-providers {:wagoe/logging {:provider :slf4j}})]
      (is (= :pass (:level (first result))))))

  (testing "errors for unknown provider"
    (let [result (doctor/check-providers {:wagoe/logging {:provider :banana}})]
      (is (= :error (:level (first result))))
      (is (re-find #"unknown provider" (:msg (first result))))))

  (testing "passes when no providers are configured"
    (let [result (doctor/check-providers {:wagoe/settings {:name "test"}})]
      (is (= :pass (:level (first result))))))

  (testing "checks multiple providers"
    (let [result (doctor/check-providers
                  {:wagoe/logging  {:provider :slf4j}
                   :wagoe/cache    {:provider :redis}
                   :wagoe/metrics  {:provider :prometheus}})]
      (is (= 1 (count result)))
      (is (= :pass (:level (first result)))))))

;; =============================================================================
;; check-jwt-secret
;; =============================================================================

(deftest ^:unit check-jwt-secret-test
  (testing "passes when user module not active"
    (let [result (doctor/check-jwt-secret {:wagoe/settings {:name "test"}} {})]
      (is (= :pass (:level (first result))))))

  (testing "passes when user module active and JWT_SECRET is long enough"
    (let [result (doctor/check-jwt-secret
                  {:wagoe/user-auth {:enabled? true}}
                  {"JWT_SECRET" "ci-test-secret-minimum-32-characters"})]
      (is (= :pass (:level (first result))))))

  (testing "errors when user module active but JWT_SECRET missing"
    (let [result (doctor/check-jwt-secret
                  {:wagoe/user-auth {:enabled? true}}
                  {})]
      (is (= :error (:level (first result))))))

  (testing "errors when JWT_SECRET is set but shorter than the runtime minimum"
    ;; This case previously passed: the check only asked whether the variable
    ;; was set. "secret-key" is 10 characters, so `bb doctor` went green and the
    ;; app then refused to boot with "JWT_SECRET must be at least 32
    ;; characters". BOU-250.
    (let [result (doctor/check-jwt-secret
                  {:wagoe/user-auth {:enabled? true}}
                  {"JWT_SECRET" "secret-key"})]
      (is (= :error (:level (first result))))
      (is (re-find #"10 characters" (:msg (first result)))
          "the message should say how short it actually is")))

  (testing "the suggested fix is itself long enough to be valid"
    ;; The old suggestion was `your-32-char-secret` — 19 characters, i.e. a
    ;; value this very check would reject.
    (let [fix (:fix (first (doctor/check-jwt-secret
                            {:wagoe/user-auth {:enabled? true}} {})))]
      (is (not (re-find #"your-32-char-secret" fix))))))

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
                   :active {:wagoe/settings {:name "app"}}}
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
                  {:active {:wagoe/settings {:name "app"}}}
                  "prod")]
      (is (= :pass (:level (first result))))))

  (testing "passes when flag is explicitly false in prod"
    (let [result (doctor/check-reset-endpoint-flag
                  {:test/reset-endpoint-enabled? false}
                  "prod")]
      (is (= :pass (:level (first result)))))))

;; =============================================================================
;; extract-active-section
;; =============================================================================

(deftest ^:unit extract-active-section-test
  (testing "extracts only active section text"
    (let [config (str ":active\n"
                      "{:wagoe/settings {:name \"test\"}\n"
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

(deftest ^:unit check-upgrade-wiring-no-longer-polices-tenant-middleware
  ;; It used to warn when an app wired the tenant module without referencing
  ;; :wagoe/tenant-http-middleware, because the http-handler had stopped
  ;; building that middleware itself and an app could mount tenant resolution
  ;; nowhere without noticing (BOU-200).
  ;;
  ;; BOU-326 made that impossible rather than detectable: the tenant library
  ;; emits the middleware from its own ig-config and contributes it to the HTTP
  ;; handler, so there is no app in which one exists without the other. The
  ;; check's advice — hand-write both into your config — is now the opposite of
  ;; what an app should do.
  (testing "an app that wires tenant and never names the middleware is fine"
    (is (= [:pass] (map :level (doctor/check-upgrade-wiring
                                "(ig/ref :wagoe/tenant-service)")))))

  (testing "and so is one that names neither"
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

  (testing "a stale ns is still the only thing reported, tenant wiring or not"
    ;; This used to assert both a stale-namespace error and a
    ;; missing-middleware warning. The second half is gone — see
    ;; check-upgrade-wiring-no-longer-polices-tenant-middleware.
    (let [results (doctor/check-upgrade-wiring
                   (str "(:require [wagoe.platform.shell.interfaces.http.tenant-middleware :as mw])\n"
                        "(ig/ref :wagoe/tenant-service)"))]
      (is (= [:error] (map :level results)))))

  (testing "new tenant-lib ns does not trip the relocated check"
    (is (= [:pass] (map :level (doctor/check-upgrade-wiring
                                "(:require [wagoe.tenant.shell.tenant-middleware :as mw])"))))))

(deftest ^:unit config-loadable-catches-configs-that-cannot-boot
  ;; `bb doctor --ci` is a CI gate. Before this check it exited 0 on a config
  ;; the application could not load at all: parse-config-minimal returns nil on
  ;; a syntax error, the caller does `(or (:active parsed) {})`, and every
  ;; downstream check then inspects an empty map and reports a pass.
  (let [check #'doctor/check-config-loadable
        level (fn [parsed] (:level (first (check parsed))))]

    (testing "a healthy config passes"
      (is (= :pass (level {:active {:wagoe/sqlite {:db "x.db"}}}))))

    (testing "an unparseable config is an error, not a swallowed warning"
      (is (= :error (level nil))))

    (testing "a missing :active section is an error"
      ;; The observed case: `:active` misspelled as `:actve`. The file parses,
      ;; so nil-checking the parse result is not enough.
      (is (= :error (level {:actve {:wagoe/sqlite {:db "x.db"}}}))))

    (testing "an empty :active section is an error"
      (is (= :error (level {:active {}}))))

    (testing "the error names a fix"
      (is (some? (:fix (first (check nil)))))
      (is (some? (:fix (first (check {:active {}}))))))))

(deftest ^:unit doctor-knows-every-ai-provider-the-code-dispatches-on
  ;; doctor keeps its own provider list because it is Babashka and cannot load
  ;; the Clojure lib. A copy drifts: adding :replicate to build-provider left
  ;; doctor reporting "unknown provider :replicate" for a provider that worked,
  ;; turning a passing config into a CI failure (BOU-281).
  (let [src (or (some #(when (.exists (io/file %)) (slurp %))
                      ["libs/ai/src/wagoe/ai/shell/module_wiring.clj"
                       "../ai/src/wagoe/ai/shell/module_wiring.clj"])
                (throw (ex-info "module_wiring.clj not found — cannot compare"
                                {:cwd (System/getProperty "user.dir")})))
        ;; The case arms of build-provider, which is the real registry.
        dispatched (set (map (comp keyword second)
                             (re-seq #"(?m)^\s+:([a-z-]+)\s+\([a-z-]+/create-" src)))
        known      (get doctor/known-providers :wagoe/ai-service)]

    (testing "the source parsed — otherwise this passes vacuously"
      (is (<= 4 (count dispatched))
          (str "only found " (pr-str dispatched) " in build-provider")))

    (testing "doctor accepts every provider the code can build"
      (is (empty? (set/difference dispatched known))
          (str "build-provider handles " (pr-str (set/difference dispatched known))
               " but doctor would reject them")))

    (testing "and claims no provider the code cannot build"
      (is (empty? (set/difference known dispatched))
          (str "doctor accepts " (pr-str (set/difference known dispatched))
               " but build-provider would throw")))))

(deftest ^:unit events-providers-match-what-the-code-can-build
  ;; Same copy-drift hazard as :wagoe/ai-service above: doctor runs on Babashka
  ;; and keeps its own list, so the two can disagree in either direction —
  ;; doctor rejecting a provider that works, or accepting a typo that fails at
  ;; Integrant startup instead of in `bb doctor`.
  (let [src (or (some #(when (.exists (io/file %)) (slurp %))
                      ["libs/events/src/wagoe/events/shell/module_wiring.clj"
                       "../events/src/wagoe/events/shell/module_wiring.clj"])
                (throw (ex-info "events module_wiring.clj not found — cannot compare"
                                {:cwd (System/getProperty "user.dir")})))
        ;; The case arms of init-key, which is the real registry.
        dispatched (set (map (comp keyword second)
                             (re-seq #"(?m)^\s+:([a-z-]+)\s+\([a-z-]+/create-" src)))
        known      (get doctor/known-providers :wagoe/events)]

    (testing "the source parsed — otherwise this passes vacuously"
      (is (<= 2 (count dispatched))
          (str "only found " (pr-str dispatched) " in init-key")))

    (testing "doctor accepts every provider the code can build"
      (is (empty? (set/difference dispatched known))
          (str "init-key handles " (pr-str (set/difference dispatched known))
               " but doctor would reject them")))

    (testing "and claims no provider the code cannot build"
      (is (empty? (set/difference known dispatched))
          (str "doctor accepts " (pr-str (set/difference known dispatched))
               " which init-key cannot build")))))

(deftest ^:unit every-profile-config-in-this-repo-parses
  ;; The reader table listed twelve Aero tags and not #boolean, which acc and
  ;; prod both use. `bb doctor --env prod` answered "config.edn could not be
  ;; parsed" about a file Aero reads without complaint, and told the reader to
  ;; repair syntax that was never broken. CI only ever ran --env dev, so the two
  ;; profiles where it mattered were the two nothing checked (BOU-324).
  (let [profiles (->> (io/file "resources/conf")
                      .listFiles
                      (filter #(.isDirectory %))
                      (map #(.getName %))
                      sort)]
    (is (<= 2 (count profiles)) "otherwise this passes by checking nothing")

    (doseq [p profiles]
      (let [results (doctor/run-checks p {})
            loadable (first (filter #(= :config-loadable (:id %)) results))]
        (is (= :pass (:level loadable))
            (str p " does not parse: " (:msg loadable)))))))

(deftest ^:unit an-aero-tag-the-reader-has-never-heard-of-is-not-a-parse-failure
  ;; Aero has more tags than the table knows and can gain more. This parser
  ;; exists to find the :active keys; approximating one tag beats refusing the
  ;; file and telling the reader their config is broken.
  (let [results (#'doctor/check-config-loadable
                 (#'doctor/parse-config-minimal
                  "{:active {:wagoe/settings {:name #udf/whatever \"x\"}}}"))]
    (is (= :pass (:level (first results))))))
