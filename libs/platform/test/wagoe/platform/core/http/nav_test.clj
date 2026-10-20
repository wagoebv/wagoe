(ns wagoe.platform.core.http.nav-test
  (:require [wagoe.platform.core.http.nav :as nav]
            [clojure.test :refer [deftest testing is]]))

(defn- entity [e & {:as more}]
  (merge {:entity e :href (str "/web/admin/" (name e)) :label (name e) :access :admin} more))

(def ^:private admin
  {:admin {:label "Admin" :items [(entity :users) (entity :tenants) (entity :invoices)
                                  (entity :notes :module :billing) (entity :tags :module :nobody)]}})

(def ^:private user
  {:user {:label "Users" :icon :users :entities [:users]
          :items [{:href "/web/admin/groups" :label "Groups" :access :admin}
                  {:href "/web/audit" :label "Audit" :access :platform-admin}]}})

(def ^:private tenant {:tenant {:label "Tenants" :entities [:tenants]}})

(def ^:private billing {:billing {:label "Billing"}})

(def ^:private all (constantly true))

(defn- shape [groups]
  (mapv (fn [g] [(:id g) (mapv :href (:items g))]) groups))

(deftest ^:unit entities-go-to-the-module-that-claims-them
  (is (= [[:user ["/web/admin/users" "/web/admin/groups" "/web/audit"]]
          [:tenant ["/web/admin/tenants"]]
          [:billing ["/web/admin/notes"]]
          [:other ["/web/admin/invoices" "/web/admin/tags"]]]
         (shape (nav/collect [admin user tenant billing] nil all))))
  (testing "an entity config's :module overrides the claim; an unknown one is ignored"
    (is (= ["/web/admin/notes"] (->> (nav/collect [admin billing] nil all) first :items (mapv :href))))))

(deftest ^:unit groups-follow-the-configured-order-then-module-order
  (is (= [:tenant :user :billing :other]
         (map :id (nav/collect [admin user tenant billing] [:tenant :unknown] all))))
  (testing ":other may be placed too"
    (is (= [:other :user :tenant :billing]
           (map :id (nav/collect [admin user tenant billing] [:other] all))))))

(deftest ^:unit contributions-under-one-id-add-up
  (let [[group] (nav/collect [user {:user {:label "Ignored" :items [{:href "/web/x"}]}}] nil all)]
    (is (= "Users" (:label group)))
    (is (= ["/web/admin/groups" "/web/audit" "/web/x"] (mapv :href (:items group))))))

(deftest ^:unit a-contribution-may-be-a-function-asked-each-time
  (is (= (nav/collect [admin user] nil all) (nav/collect [(constantly admin) user] nil all))))

(deftest ^:unit empty-groups-are-dropped
  (is (= [:user] (map :id (nav/collect [{:admin {:label "Admin" :items [(entity :users)]}} user] nil all)))
      "the admin's own group, and no :other without leftovers")
  (is (= [] (nav/collect [tenant] nil all))))

(def ^:private groups (nav/collect [admin user tenant] nil all))

(deftest ^:unit items-are-filtered-by-access-and-mounting
  (testing "a platform admin sees everything that is mounted"
    (is (= [[:user ["/web/admin/users" "/web/admin/groups" "/web/audit"]]
            [:tenant ["/web/admin/tenants"]]
            [:other ["/web/admin/invoices" "/web/admin/notes" "/web/admin/tags"]]]
           (shape (nav/for-user groups "/web/admin/" {:role :admin})))))
  (testing "a tenant admin does not see the platform's audit trail"
    (is (= ["/web/admin/users" "/web/admin/groups"]
           (->> (nav/for-user groups "/" {:role "admin" :tenant-id 1}) first :items (mapv :href)))))
  (testing "a user sees no admin pages, so no groups"
    (is (= [] (nav/for-user groups "/" {:role :user}))))
  (testing "an entity is linked though /web/admin/:entity, not its path, is the route"
    (is (= [:user :tenant :other]
           (map :id (nav/collect [admin user tenant] nil #{"/web/admin/groups" "/web/audit"})))))
  (testing "an unmounted page is not linked"
    (is (= ["/web/admin/users" "/web/audit"]
           (->> (nav/collect [admin user tenant] nil #(not= % "/web/admin/groups")) first :items (mapv :href))))))

(deftest ^:unit the-current-page-is-active-and-its-group-open
  (let [result (nav/for-user groups "/web/admin/tenants/42" {:role :admin})]
    (is (= [[:user false] [:tenant true] [:other false]] (mapv (juxt :id :open?) result)))
    (is (= ["/web/admin/tenants"] (->> result (mapcat :items) (filter :active?) (map :href)))))
  (testing "the longest matching href wins, and a shared prefix is not a match"
    (let [result (nav/for-user [{:id :a :items [{:href "/web/a"} {:href "/web/a/b"} {:href "/web/ab"}]}]
                               "/web/a/b/c" {:role :user})]
      (is (= ["/web/a/b"] (->> result (mapcat :items) (filter :active?) (map :href)))))))

(deftest ^:unit access-is-decided-in-one-place
  (is (nav/allowed? nil {:role :user}))
  (is (not (nav/allowed? nil nil)))
  (is (nav/allowed? :admin {:role "admin" :tenant-id 1}))
  (is (not (nav/allowed? :platform-admin {:role :admin :tenant-id 1})))
  (is (not (nav/allowed? :unknown {:role :admin})))
  (testing "a fn of the signed-in user"
    (is (nav/allowed? #(= 1 (:id %)) {:id 1}))
    (is (not (nav/allowed? #(= 1 (:id %)) {:id 2})))
    (is (not (nav/allowed? (constantly true) nil)))))
