(ns wagoe.cli-error-handling-test
  "Error handling on the CLI path.

   This file used to carry three ^:contract HTTP tests as well. They assembled
   their own stack out of `interfaces.http.middleware` — a namespace no
   application ran — and asserted RFC 7807, which the HTTP path does not
   produce, so they constrained nothing that ships. The wired path is covered by
   `security_test` (mapping, leak rules) and by
   `reitit-router-test` end to end (BOU-372)."
  (:require [wagoe.platform.shell.utils.error-handling :as eh]
            [wagoe.platform.shell.interfaces.cli.middleware :as cli-middleware]
            [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]))

;; =============================================================================
;; CLI Error Handling End-to-End Tests
;; =============================================================================

(deftest ^:unit test-cli-error-handling-end-to-end
  (testing "complete CLI error handling flow with context"
    (let [operation-context {:operation "bulk-import-users"
                             :user-id "admin-user"
                             :file-path "/data/users.csv"
                             :batch-size 100
                             :dry-run false}

          business-error (ex-info "CSV parsing failed"
                                  {:type :csv-error
                                   :row 25
                                   :column "email"
                                   :error "invalid format"
                                   :value "not-an-email"})

          failing-operation (fn [_context] (throw business-error))]

      (try
        (cli-middleware/with-cli-error-reporting operation-context failing-operation)
        (is false "Should have thrown enhanced exception")
        (catch Exception enhanced-e
          (let [error-data (ex-data enhanced-e)
                cli-context (:cli-context error-data)]

            (is (= "CSV parsing failed" (.getMessage enhanced-e)))
            (is (= {:type :csv-error :row 25 :column "email" :error "invalid format" :value "not-an-email"}
                   (:original-data error-data)))

            (is (= "bulk-import-users" (:operation cli-context)))
            (is (= "admin-user" (:user-id cli-context)))
            (is (= "/data/users.csv" (:file-path cli-context)))
            (is (= 100 (:batch-size cli-context)))
            (is (false? (:dry-run cli-context)))
            (is (contains? cli-context :timestamp))
            (is (contains? cli-context :environment))
            (is (contains? cli-context :process-id))

            (let [formatted-basic (eh/format-cli-error enhanced-e :include-context false)
                  formatted-full (eh/format-cli-error enhanced-e :include-context true)]

              (is (str/includes? formatted-basic "CSV parsing failed"))
              (is (str/includes? formatted-basic ":row 25"))
              (is (not (str/includes? formatted-basic "Operation:")))

              (is (str/includes? formatted-full "CSV parsing failed"))
              (is (str/includes? formatted-full "Operation: bulk-import-users"))
              (is (str/includes? formatted-full "User ID: admin-user"))
              (is (str/includes? formatted-full "file-path"))))))))

  (testing "nested CLI operations with context inheritance"
    (let [parent-context {:operation "system-maintenance" :user-id "system-admin"}
          child-context {:operation "database-cleanup" :database "users"}

          child-error (ex-info "Cleanup failed" {:type :cleanup-error :table "user_sessions" :affected-rows 0})

          child-operation (fn [_] (throw child-error))
          parent-operation (fn [context]
                             (cli-middleware/with-cli-error-reporting
                               (merge context child-context)
                               child-operation))]

      (try
        (cli-middleware/with-cli-error-reporting parent-context parent-operation)
        (catch Exception e
          (let [cli-context (get (ex-data e) :cli-context)]
            (is (= "database-cleanup" (:operation cli-context)))
            (is (= "system-admin" (:user-id cli-context)))
            (is (= "users" (:database cli-context)))))))))
