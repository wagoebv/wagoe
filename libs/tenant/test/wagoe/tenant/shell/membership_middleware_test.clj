(ns wagoe.tenant.shell.membership-middleware-test
  "While an administrator views the app as another user (BOU-868), the tenant
   a request runs in refuses a view of its admin, or one the administrator
   could not see themselves (BOU-1004)."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.tenant.ports :as ports]
            [wagoe.tenant.shell.membership-middleware :as sut]))

(def ^:private tenant #uuid "a0000000-0000-0000-0000-00000000000a")

(defn- memberships
  "An ITenantMembershipService answering from {[user-id tenant-id] membership}."
  [m]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify ports/ITenantMembershipService
    (get-active-membership [_ user-id tenant-id] (get m [user-id tenant-id]))))

(defn- run [ms request]
  ((sut/wrap-tenant-membership ms (fn [req] {:status 200 :membership (:tenant-membership req)}))
   (merge {:tenant {:id tenant}} request)))

(deftest ^:unit ^:security a-view-keeps-to-what-the-administrator-may-see
  (let [admin {:id 1 :role :admin :tenant-id tenant}
        ada   {:id 2 :role :user}
        view  {:viewing-as {:admin admin}}]
    (testing "a member viewed as by their tenant's administrator"
      (let [r (run (memberships {[2 tenant] {:role :member}}) (assoc view :user ada))]
        (is (= 200 (:status r)))
        (is (= :member (get-in r [:membership :role])))))
    (testing "an admin of this tenant by membership is not viewed as"
      (is (= 403 (:status (run (memberships {[2 tenant] {:role :admin}}) (assoc view :user ada))))))
    (testing "nor is a tenant the administrator may not use"
      (let [outsider {:id 3 :role :admin :tenant-id #uuid "b0000000-0000-0000-0000-00000000000b"}]
        (is (= 403 (:status (run (memberships {[2 tenant] {:role :member}})
                                 {:user ada :viewing-as {:admin outsider}}))))
        (is (= 200 (:status (run (memberships {[2 tenant] {:role :member} [3 tenant] {:role :admin}})
                                 {:user ada :viewing-as {:admin outsider}})))
            "unless they are a member of it")))
    (testing "a platform administrator may see every tenant"
      (is (= 200 (:status (run (memberships {[2 tenant] {:role :member}})
                               {:user ada :viewing-as {:admin {:id 4 :role :admin}}})))))
    (testing "not viewing: an admin by membership is just that"
      (is (= :admin (get-in (run (memberships {[2 tenant] {:role :admin}}) {:user ada}) [:membership :role]))))))
