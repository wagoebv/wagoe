(ns wagoe.platform.shell.interfaces.http.common-test
  (:require [wagoe.platform.shell.interfaces.http.common :as sut]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is]]))

;; =============================================================================
;; health-check-handler
;; =============================================================================

(deftest ^:unit health-check-handler-returns-basic-status
  (let [handler (sut/health-check-handler "test-service" "1.0.0")
        response (handler {})]
    (is (= 200 (:status response)))
    (let [body (json/parse-string (:body response) true)]
      (is (= "ok" (:status body)))
      (is (= "test-service" (:service body)))
      (is (= "1.0.0" (:version body)))
      (is (string? (:timestamp body))))))

(deftest ^:unit health-check-handler-merges-additional-checks
  (let [handler (sut/health-check-handler "svc" "2.0" (fn [] {:extra "data"}))
        body (json/parse-string (:body (handler {})) true)]
    (is (= "data" (:extra body)))
    (is (= "ok" (:status body)))))

(deftest ^:unit health-check-handler-defaults-version-to-unknown
  (let [handler (sut/health-check-handler "svc")
        body (json/parse-string (:body (handler {})) true)]
    (is (= "unknown" (:version body)))))

;; =============================================================================
;; readiness-handler — database only
;; =============================================================================

(defn- mock-datasource
  "Create a mock DataSource that returns a mock Connection."
  [healthy?]
  (reify javax.sql.DataSource
    (getConnection [_]
      (if healthy?
        (reify java.sql.Connection
          (prepareStatement [_ _sql]
            (reify java.sql.PreparedStatement
              (execute [_] true)
              (close [_])))
          (close [_]))
        (throw (java.sql.SQLException. "connection refused"))))))

(deftest ^:unit readiness-handler-returns-200-when-db-healthy
  (let [db-ctx {:datasource (mock-datasource true)}
        handler (sut/readiness-handler db-ctx nil)
        response (handler {})
        body (json/parse-string (:body response) true)]
    (is (= 200 (:status response)))
    (is (= "ok" (:status body)))
    (is (= "ok" (get-in body [:components :database :status])))
    (is (number? (get-in body [:components :database :response-time-ms])))
    (is (nil? (get-in body [:components :cache])))))

(deftest ^:unit readiness-handler-returns-503-when-db-down
  (let [db-ctx {:datasource (mock-datasource false)}
        handler (sut/readiness-handler db-ctx nil)
        response (handler {})
        body (json/parse-string (:body response) true)]
    (is (= 503 (:status response)))
    (is (= "degraded" (:status body)))
    (is (= "down" (get-in body [:components :database :status])))
    (is (string? (get-in body [:components :database :error])))))

;; BOU-558: readiness is reachable by load balancers; a driver message names
;; hosts, users and databases.
(def ^:private secret-message
  "Connection to db.internal.example:5432 refused for user app_admin password=hunter2")

(deftest ^:unit readiness-handler-keeps-db-exception-text-out-of-body
  (let [db-ctx {:datasource (reify javax.sql.DataSource
                              (getConnection [_]
                                (throw (java.sql.SQLException. ^String secret-message))))}
        response ((sut/readiness-handler db-ctx nil) {})
        body (json/parse-string (:body response) true)]
    (is (= 503 (:status response)))
    (is (= "down" (get-in body [:components :database :status])))
    (is (not (re-find #"db\.internal|5432|app_admin|hunter2" (:body response))))))

;; =============================================================================
;; readiness-handler — with cache
;; =============================================================================

(defn- mock-cache [healthy?]
  (reify
    wagoe.cache.ports.ICacheManagement
    (ping [_] healthy?)
    (close! [_] true)
    (flush-all! [_] 0)))

(deftest ^:unit readiness-handler-includes-cache-when-provided
  (let [db-ctx {:datasource (mock-datasource true)}
        handler (sut/readiness-handler db-ctx (mock-cache true))
        response (handler {})
        body (json/parse-string (:body response) true)]
    (is (= 200 (:status response)))
    (is (= "ok" (:status body)))
    (is (= "ok" (get-in body [:components :database :status])))
    (is (= "ok" (get-in body [:components :cache :status])))))

(deftest ^:unit readiness-handler-returns-503-when-cache-down
  (let [db-ctx {:datasource (mock-datasource true)}
        handler (sut/readiness-handler db-ctx (mock-cache false))
        response (handler {})
        body (json/parse-string (:body response) true)]
    (is (= 503 (:status response)))
    (is (= "degraded" (:status body)))
    (is (= "ok" (get-in body [:components :database :status])))
    (is (= "down" (get-in body [:components :cache :status])))))

(deftest ^:unit readiness-handler-returns-503-when-both-down
  (let [db-ctx {:datasource (mock-datasource false)}
        handler (sut/readiness-handler db-ctx (mock-cache false))
        response (handler {})
        body (json/parse-string (:body response) true)]
    (is (= 503 (:status response)))
    (is (= "degraded" (:status body)))
    (is (= "down" (get-in body [:components :database :status])))
    (is (= "down" (get-in body [:components :cache :status])))))

(deftest ^:unit readiness-handler-handles-nil-db-context
  (let [handler (sut/readiness-handler nil nil)
        response (handler {})
        body (json/parse-string (:body response) true)]
    (is (= 200 (:status response)))
    (is (= "ok" (:status body)))
    (is (empty? (:components body)))))

(deftest ^:unit readiness-handler-keeps-cache-exception-text-out-of-body
  (let [cache (reify wagoe.cache.ports.ICacheManagement
                (ping [_] (throw (ex-info ^String secret-message {})))
                (close! [_] true)
                (flush-all! [_] 0))
        response ((sut/readiness-handler nil cache) {})
        body (json/parse-string (:body response) true)]
    (is (= 503 (:status response)))
    (is (= "down" (get-in body [:components :cache :status])))
    (is (not (re-find #"db\.internal|5432|app_admin|hunter2" (:body response))))))
