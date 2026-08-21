(ns wagoe.devtools.core.error-classifier-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.devtools.core.error-classifier :as classifier]))

(deftest ^:unit classify-strategy-1-explicit-code-test
  (testing "ex-data with :wagoe/error-code uses that code directly"
    (let [ex (ex-info "validation failed" {:wagoe/error-code "BND-201"
                                           :schema :user/create})
          result (classifier/classify ex)]
      (is (= "BND-201" (:code result)))
      (is (= :validation (:category result)))
      (is (= :ex-data (:source result))))))

(deftest ^:unit classify-strategy-2-ex-data-pattern-test
  (testing "Malli validation error → BND-201"
    (let [ex (ex-info "validation" {:type :malli.core/invalid})
          result (classifier/classify ex)]
      (is (= "BND-201" (:code result)))
      (is (= :ex-data-pattern (:source result)))))

  (testing "ex-data with :type :db/error → BND-303"
    (let [ex (ex-info "db error" {:type :db/error})
          result (classifier/classify ex)]
      (is (= "BND-303" (:code result))))))

(deftest ^:unit classify-strategy-3-exception-type-test
  (testing "SQLException → BND-303"
    (let [ex (java.sql.SQLException. "connection refused")]
      (is (= "BND-303" (:code (classifier/classify ex))))))

  (testing "ConnectException → BND-303"
    (let [ex (java.net.ConnectException. "Connection refused")]
      (is (= "BND-303" (:code (classifier/classify ex)))))))

(deftest ^:unit classify-strategy-4-message-pattern-test
  (testing "relation does not exist → BND-301"
    (let [ex (java.sql.SQLException. "ERROR: relation \"invoices\" does not exist")]
      (is (= "BND-301" (:code (classifier/classify ex))))))

  (testing "table not found → BND-301"
    (let [ex (java.sql.SQLException. "Table \"INVOICES\" not found")]
      (is (= "BND-301" (:code (classifier/classify ex)))))))

(deftest ^:unit classify-strategy-5-unclassified-test
  (testing "generic exception returns nil code"
    (let [ex (Exception. "something went wrong")]
      (is (nil? (:code (classifier/classify ex)))))))

(deftest ^:unit classify-chained-exception-test
  (testing "root cause is classified when wrapper has no :wagoe/error-code"
    (let [root (java.sql.SQLException. "ERROR: relation \"users\" does not exist")
          wrapper (ex-info "operation failed" {:operation :save} root)
          result (classifier/classify wrapper)]
      (is (= "BND-301" (:code result)))))

  (testing "wrapper :wagoe/error-code takes precedence over root cause"
    (let [root (java.sql.SQLException. "connection refused")
          wrapper (ex-info "known error" {:wagoe/error-code "BND-201"} root)
          result (classifier/classify wrapper)]
      (is (= "BND-201" (:code result))))))

(deftest ^:unit classify-configuration-error-test
  (testing "JWT_SECRET configuration error → BND-103"
    (let [ex (ex-info "JWT_SECRET not configured"
                      {:type :configuration-error :required-env-var "JWT_SECRET"})
          result (classifier/classify ex)]
      (is (= "BND-103" (:code result)))
      (is (= :config (:category result)))
      (is (= :ex-data-pattern (:source result)))))

  (testing "other missing env var → BND-101"
    (let [ex (ex-info "DATABASE_URL not configured"
                      {:type :configuration-error :required-env-var "DATABASE_URL"})
          result (classifier/classify ex)]
      (is (= "BND-101" (:code result)))
      (is (= :config (:category result)))))

  (testing "configuration error without :required-env-var stays unclassified"
    (let [ex (ex-info "Tenant schema provider not configured"
                      {:type :configuration-error :job-id 42})
          result (classifier/classify ex)]
      (is (nil? (:code result))
          "should not misclassify as JWT-specific BND-103"))))

(deftest ^:unit an-unknown-provider-is-bnd-102
  ;; BND-102 ("Unknown Provider") sat in the catalogue with nothing producing
  ;; it. The module wirings of ai, geo, events, payments and storage threw the
  ;; same mistake under four different types — one of them :validation-error,
  ;; which the HTTP layer maps to 400, telling a caller they sent a bad request
  ;; when the server was misconfigured. One type now (BOU-323).
  (is (= "BND-102" (:code (classifier/classify
                           (ex-info "Unknown AI provider"
                                    {:type :unknown-provider :provider :nope})))))

  (testing "a config error that merely names a provider is not misfiled as one"
    ;; A missing Sentry DSN names its provider too, and answering "check the
    ;; valid providers list" to that is worse than answering nothing.
    (is (nil? (:code (classifier/classify
                      (ex-info "Sentry DSN is required"
                               {:type :configuration-error :provider :sentry})))))))

(deftest ^:unit the-thrower-s-type-beats-the-cause-s-class
  ;; classify walked to the root cause first, so every wrapped database failure
  ;; was read as its SQLException: an INSERT violating a unique constraint came
  ;; back as BND-303 "Database Connection Failed — verify the database is
  ;; running". The code that threw chose a :type; that choice is better
  ;; information than the class of what it wrapped (BOU-323).
  (let [constraint (java.sql.SQLException.
                    "duplicate key value violates unique constraint \"users_email_key\"")]
    (is (= "BND-304" (:code (classifier/classify
                             (ex-info "Database update failed"
                                      {:type :database-error} constraint))))))

  (testing "a connection failure still reads as one"
    (is (= "BND-303" (:code (classifier/classify
                             (ex-info "Database initialization failed"
                                      {:type :db/error}
                                      (java.sql.SQLException. "connection refused")))))))

  (testing "an untyped wrapper still falls through to the cause"
    (is (= "BND-303" (:code (classifier/classify
                             (ex-info "boom" {}
                                      (java.sql.SQLException. "connection refused")))))))

  (testing "and an explicit :wagoe/error-code still wins over everything"
    (is (= "BND-201" (:code (classifier/classify
                             (ex-info "x" {:wagoe/error-code "BND-201"
                                           :type :database-error})))))))
