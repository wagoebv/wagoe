(ns wagoe.test-support.shell.handler
  "HTTP wrapper for POST /test/reset. Guarded at mount-time by profile flag."
  (:require [clojure.tools.logging :as log]))

(defn make-reset-handler
  "Returns a ring handler that truncates, optionally seeds, and returns JSON.

   deps: {:truncate! (fn [_]) :seed! (fn [_])}
   Both functions receive the full deps map so they can pull services as needed."
  [deps]
  (fn [request]
    (try
      (let [seed-kind (or (get-in request [:body-params :seed])
                          (get-in request [:params :seed])
                          "baseline")
            _        ((:truncate! deps) deps)
            seeded   (if (= "empty" seed-kind)
                       {}
                       ((:seed! deps) deps))]
        (log/info "test-reset invoked" {:seed seed-kind})
        {:status 200
         :body   {:ok true :seeded seeded}})
      (catch Throwable t
        (log/error t "test-reset failed")
        {:status 500
         :body   {:ok false :error (.getMessage t)}}))))
