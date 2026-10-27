(ns wagoe.user.shell.group-repository-contract-test
  "Groups, members, roles and assignments on the engine WAGOE_TEST_DB names:
   the round trip, assignments through several groups and Everyone, and what
   deleting a user or a group leaves behind."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [support.test-db :as test-db]
            [wagoe.platform.database :as db]
            [wagoe.user.core.group :as group]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.group-persistence :as sut]
            [wagoe.user.shell.persistence :as persistence]))

(defn- user! [users email]
  (ports/create-user users {:email email :name "Member" :role :user :active true :password-hash "x"}))

(defn- count-rows [ctx table group-or-user-col id]
  (-> (jdbc/execute-one! (:datasource ctx)
                         [(str "SELECT COUNT(*) AS n FROM " table " WHERE " group-or-user-col " = ?") (str id)])
      vals first long))

(deftest ^:contract groups-round-trip
  (test-db/with-db "user_groups"
    (fn [ctx]
      (persistence/initialize-user-schema! ctx)
      ;; Twice: boot and the migration both run it.
      (persistence/initialize-user-schema! ctx)
      (let [users   (persistence/create-user-repository ctx)
            repo    (sut/create-group-repository ctx)
            ada     (user! users "ada@example.com")
            bob     (user! users "bob@example.com")
            finance (ports/insert-group repo {:name "Finance" :description "Pays"})
            audit   (ports/insert-group repo {:name "Audit"})
            dg      (random-uuid)]
        (testing "a group reads back"
          (is (= {:name "Finance" :description "Pays"}
                 (select-keys (ports/find-group repo (:id finance)) [:name :description])))
          (is (= "Renamed" (:name (ports/modify-group repo {:id (:id audit) :name "Renamed" :description nil}))))
          (ports/modify-group repo {:id (:id audit) :name "Audit" :description nil})
          (is (= "Pays" (:description (ports/modify-group repo {:id (:id finance) :name "Finance"})))
              "a description not given is kept"))

        (testing "members"
          (is (true? (ports/insert-member repo (:id finance) (:id ada))))
          (is (false? (ports/insert-member repo (:id finance) (:id ada))) "a member already")
          (is (true? (ports/insert-member repo (:id audit) (:id ada))))
          (is (true? (ports/insert-member repo (:id audit) (:id bob))))
          (is (= ["ada@example.com" "bob@example.com"] (map :email (ports/find-members repo (:id audit)))))
          (is (thrown? Exception (ports/insert-member repo (:id finance) (random-uuid)))
              "a foreign key refuses a user who does not exist"))

        (testing "a managed role keeps its permissions"
          (ports/insert-role repo {:name :invoice/approver :permissions #{:invoice/approve :invoice/read}})
          (is (= [{:name :invoice/approver :permissions #{:invoice/approve :invoice/read} :description nil}]
                 (map #(select-keys % [:name :permissions :description]) (ports/find-roles repo))))
          (is (= {:type :conflict :field :name}
                 (try (ports/insert-role repo {:name :invoice/approver :permissions #{:a}}) nil
                      (catch clojure.lang.ExceptionInfo e (select-keys (ex-data e) [:type :field])))))
          (is (true? (ports/modify-role repo {:name :invoice/approver :description "Approves"
                                              :permissions #{:invoice/approve}})))
          (is (= #{:invoice/approve} (:permissions (first (ports/find-roles repo))))))

        (testing "a scope keeps its types"
          (let [scope {:domain-id 7 :director-general-id dg :level :high :tags #{"a"}}
                a     (ports/insert-assignment repo {:group-id (:id finance) :role :invoice/approver :scope scope})]
            (is (= [{:role :invoice/approver :scope scope}]
                   (map #(select-keys % [:role :scope]) (ports/find-assignments repo (:id finance)))))
            (is (= (:id a) (:id (first (ports/find-assignments repo (:id finance))))))))

        (testing "assignments through every group the user is in, Everyone and the named ones"
          (ports/ensure-everyone repo)
          (ports/insert-assignment repo {:group-id (:id audit) :role :invoice/approver :scope {:domain-id 8}})
          (ports/insert-assignment repo {:group-id (:id audit) :role :report/viewer :scope {}})
          (ports/insert-assignment repo {:group-id group/everyone-id :role :orders/editor :scope {}})
          (let [it     (ports/insert-group repo {:name "IT"})
                _      (ports/insert-assignment repo {:group-id (:id it) :role :it/editor :scope {}})
                roles  #(set (map (juxt :group-name :role) (ports/find-user-assignments repo (:id %1) %2)))]
            (is (= #{["Audit" :invoice/approver] ["Audit" :report/viewer] ["Finance" :invoice/approver]
                     ["Everyone" :orders/editor]}
                   (roles ada nil)))
            (is (= #{["Audit" :invoice/approver] ["Audit" :report/viewer] ["Everyone" :orders/editor] ["IT" :it/editor]}
                   (roles bob #{"IT" "No such group"}))
                "a provider's group counts; one with no group of that name gives nothing"))
          (is (= ["Audit" "Finance"] (map :name (ports/find-user-groups repo (:id ada)))))
          (is (= {"Audit" [2 2] "Finance" [1 1] "Everyone" [0 1]}
                 (into {} (map (juxt :name (juxt :member-count :role-count))) (filter (comp #{"Audit" "Finance" "Everyone"} :name) (ports/find-groups repo))))))

        (testing "a soft-deleted user holds nothing and is not listed"
          (ports/soft-delete-user users (:id bob))
          (is (empty? (ports/find-user-assignments repo (:id bob) nil)))
          (is (empty? (ports/find-user-groups repo (:id bob))))
          (is (= ["ada@example.com"] (map :email (ports/find-members repo (:id audit))))))

        (testing "erasing a user removes their memberships"
          (ports/hard-delete-user users (:id bob))
          (is (zero? (count-rows ctx "user_group_members" "user_id" (:id bob)))))

        (testing "removing a member, an assignment, a role and a group"
          (is (true? (ports/remove-member repo (:id finance) (:id ada))))
          (is (false? (ports/remove-member repo (:id finance) (:id ada))))
          (let [a (first (ports/find-assignments repo (:id finance)))]
            (is (false? (ports/remove-assignment repo (:id audit) (:id a))) "only from its own group")
            (is (true? (ports/remove-assignment repo (:id finance) (:id a)))))
          (is (true? (ports/remove-role repo :invoice/approver)))
          (is (= [:report/viewer] (map :role (ports/find-assignments repo (:id audit))))
              "a removed role takes its assignments")
          (is (true? (ports/remove-group repo (:id audit))))
          (is (nil? (ports/find-group repo (:id audit))))
          (is (zero? (count-rows ctx "user_group_members" "group_id" (:id audit))))
          (is (zero? (count-rows ctx "user_group_roles" "group_id" (:id audit))))
          (is (= [:orders/editor] (map :role (ports/find-user-assignments repo (:id ada) nil)))))))))

(deftest ^:contract keys-cascade-on-the-database
  ;; Not only the repository's explicit deletes: a row removed by hand takes
  ;; its memberships and assignments with it.
  (test-db/with-db "user_group_keys"
    (fn [ctx]
      (persistence/initialize-user-schema! ctx)
      (let [users (persistence/create-user-repository ctx)
            repo  (sut/create-group-repository ctx)
            ada   (user! users "ada@example.com")
            group (ports/insert-group repo {:name "Finance"})
            ds    (:datasource ctx)]
        (ports/insert-member repo (:id group) (:id ada))
        (ports/insert-assignment repo {:group-id (:id group) :role :a/b :scope {}})
        (jdbc/execute! ds ["DELETE FROM user_groups WHERE id = ?" (str (:id group))])
        (is (zero? (count-rows ctx "user_group_members" "group_id" (:id group))))
        (is (zero? (count-rows ctx "user_group_roles" "group_id" (:id group))))
        (let [group (ports/insert-group repo {:name "Audit"})]
          (ports/insert-member repo (:id group) (:id ada))
          (jdbc/execute! ds ["DELETE FROM users WHERE id = ?" (str (:id ada))])
          (jdbc/execute! ds ["DELETE FROM auth_users WHERE id = ?" (str (:id ada))])
          (is (zero? (count-rows ctx "user_group_members" "user_id" (:id ada)))))))))

(deftest ^:contract role-holders-through-every-group-and-everyone
  (test-db/with-db "user_group_holders"
    (fn [ctx]
      (persistence/initialize-user-schema! ctx)
      (let [users   (persistence/create-user-repository ctx)
            repo    (sut/create-group-repository ctx)
            [ada bob cyd dan] (map #(user! users (str % "@example.com")) ["ada" "bob" "cyd" "dan"])
            finance (ports/insert-group repo {:name "Finance"})
            audit   (ports/insert-group repo {:name "Audit"})
            holders #(set (map (juxt :user-id :role :scope) (ports/find-role-holders repo %)))]
        (ports/ensure-everyone repo)
        (doseq [[g u] [[finance ada] [finance bob] [audit ada] [audit cyd] [audit dan]]]
          (ports/insert-member repo (:id g) (:id u)))
        (ports/insert-assignment repo {:group-id (:id finance) :role :invoice/approver :scope {:domain-id 1}})
        (ports/insert-assignment repo {:group-id (:id audit) :role :invoice/approver :scope {:domain-id 2}})
        (ports/insert-assignment repo {:group-id group/everyone-id :role :report/viewer :scope {}})
        (ports/soft-delete-user users (:id cyd))
        (jdbc/execute! (:datasource ctx) ["UPDATE auth_users SET active = ? WHERE id = ?" false (str (:id dan))])
        (is (= #{[(:id ada) :invoice/approver {:domain-id 1}] [(:id ada) :invoice/approver {:domain-id 2}]
                 [(:id bob) :invoice/approver {:domain-id 1}]}
               (holders #{:invoice/approver}))
            "soft-deleted cyd and inactive dan hold nothing")
        (is (= #{[(:id ada) :report/viewer {}] [(:id bob) :report/viewer {}]}
               (holders #{:report/viewer}))
            "Everyone's role reaches every active user")
        (is (= #{(:id finance) (:id audit)} (set (map :group-id (ports/find-role-holders repo #{:invoice/approver}))))
            "each through its group")
        (is (= [] (ports/find-role-holders repo #{})))))))

(deftest ^:contract everyone-is-made-once
  (test-db/with-db "user_group_everyone"
    (fn [ctx]
      (persistence/initialize-user-schema! ctx)
      (let [repo (sut/create-group-repository ctx)
            mine (ports/insert-group repo {:name "Everyone"})]
        (is (true? (ports/ensure-everyone repo)))
        (is (false? (ports/ensure-everyone repo)))
        (is (= "Everyone" (:name (ports/find-group repo group/everyone-id))))
        (is (str/starts-with? (:name (ports/find-group repo (:id mine))) "Everyone (")
            "an application's group of that name steps aside")))))

(deftest ^:contract a-group-name-is-unique
  (test-db/with-db "user_group_names"
    (fn [ctx]
      (persistence/initialize-user-schema! ctx)
      (let [repo  (sut/create-group-repository ctx)
            audit (ports/insert-group repo {:name "Audit"})
            taken #(try (%) nil (catch clojure.lang.ExceptionInfo e (select-keys (ex-data e) [:type :field])))]
        (ports/insert-group repo {:name "Finance"})
        (is (= {:type :conflict :field :name} (taken #(ports/insert-group repo {:name "Finance"}))))
        (is (= {:type :conflict :field :name} (taken #(ports/modify-group repo {:id (:id audit) :name "Finance"}))))
        (testing "names that already repeat do not stop boot"
          (db/drop-index-if-exists! ctx "uk_user_groups_name" :user_groups)
          (ports/insert-group repo {:name "Finance"})
          (persistence/initialize-user-schema! ctx)
          (is (= 2 (count (filter #(= "Finance" (:name %)) (ports/find-groups repo))))))))))

(defn- index-names
  "The lower-case names of `table`'s indexes, from the JDBC catalog."
  [ctx table]
  (with-open [conn (jdbc/get-connection (:datasource ctx))]
    (let [meta (.getMetaData conn)]
      (into #{}
            (mapcat (fn [t]
                      (with-open [result (.getIndexInfo meta (.getCatalog conn) nil t false true)]
                        (loop [names []]
                          (if (.next result)
                            (recur (conj names (some-> (.getString result "INDEX_NAME") str/lower-case)))
                            names)))))
            [table (str/upper-case table)]))))

(deftest ^:contract the-per-request-queries-are-indexed
  (test-db/with-db "user_group_indexes"
    (fn [ctx]
      (persistence/initialize-user-schema! ctx)
      (is (set/subset? #{"idx_user_group_members_user_id" "uk_user_group_members_group_user"}
                       (index-names ctx "user_group_members")))
      (is (set/subset? #{"idx_user_group_roles_group_id" "idx_user_group_roles_role"}
                       (index-names ctx "user_group_roles")))
      (is (contains? (index-names ctx "user_roles") "uk_user_roles_name"))
      (is (contains? (index-names ctx "user_groups") "uk_user_groups_name")))))
