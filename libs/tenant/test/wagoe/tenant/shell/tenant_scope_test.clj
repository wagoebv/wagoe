(ns wagoe.tenant.shell.tenant-scope-test
  "Only a tenant's member, its user or a platform admin runs a request in it (BOU-982)."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.tenant.shell.membership-middleware :as membership-mw]))

(def ^:private tenant-a #uuid "aaaaaaaa-0000-0000-0000-000000000000")
(def ^:private tenant-b #uuid "bbbbbbbb-0000-0000-0000-000000000000")

(def ^:private gate (membership-mw/wrap-tenant-scope (fn [_] {:status 200})))

(defn- status [user tenant & [membership]]
  (:status (gate (cond-> {:uri "/api/v1/products" :request-method :get}
                   user       (assoc :user user)
                   tenant     (assoc :tenant {:id tenant})
                   membership (assoc :tenant-membership membership)))))

(deftest ^:unit ^:security a-caller-outside-the-chosen-tenant-is-refused
  (testing "a user of tenant A naming tenant B by header or host"
    (is (= 403 (status {:id 1 :role :user :tenant-id tenant-a} tenant-b))))
  (testing "a tenant's admin is no admin of another"
    (is (= 403 (status {:id 1 :role :admin :tenant-id tenant-a} tenant-b))))
  (testing "a user of no tenant without a membership"
    (is (= 403 (status {:id 1 :role :user} tenant-b)))))

(deftest ^:unit ^:security a-user-of-a-tenant-gets-no-public-schema
  (is (= 403 (status {:id 1 :role :admin :tenant-id tenant-a} nil))))

(deftest ^:unit ^:security members-users-and-platform-admins-pass
  (is (= 200 (status {:id 1 :role :user :tenant-id tenant-b} tenant-b)))
  (is (= 200 (status {:id 1 :role :user} tenant-b {:role :member})))
  (is (= 200 (status {:id 1 :role :admin} tenant-b)))
  (is (= 200 (status {:id 1 :role :user} nil)))
  (testing "a caller who is not signed in is left to the route's authentication"
    (is (= 200 (status nil tenant-b)))))
