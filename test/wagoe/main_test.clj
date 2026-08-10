(ns wagoe.main-test
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [wagoe.main :as main]))

(deftest ^:unit worker-ig-config-drops-http-surface
  (let [full {:wagoe/http-server  1
              :wagoe/http-handler  2
              :wagoe/dashboard     3
              :wagoe/db-context    4
              :wagoe/user-service  5}
        worker (main/worker-ig-config full)]
    (testing "the HTTP surface keys are removed so the worker binds no port"
      (is (empty? (set/intersection (set (keys worker))
                                    (set main/http-surface-keys)))))
    (testing "background/service components are kept"
      (is (= {:wagoe/db-context   4
              :wagoe/user-service 5}
             worker)))))

;; =============================================================================
;; Startup failure reporting
;; =============================================================================

(deftest ^:unit startup-failure-summary-omits-the-config-map
  ;; Integrant's ex-data carries :value — the config for the failing key — so
  ;; logging the exception printed the database configuration. Measured against
  ;; the prod profile, POSTGRES_PASSWORD appeared twice in the output of a
  ;; failed boot, which container logs ship onward.
  (let [db-config {:adapter  :postgresql
                   :host     "db.internal"
                   :username "app"
                   :password "S3cr3t-Prod-Passw0rd"}
        cause     (ex-info "FATAL: database \"app\" does not exist" {})
        failure   (ex-info "Error on key :wagoe/db-context when building system"
                           {:reason :integrant.core/build-threw-exception
                            :key    :wagoe/db-context
                            :value  db-config
                            :system {:wagoe/logging "…"}}
                           cause)
        summary   (main/startup-failure-summary failure)]

    (testing "the secret does not appear"
      (is (not (str/includes? summary "S3cr3t-Prod-Passw0rd"))))

    (testing "nor does any part of the config map"
      (is (not (str/includes? summary "db.internal")))
      (is (not (str/includes? summary ":password"))))

    (testing "the failing component is named"
      (is (str/includes? summary ":wagoe/db-context")))

    (testing "the actual reason is reported, not the Integrant wrapper"
      ;; "Error on key … when building system" says nothing a caller can act on.
      (is (str/includes? summary "database \"app\" does not exist")))

    (testing "the exception type is named, for a cause with no message"
      (is (str/includes? summary "ExceptionInfo")))))

(deftest ^:unit startup-failure-summary-handles-plain-failures
  (testing "an exception with no ex-data still reports its message"
    (let [summary (main/startup-failure-summary (RuntimeException. "boom"))]
      (is (str/includes? summary "boom"))
      (is (not (str/includes? summary "could not be built")))))

  (testing "the innermost cause is the one reported"
    (let [deep (ex-info "outer" {} (ex-info "middle" {} (RuntimeException. "innermost")))]
      (is (str/includes? (main/startup-failure-summary deep) "innermost"))))

  (testing "root-cause of an exception with no cause is itself"
    (let [e (RuntimeException. "only")]
      (is (identical? e (main/root-cause e))))))

(deftest ^:unit startup-failure-sites-do-not-log-the-exception
  ;; The unit tests above pass against a `startup-failure-summary` that nothing
  ;; calls. What leaked the password was `(log/error e "Failed to start …")` —
  ;; logging the exception object, whose ex-data carries the config map. This
  ;; asserts the call sites, not just the helper.
  (let [src (or (some #(when (.exists (io/file %)) (slurp %))
                      ["src/wagoe/main.clj" "../../src/wagoe/main.clj"])
                (throw (ex-info "src/wagoe/main.clj not found — cannot check"
                                {:cwd (System/getProperty "user.dir")})))
        start-sites (re-seq #"\(log/error[^)]*\"Failed to start[^\"]*\"" src)]

    (testing "the source was read — otherwise this passes vacuously"
      (is (str/includes? src "startup-failure-summary")))

    (testing "no startup failure logs the exception object"
      (is (empty? start-sites)
          (str "these log the exception, and its ex-data holds the config: "
               (pr-str start-sites))))

    (testing "both server and worker report through the summary"
      (is (= 2 (count (re-seq #"\(log/error \(startup-failure-summary e\)\)" src)))))))

;; =============================================================================
;; Module wiring loaded by the entry point
;; =============================================================================

(deftest ^:unit entry-point-registers-every-module-it-owns
  ;; platform used to require these, which made the SMTP/IMAP/Twilio adapters a
  ;; mandatory dependency of the HTTP layer — and, for `external`, one platform
  ;; never declared in its deps.edn (the last entry in check:deps' allowlist).
  ;; Loading them here instead is the pattern BOU-171/192/198 established.
  ;;
  ;; Requiring wagoe.main is what registers them, so a key missing here means a
  ;; project that activates that component gets "No method in multimethod
  ;; ig/init-key" at startup rather than a running system.
  (let [registered (set (keys (methods ig/init-key)))]
    (testing "the external adapters are registered"
      (doseq [k [:wagoe.external/smtp :wagoe.external/imap :wagoe.external/twilio]]
        (is (contains? registered k) (str k " has no ig/init-key method"))))

    (testing "and the feature modules the entry point owns"
      (doseq [k [:wagoe/user-service :wagoe/admin-service]]
        (is (contains? registered k) (str k " has no ig/init-key method"))))))
