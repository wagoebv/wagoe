(ns wagoe.admin.shell.permissions-test
  "Tests for the permission assertion wrappers.

   The throwing guards live in the shell; the pure decision predicates they
   wrap are tested in wagoe.admin.core.permissions-test."
  (:require [wagoe.admin.shell.permissions :as shell-permissions]
            [clojure.test :refer [deftest is testing]]))

(def admin-user
  "Sample admin user"
  {:id #uuid "00000000-0000-0000-0000-000000000001"
   :email "admin@example.com"
   :name "Admin User"
   :role :admin
   :active true})

(def regular-user
  "Sample regular user"
  {:id #uuid "00000000-0000-0000-0000-000000000002"
   :email "user@example.com"
   :name "Regular User"
   :role :user
   :active true})

(deftest ^:unit assert-can-access-admin!-test
  (testing "Assert admin access permission"
    (testing "Admin user passes"
      (is (= true (shell-permissions/assert-can-access-admin! admin-user)))
      (is (= true (shell-permissions/assert-can-access-admin! admin-user {}))))

    (testing "Regular user throws"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"User does not have admin role"
           (shell-permissions/assert-can-access-admin! regular-user))))

    (testing "Nil user throws"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"User not authenticated"
           (shell-permissions/assert-can-access-admin! nil))))

    (testing "Exception has correct type"
      (try
        (shell-permissions/assert-can-access-admin! regular-user)
        (is false "Should have thrown exception")
        (catch clojure.lang.ExceptionInfo e
          (is (= :forbidden (:type (ex-data e)))))))))

(deftest ^:unit assert-can-view-entity!-test
  (testing "Assert entity view permission"
    (testing "Admin user passes"
      (is (= true (shell-permissions/assert-can-view-entity! admin-user :users))))

    (testing "Regular user throws"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"User cannot view entity: users"
           (shell-permissions/assert-can-view-entity! regular-user :users))))

    (testing "Exception contains entity info"
      (try
        (shell-permissions/assert-can-view-entity! regular-user :users)
        (is false "Should have thrown exception")
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)]
            (is (= :forbidden (:type data)))
            (is (= :users (:entity data)))
            (is (= :user (:user-role data)))
            (is (= :admin (:required-role data)))))))))

(deftest ^:unit assert-can-edit-entity!-test
  (testing "Assert entity edit permission"
    (testing "Admin user passes"
      (is (= true (shell-permissions/assert-can-edit-entity! admin-user :users)))
      (is (= true (shell-permissions/assert-can-edit-entity! admin-user :users {})))
      (is (= true (shell-permissions/assert-can-edit-entity! admin-user :users {} {:id 123}))))

    (testing "Regular user throws"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"User cannot edit entity: users"
           (shell-permissions/assert-can-edit-entity! regular-user :users))))

    (testing "Exception contains record info when provided"
      (try
        (shell-permissions/assert-can-edit-entity! regular-user :users {} {:id 123})
        (is false "Should have thrown exception")
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)]
            (is (= :forbidden (:type data)))
            (is (= 123 (:record-id data)))))))))

(deftest ^:unit assert-can-delete-entity!-test
  (testing "Assert entity delete permission"
    (testing "Admin user passes"
      (is (= true (shell-permissions/assert-can-delete-entity! admin-user :users)))
      (is (= true (shell-permissions/assert-can-delete-entity! admin-user :users {})))
      (is (= true (shell-permissions/assert-can-delete-entity! admin-user :users {} {:id 123}))))

    (testing "Regular user throws"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"User cannot delete entity: users"
           (shell-permissions/assert-can-delete-entity! regular-user :users))))

    (testing "Exception contains record info when provided"
      (try
        (shell-permissions/assert-can-delete-entity! regular-user :users {} {:id 123})
        (is false "Should have thrown exception")
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)]
            (is (= :forbidden (:type data)))
            (is (= 123 (:record-id data)))))))))
