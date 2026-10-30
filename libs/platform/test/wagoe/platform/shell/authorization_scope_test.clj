(ns wagoe.platform.shell.authorization-scope-test
  "Scope enforcement through the router (ADR-041 §4), with a test double for
   the user module's rights."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [wagoe.platform.core.scope :as scope]
            [wagoe.platform.ports.authorization :as ports]
            [wagoe.platform.shell.authorization :as sut]
            [wagoe.platform.shell.http.reitit-router :as router]))

(def ^:private org-tree
  "Departments under directorates: 1 → 10, 11. A department can be added
   after a right is assigned."
  (atom {1 #{10 11}}))

(defn- with-descendants [ids]
  (into (set ids) (mapcat #(get @org-tree % #{})) ids))

(def ^:private registry
  (sut/create-registry
   {:wagoe/orders {:scopes        {:domain-id     {:label "Domain" :entity :domains}
                                   :department-id {:label   "Department"
                                                   :sources #{:user/department}
                                                   :expand  with-descendants}}
                   :scope-sources {:user/department {:label   "The member's department"
                                                     :resolve (fn [user] (:departments user))}}}}))

(deftest ^:unit rights-resolve-into-kept-and-dropped
  ;; BOU-865
  (let [kept    {:permission :orders/read :scope {:department-id :user/department}}
        dropped {:permission :orders/write :scope {:domain-id :user/domain}}]
    (is (= {:kept    [{:permission :orders/read :scope {:department-id #{10}}}]
            :dropped [dropped]}
           (sut/resolve-rights-split registry {:departments #{10}} [kept dropped])))))

(deftest ^:unit rights-resolve-per-user
  (let [assignment [{:permission :orders/read :scope {:department-id :user/department}}]]
    (testing "one group, each member their own department"
      (is (= [{:permission :orders/read :scope {:department-id #{10}}}]
             (sut/resolve-rights registry {:departments #{10}} assignment)))
      (is (= #{11} (get-in (sut/resolve-rights registry {:departments #{11}} assignment) [0 :scope :department-id]))))
    (testing "nothing resolved is no rights, not rights everywhere"
      (is (= [] (sut/resolve-rights registry {:departments #{}} assignment)))
      (is (= [] (sut/resolve-rights registry {:departments #{nil}} assignment)) "a nil is no department"))
    (testing "a source nothing resolves gives no rights"
      (is (= [] (sut/resolve-rights registry {} [{:permission :orders/read :scope {:domain-id :user/domain}}]))))
    (testing "expand runs after the source, and covers departments added later"
      (let [user {:departments #{1}}]
        (is (= #{1 10 11} (get-in (sut/resolve-rights registry user assignment) [0 :scope :department-id])))
        (swap! org-tree update 1 conj 12)
        (try
          (is (contains? (get-in (sut/resolve-rights registry user assignment) [0 :scope :department-id]) 12))
          (finally (swap! org-tree update 1 disj 12)))))
    (testing "a fixed value, and no scope"
      (is (= [{:permission :orders/read :scope {:domain-id #{42}}}
              {:permission :orders/write :scope {}}]
             (sut/resolve-rights registry nil [{:permission :orders/read :scope {:domain-id 42}}
                                               {:permission :orders/write :scope nil}]))))))

;; =============================================================================
;; Through the router
;; =============================================================================

(defn- datasource []
  (let [ds (jdbc/get-datasource {:jdbcUrl (str "jdbc:h2:mem:scope-" (random-uuid) ";DB_CLOSE_DELAY=-1")})]
    (jdbc/execute! ds ["CREATE TABLE customers (id INT PRIMARY KEY, domain_id INT)"])
    (jdbc/execute! ds ["CREATE TABLE orders (id INT PRIMARY KEY, domain_id INT, customer_id INT)"])
    (jdbc/execute! ds ["INSERT INTO customers VALUES (1, 42), (2, 43)"])
    (jdbc/execute! ds ["INSERT INTO orders VALUES (100, 42, 1), (101, 43, 2), (102, 42, 2)"])
    ds))

(defn- order-ids [ds where]
  (mapv :id (jdbc/execute! ds (sql/format {:select [:id] :from [:orders] :where where :order-by [:id]})
                           {:builder-fn rs/as-unqualified-lower-maps})))

(def ^:private users
  {"everywhere" {:rights [{:permission :orders/read :scope nil}]}
   "domain-42"  {:rights [{:permission :orders/read :scope {:domain-id 42}}]}
   "two-keys"   {:rights [{:permission :orders/read :scope {:domain-id 42 :department-id 10}}]}
   "nothing"    {:rights []}
   "admin"      {:role :admin :rights []}})

(defn- handler [ds]
  (router/compile-routes
   [["/api/orders" {:wagoe/module :wagoe/orders
                    :get {:handler (fn [_] {:status 200 :body {:ok true}})}}]
    ["/api/domains/:domain-id/orders"
     {:scope      {:domain-id :path/domain-id}
      :permission :orders/read
      :get        {:handler (fn [_] {:status 200 :body {:ok true}})}}]
    ["/api/my-orders"
     {:get {:scope      :handler
            :permission :orders/read
            :handler (fn [request]
                       {:status 200
                        :body   {:ids (order-ids ds (scope/scope-filter (:wagoe/scopes request)
                                                                        {:domain-id :domain_id}))}})}}]
    ["/api/health" {:get {:handler (fn [_] {:status 200 :body {:ok true}}) :public true}}]]
   {:swagger-enabled false
    :system          {}
    :authentication  {:middleware [(fn [h] (fn [req]
                                             (h (if-let [who (get-in req [:headers "x-user"])]
                                                  (assoc req :user (assoc (users who) :id who))
                                                  req))))]}
    :authorization   [(sut/authorization-middleware registry
                                                (reify ports/IRights (rights [_ user] (:rights user)))
                                                {:mode :enforce})]}))

(defn- call [h uri user]
  (let [r (h {:request-method :get :uri uri :scheme :http :server-name "localhost" :server-port 80
              :headers (cond-> {"accept" "application/json"} user (assoc "x-user" user))})]
    {:status (:status r)
     :body   (let [b (:body r)] (json/parse-string (if (string? b) b (slurp b)) true))}))

(deftest ^:integration scope-is-enforced-through-the-router
  (let [ds (datasource)
        h  (handler ds)]
    (testing "a route without :scope admits only unscoped holders"
      (is (= 200 (:status (call h "/api/orders" "everywhere"))))
      (is (= 403 (:status (call h "/api/orders" "domain-42"))))
      (is (= 403 (:status (call h "/api/orders" "nothing")))))
    (testing ":scope {:domain-id :path/domain-id}: the platform checks the parameter"
      (is (= 200 (:status (call h "/api/domains/42/orders" "domain-42"))))
      (let [{:keys [status body]} (call h "/api/domains/43/orders" "domain-42")]
        (is (= 404 status) "outside the scope is a 404")
        (is (= "not-found" (get-in body [:error :type]))))
      (is (= 403 (:status (call h "/api/domains/42/orders" "two-keys")))
          "a scope key nobody checks never passes")
      (is (= 200 (:status (call h "/api/domains/43/orders" "everywhere")))))
    (testing ":scope :handler: the handler filters with scope-filter"
      (is (= [100 102] (get-in (call h "/api/my-orders" "domain-42") [:body :ids])))
      (is (= [100 101 102] (get-in (call h "/api/my-orders" "everywhere") [:body :ids])))
      (is (= [] (get-in (call h "/api/my-orders" "two-keys") [:body :ids]))
          "the handler maps no department, so that scope admits nothing"))
    (testing "a platform admin holds everywhere"
      (is (= 200 (:status (call h "/api/orders" "admin")))))
    (testing "a public route is not checked"
      (is (= 200 (:status (call h "/api/health" nil)))))))

(deftest ^:integration a-relation-scope-filters-the-rows-the-join-does
  (let [ds       (datasource)
        scopes   [{:domain-id #{42}}]
        relation (fn [ids] [:in :customer_id {:select [:id] :from [:customers] :where [:in :domain_id ids]}])
        joined   (mapv :id (jdbc/execute! ds ["SELECT o.id FROM orders o JOIN customers c ON c.id = o.customer_id
                                               WHERE c.domain_id = 42 ORDER BY o.id"]
                                          {:builder-fn rs/as-unqualified-lower-maps}))]
    (is (= [100] joined))
    (is (= joined (order-ids ds (scope/scope-filter scopes {:domain-id relation}))))))
