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
  ;; it. The module wirings for ai, geo and the observability adapters throw
  ;; :configuration-error naming the :provider they could not resolve
  ;; (BOU-323) — that is the case the entry describes.
  (is (= "BND-102" (:code (classifier/classify (ex-info "Unknown AI provider"
                                                 {:type :configuration-error
                                                  :provider :nope})))))
  (testing "a configuration error without a provider is not misfiled as one"
    (is (nil? (:code (classifier/classify (ex-info "Datadog API key is required"
                                            {:type :configuration-error})))))))
