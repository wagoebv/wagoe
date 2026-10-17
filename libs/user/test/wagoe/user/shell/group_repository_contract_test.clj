(ns wagoe.user.shell.group-repository-contract-test
  "Groups, members and grants on the engine WAGOE_TEST_DB names: the round
   trip, grants through several groups, and what deleting a user or a group
   leaves behind."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [support.test-db :as test-db]
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

        (testing "a scope keeps its types"
          (let [scope {:domain-id 7 :director-general-id dg :level :high :tags #{"a"}}
                g     (ports/insert-grant repo {:group-id (:id finance) :permission :invoice/approve :scope scope})]
            (is (= [{:permission :invoice/approve :scope scope}]
                   (map #(select-keys % [:permission :scope]) (ports/find-grants repo (:id finance)))))
            (is (= (:id g) (:id (first (ports/find-grants repo (:id finance))))))))

        (testing "grants through every group the user is in"
          (ports/insert-grant repo {:group-id (:id audit) :permission :invoice/approve :scope {:domain-id 8}})
          (ports/insert-grant repo {:group-id (:id audit) :permission :report/view :scope {}})
          (is (= #{["Audit" :invoice/approve] ["Audit" :report/view] ["Finance" :invoice/approve]}
                 (set (map (juxt :group-name :permission) (ports/find-user-grants repo (:id ada))))))
          (is (= #{:invoice/approve :report/view}
                 (set (map :permission (ports/find-user-grants repo (:id bob))))))
          (is (= ["Audit" "Finance"] (map :name (ports/find-user-groups repo (:id ada)))))
          (is (= [{:name "Audit" :member-count 2 :grant-count 2} {:name "Finance" :member-count 1 :grant-count 1}]
                 (map #(select-keys % [:name :member-count :grant-count]) (ports/find-groups repo)))))

        (testing "a soft-deleted user holds nothing and is not listed"
          (ports/soft-delete-user users (:id bob))
          (is (empty? (ports/find-user-grants repo (:id bob))))
          (is (empty? (ports/find-user-groups repo (:id bob))))
          (is (= ["ada@example.com"] (map :email (ports/find-members repo (:id audit))))))

        (testing "erasing a user removes their memberships"
          (ports/hard-delete-user users (:id bob))
          (is (zero? (count-rows ctx "user_group_members" "user_id" (:id bob)))))

        (testing "removing a member, a grant and a group"
          (is (true? (ports/remove-member repo (:id finance) (:id ada))))
          (is (false? (ports/remove-member repo (:id finance) (:id ada))))
          (let [grant (first (ports/find-grants repo (:id finance)))]
            (is (false? (ports/remove-grant repo (:id audit) (:id grant))) "only from its own group")
            (is (true? (ports/remove-grant repo (:id finance) (:id grant)))))
          (is (true? (ports/remove-group repo (:id audit))))
          (is (nil? (ports/find-group repo (:id audit))))
          (is (zero? (count-rows ctx "user_group_members" "group_id" (:id audit))))
          (is (zero? (count-rows ctx "user_group_grants" "group_id" (:id audit))))
          (is (empty? (ports/find-user-grants repo (:id ada)))))))))

(deftest ^:contract keys-cascade-on-the-database
  ;; Not only the repository's explicit deletes: a row removed by hand takes
  ;; its memberships and grants with it.
  (test-db/with-db "user_group_keys"
    (fn [ctx]
      (persistence/initialize-user-schema! ctx)
      (let [users (persistence/create-user-repository ctx)
            repo  (sut/create-group-repository ctx)
            ada   (user! users "ada@example.com")
            group (ports/insert-group repo {:name "Finance"})
            ds    (:datasource ctx)]
        (ports/insert-member repo (:id group) (:id ada))
        (ports/insert-grant repo {:group-id (:id group) :permission :a :scope {}})
        (jdbc/execute! ds ["DELETE FROM user_groups WHERE id = ?" (str (:id group))])
        (is (zero? (count-rows ctx "user_group_members" "group_id" (:id group))))
        (is (zero? (count-rows ctx "user_group_grants" "group_id" (:id group))))
        (let [group (ports/insert-group repo {:name "Audit"})]
          (ports/insert-member repo (:id group) (:id ada))
          (jdbc/execute! ds ["DELETE FROM users WHERE id = ?" (str (:id ada))])
          (jdbc/execute! ds ["DELETE FROM auth_users WHERE id = ?" (str (:id ada))])
          (is (zero? (count-rows ctx "user_group_members" "user_id" (:id ada)))))))))

(deftest ^:contract permission-holders-through-every-group
  (test-db/with-db "user_group_holders"
    (fn [ctx]
      (persistence/initialize-user-schema! ctx)
      (let [users   (persistence/create-user-repository ctx)
            repo    (sut/create-group-repository ctx)
            [ada bob cyd dan] (map #(user! users (str % "@example.com")) ["ada" "bob" "cyd" "dan"])
            finance (ports/insert-group repo {:name "Finance"})
            audit   (ports/insert-group repo {:name "Audit"})
            holders #(set (map (juxt :user-id :scope) (ports/find-permission-holders repo %)))]
        (doseq [[g u] [[finance ada] [finance bob] [audit ada] [audit cyd] [audit dan]]]
          (ports/insert-member repo (:id g) (:id u)))
        (ports/insert-grant repo {:group-id (:id finance) :permission :invoice/approve :scope {:domain-id 1}})
        (ports/insert-grant repo {:group-id (:id audit) :permission :invoice/approve :scope {:domain-id 1}})
        (ports/insert-grant repo {:group-id (:id audit) :permission :invoice/approve :scope {:domain-id 2}})
        (ports/insert-grant repo {:group-id (:id audit) :permission :report/view :scope {}})
        (ports/soft-delete-user users (:id cyd))
        (jdbc/execute! (:datasource ctx) ["UPDATE auth_users SET active = ? WHERE id = ?" false (str (:id dan))])
        (is (= #{[(:id ada) {:domain-id 1}] [(:id ada) {:domain-id 2}] [(:id bob) {:domain-id 1}]}
               (holders :invoice/approve))
            "soft-deleted cyd and inactive dan hold nothing")
        (is (= 3 (count (ports/find-permission-holders repo :invoice/approve)))
            "ada once in {:domain-id 1}, though two groups grant it")
        (is (= #{:invoice/approve} (set (map :permission (ports/find-permission-holders repo :invoice/approve)))))
        (is (= #{[(:id ada) {}]} (holders :report/view)))
        (is (= #{} (holders :nobody/holds)))))))

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
      (is (set/subset? #{"idx_user_group_grants_group_id" "idx_user_group_grants_permission"}
                       (index-names ctx "user_group_grants"))))))
