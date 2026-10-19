(ns wagoe.op-metrics-test
  "Op metrics from the generic platform interceptors reach the configured
   emitter. They read `:metrics-emitter` from the pipeline's :system; for a
   while nothing put it there, so they never fired and no test noticed."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.observability.metrics.ports :as metrics]
            [wagoe.system-config :as sys-config]
            [wagoe.test :as wt]
            [wagoe.user.ports :as user-ports]))

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
  ;; Its own H2 database: a full run boots many systems in one JVM.
  (wt/with-test-system [system {:ig-config sys-config/ig-config
                                :config    #(assoc-in % [:active :wagoe/metrics] {:provider :prometheus})}]
    (let [emitter (:wagoe/metrics system)
          handler (route-handler (:api (:wagoe/user-routes system)) "/users/:id" :get)
          user    (user-ports/register-user (:wagoe/user-service system)
                                            {:email "op-metrics@example.com" :name "Op" :role :user
                                             :password "Kit-Passw0rd-469!"})]
      (is (some? handler) "GET /users/:id is not in the user module's routes")
      (is (= 200 (:status (handler {:request-method :get
                                    :uri            (str "/api/v1/users/" (:id user))
                                    :parameters     {:path {:id (str (:id user))}}
                                    :headers        {}}))))
      (testing "the attempt is counted on the system's emitter"
        (is (= 1 (counter-total emitter "user-get.attempt"))))
      ;; User ops answer with a :response and no :result; a 200 counted as an
      ;; error until op-metrics read the response.
      (testing "a 200 counts as a success, with its latency"
        (is (= 1 (counter-total emitter "user-get.success")))
        (is (= 0 (counter-total emitter "user-get.error")))
        (is (= 1 (-> (metrics/get-metric-values emitter "user-get.latency.seconds")
                     :series vals first :count)))))))
