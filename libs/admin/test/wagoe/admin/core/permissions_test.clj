(ns wagoe.admin.core.permissions-test
  "Unit tests for admin permissions pure functions.

   Tests cover:
   - Role-based permission checks (has-role?, is-admin?, is-authenticated?)
   - Admin access control (can-access-admin?, explain-admin-access-denial)
   - Entity-level permissions (can-view-entity?, can-create-entity?, etc.)
   - Entity filtering (filter-visible-entities)
   - Permission metadata (get-entity-permissions, get-admin-permissions)
   Permission assertion wrappers (assert-can-*!) moved to the shell; their
   tests live in wagoe.admin.shell.permissions-test."
  (:require [wagoe.admin.core.permissions :as permissions]
            [clojure.test :refer [deftest is testing]]))

^{:kaocha.testable/meta {:unit true :admin true}}

;; =============================================================================
;; Test Fixtures
;; =============================================================================

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

(def manager-user
  "Sample manager user"
  {:id #uuid "00000000-0000-0000-0000-000000000003"
   :email "manager@example.com"
   :name "Manager User"
   :role :manager
   :active true})

(def user-without-role
  "User with no role assigned"
  {:id #uuid "00000000-0000-0000-0000-000000000004"
   :email "norole@example.com"
   :name "No Role User"})

;; =============================================================================
;; Role-Based Permission Checks
;; =============================================================================

(deftest ^:unit has-role?-test
  (testing "Check if user has specific role"
    (testing "Admin user"
      (is (permissions/has-role? admin-user :admin))
      (is (not (permissions/has-role? admin-user :user)))
      (is (not (permissions/has-role? admin-user :manager))))

    (testing "Regular user"
      (is (permissions/has-role? regular-user :user))
      (is (not (permissions/has-role? regular-user :admin)))
      (is (not (permissions/has-role? regular-user :manager))))

    (testing "Manager user"
      (is (permissions/has-role? manager-user :manager))
      (is (not (permissions/has-role? manager-user :admin)))
      (is (not (permissions/has-role? manager-user :user))))

    (testing "Nil user"
      (is (not (permissions/has-role? nil :admin)))
      (is (not (permissions/has-role? nil :user))))

    (testing "User without role"
      (is (not (permissions/has-role? user-without-role :admin)))
      (is (not (permissions/has-role? user-without-role :user))))))

(deftest ^:unit is-admin?-test
  (testing "Check if user is admin"
    (is (permissions/is-admin? admin-user))
    (is (not (permissions/is-admin? regular-user)))
    (is (not (permissions/is-admin? manager-user)))
    (is (not (permissions/is-admin? nil)))
    (is (not (permissions/is-admin? user-without-role)))))

(deftest ^:unit is-authenticated?-test
  (testing "Check if user is authenticated"
    (testing "Users with roles are authenticated"
      (is (permissions/is-authenticated? admin-user))
      (is (permissions/is-authenticated? regular-user))
      (is (permissions/is-authenticated? manager-user)))

    (testing "Nil user is not authenticated"
      (is (not (permissions/is-authenticated? nil))))

    (testing "User without role is not authenticated"
      (is (not (permissions/is-authenticated? user-without-role))))

    (testing "Empty map is not authenticated"
      (is (not (permissions/is-authenticated? {}))))))

;; =============================================================================
;; Admin Access Control
;; =============================================================================

(deftest ^:unit can-access-admin?-test
  (testing "Week 1: Admin access requires :admin role"
    (testing "Admin user can access"
      (is (permissions/can-access-admin? admin-user))
      (is (permissions/can-access-admin? admin-user nil))
      (is (permissions/can-access-admin? admin-user {})))

    (testing "Regular user cannot access"
      (is (not (permissions/can-access-admin? regular-user)))
      (is (not (permissions/can-access-admin? manager-user))))

    (testing "Nil user cannot access"
      (is (not (permissions/can-access-admin? nil))))

    (testing "User without role cannot access"
      (is (not (permissions/can-access-admin? user-without-role))))))

