(ns wagoe.user.shell.authorization-upgrade-test
  "An app on the old model, users of each legacy role and a group with
   grants, moved over: after the migration each user has under :enforce what
   they had under :off, or the findings name the difference."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [support.test-db :as test-db]
            [wagoe.platform.core.authorization :as authz-core]
            [wagoe.platform.shell.authorization :as authorization]
            [wagoe.user.core.authorization-upgrade :as core]
            [wagoe.user.core.group :as group]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.cli-entry.authorization-upgrade :as sut]
            [wagoe.user.shell.group-persistence :as group-persistence]
            [wagoe.user.shell.group-service :as group-service]
            [wagoe.user.shell.persistence :as persistence]))

(def ^:private registry
  (authorization/create-registry
   {:wagoe/orders {:permissions {:orders/refund {:label "Refund"}}}
    :wagoe/admin  {:permissions (authz-core/read-write-permissions "admin.users" "users")}}))

(def ^:private report
  "The orders module's routes, one a custom permission, and one mounted by hand."
  [{:path "/orders" :method :get :rule :module :permissions [:orders/read]}
   {:path "/orders" :method :post :rule :module :permissions [:orders/write]}
   {:path "/orders/:id/refund" :method :post :rule :permission :permissions [:orders/refund]}
   {:path "/legacy" :method :get :rule :none :permissions []}])

(defn- user! [users email role & [tenant-id]]
  (ports/create-user users (cond-> {:email email :name email :role role :active true :password-hash "x"}
                             tenant-id (assoc :tenant-id tenant-id))))

(defn- outcomes [findings]
  (set (map (juxt (comp :email :user) (comp #(or (:path %) (:admin %)) :endpoint) :permission :access) findings)))

(deftest ^:integration the-move-keeps-access-or-names-the-difference
  (test-db/with-db "user_authorization_upgrade"
    (fn [ctx]
      (persistence/initialize-user-schema! ctx)
      (let [users   (persistence/create-user-repository ctx)
            audit   (persistence/create-audit-repository ctx {:default-limit 50 :max-limit 100})
            svc     (group-service/create-group-service (group-persistence/create-group-repository ctx)
                                                        users audit (group-service/prepare-settings nil)
                                                        {:registry registry})
            _root   (user! users "root@x" :admin)
            _tenant (user! users "tenant@x" :admin (random-uuid))
            ada     (user! users "ada@x" :user)
            vic     (user! users "vic@x" :user)
            finance (ports/create-group svc {:name "Finance"})]
        (jdbc/execute! (:datasource ctx) ["UPDATE users SET role = 'viewer' WHERE id = ?" (str (:id vic))])
        (ports/add-group-member svc (:id finance) (:id ada))
        (jdbc/execute! (:datasource ctx)
                       ["INSERT INTO user_group_grants (id, group_id, permission, scope, created_at) VALUES (?, ?, ?, ?, ?)"
                        (str (random-uuid)) (str (:id finance)) "orders/refund" "{}"
                        (java.sql.Timestamp/from (java.time.Instant/now))])

        (testing "check reads the plan without moving anything"
          (let [plan (group-service/migration-plan svc)]
            (is (= [:orders/editor] (:everyone-roles plan)) "the refund permission is gated, the editor role is not")
            (is (= [(:id vic)] (:viewers plan)))
            (is (= [:orders/refund] (map :permission (:grants plan))))
            (is (= plan (group-service/migration-plan svc)) "reading twice changes nothing")
            (is (nil? (ports/get-group svc group/everyone-id)))))

        (group-service/migrate! svc)
        (let [moved    (vec (sut/users-with-rights registry svc users))
              findings (concat (core/route-findings report moved)
                               (core/admin-findings [:admin.users/read :admin.users/write] moved))]
          (is (= {:everyone-roles nil :viewers [] :readers-roles nil :grants []}
                 (group-service/migration-plan svc))
              "after the move the plan is empty")
          (is (= #{["ada@x" "/legacy" nil :refused]
                   ["vic@x" "/legacy" nil :refused]
                   ["tenant@x" "/legacy" nil :refused]
                   ["vic@x" "/orders/:id/refund" :orders/refund :refused]
                   ["tenant@x" "/orders/:id/refund" :orders/refund :refused]
                   ["tenant@x" :admin.users/read :admin.users/read :refused]
                   ["tenant@x" :admin.users/write :admin.users/write :refused]}
                 (outcomes findings))
              "Everyone keeps every module route open, ada the refund her group was granted; the
               tenant admin loses the admin, and the hand-mounted route is refused to all but root")
          (is (core/blocking? findings)))))))
