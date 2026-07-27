(ns wagoe.devtools.core.router
  "Pure functions for manipulating Reitit route data structures.
   All functions take and return route trees (vectors of [path handler-map]).")

(defn add-route
  "Add a route to the route tree. If the path already exists, merges the method."
  [routes method path handler-fn]
  (let [existing (first (filter #(= path (first %)) routes))]
    (if existing
      (mapv (fn [[p data :as route]]
              (if (= p path)
                [p (assoc data method {:handler handler-fn})]
                route))
            routes)
      (conj (vec routes) [path {method {:handler handler-fn}}]))))

(defn remove-route
  "Remove a method from a route. Removes the path entirely if it was the last method."
  [routes method path]
  (let [updated (mapv (fn [[p data :as route]]
                        (if (= p path)
                          [p (dissoc data method)]
                          route))
                      routes)]
    (vec (remove (fn [[_ data]] (empty? data)) updated))))

;; find-handler-in-routes MUST appear before inject/remove-tap-interceptor
(defn- find-handler-in-routes
  "Find the [path method] for a handler by its :name keyword."
  [routes handler-name]
  (first
   (for [[path data] routes
         [method handler-data] data
         :when (= handler-name (:name handler-data))]
     [path method])))

(defn inject-tap-interceptor
  "Add a :devtools/tap interceptor to a handler's interceptor chain."
  [routes handler-name tap-fn]
  (if-let [[target-path target-method] (find-handler-in-routes routes handler-name)]
    (mapv (fn [[path data :as route]]
            (if (= path target-path)
              [path (update data target-method
                            (fn [handler-data]
                              (let [tap-interceptor {:name  :devtools/tap
                                                     :enter (fn [ctx] (tap-fn ctx))}
                                    existing (or (:interceptors handler-data) [])]
                                (assoc handler-data :interceptors
                                       (vec (cons tap-interceptor existing))))))]
              route))
          routes)
    routes))

(defn remove-tap-interceptor
  "Remove the :devtools/tap interceptor from a handler's chain."
  [routes handler-name]
  (if-let [[target-path target-method] (find-handler-in-routes routes handler-name)]
    (mapv (fn [[path data :as route]]
            (if (= path target-path)
              [path (update-in data [target-method :interceptors]
                               (fn [interceptors]
                                 (vec (remove #(= :devtools/tap (:name %)) interceptors))))]
              route))
          routes)
    routes))
