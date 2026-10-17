(ns wagoe.op-metrics-test
  "Op metrics from the generic platform interceptors reach the configured
   emitter. They read `:metrics-emitter` from the pipeline's :system; for a
   while nothing put it there, so they never fired and no test noticed."
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [wagoe.config :as config]
            [wagoe.observability.metrics.ports :as metrics]
            [wagoe.system-config :as sys-config])
  (:import [java.util UUID]))

(defn- route-handler
  "The `method` handler of the route at `path` in reitit route data."
  [routes path method]
  (some (fn [node]
          (when (and (vector? node) (= path (first node)))
            (get-in (second node) [method :handler])))
        (tree-seq sequential? seq routes)))

(defn- counter-total [m metric-name]
  (reduce + 0 (vals (:series (metrics/get-metric-values m metric-name)))))

(deftest ^:integration user-op-metrics-reach-the-configured-emitter
  (let [cfg    (-> (config/load-config {:profile :test})
                   (assoc-in [:active :wagoe/metrics] {:provider :prometheus}))
        system (ig/init (sys-config/ig-config cfg) [:wagoe/user-routes])]
    (try
      (let [emitter (:wagoe/metrics system)
            handler (route-handler (:api (:wagoe/user-routes system)) "/users/:id" :get)]
        (is (some? handler) "GET /users/:id is not in the user module's routes")
        (handler {:request-method :get
                  :uri            "/api/v1/users/x"
                  :path-params    {:id (str (UUID/randomUUID))}
                  :headers        {}})
        (testing "the attempt is counted on the system's emitter"
          (is (= 1 (counter-total emitter "user-get.attempt"))))
        (testing "and the outcome and latency with it"
          (is (= 1 (+ (counter-total emitter "user-get.success")
                      (counter-total emitter "user-get.error"))))
          (is (= 1 (-> (metrics/get-metric-values emitter "user-get.latency.seconds")
                       :series vals first :count)))))
      (finally (ig/halt! system)))))
