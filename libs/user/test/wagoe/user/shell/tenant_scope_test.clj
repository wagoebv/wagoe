(ns wagoe.user.shell.tenant-scope-test
  "A user listing holds the caller's tenant and nothing else (BOU-948)."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.platform.shell.adapters.database.factory :as factory]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.persistence :as persistence]
            [wagoe.user.shell.tenant-scope :as sut])
  (:import [java.util UUID]))

(def ^:private tenant-a #uuid "a0000000-0000-0000-0000-00000000000a")
(def ^:private tenant-b #uuid "b0000000-0000-0000-0000-00000000000b")

(deftest ^:integration ^:security a-listing-holds-only-the-callers-tenant
  (let [ctx (factory/db-context
             (factory/h2-config (str "mem:user_scope_" (System/nanoTime) ";DB_CLOSE_DELAY=-1")))]
    (try
      (persistence/initialize-user-schema! ctx)
      (let [repo   (persistence/create-user-repository ctx)
            add!   (fn [email tenant-id]
                     (ports/create-user repo (cond-> {:email email :name email :role :user
                                                      :active true :password-hash "x"}
                                               tenant-id (assoc :tenant-id tenant-id))))
            _      (add! "a@example.com" tenant-a)
            _      (add! "b@example.com" tenant-b)
            _      (add! "none@example.com" nil)
            listed (fn [request]
                     (some->> (sut/list-options request {})
                              (ports/find-users repo)
                              :users
                              (map :email)
                              set))
            user   (fn [m] (merge {:id (UUID/randomUUID) :role :user} m))]
        (testing "a member of the resolved tenant lists that tenant"
          (is (= #{"a@example.com"}
                 (listed {:user (user {}) :tenant {:id tenant-a}
                          :tenant-membership {:tenant-id tenant-a :role :member}}))))
        (testing "a tenant's own admin lists their tenant, wherever they ask"
          (is (= #{"a@example.com"} (listed {:user (user {:role :admin :tenant-id tenant-a})})))
          (is (= #{"a@example.com"} (listed {:user (user {:role :admin :tenant-id tenant-a})
                                             :tenant {:id tenant-a}}))))
        (testing "a caller outside the resolved tenant lists no one"
          (is (nil? (listed {:user (user {:role :admin :tenant-id tenant-a}) :tenant {:id tenant-b}})))
          (is (nil? (listed {:user (user {}) :tenant {:id tenant-b}}))))
        (testing "a user of no tenant lists the users of no tenant"
          (is (= #{"none@example.com"} (listed {:user (user {})}))))
        (testing "a platform admin lists everyone, or the resolved tenant"
          (is (= #{"a@example.com" "b@example.com" "none@example.com"}
                 (listed {:user (user {:role :admin})})))
          (is (= #{"b@example.com"}
                 (listed {:user (user {:role :admin}) :tenant {:id tenant-b}})))))
      (finally (factory/close-db-context! ctx)))))

(deftest ^:unit ^:security lists?-agrees-with-the-listing
  (let [of-a  {:id (UUID/randomUUID) :tenant-id tenant-a}
        none  {:id (UUID/randomUUID)}]
    (testing "a member of the resolved tenant may name its users only"
      (let [request {:user {:id (UUID/randomUUID) :role :user} :tenant {:id tenant-a}
                     :tenant-membership {:tenant-id tenant-a :role :admin}}]
        (is (sut/lists? request of-a))
        (is (not (sut/lists? request none)))))
    (testing "a caller outside the resolved tenant names no one"
      (is (not (sut/lists? {:user {:id (UUID/randomUUID) :role :admin :tenant-id tenant-a}
                            :tenant {:id tenant-b}}
                           (assoc of-a :tenant-id tenant-b)))))
    (testing "a platform admin names anyone"
      (is (sut/lists? {:user {:id (UUID/randomUUID) :role :admin}} of-a)))))
