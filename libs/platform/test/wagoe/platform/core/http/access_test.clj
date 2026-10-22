(ns wagoe.platform.core.http.access-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.platform.core.http.access :as access]))

(def ^:private tenant-a (random-uuid))
(def ^:private tenant-b (random-uuid))

(defn- in-a
  "A request resolved to tenant A, for `user`, with `membership` if any."
  ([user] (in-a user nil))
  ([user membership]
   (cond-> {:user user :tenant {:id tenant-a}}
     membership (assoc :tenant-membership membership))))

(deftest ^:unit forbidden-response-no-longer-sends-a-browser-to-sign-in
  (let [resp (access/forbidden-response "No" {:uri "/web/orders"} "cid")]
    (is (= 403 (:status resp)))
    (is (nil? (get-in resp [:headers "Location"])))))

(deftest ^:unit in-tenant-scope
  (testing "in a tenant: a member, a user of it, a platform admin"
    (is (access/in-tenant-scope? (in-a {:role :user :tenant-id tenant-b} {:role :member})))
    (is (access/in-tenant-scope? (in-a {:role :user :tenant-id (str tenant-a)})))
    (is (access/in-tenant-scope? (in-a {:role :admin}))))
  (testing "not another tenant's admin"
    (is (not (access/in-tenant-scope? (in-a {:role :admin :tenant-id tenant-b})))))
  (testing "without a tenant: only a user of no tenant"
    (is (access/in-tenant-scope? {:user {:role :user}}))
    (is (not (access/in-tenant-scope? {:user {:role :admin :tenant-id tenant-a}})))))

(deftest ^:unit tenant-admin
  (testing "another tenant's admin, a plain member here, is not one"
    (is (not (access/tenant-admin? (in-a {:role :admin :tenant-id tenant-b} {:role :member})))))
  (testing "an admin by membership, an admin user of the tenant, a platform admin"
    (is (access/tenant-admin? (in-a {:role :user :tenant-id tenant-b} {:role "admin"})))
    (is (access/tenant-admin? (in-a {:role :admin :tenant-id tenant-a})))
    (is (access/tenant-admin? (in-a {:role "admin"}))))
  (testing "without a tenant, the global role"
    (is (access/tenant-admin? {:user {:role :admin}}))
    (is (not (access/tenant-admin? {:user {:role :user}})))))
