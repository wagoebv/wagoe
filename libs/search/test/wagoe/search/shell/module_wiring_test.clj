(ns wagoe.search.shell.module-wiring-test
  "The search component picks its strategies from the adapter's capabilities
   (ADR-039), and holds the index definitions it is handed."
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [wagoe.platform.shell.adapters.database.h2.core :as h2]
            [wagoe.platform.shell.adapters.database.postgresql.core :as postgresql]
            [wagoe.search.ports :as ports]
            [wagoe.search.shell.module-wiring :as sut]))

(defn- store-for [adapter]
  (select-keys (:store (ig/init-key :wagoe/search {:db-ctx {:adapter adapter :datasource ::unused}}))
               [:full-text? :on-conflict?]))

(deftest ^:unit strategies-follow-the-adapters-capabilities
  (is (= {:full-text? true :on-conflict? true} (store-for (postgresql/new-adapter))))
  (is (= {:full-text? false :on-conflict? false} (store-for (h2/new-adapter)))))

;; =============================================================================
;; Definitions live on the component
;; =============================================================================

(def ^:private products {:entity-type :product :fields [{:name :title :weight :a}] :api? true})

(defn- engine [config]
  (:engine (ig/init-key :wagoe/search (merge {:db-ctx {:adapter (h2/new-adapter) :datasource ::unused}}
                                             config))))

(deftest ^:unit definitions-come-from-config-and-modules
  (let [e (engine {:indexes     {:products products}
                   :contributed {:orders {:id :orders :entity-type :order :fields []}}})]
    (is (= :products (:id (ports/index-definition e :products))) "the key is the id")
    (is (= :order (:entity-type (ports/index-definition e :orders))))
    (is (= #{:products :orders} (set (map :id (ports/list-indices e))))))
  (testing "an id defined twice, or a key that disagrees with :id, fails the boot"
    (doseq [config [{:indexes {:products products} :contributed {:products products}}
                    {:indexes {:products (assoc products :id :items)}}]]
      (is (= :configuration-error
             (:type (ex-data (try (engine config) nil
                                  (catch clojure.lang.ExceptionInfo e e)))))
          (pr-str config)))))

(deftest ^:unit two-systems-in-one-jvm-keep-their-own-indexes
  (let [a (engine {:indexes {:products products}})
        b (engine {:indexes {:orders (assoc products :entity-type :order)}})]
    (is (some? (ports/index-definition a :products)))
    (is (nil? (ports/index-definition a :orders)))
    (is (some? (ports/index-definition b :orders)))
    (is (nil? (ports/index-definition b :products)))))

(deftest ^:unit config-indexes-reach-the-component
  (is (= {:products products}
         (get-in (sut/ig-config {:indexes {:products products}} nil)
                 [:components :wagoe/search :indexes]))))
