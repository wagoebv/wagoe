(ns wagoe.platform.shell.authorization-explain-test
  "Explain answers as the interceptor decides (BOU-851): for generated rights,
   users and records, its yes is a 200 through the router and its no a refusal."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [wagoe.platform.ports.authorization :as ports]
            [wagoe.platform.shell.authorization :as sut]
            [wagoe.platform.shell.http.reitit-router :as router]))

(def ^:private registry
  (sut/create-registry
   {:wagoe/orders {:scopes        {:domain-id     {:label "Domain" :sources #{:user/domain}}
                                   :department-id {:label   "Department"
                                                   :sources #{:user/department}
                                                   ;; 1 holds 10 and 11
                                                   :expand  (fn [ids] (into (set ids) (mapcat #(get {1 [10 11]} %)) ids))}}
                   :scope-sources {:user/domain     {:label "Domains" :resolve #(:domains %)}
                                   :user/department {:label "Departments" :resolve #(:departments %)}}}}))

(def ^:private rights-source
  (reify ports/IRights (rights [_ user] (:rights user))))

(def ^:dynamic ^:private *user* nil)

(def ^:private ok (fn [_] {:status 200 :body {:ok true}}))

(def ^:private handler
  (router/compile-routes
   [["/api/anywhere" {:permission :orders/read :scope :handler :get {:handler ok}}]
    ["/api/d/:domain-id" {:permission :orders/read :scope {:domain-id :path/domain-id} :get {:handler ok}}]
    ["/api/p/:department-id" {:permission :orders/read :scope {:department-id :path/department-id}
                              :get        {:handler ok}}]
    ["/api/dp/:domain-id/:department-id" {:permission :orders/read
                                          :scope      {:domain-id :path/domain-id :department-id :path/department-id}
                                          :get        {:handler ok}}]]
   {:swagger-enabled false
    :system          {}
    :authentication  {:middleware [(fn [h] (fn [req] (h (assoc req :user *user*))))]}
    :authorization   [(sut/authorization-middleware registry rights-source {:mode :enforce})]}))

(defn- uri [{:keys [domain-id department-id]}]
  (cond
    (and domain-id department-id) (str "/api/dp/" domain-id "/" department-id)
    domain-id                     (str "/api/d/" domain-id)
    department-id                 (str "/api/p/" department-id)
    :else                         "/api/anywhere"))

(defn- status [user record]
  (binding [*user* user]
    (:status (handler {:request-method :get :uri (uri record) :scheme :http :server-name "localhost"
                       :server-port 80 :headers {"accept" "application/json"}}))))

(def ^:private value (gen/elements [1 2 10 11]))

(def ^:private gen-scope
  (gen/one-of [(gen/return nil)
               (gen/map (gen/elements [:domain-id :department-id])
                        (gen/one-of [value (gen/elements [:user/domain :user/department])])
                        {:max-elements 2})]))

(def ^:private gen-user
  (gen/let [rights      (gen/vector (gen/hash-map :permission (gen/elements [:orders/read :orders/write])
                                                  :scope gen-scope)
                                    0 4)
            domains     (gen/set value {:max-elements 2})
            departments (gen/set value {:max-elements 2})
            admin?      (gen/frequency [[1 (gen/return true)] [9 (gen/return false)]])]
    (cond-> {:id "u" :rights rights :domains domains :departments departments}
      admin? (assoc :role :admin))))

(def ^:private gen-record
  (gen/let [domain (gen/one-of [(gen/return nil) value])
            dept   (gen/one-of [(gen/return nil) value])]
    (cond-> {} domain (assoc :domain-id (str domain)) dept (assoc :department-id (str dept)))))

(defspec ^:property explain-decides-as-the-interceptor 300
  (prop/for-all [user gen-user record gen-record]
                (= (= 200 (status user record))
                   (:allowed? (sut/explain registry rights-source user :orders/read (not-empty record))))))

(deftest ^:unit explain-keeps-where-a-right-came-from
  (let [user {:id "u" :domains #{} :rights [{:permission :orders/read :scope {:domain-id :user/domain} :group-name "A"}
                                            {:permission :orders/read :scope {:domain-id 1} :group-name "B"}]}
        r    (sut/explain registry rights-source user :orders/read {:domain-id "2"})]
    (is (not (:allowed? r)))
    (is (= [["B" :scope-mismatch {:domain-id 1}] ["A" :unresolved {:domain-id :user/domain}]]
           (map (juxt (comp :group-name :right) :reason (comp :stored-scope :right)) (:misses r))))))
