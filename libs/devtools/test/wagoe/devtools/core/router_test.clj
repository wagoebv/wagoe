(ns wagoe.devtools.core.router-test
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.devtools.core.router :as router]))

(def sample-routes
  [["/api/users"
    {:get  {:handler (fn [_] {:status 200}) :name :list-users}
     :post {:handler (fn [_] {:status 201}) :name :create-user}}]
   ["/api/users/:id"
    {:get {:handler (fn [_] {:status 200}) :name :get-user}}]])

(deftest ^:unit add-route-test
  (testing "adds a new route to the route tree"
    (let [new-handler (fn [_] {:status 200 :body {:hello "world"}})
          updated (router/add-route sample-routes :get "/api/test" new-handler)]
      (is (some #(= "/api/test" (first %)) updated))))
  (testing "adds method to existing path"
    (let [new-handler (fn [_] {:status 204})
          updated (router/add-route sample-routes :delete "/api/users" new-handler)]
      (is (= 2 (count updated)))
      (let [users-route (first (filter #(= "/api/users" (first %)) updated))]
        (is (contains? (second users-route) :delete))))))

(deftest ^:unit remove-route-test
  (testing "removes a route by method and path"
    (let [updated (router/remove-route sample-routes :get "/api/users")
          users-route (first (filter #(= "/api/users" (first %)) updated))]
      (is (not (contains? (second users-route) :get)))
      (is (contains? (second users-route) :post))))
  (testing "removes entire path entry when last method removed"
    (let [updated (router/remove-route sample-routes :get "/api/users/:id")]
      (is (not (some #(= "/api/users/:id" (first %)) updated))))))

(deftest ^:unit inject-tap-interceptor-test
  (testing "injects a tap interceptor into a handler's chain"
    (let [tap-fn (fn [ctx] (assoc ctx ::tapped true))
          updated (router/inject-tap-interceptor sample-routes :create-user tap-fn)
          users-route (first (filter #(= "/api/users" (first %)) updated))
          post-data (get (second users-route) :post)
          interceptors (:interceptors post-data)]
      (is (some #(= :devtools/tap (:name %)) interceptors)))))

(deftest ^:unit remove-tap-interceptor-test
  (testing "removes the tap interceptor from a handler's chain"
    (let [tap-fn (fn [ctx] ctx)
          with-tap (router/inject-tap-interceptor sample-routes :create-user tap-fn)
          without-tap (router/remove-tap-interceptor with-tap :create-user)
          users-route (first (filter #(= "/api/users" (first %)) without-tap))
          post-data (get (second users-route) :post)
          interceptors (:interceptors post-data)]
      (is (not (some #(= :devtools/tap (:name %)) interceptors))))))
