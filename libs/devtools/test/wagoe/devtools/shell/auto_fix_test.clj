(ns wagoe.devtools.shell.auto-fix-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [wagoe.devtools.shell.auto-fix :as executor]))

(use-fixtures :each
  (fn [f]
    (let [original-dir (System/getProperty "user.dir")
          tmp-dir (io/file (System/getProperty "java.io.tmpdir")
                           (str "auto-fix-test-" (System/currentTimeMillis)))]
      (.mkdirs tmp-dir)
      (try
        (System/setProperty "user.dir" (.getAbsolutePath tmp-dir))
        (f)
        (finally
          (System/setProperty "user.dir" original-dir)
          ;; Clean up any .env written by tests
          (let [env-file (io/file tmp-dir ".env")]
            (when (.exists env-file) (.delete env-file)))
          (.delete tmp-dir))))))

(deftest ^:integration execute-safe-fix-test
  (testing "safe fix executes without confirmation at :full guidance"
    (let [fix {:fix-id :set-env-var
               :label "Set DATABASE_URL"
               :safe? true
               :action :set-env
               :params {:var-name "TEST_AUTO_FIX_VAR" :value "test-value"}}
          output (with-out-str
                   (executor/execute-fix! fix
                                          {:guidance-level :full
                                           :confirm-fn (fn [_] (throw (ex-info "should not confirm" {})))}))]
      (is (str/includes? output "Applying"))
      (is (str/includes? output "Written to .env"))))

  (testing "safe fix at :minimal guidance does not print 'Applying' label"
    (let [fix {:fix-id :set-env-var
               :label "Set var"
               :safe? true
               :action :set-env
               :params {:var-name "TEST_AUTO_FIX_SILENT" :value "silent"}}
          output (with-out-str
                   (executor/execute-fix! fix {:guidance-level :minimal}))]
      (is (not (str/includes? output "Applying"))))))

(deftest ^:integration execute-risky-fix-requires-confirmation-test
  (testing "risky fix requires confirmation even at :minimal"
    (let [confirmed? (atom false)
          fix {:fix-id :refactor-fcis
               :label "Show refactoring"
               :safe? false
               :action :show-refactoring
               :params {}}]
      (with-out-str
        (executor/execute-fix! fix
                               {:guidance-level :minimal
                                :confirm-fn (fn [_] (reset! confirmed? true) true)}))
      (is (true? @confirmed?))))

  (testing "risky fix aborted when user declines"
    (let [fix {:fix-id :refactor-fcis
               :label "Show refactoring"
               :safe? false
               :action :show-refactoring
               :params {}}
          output (with-out-str
                   (executor/execute-fix! fix
                                          {:guidance-level :full
                                           :confirm-fn (fn [_] false)}))]
      (is (str/includes? output "Aborted"))))

  (testing "failed action reports failure to user"
    (let [fix {:fix-id :unknown
               :label "Do something"
               :safe? true
               :action :nonexistent-action
               :params {}}
          output (with-out-str
                   (executor/execute-fix! fix {:guidance-level :full}))]
      (is (str/includes? output "could not be applied")))))

(deftest ^:integration set-jwt-writes-env-file-test
  (testing ":set-jwt writes JWT_SECRET to .env"
    (let [output (with-out-str
                   (executor/execute-fix!
                    {:fix-id :set-jwt-secret
                     :label "Generate dev JWT_SECRET"
                     :safe? true
                     :action :set-jwt
                     :params {}}
                    {:guidance-level :full}))
          env-file (io/file ".env")]
      (is (str/includes? output "Written to .env"))
      (is (str/includes? output "Restart the REPL"))
      (when (.exists env-file)
        (let [content (slurp env-file)]
          (is (str/includes? content "JWT_SECRET=\"dev-secret-")))))))

(deftest ^:integration set-env-reads-required-env-var-test
  (testing ":set-env reads :required-env-var from ex-data params"
    (let [output (with-out-str
                   (executor/execute-fix!
                    {:fix-id :set-env-var
                     :label "Set missing env var"
                     :safe? true
                     :action :set-env
                     :params {:required-env-var "DATABASE_URL" :value "postgres://localhost/dev"}}
                    {:guidance-level :full}))
          env-file (io/file ".env")]
      (is (str/includes? output "Written to .env"))
      (when (.exists env-file)
        (let [content (slurp env-file)]
          (is (str/includes? content "DATABASE_URL=\"postgres://localhost/dev\"")))))))
