(ns wagoe.shared.ui.core.shell-test
  (:require [wagoe.shared.ui.core.shell :as shell]
            [clojure.test :refer [deftest testing is]]))

(defn- nodes [tree]
  (filter vector? (tree-seq coll? seq tree)))

(defn- tagged [tree tag]
  (filter #(= tag (first %)) (nodes tree)))

(def ^:private nav
  [{:label "Entities"
    :items [{:href "/web/admin/users" :label "Users" :icon :users :active? true}
            {:href "/web/admin/orders" :label "Orders"}]}
   {:items [{:href "/web/audit" :label "Audit"}]}])

(deftest ^:unit the-sidebar-renders-its-nav-data
  (let [sidebar (shell/sidebar nav)]
    (is (= :aside.admin-sidebar (first sidebar)))
    (testing "a heading per group that has a label"
      (is (= [[:h3 "Entities"]] (tagged sidebar :h3))))
    (testing "a list per group, an item per entry, in order"
      (is (= 2 (count (tagged sidebar :ul.entity-list))))
      (is (= ["/web/admin/users" "/web/admin/orders" "/web/audit"]
             (->> (tagged sidebar :li) (map #(:href (second (nth % 2))))))))
    (testing "only the active item is marked"
      (is (= ["active" nil nil] (map #(:class (second %)) (tagged sidebar :li)))))))

(deftest ^:unit the-shell-wraps-content-with-sidebar-and-topbar
  (let [page (shell/admin-shell [:p "Body"] {:nav nav :page-title "Users"
                                             :user {:display-name "Ada Lovelace"}})]
    (is (seq (tagged page :aside.admin-sidebar)))
    (is (= [[:h1 {:class "admin-topbar-title"} "Users"]] (tagged page :h1)))
    (is (= "AL" (last (first (tagged page :summary)))))
    (is (some #{[:p "Body"]} (nodes page)))))