(deftest ^:unit explain-admin-access-denial-test
  (testing "Explanation for admin access denial"
    (testing "Nil user"
      (let [explanation (permissions/explain-admin-access-denial nil)]
        (is (true? (:denied explanation)))
        (is (= "User not authenticated" (:reason explanation)))
        (is (= :admin (:required-role explanation)))))

    (testing "User without role"
      (let [explanation (permissions/explain-admin-access-denial user-without-role)]
        (is (true? (:denied explanation)))
        (is (= "User has no assigned role" (:reason explanation)))
        (is (= :admin (:required-role explanation)))
        (is (= (:id user-without-role) (:user-id explanation)))))

    (testing "Regular user"
      (let [explanation (permissions/explain-admin-access-denial regular-user)]
        (is (true? (:denied explanation)))
        (is (= "User does not have admin role" (:reason explanation)))
        (is (= :user (:user-role explanation)))
        (is (= :admin (:required-role explanation)))))

    (testing "Admin user (no denial)"
      (let [explanation (permissions/explain-admin-access-denial admin-user)]
        (is (false? (:denied explanation)))
        (is (= "Access granted" (:reason explanation)))))))

;; =============================================================================
;; Entity-Level Permissions
;; =============================================================================

(deftest ^:unit can-view-entity?-test
  (testing "Week 1: Admin can view all entities"
    (testing "Admin user"
      (is (permissions/can-view-entity? admin-user :users))
      (is (permissions/can-view-entity? admin-user :orders))
      (is (permissions/can-view-entity? admin-user :products))
      (is (permissions/can-view-entity? admin-user :users {}))
      (is (permissions/can-view-entity? admin-user :users {:label "Users"})))

    (testing "Regular user cannot view"
      (is (not (permissions/can-view-entity? regular-user :users)))
      (is (not (permissions/can-view-entity? manager-user :users))))

    (testing "Nil user cannot view"
      (is (not (permissions/can-view-entity? nil :users))))))

(deftest ^:unit can-create-entity?-test
  (testing "Week 1: Admin can create in all entities"
    (is (permissions/can-create-entity? admin-user :users))
    (is (permissions/can-create-entity? admin-user :orders))
    (is (not (permissions/can-create-entity? regular-user :users)))
    (is (not (permissions/can-create-entity? nil :users)))))

(deftest ^:unit can-edit-entity?-test
  (testing "Week 1: Admin can edit all entities"
    (testing "Admin user"
      (is (permissions/can-edit-entity? admin-user :users))
      (is (permissions/can-edit-entity? admin-user :users {}))
      (is (permissions/can-edit-entity? admin-user :users {} {:id 123})))

    (testing "Regular user cannot edit"
      (is (not (permissions/can-edit-entity? regular-user :users)))
      (is (not (permissions/can-edit-entity? regular-user :users {} {}))))

    (testing "Nil user cannot edit"
      (is (not (permissions/can-edit-entity? nil :users))))))

(deftest ^:unit can-delete-entity?-test
  (testing "Week 1: Admin can delete from all entities"
    (testing "Admin user"
      (is (permissions/can-delete-entity? admin-user :users))
      (is (permissions/can-delete-entity? admin-user :users {}))
      (is (permissions/can-delete-entity? admin-user :users {} {:id 123})))

    (testing "Regular user cannot delete"
      (is (not (permissions/can-delete-entity? regular-user :users)))
      (is (not (permissions/can-delete-entity? regular-user :users {} {}))))

    (testing "Nil user cannot delete"
      (is (not (permissions/can-delete-entity? nil :users))))))

(deftest ^:unit can-bulk-delete-entity?-test
  (testing "Week 1: Bulk delete same as single delete"
    (is (permissions/can-bulk-delete-entity? admin-user :users))
    (is (permissions/can-bulk-delete-entity? admin-user :users {}))
    (is (not (permissions/can-bulk-delete-entity? regular-user :users)))
    (is (not (permissions/can-bulk-delete-entity? nil :users)))))

