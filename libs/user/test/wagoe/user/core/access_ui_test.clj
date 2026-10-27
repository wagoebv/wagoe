(ns wagoe.user.core.access-ui-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.user.core.access-ui :as sut]
            [wagoe.user.core.group :as group]
            [wagoe.user.core.rights-ui :as rights-ui]))

(def ^:private registry
  {:orders/read  {:label "Read orders"}
   :orders/write {:label "Change orders"}
   :crm/read     {:label "Read customers"}})

(def ^:private roles
  (rights-ui/role-index
   [{:name :orders/editor :permissions #{:orders/read :orders/write} :source :code}
    {:name :desk/clerk :permissions #{:orders/read :crm/read} :source :managed}]))

(def ^:private groups
  [{:id group/everyone-id :name "Everyone"} {:id 2 :name "Sales"} {:id 1 :name "Desk"}])

(def ^:private holders
  [{:group-id 2 :group-name "Sales" :role :orders/editor :scope {:domain-id 4}}
   {:group-id 1 :group-name "Desk" :role :desk/clerk :scope {}}
   {:group-id group/everyone-id :group-name "Everyone" :role :orders/editor :scope {} :everyone? true}])

(deftest ^:unit groups-by-modules-each-cell-the-assignments-giving-permissions-there
  (let [ov (sut/overview groups holders roles registry {})]
    (is (= ["crm" "orders"] (:modules ov)))
    (is (= ["Desk" "Sales"] (map (comp :name :group) (:rows ov))) "Everyone is not a row")
    (is (= [:everyone?] (keep #(when (:everyone? %) :everyone?) (:everyone ov))))
    (testing "a role spanning modules shows in each, with its permissions there"
      (let [desk (:cells (first (:rows ov)))]
        (is (= [[:crm/read]] (map :permissions (get desk "crm"))))
        (is (= [[:orders/read]] (map :permissions (get desk "orders"))))))
    (is (= {} (select-keys (:cells (second (:rows ov))) ["crm"])) "Sales holds nothing in crm"))
  (testing "filters narrow the columns and rows, the filter options stay"
    (let [ov (sut/overview groups holders roles registry {:module "orders" :group "2"})]
      (is (= ["orders"] (:modules ov)))
      (is (= ["crm" "orders"] (:all-modules ov)))
      (is (= ["Sales"] (map (comp :name :group) (:rows ov)))))))

(deftest ^:unit a-cell-shows-the-level-and-whether-it-is-scoped
  (let [ov    (sut/overview groups holders roles registry {})
        nodes (tree-seq coll? seq (sut/matrix ov roles registry {}))
        keys  (set (keep #(when (and (vector? %) (= :t (first %))) (second %)) nodes))]
    (is (contains? keys :user.access/level-editor))
    (is (contains? keys :user.access/scoped))
    (is (some #{"desk/clerk"} nodes) "a managed role by its name")
    (is (some #{"Read customers"} nodes) "permissions by their registry label")
    (is (some #(and (map? %) (= (rights-ui/permission-path :crm/read) (:href %))) nodes)
        "a permission opens who holds it")))
