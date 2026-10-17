(ns wagoe.search.shell.registry-test
  "Unit tests for the search index definition registry (shell state)."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [wagoe.search.ports :as ports]
            [wagoe.search.shell.registry :as registry]
            [wagoe.search.shell.service :as service]))

;; =============================================================================
;; Test fixture
;; =============================================================================

(defn- registry-fixture [f]
  (registry/clear-registry!)
  (f)
  (registry/clear-registry!))

(use-fixtures :each registry-fixture)

;; =============================================================================
;; Registry
;; =============================================================================

(def ^:private sample-def
  {:id          :product-search
   :entity-type :product
   :language    :english
   :fields      [{:name :title       :weight :a}
                 {:name :description :weight :b}]
   :options     {:highlight? true}})

(deftest ^:unit register-and-get-test
  (testing "registers and retrieves a definition by id"
    (registry/register-search! sample-def)
    (let [found (registry/get-search :product-search)]
      (is (= :product-search (:id found)))
      (is (= :product (:entity-type found)))))

  (testing "returns nil for unknown index"
    (is (nil? (registry/get-search :unknown-index))))

  (testing "list-searches returns registered ids"
    (registry/register-search! sample-def)
    (is (contains? (set (registry/list-searches)) :product-search)))

  (testing "clear-registry! removes all definitions"
    (registry/register-search! sample-def)
    (registry/clear-registry!)
    (is (empty? (registry/list-searches)))))

;; =============================================================================
;; defsearch macro
;; =============================================================================

(deftest ^:unit defsearch-macro-test
  (testing "defsearch binds a var and registers the definition"
    (registry/defsearch order-search
      {:id          :order-search
       :entity-type :order
       :language    :english
       :fields      [{:name :status :weight :a}]})
    (is (= :order-search (:id order-search)))
    (is (some? (registry/get-search :order-search)))))

;; =============================================================================
;; Deprecated fallback
;; =============================================================================

(deftest ^:unit the-service-still-reads-the-registry
  (registry/register-search! sample-def)
  (let [svc (service/create-search-service nil {:own (assoc sample-def :id :own)})]
    (testing "for an id its own definitions lack"
      (is (= :product (:entity-type (ports/index-definition svc :product-search))))
      (is (= #{:own :product-search} (set (map :id (ports/list-indices svc))))))
    (testing "and its own definitions win"
      (let [svc (service/create-search-service nil {:product-search (assoc sample-def :entity-type :item)})]
        (is (= :item (:entity-type (ports/index-definition svc :product-search))))))))
