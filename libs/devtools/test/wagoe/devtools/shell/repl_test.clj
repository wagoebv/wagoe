(ns wagoe.devtools.shell.repl-test
  (:require [clojure.string]
            [clojure.test :refer [deftest is testing]]
            [wagoe.devtools.shell.repl :as repl]
            [integrant.core :as ig]))

;; =============================================================================
;; extract-module
;; =============================================================================

(deftest ^:unit extract-module-from-handler-test
  (testing "extracts user module from handler string"
    (is (= "user" (repl/extract-module "wagoe.user.shell.http/list-users"))))

  (testing "extracts admin module from handler string"
    (is (= "admin" (repl/extract-module "wagoe.admin.shell.http/dashboard"))))

  (testing "extracts platform module from a deeper handler path"
    (is (= "platform" (repl/extract-module "wagoe.platform.shell.interfaces.http.common/health-check-handler"))))

  (testing "returns nil for non-boundary handler"
    (is (nil? (repl/extract-module "some.other/handler"))))

  (testing "returns nil for nil input"
    (is (nil? (repl/extract-module nil)))))

;; =============================================================================
;; build-simulate-request
;; =============================================================================

(deftest ^:unit build-simulate-request-test
  (testing "GET request has correct :request-method and :uri"
    (let [req (repl/build-simulate-request :get "/api/users" {})]
      (is (= :get (:request-method req)))
      (is (= "/api/users" (:uri req)))))

  (testing "POST request with :body has non-nil body"
    (let [req (repl/build-simulate-request :post "/api/users"
                                           {:body {:name "Alice" :email "alice@example.com"}})]
      (is (= :post (:request-method req)))
      (is (some? (:body req)))))

  (testing "default headers include content-type and accept"
    (let [req (repl/build-simulate-request :get "/api/users" {})]
      (is (= "application/json" (get-in req [:headers "content-type"])))
      (is (= "application/json" (get-in req [:headers "accept"])))))

  (testing "extra headers are merged in"
    (let [req (repl/build-simulate-request :get "/api/users"
                                           {:headers {"authorization" "Bearer token123"}})]
      (is (= "Bearer token123" (get-in req [:headers "authorization"])))))

  (testing "query-params are encoded into :query-string"
    (let [req (repl/build-simulate-request :get "/api/users"
                                           {:query-params {:page "1" :limit "20"}})]
      (is (string? (:query-string req)))
      (is (clojure.string/includes? (:query-string req) "page=1"))
      (is (clojure.string/includes? (:query-string req) "limit=20"))
      (is (nil? (:query-params req))))))

;; =============================================================================
;; build-query-map
;; =============================================================================

(deftest ^:unit build-query-honeysql-test
  (testing "basic query has correct :from, :select, and default :limit"
    (let [q (repl/build-query-map :users {})]
      (is (= [:users] (:from q)))
      (is (= [:*] (:select q)))
      (is (= 20 (:limit q)))))

  (testing "custom :limit overrides default"
    (let [q (repl/build-query-map :products {:limit 5})]
      (is (= 5 (:limit q)))))

  (testing ":where clause is included when provided"
    (let [where-clause [:= :status "active"]
          q (repl/build-query-map :users {:where where-clause})]
      (is (= where-clause (:where q)))))

  (testing ":order-by clause is included when provided"
    (let [order [:created-at :desc]
          q (repl/build-query-map :users {:order-by order})]
      (is (= order (:order-by q)))))

  (testing "no :where key when not provided"
    (let [q (repl/build-query-map :users {})]
      (is (not (contains? q :where)))))

  (testing "table name is correctly placed in :from vector"
    (let [q (repl/build-query-map :orders {})]
      (is (= [:orders] (:from q))))))

;; =============================================================================
;; find-dependents (transitive)
;; =============================================================================

(deftest ^:unit find-dependents-direct-test
  (testing "finds direct dependents"
    (let [config {:wagoe/db       {:host "localhost"}
                  :wagoe/repo     {:db (ig/ref :wagoe/db)}
                  :wagoe/unrelated {:foo "bar"}}]
      (is (= [:wagoe/repo] (repl/find-dependents config :wagoe/db))))))

(deftest ^:unit find-dependents-transitive-test
  (testing "chain: repo before service before handler"
    (let [config {:wagoe/db       {:host "localhost"}
                  :wagoe/repo     {:db (ig/ref :wagoe/db)}
                  :wagoe/service  {:repo (ig/ref :wagoe/repo)}
                  :wagoe/handler  {:svc (ig/ref :wagoe/service)}
                  :wagoe/unrelated {:foo "bar"}}
          deps   (repl/find-dependents config :wagoe/db)]
      (is (= 3 (count deps)))
      (is (every? (set deps) [:wagoe/repo :wagoe/service :wagoe/handler]))
      (is (< (.indexOf deps :wagoe/repo) (.indexOf deps :wagoe/service)))
      (is (< (.indexOf deps :wagoe/service) (.indexOf deps :wagoe/handler)))))

  (testing "diamond: handler depends on both db and service, so service before handler"
    ;; Changing :wagoe/db should restart :wagoe/service first,
    ;; then :wagoe/handler (which refs both db AND service).
    ;; BFS level-order would put them on the same level — wrong.
    (let [config {:wagoe/db       {:host "localhost"}
                  :wagoe/service  {:db (ig/ref :wagoe/db)}
                  :wagoe/handler  {:db  (ig/ref :wagoe/db)
                                      :svc (ig/ref :wagoe/service)}
                  :wagoe/unrelated {:foo "bar"}}
          deps   (repl/find-dependents config :wagoe/db)]
      (is (= 2 (count deps)))
      (is (every? (set deps) [:wagoe/service :wagoe/handler]))
      ;; service must restart before handler so handler gets fresh service ref
      (is (< (.indexOf deps :wagoe/service) (.indexOf deps :wagoe/handler))))))

(deftest ^:unit find-dependents-no-dependents-test
  (testing "returns empty when no dependents exist"
    (let [config {:wagoe/db   {:host "localhost"}
                  :wagoe/cache {:ttl 300}}]
      (is (empty? (repl/find-dependents config :wagoe/db))))))