;; =============================================================================
;; Entity Filtering
;; =============================================================================

(deftest ^:unit filter-visible-entities-test
  (testing "Filter entities based on view permissions"
    (let [all-entities #{:users :orders :products}]

      (testing "Admin sees all entities"
        (let [visible (permissions/filter-visible-entities admin-user all-entities)]
          (is (= 3 (count visible)))
          (is (= (set visible) all-entities))))

      (testing "Regular user sees no entities"
        (let [visible (permissions/filter-visible-entities regular-user all-entities)]
          (is (= 0 (count visible)))
          (is (= [] visible))))

      (testing "Nil user sees no entities"
        (let [visible (permissions/filter-visible-entities nil all-entities)]
          (is (= 0 (count visible)))
          (is (= [] visible))))

      (testing "Works with vector input"
        (let [entity-vec [:users :orders :products]
              visible (permissions/filter-visible-entities admin-user entity-vec)]
          (is (= 3 (count visible)))))

      (testing "With entity configs"
        (let [configs {:users {:label "Users"}
                       :orders {:label "Orders"}}
              visible (permissions/filter-visible-entities admin-user all-entities configs)]
          (is (= 3 (count visible))))))))

(deftest ^:unit get-accessible-entities-count-test
  (testing "Count accessible entities"
    (let [entities #{:users :orders :products}]
      (is (= 3 (permissions/get-accessible-entities-count admin-user entities)))
      (is (= 0 (permissions/get-accessible-entities-count regular-user entities)))
      (is (= 0 (permissions/get-accessible-entities-count nil entities))))))

;; =============================================================================
;; Permission Metadata
;; =============================================================================

(deftest ^:unit get-entity-permissions-test
  (testing "Get all permission flags for entity"
    (testing "Admin user permissions"
      (let [perms (permissions/get-entity-permissions admin-user :users)]
        (is (true? (:can-view perms)))
        (is (true? (:can-create perms)))
        (is (true? (:can-edit perms)))
        (is (true? (:can-delete perms)))
        (is (true? (:can-bulk-delete perms)))
        (is (false? (:can-export perms)))   ; Week 2+
        (is (false? (:can-import perms))))) ; Week 2+

    (testing "Regular user permissions"
      (let [perms (permissions/get-entity-permissions regular-user :users)]
        (is (false? (:can-view perms)))
        (is (false? (:can-create perms)))
        (is (false? (:can-edit perms)))
        (is (false? (:can-delete perms)))
        (is (false? (:can-bulk-delete perms)))))

    (testing "Nil user permissions"
      (let [perms (permissions/get-entity-permissions nil :users)]
        (is (false? (:can-view perms)))
        (is (false? (:can-create perms)))))

    (testing "With entity config"
      (let [config {:label "Users" :soft-delete true}
            perms (permissions/get-entity-permissions admin-user :users config)]
        (is (true? (:can-view perms)))
        (is (true? (:can-delete perms)))))))

(deftest ^:unit get-admin-permissions-test
  (testing "Get global admin permissions"
    (testing "Admin user"
      (let [perms (permissions/get-admin-permissions admin-user)]
        (is (true? (:can-access-admin perms)))
        (is (false? (:can-view-audit-log perms)))  ; Week 2+
        (is (false? (:can-manage-users perms)))))  ; Week 2+

    (testing "Regular user"
      (let [perms (permissions/get-admin-permissions regular-user)]
        (is (false? (:can-access-admin perms)))))

    (testing "Nil user"
      (let [perms (permissions/get-admin-permissions nil)]
        (is (false? (:can-access-admin perms)))))

    (testing "With admin config"
      (let [config {:require-role :admin}
            perms (permissions/get-admin-permissions admin-user config)]
        (is (true? (:can-access-admin perms)))))))
