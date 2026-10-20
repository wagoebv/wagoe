(ns wagoe.platform.core.scope-test
  (:require [clojure.test :refer [deftest is testing]]
            [honey.sql :as sql]
            [malli.core :as m]
            [wagoe.platform.core.scope :as sut]))

(def ^:private rights
  [{:permission :orders/read  :scope {:domain-id #{42}}}
   {:permission :orders/read  :scope {:domain-id #{43}}}
   {:permission :orders/write :scope {}}
   {:permission :orders/write :scope nil}])

(deftest ^:unit rights-add-up-per-permission
  (is (= [{:domain-id #{42}} {:domain-id #{43}}] (sut/scopes-for rights :orders/read)))
  (testing "no scope is everywhere, one way or the other"
    (is (= [{}] (sut/scopes-for rights :orders/write)))
    (is (sut/everywhere? (sut/scopes-for rights :orders/write))))
  (is (= [] (sut/scopes-for rights :orders/refund))))

(deftest ^:unit in-scope-compares-values-as-a-path-parameter-arrives
  (let [scopes [{:domain-id #{42}} {:domain-id #{7} :category-id #{1}}]]
    (is (sut/in-scope? scopes {:domain-id "42"}))
    (is (sut/in-scope? scopes {:domain-id 7 :category-id 1}))
    (testing "a scope with a key the values lack admits nothing"
      (is (not (sut/in-scope? scopes {:domain-id 7}))))
    (is (not (sut/in-scope? scopes {:domain-id 43})))
    (is (sut/in-scope? [{}] {}))))

(deftest ^:unit a-route-without-scope-admits-only-unscoped-holders
  (is (= {:scopes [{}]} (sut/route-decision [{}] nil {})))
  (is (= :forbidden (get-in (sut/route-decision [{:domain-id #{42}}] nil {}) [:error :type])))
  (testing "nor anyone who does not hold the permission"
    (is (= :forbidden (get-in (sut/route-decision [] :handler {}) [:error :type])))))

(deftest ^:unit a-route-scope-checks-its-path-parameter
  (let [route {:domain-id :path/domain-id}]
    (is (= {:scopes [{:domain-id #{42}}]} (sut/route-decision [{:domain-id #{42}}] route {:domain-id "42"})))
    (testing "outside the scope is not found, so the record does not leak"
      (is (= :not-found (get-in (sut/route-decision [{:domain-id #{42}}] route {:domain-id "43"}) [:error :type]))))
    (testing "a scope with a key the route does not check never passes"
      (is (= :forbidden (get-in (sut/route-decision [{:domain-id #{42} :category-id #{1}}] route {:domain-id "42"})
                                [:error :type]))))
    (testing "assignments add up: any one admitting is enough"
      (is (some? (:scopes (sut/route-decision [{:domain-id #{1} :category-id #{1}} {:domain-id #{42}}]
                                              route {:domain-id "42"})))))
    (is (some? (:scopes (sut/route-decision [{}] route {:domain-id "99"}))))))

(deftest ^:unit a-handler-scope-admits-any-holder
  (is (= {:scopes [{:domain-id #{42}}]} (sut/route-decision [{:domain-id #{42}}] :handler {}))))

(deftest ^:unit scope-filter-is-a-honeysql-where
  (testing "true when one scope holds everywhere"
    (is (= [:= 1 1] (sut/scope-filter [{:domain-id #{42}} {}] {:domain-id :domain_id}))))
  (is (= [:or [:and [:in :domain_id [42]]]] (sut/scope-filter [{:domain-id #{42}}] {:domain-id :domain_id})))
  (is (= [:or [:and [:in :domain_id [42 43]]] [:and [:in :category_id [1]] [:in :domain_id [7]]]]
         (sut/scope-filter [{:domain-id #{43 42}} {:domain-id #{7} :category-id #{1}}]
                           {:domain-id :domain_id :category-id :category_id})))
  (testing "safe by default: a key nobody maps, or no scope at all, admits nothing"
    (is (= [:= 1 0] (sut/scope-filter [{:category-id #{1}}] {:domain-id :domain_id})))
    (is (= [:= 1 0] (sut/scope-filter [] {:domain-id :domain_id}))))
  (testing "a scope through a relation is the condition the entity declares"
    (let [via-customer (fn [ids] [:in :customer_id {:select [:id] :from [:customers] :where [:in :domain_id ids]}])]
      (is (= ["SELECT * FROM orders WHERE ((customer_id IN (SELECT id FROM customers WHERE domain_id IN (?))))" 42]
             (sql/format {:select [:*] :from [:orders]
                          :where  (sut/scope-filter [{:domain-id #{42}}] {:domain-id via-customer})}))))))

(deftest ^:unit the-scope-schema-follows-the-declared-keys
  (let [schema (m/schema (sut/scope-schema {:domain-id     {:label "Domain" :entity :domains :schema :int}
                                            :department-id {:label "Department" :sources #{:user/department}}}))]
    (is (m/validate schema {:domain-id 42}))
    (is (m/validate schema {:department-id :user/department}))
    (is (m/validate schema {:department-id 7}))
    (is (not (m/validate schema {:department-id :user/boss})) "a source the key does not take")
    (is (not (m/validate schema {:domain-id :user/department})))
    (let [kw (m/schema (sut/scope-schema {:team {:schema :keyword :sources #{:user/team}}}))]
      (is (m/validate kw {:team :red}))
      (is (m/validate kw {:team :user/team}))
      (is (not (m/validate kw {:team :user/boss})) "a schema that admits keywords does not let any source in"))
    (is (not (m/validate schema {:domain-id "42"})))
    (is (not (m/validate schema {:area-id 1})))
    (is (= {:optional true :wagoe/entity :domains}
           (some (fn [[k props]] (when (= :domain-id k) props)) (m/children schema))))))

(deftest ^:unit sources-are-user-keywords
  (is (sut/source? :user/department))
  (is (not (sut/source? :department)))
  (is (not (sut/source? "user/department"))))
