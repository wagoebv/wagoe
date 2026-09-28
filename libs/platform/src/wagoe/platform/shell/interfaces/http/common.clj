(ns wagoe.platform.shell.interfaces.http.common
  "Common HTTP utilities: error responses in the one error shape
   (`wagoe.platform.core.http.errors`) and health checks.

   The pure transformations are in wagoe.platform.core.http.problem-details."
  (:require [wagoe.platform.core.http.errors :as errors]
            [wagoe.platform.core.http.problem-details :as core-problem]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

;; =============================================================================
;; Error responses (re-exported from core)
;; =============================================================================

(def default-error-mappings
  "Default mapping of exception types to HTTP status codes and titles.
   
   Re-exported from core for backward compatibility."
  core-problem/default-error-mappings)

(defn exception->problem
  "Convert exception to an error response in the one error shape.

   Args:
     ex: Exception to convert
     correlation-id: Request correlation ID
     uri: Request URI
     error-mappings: Optional custom error type mappings (overrides defaults)

   Returns:
     Ring response map"
  ([ex correlation-id uri]
   (exception->problem ex correlation-id uri {}))
  ([ex correlation-id uri error-mappings]
   (core-problem/exception->problem-response ex correlation-id uri error-mappings)))

;; =============================================================================
;; Standard HTTP Response Handlers
;; =============================================================================

(defn create-not-found-handler
  "Create a standardized 404 Not Found handler.

   Returns:
     Ring handler function for 404 responses"
  []
  (fn [_]
    (core-problem/not-found "The requested resource was not found")))

(defn create-method-not-allowed-handler
  "Create a standardized 405 Method Not Allowed handler.

   Args:
     allowed-methods: Collection of allowed HTTP methods

   Returns:
     Ring handler function for 405 responses"
  [allowed-methods]
  (fn [_]
    (-> (core-problem/problem-details->response
         405 (errors/body :method-not-allowed
                          (str "Allowed methods: " (str/join ", " (map name allowed-methods)))))
        (assoc-in [:headers "Allow"] (str/join ", " (map name allowed-methods))))))

(defn health-check-handler
  "Create a generic health check handler.

   Args:
     service-name: Name of the service
     version: Service version (optional)
     additional-checks: Optional function that returns additional health data

   Returns:
     Ring handler function for health checks"
  ([service-name]
   (health-check-handler service-name nil nil))
  ([service-name version]
   (health-check-handler service-name version nil))
  ([service-name version additional-checks]
   (fn [_request]
     (let [base-health {:status "ok"
                        :service service-name
                        :version (or version "unknown")
                        :timestamp (str (java.time.Instant/now))}
           additional (when additional-checks (additional-checks))]
       {:status 200
        :headers {"Content-Type" "application/json"}
        :body (json/generate-string (merge base-health additional))}))))

;; =============================================================================
;; Component Health Checks
;; =============================================================================

(defn- log-check-failure
  "One line, no stack trace: readiness is polled, and the reason stays in the
   log because the response body is readable by load balancers (BOU-558)."
  [component ^Throwable e]
  (log/warnf "%s readiness check failed: %s: %s"
             component (.getName (class e)) (.getMessage e)))

(defn- check-database
  "Check database connectivity by executing SELECT 1.

   Args:
     db-context: Database context {:datasource ds :adapter adapter}

   Returns:
     Component status map"
  [db-context]
  (try
    (let [start (System/currentTimeMillis)
          ds (:datasource db-context)]
      (with-open [conn (.getConnection ds)]
        (with-open [stmt (.prepareStatement conn "SELECT 1")]
          (.execute stmt)))
      {:status "ok"
       :response-time-ms (- (System/currentTimeMillis) start)})
    (catch Exception e
      (log-check-failure "Database" e)
      {:status "down"
       :error "database unreachable"})))

(defn- check-cache
  "Check cache connectivity via ping.

   Calls .ping on the cache component (ICacheManagement protocol).

   Args:
     cache: Cache component implementing ICacheManagement

   Returns:
     Component status map"
  [cache]
  (try
    (let [start (System/currentTimeMillis)
          reachable? (.ping cache)]
      (if reachable?
        {:status "ok"
         :response-time-ms (- (System/currentTimeMillis) start)}
        {:status "down"
         :error "ping returned false"}))
    (catch Exception e
      (log-check-failure "Cache" e)
      {:status "down"
       :error "cache unreachable"})))

(defn readiness-handler
  "Create a readiness check handler that verifies dependency health.

   Checks database and (optionally) cache connectivity. Returns 200
   when all components are healthy, 503 when any component is down.

   Args:
     db-context: Database context (required)
     cache: Cache component (optional, nil if not configured)

   Returns:
     Ring handler function for readiness checks"
  [db-context cache]
  (fn [_request]
    (let [components (cond-> {}
                       db-context
                       (assoc :database (check-database db-context))

                       cache
                       (assoc :cache (check-cache cache)))
          all-ok? (every? #(= "ok" (:status %)) (vals components))
          overall (if all-ok? "ok" "degraded")]
      {:status (if all-ok? 200 503)
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string
              {:status overall
               :components components
               :timestamp (str (java.time.Instant/now))})})))

