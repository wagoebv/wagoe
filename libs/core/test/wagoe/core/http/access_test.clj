(ns wagoe.core.http.access-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.core.http.access :as access]))

(def ^:private tenant-a (random-uuid))
(def ^:private tenant-b (random-uuid))

(defn- in-a
  "A request resolved to tenant A, for `user`, with `membership` if any."
  ([user] (in-a user nil))
  ([user membership]
   (cond-> {:user user :tenant {:id tenant-a}}
     membership (assoc :tenant-membership membership))))

(deftest ^:unit the-correlation-id-is-the-callers-or-fresh
  (is (= "theirs" (access/correlation-id {:headers {"x-correlation-id" "theirs"}} "fresh")))
  (is (= "fresh" (access/correlation-id {:headers {}} "fresh"))))

(deftest ^:unit a-refusal-is-the-error-body
  (is (= {:status  403
          :body    {:error {:type "forbidden" :message "No" :correlation-id "cid"}}
          :headers {"X-Correlation-ID" "cid"}}
         (access/forbidden-response "No" {:uri "/web/orders"} "cid")))
  (is (= 409 (:status (access/refusal-response 409 "conflict" "Taken" nil)))))

(deftest ^:unit admins
  (is (access/admin? {:user {:role "admin"}}))
  (is (not (access/admin? {:user {:role :user}})))
  (testing "a platform admin belongs to no tenant"
    (is (access/platform-admin? {:role :admin}))
    (is (not (access/platform-admin? {:role :admin :tenant-id tenant-a})))))

(deftest ^:unit same-id
  (is (access/same-id? tenant-a (str tenant-a)))
  (is (not (access/same-id? nil nil))))

(deftest ^:unit ^:security in-tenant-scope
  (testing "in a tenant: a member, a user of it, a platform admin"
    (is (access/in-tenant-scope? (in-a {:role :user :tenant-id tenant-b} {:role :member})))
    (is (access/in-tenant-scope? (in-a {:role :user :tenant-id (str tenant-a)})))
    (is (access/in-tenant-scope? (in-a {:role :admin}))))
  (testing "not another tenant's admin"
    (is (not (access/in-tenant-scope? (in-a {:role :admin :tenant-id tenant-b})))))
  (testing "without a tenant: only a user of no tenant"
    (is (access/in-tenant-scope? {:user {:role :user}}))
    (is (not (access/in-tenant-scope? {:user {:role :admin :tenant-id tenant-a}})))))

(deftest ^:unit ^:security tenant-admin
  (testing "another tenant's admin, a plain member here, is not one"
    (is (not (access/tenant-admin? (in-a {:role :admin :tenant-id tenant-b} {:role :member})))))
  (testing "an admin by membership, an admin user of the tenant, a platform admin"
    (is (access/tenant-admin? (in-a {:role :user :tenant-id tenant-b} {:role "admin"})))
    (is (access/tenant-admin? (in-a {:role :admin :tenant-id tenant-a})))
    (is (access/tenant-admin? (in-a {:role "admin"}))))
  (testing "without a tenant, the global role"
    (is (access/tenant-admin? {:user {:role :admin}}))
    (is (not (access/tenant-admin? {:user {:role :user}})))))

(deftest ^:unit roles-here-are-those-held-where-the-request-runs
  ;; BOU-1005
  (let [a #uuid "a0000000-0000-0000-0000-00000000000a"
        b #uuid "b0000000-0000-0000-0000-00000000000b"]
    (is (= [] (access/roles-here {})))
    (is (= [:admin] (access/roles-here {:user {:role :admin}})) "a platform admin")
    (is (= [:admin] (access/roles-here {:user {:role :admin :tenant-id a} :tenant {:id a}})) "their tenant's admin")
    (is (= [:user] (access/roles-here {:user {:role :user :tenant-id a} :tenant {:id a}})))
    (is (= [:member] (access/roles-here {:user {:role :admin :tenant-id b} :tenant {:id a}
                                      :tenant-membership {:role :member}}))
        "another tenant's admin is a member here")
    (is (= [:admin] (access/roles-here {:user {:role :user :tenant-id b} :tenant {:id a}
                                     :tenant-membership {:role :admin}}))
        "an admin here by membership")
    (is (= [] (access/roles-here {:user {:role :admin :tenant-id b} :tenant {:id a}}))
        "no membership here: no role")))
