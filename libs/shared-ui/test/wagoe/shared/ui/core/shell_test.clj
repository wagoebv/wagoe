(ns wagoe.shared.ui.core.shell-test
  (:require [wagoe.platform.core.http.nav :as nav]
            [wagoe.platform.core.http.pages :as pages]
            [wagoe.shared.ui.core.shell :as shell]
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

(def ^:private groups
  [{:id :user :label "Users" :icon :users
    :items [{:href "/web/admin/users" :label "Users" :access :admin}
            {:href "/web/audit" :label "Audit" :access :platform-admin}]}
   {:id :other :label "Other" :icon :folder
    :items [{:href "/web/admin/notes" :label "Notes" :access :admin}]}])

(deftest ^:unit a-module-group-is-a-collapsible-button-with-its-items
  (binding [nav/*nav* {:groups groups :uri "/web/admin/users/7"}]
    (let [sidebar      (shell/sidebar (shell/user-nav {:role :admin :tenant-id 1}))
          [user other] (tagged sidebar :div)
          toggle       #(first (tagged % :button.nav-module-toggle))]
      (testing "the current page's group starts open and marked; the others closed"
        (is (= "nav-module has-active" (:class (second user))))
        (is (= "navGroup('user', true)" (:x-data (second user))))
        (is (= "true" (:aria-expanded (second (toggle user)))))
        (is (= "false" (:aria-expanded (second (toggle other)))))
        (is (= "open" (:x-bind:aria-expanded (second (toggle other))))))
      (testing "the group is named on its button and atop its flyout, in full in the title"
        (is (some #{[:span.nav-text {:title "Users"} "Users"]} (nodes (toggle user))))
        (is (= [[:li.nav-flyout-title {:title "Other"} "Other"]] (tagged other :li.nav-flyout-title)))
        (is (= [{:title "Notes"} "Notes"] (rest (last (tagged other :span.nav-text)))) "and its items"))
      (testing "only what the user may open, the current page active"
        (is (= [["/web/admin/users" "active"] ["/web/admin/notes" nil]]
               (->> (tagged sidebar :li)
                    (map #(vector (:href (second (nth % 2))) (:class (second %)))))))))))

(deftest ^:unit the-shell-renders-the-request-navigation-unless-given-one
  (binding [nav/*nav* {:groups groups :uri "/"}]
    (is (seq (tagged (shell/admin-shell [:p] {:user {:role :admin}}) :button.nav-module-toggle)))
    (is (empty? (tagged (shell/admin-shell [:p] {:user {:role :admin} :nav nav})
                        :button.nav-module-toggle))))
  (testing "outside a request there is none"
    (is (nil? (shell/user-nav {:role :admin})))))

(deftest ^:unit the-user-menu-links-to-the-profile-when-mounted
  (let [profile-links #(->> (tagged (shell/admin-shell [:p] {:nav nav :user {:email "a@b.c"}}) :a)
                            (filter (comp #{"/web/profile"} :href second)))]
    (is (= 1 (count (profile-links))))
    (binding [pages/*mounted?* (complement #{"/web/profile"})]
      (is (empty? (profile-links))))))
