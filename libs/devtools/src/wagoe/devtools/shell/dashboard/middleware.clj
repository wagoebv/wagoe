(ns wagoe.devtools.shell.dashboard.middleware
  (:require [clojure.string :as str]
            [ring.middleware.params :as ring-params])
  (:import [java.time Instant]))

(def ^:private max-entries 200)

(defonce ^:private request-log* (atom []))

(def ^:private sensitive-headers
  #{"authorization" "cookie" "x-api-key" "x-auth-token" "set-cookie"})

(def ^:private sensitive-param-keys
  #{"password" "password_hash" "password-hash" "passwordHash"
    "token" "secret" "api_key" "api-key" "apiKey"
    "access_token" "access-token" "accessToken"
    "refresh_token" "refresh-token" "refreshToken"
    "credit_card" "credit-card" "creditCard" "cvv" "ssn"})

(defn request-log
  "Return captured requests, newest first."
  []
  @request-log*)

(defn clear-request-log! []
  (reset! request-log* []))

(defn- sanitize-headers [headers]
  (reduce-kv (fn [m k v]
               (assoc m k (if (contains? sensitive-headers (str/lower-case (str k)))
                            "[REDACTED]"
                            v)))
             {} headers))

(defn- sanitize-params
  "Recursively redact values for keys that look like credentials or tokens."
  [params]
  (when params
    (cond
      (map? params)
      (reduce-kv (fn [m k v]
                   (let [k-str (str/lower-case (str (if (keyword? k) (name k) k)))]
                     (assoc m k (if (contains? sensitive-param-keys k-str)
                                  "[REDACTED]"
                                  (sanitize-params v)))))
                 {} params)
      (sequential? params)
      (mapv sanitize-params params)
      :else params)))

(defn- truncate-body [body]
  (when body
    (let [s (str body)]
      (if (> (count s) 2000)
        (subs s 0 2000)
        s))))

(defn- parse-query-params
  "Parse query string into a params map. Used when the capture middleware
   runs outside of Ring's wrap-params and :params is not yet populated."
  [request]
  (if (seq (:params request))
    (:params request)
    (:query-params (ring-params/params-request request))))

(defn- log-entry! [request response duration]
  (swap! request-log*
         (fn [log]
           (let [parsed-params (parse-query-params request)
                 entry {:id          (random-uuid)
                        :timestamp   (Instant/now)
                        :method      (:request-method request)
                        :path        (:uri request)
                        :status      (or (:status response) 500)
                        :duration-ms (Math/round ^double duration)
                        :request     {:headers     (sanitize-headers (:headers request))
                                      :params      (sanitize-params parsed-params)
                                      :body-params (sanitize-params (:body-params request))}
                        :response    {:status  (or (:status response) 500)
                                      :headers (sanitize-headers (:headers response))
                                      :body    (truncate-body (:body response))}}
                 new-log (into [entry] log)]
             (if (> (count new-log) max-entries)
               (subvec new-log 0 max-entries)
               new-log)))))

(defn wrap-request-capture
  "Ring middleware that captures request/response pairs into a bounded log.
   Records both successful responses and exceptions (as 500).
   Preserves handler metadata (e.g. :reitit/router) so route discovery still works."
  [handler]
  (let [wrapper (fn [request]
                  (let [start-ns (System/nanoTime)]
                    (try
                      (let [response (handler request)
                            duration (/ (- (System/nanoTime) start-ns) 1e6)]
                        (log-entry! request response duration)
                        response)
                      (catch Throwable t
                        (let [duration (/ (- (System/nanoTime) start-ns) 1e6)]
                          (log-entry! request {:status 500 :body (.getMessage t)} duration))
                        (throw t)))))]
    (with-meta wrapper (meta handler))))
