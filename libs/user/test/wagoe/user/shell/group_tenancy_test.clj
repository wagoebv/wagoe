(ns wagoe.user.shell.group-tenancy-test
  "Groups, roles and Everyone per tenant (BOU-996): a tenant's admin manages
   their tenant's, a user's rights come from their own tenant's only, and the
   groups of before move into the tenants of their members without anyone
   losing access."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [support.test-db :as test-db]
            [wagoe.platform.database :as db]
            [wagoe.platform.shell.authorization :as authorization]
            [wagoe.user.core.group :as group]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.group-persistence :as group-persistence]
            [wagoe.user.shell.group-service :as sut]
            [wagoe.user.shell.persistence :as persistence]))

^{:kaocha.testable/meta {:integration true :user true}}

(def ^:private tenant-a #uuid "a0000000-0000-0000-0000-00000000000a")
(def ^:private tenant-b #uuid "b0000000-0000-0000-0000-00000000000b")

(def ^:private registry
  (authorization/create-registry {:wagoe/orders nil :wagoe/reports nil}))

(def ^:private ^:dynamic *system* nil)


(def ^:private ^:dynamic *provided*
  "Group names the membership hook gives every user, as an identity provider would."
  #{})

(use-fixtures :each
  (fn [f]
    (test-db/with-tenant-db "group_tenancy"
      (fn [ctx]
        (persistence/initialize-user-schema! ctx)
        (let [users (persistence/create-user-repository ctx)
              repo  (group-persistence/create-group-repository ctx)
              svc   (sut/create-group-service repo users
                                              (persistence/create-audit-repository ctx {:default-limit 50 :max-limit 100})
                                              (sut/prepare-settings nil)
                                              {:registry registry :membership (fn [_] *provided*)})]
          ;; No tenant's Everyone, as the boot migration makes it
          (ports/ensure-everyone repo)
          (binding [*system* {:svc svc :users users :repo repo}]
            (f)))))))

(defn- user! [email tenant-id]
  (ports/create-user (:users *system*) {:email email :name "Member" :role :user :active true
                                        :password-hash "x" :tenant-id tenant-id}))

(defmacro ^:private in [tenant-id & body]
  `(sut/with-tenant ~tenant-id (fn [] ~@body)))

(defn- refusal [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

(defn- permissions-of [user]
  (set (map :permission (ports/user-grants (:svc *system*) user))))

(deftest ^:integration ^:security a-tenants-admin-sees-and-changes-only-their-tenants-groups
  (let [svc (:svc *system*)
        a   (in tenant-a (ports/create-group svc {:name "Finance"}))
        b   (in tenant-b (ports/create-group svc {:name "Finance"}))]
    (testing "the same name in two tenants"
      (is (not= (:id a) (:id b))))
    (testing "one tenant lists its own groups and its own Everyone"
      (is (= #{(:id a) (group/everyone-id-of tenant-a)}
             (set (map :id (in tenant-a (sut/ensure-everyone! svc) (ports/list-groups svc)))))))
    (testing "another tenant's group is not found, so not changed"
      (is (nil? (in tenant-a (ports/get-group svc (:id b)))))
      (is (= :not-found (refusal #(in tenant-a (ports/update-group svc {:id (:id b) :name "Mine"})))))
      (is (not (in tenant-a (ports/delete-group svc (:id b)))) "deletes nothing")
      (is (= "Finance" (:name (in tenant-b (ports/get-group svc (:id b)))))))
    (testing "within a tenant a name is still unique"
      (is (= :conflict (refusal #(in tenant-a (ports/create-group svc {:name "Finance"}))))))))

(deftest ^:integration ^:security a-group-takes-only-its-tenants-users
  (let [svc (:svc *system*)
        bob (user! "bob@b.test" tenant-b)
        g   (in tenant-a (ports/create-group svc {:name "Finance"}))]
    (is (= :not-found (refusal #(in tenant-a (ports/add-group-member svc (:id g) (:id bob))))))
    (is (empty? (:members (in tenant-a (ports/get-group svc (:id g))))))))

(deftest ^:integration ^:security a-tenants-admin-ends-only-their-tenants-memberships
  ;; BOU-1001: the remove wizard's "end on a day"
  (let [svc  (:svc *system*)
        bob  (user! "bob@b.test" tenant-b)
        b    (in tenant-b (ports/create-group svc {:name "Finance"}))
        day  (.plusSeconds (java.time.Instant/now) 86400)
        ends #(:expires-at (some (fn [g] (when (= (:id b) (:id g)) g)) (in tenant-b (ports/user-groups svc bob))))]
    (in tenant-b (ports/add-group-member svc (:id b) (:id bob)))
    (is (= :not-found (refusal #(in tenant-a (ports/end-membership-at svc (:id b) (:id bob) day)))))
    (is (nil? (ends)) "tenant B's membership does not end")
    (in tenant-b (ports/end-membership-at svc (:id b) (:id bob) day))
    (is (some? (ends)) "in its own tenant it does")))

(deftest ^:integration ^:security a-users-rights-come-from-their-own-tenant
  ;; A role of one name in two tenants is two roles
  (let [svc (:svc *system*)
        ada (user! "ada@a.test" tenant-a)
        bob (user! "bob@b.test" tenant-b)]
    (in tenant-a
        (ports/create-role svc {:name :sales/lead :permissions #{:orders/read}})
        (let [g (ports/create-group svc {:name "Sales"})]
          (ports/add-group-member svc (:id g) (:id ada))
          (ports/add-group-role svc (:id g) {:role :sales/lead})))
    (in tenant-b
        (ports/create-role svc {:name :sales/lead :permissions #{:reports/read}})
        (let [g (ports/create-group svc {:name "Sales"})]
          (ports/add-group-member svc (:id g) (:id bob))
          (ports/add-group-role svc (:id g) {:role :sales/lead})))
    (is (= #{:orders/read} (permissions-of ada)))
    (is (= #{:reports/read} (permissions-of bob)))
    (testing "a tenant's role list holds its own managed roles"
      (is (= #{:orders/read} (:permissions (some #(when (= :sales/lead (:name %)) %) (in tenant-a (ports/list-roles svc)))))))))

(deftest ^:integration ^:security everyone-is-per-tenant
  (let [svc (:svc *system*)
        ada (user! "ada@a.test" tenant-a)
        bob (user! "bob@b.test" tenant-b)]
    (in nil (ports/add-group-role svc group/everyone-id {:role :orders/viewer}))
    (testing "until a tenant has its own, no tenant's Everyone counts for it"
      (is (contains? (permissions-of ada) :orders/read))
      (is (contains? (permissions-of bob) :orders/read)))
    (testing "its own starts from that, and then only it counts"
      (in tenant-a (sut/ensure-everyone! svc))
      (let [everyone-a (group/everyone-id-of tenant-a)]
        (is (= [:orders/viewer] (map :role (:roles (in tenant-a (ports/get-group svc everyone-a))))))
        (in tenant-a (ports/add-group-role svc everyone-a {:role :reports/viewer})))
      (is (contains? (permissions-of ada) :reports/read))
      (is (not (contains? (permissions-of bob) :reports/read)) "B keeps no tenant's"))
    (is (group/everyone? (group/everyone-id-of tenant-a)))
    (is (not= (group/everyone-id-of tenant-a) (group/everyone-id-of tenant-b)))))

(deftest ^:integration ^:security the-groups-of-before-move-into-their-members-tenants
  ;; Groups were no tenant's: one held members of two tenants
  (let [{:keys [svc repo]} *system*
        ada (user! "ada@a.test" tenant-a)
        bob (user! "bob@b.test" tenant-b)
        g   (in nil (ports/create-group svc {:name "Finance"}))]
    (in nil
        (ports/create-role svc {:name :finance/clerk :permissions #{:orders/read}})
        (ports/add-group-role svc (:id g) {:role :finance/clerk})
        (ports/insert-member repo (:id g) (:id ada))
        (ports/insert-member repo (:id g) (:id bob)))
    (sut/migrate! svc)
    (testing "nobody loses access"
      (is (contains? (permissions-of ada) :orders/read))
      (is (contains? (permissions-of bob) :orders/read)))
    (testing "each tenant has its own Finance, with its own members"
      (let [finance #(some (fn [x] (when (= "Finance" (:name x)) x)) (in % (ports/list-groups svc)))]
        (is (= ["ada@a.test"] (map :email (:members (in tenant-a (ports/get-group svc (:id (finance tenant-a))))))))
        (is (= ["bob@b.test"] (map :email (:members (in tenant-b (ports/get-group svc (:id (finance tenant-b))))))))
        (is (not= (:id (finance tenant-a)) (:id (finance tenant-b))))))
    (testing "and running it again changes nothing"
      (sut/migrate! svc)
      (is (= 1 (count (filter #(= "Finance" (:name %)) (in tenant-a (ports/list-groups svc)))))))))

(deftest ^:integration ^:security a-member-of-another-tenant-has-that-tenants-rights-there
  ;; A request in tenant B gave B's member their own tenant admin's grants (BOU-996)
  (let [svc (:svc *system*)
        ada (user! "ada@a.test" tenant-a)]
    (in tenant-a
        (let [g (ports/create-group svc {:name "Sales"})]
          (ports/add-group-member svc (:id g) (:id ada))
          (ports/add-group-role svc (:id g) {:role :orders/editor})))
    (is (contains? (permissions-of ada) :orders/write) "at home")
    (is (not (contains? (binding [db/*tenant-id* tenant-b] (permissions-of ada)) :orders/write))
        "in B, none of A's groups count")))

(deftest ^:integration ^:security removing-a-member-of-another-tenants-group-changes-nothing
  (let [svc (:svc *system*)
        bob (user! "bob@b.test" tenant-b)
        g   (in tenant-b (let [g (ports/create-group svc {:name "Finance"})] (ports/add-group-member svc (:id g) (:id bob)) g))]
    (is (not (in tenant-a (ports/remove-group-member svc (:id g) (:id bob)))))
    (is (= ["bob@b.test"] (map :email (:members (in tenant-b (ports/get-group svc (:id g)))))))))

(deftest ^:integration ^:security a-tenants-everyone-changes-stay-off-other-tenants-timelines
  (let [svc (:svc *system*)
        _   (user! "ada@a.test" tenant-a)
        bob (user! "bob@b.test" tenant-b)]
    (in tenant-a
        (sut/ensure-everyone! svc)
        (ports/add-group-role svc (group/everyone-id-of tenant-a) {:role :reports/viewer}))
    (is (empty? (filter #(= (str (group/everyone-id-of tenant-a)) (str (:group-id %)))
                        (in tenant-b (ports/rights-changes svc bob 50)))))))

(deftest ^:integration outside-a-tenant-the-service-works-in-no-tenants-groups
  ;; Unbound, it mixed every tenant's roles of a name, and an import found several groups
  (let [svc (:svc *system*)
        ada (user! "ada@a.test" tenant-a)]
    (in tenant-a
        (ports/create-role svc {:name :sales/lead :permissions #{:orders/read}})
        (let [g (ports/create-group svc {:name "Sales"})]
          (ports/add-group-member svc (:id g) (:id ada))
          (ports/add-group-role svc (:id g) {:role :sales/lead})))
    (in tenant-b (ports/create-group svc {:name "Sales"}))
    (is (contains? (set (map :user-id (in tenant-a (ports/permission-holders svc :orders/read)))) (:id ada)))
    (is (empty? (ports/permission-holders svc :orders/read)) "no tenant's: nobody holds it")
    (is (= ["Sales"] (map (comp :name :group) (ports/import-groups! svc [{:name "Sales"}] {})))
        "an import outside a tenant upserts no tenant's group")))

(deftest ^:integration ^:security an-import-adds-only-its-tenants-users
  (let [svc (:svc *system*)
        bob (user! "bob@b.test" tenant-b)]
    (is (= :validation-error (refusal #(in tenant-a (ports/import-groups! svc [{:name "Sales" :members [(:id bob)]}] {})))))))

(deftest ^:integration a-providers-group-of-no-tenant-counts-until-the-tenant-has-its-own
  ;; The migration copies groups with stored members; a provider fills its groups by name
  (let [svc (:svc *system*)
        ada (user! "ada@a.test" tenant-a)]
    (in nil (let [g (ports/create-group svc {:name "okta-sales"})] (ports/add-group-role svc (:id g) {:role :orders/editor})))
    (binding [*provided* #{"okta-sales"}]
      (is (contains? (permissions-of ada) :orders/write))
      (in tenant-a (ports/create-group svc {:name "okta-sales"}))
      (is (not (contains? (permissions-of ada) :orders/write)) "the tenant's own, with no role, counts instead"))))

(deftest ^:integration the-move-keeps-a-membership-already-in-the-copy
  (let [{:keys [svc repo]} *system*
        ada (user! "ada@a.test" tenant-a)
        g   (in nil (ports/create-group svc {:name "Finance"}))]
    (in nil (ports/insert-member repo (:id g) (:id ada)))
    (sut/migrate! svc)
    ;; Added to the old group again, by code outside a request
    (in nil (ports/insert-member repo (:id g) (:id ada)))
    (sut/migrate! svc)
    (let [finance (some #(when (= "Finance" (:name %)) %) (in tenant-a (ports/list-groups svc)))]
      (is (= ["ada@a.test"] (map :email (:members (in tenant-a (ports/get-group svc (:id finance))))))))
    (is (empty? (:members (in nil (ports/get-group svc (:id g))))) "the old group keeps nobody of a tenant")))
