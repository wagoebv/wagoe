(ns wagoe.user.core.group-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.user.core.audit :as audit]
            [wagoe.user.core.group :as sut]))

(def ^:private grants
  [{:permission :invoice/approve :scope {:domain-id 1} :group-name "Finance"}
   {:permission :invoice/approve :scope {:domain-id 2} :group-name "Audit"}
   {:permission :invoice/approve :scope {:domain-id 1} :group-name "Audit"}
   {:permission :report/view :scope {} :group-name "Audit"}])

(deftest ^:unit what-counts-as-a-refusal
  ;; BOU-915: shown to the admin, not raised
  (is (sut/refusal? {:type :validation-error :field :scope}))
  (is (sut/refusal? {:type :not-found :user-id 1}))
  (is (sut/refusal? {:type :conflict :field :name}) "a name taken")
  (is (not (sut/refusal? {:type :conflict})) "a write that lost a race")
  (is (not (sut/refusal? {:type :internal-error})))
  (is (not (sut/refusal? nil))))

(deftest ^:unit holding-a-permission
  (is (true? (sut/holds? grants :invoice/approve)))
  (is (true? (sut/holds? grants :report/view)))
  (is (false? (sut/holds? grants :invoice/delete)))
  (is (false? (sut/holds? [] :invoice/approve))))

(deftest ^:unit scopes-across-groups
  (testing "every distinct scope, from every group"
    (is (= [{:domain-id 1} {:domain-id 2}] (sut/grants-for grants :invoice/approve))))
  (is (= [] (sut/grants-for grants :invoice/delete)))
  (testing "the application judges the scope"
    (is (true? (sut/authorized? grants :invoice/approve #(= 2 (:domain-id %)))))
    (is (false? (sut/authorized? grants :invoice/approve #(= 3 (:domain-id %)))))
    (is (false? (sut/authorized? grants :invoice/delete (constantly true))))))

(def ^:private declared
  {:permissions  [:enum :invoice/approve :report/view]
   :scope-schema [:map {:closed true}
                  [:domain-id {:optional true} :int]
                  [:director-general-id {:optional true} :uuid]]})

(defrecord Tagged [v])

(deftest ^:unit grants-checked-against-what-the-application-declares
  (is (nil? (sut/grant-refusal {:permission :invoice/approve :scope {:domain-id 4}} declared)))
  (is (nil? (sut/grant-refusal {:permission :report/view :scope {}} declared)))
  (testing "an undeclared permission"
    (is (= :permission (get-in (sut/grant-refusal {:permission :invoice/delete :scope {}} declared)
                               [:error :field]))))
  (testing "an undeclared scope key, and a value of the wrong type"
    (doseq [scope [{:tenant-id 1} {:domain-id "4"}]]
      (let [{:keys [error]} (sut/grant-refusal {:permission :report/view :scope scope} declared)]
        (is (= :validation-error (:type error)))
        (is (= :scope (:field error)))
        (is (some? (:errors error))))))
  (testing "a code role's name, which would shadow the grant's managed role"
    (is (= :permission (get-in (sut/grant-refusal {:permission :orders/editor :scope {}} {}
                                                  {:orders/editor {:permissions #{:orders/write}}})
                               [:error :field]))))
  (testing "nothing declared: any keyword and any map of EDN"
    (is (nil? (sut/grant-refusal {:permission :anything :scope {:x #{1 :a "b"} :y (random-uuid)}} {})))
    (is (= :permission (get-in (sut/grant-refusal {:permission "invoice/approve" :scope {}} {}) [:error :field])))
    (is (= :scope (get-in (sut/grant-refusal {:permission :a :scope [1]} {}) [:error :field])))
    (is (= :scope (get-in (sut/grant-refusal {:permission :a :scope {:at (java.time.Instant/now)}} {})
                          [:error :field]))
        "an Instant does not read back from EDN")
    (testing "what prints but does not read back"
      (doseq [x [(keyword "a b") (symbol "x,y") (sorted-map) (java.util.HashMap.)]]
        (is (= (map? x) (sut/edn-value? x)) (pr-str x)))
      (is (false? (sut/edn-value? (->Tagged 1)))))))

(deftest ^:unit audit-entries-name-each-user-whose-rights-changed
  (let [ctx     {:actor-id (random-uuid) :actor-email "admin@example.com"}
        group   {:id (random-uuid) :name "Finance"}
        members [{:id (random-uuid) :email "a@example.com"} {:id (random-uuid) :email "b@example.com"}]
        entries (audit/group-audit-entries :grant-add ctx group members
                                           [{:permission :invoice/approve :scope {:domain-id 1}}])]
    (is (= ["a@example.com" "b@example.com"] (map :target-user-email entries)))
    (is (every? #(= :grant-add (:action %)) entries))
    (is (= {:grants [{:permission "invoice/approve" :scope "{:domain-id 1}"}]} (:changes (first entries))))
    (is (= {:group-id (str (:id group)) :group-name "Finance"} (:metadata (first entries))))
    (is (empty? (audit/group-audit-entries :grant-add ctx group [] [{:permission :a :scope {}}])))
    (testing "an entry keeps what the change did to that member's effective rights"
      (let [[a b] (audit/group-audit-entries :grant-add ctx group members [{:permission :a :scope {}}]
                                             {(str (:id (first members)))
                                              {:added [{:permission :a :scope {:domain-id #{70 7}}}] :removed []}})]
        (is (= {:added [{:permission "a" :scope "{:domain-id #{7 70}}"}] :removed []} (get-in a [:changes :effective])))
        (is (not (contains? (:changes b) :effective)))))))

(deftest ^:unit a-preview-sums-up-each-users-diff
  (let [diff   (fn [id added removed] {:user {:id id :email (str id "@example.com")} :added added :removed removed})
        read   {:permission :invoice/read :scope {}}
        write  {:permission :invoice/write :scope {}}
        diffs  (into [(diff 1 [read write] []) (diff 2 [read] [write]) (diff 3 [] [])]
                     (map #(diff % [read] [])) (range 10 (+ 10 sut/preview-page-size 5)))
        result (sut/diff-summary diffs 1)]
    (is (= (first diffs) (:user result)) "the user the change names")
    (is (= (+ 1 sut/preview-page-size 5) (:affected-count result)) "the others whose rights change; 3's do not")
    (is (= sut/preview-page-size (count (:affected result))))
    (is (= {:id 2 :email "2@example.com"} (first (:affected result))))
    (is (= [{:permission :invoice/read :added (+ 2 sut/preview-page-size 5) :removed 0}
            {:permission :invoice/write :added 1 :removed 1}]
           (:permissions result)))
    (is (nil? (:user (sut/diff-summary diffs nil))))))

(deftest ^:unit a-diff-with-a-count-stands-for-that-many-users
  ;; BOU-862: Everyone's users without another way to the permission, as one
  (let [read   {:permission :invoice/read :scope {}}
        result (sut/diff-summary [{:user {:id 1 :email "a@example.com"} :added [read] :removed []}
                                  {:added [read] :removed [] :count 1000}]
                                 nil)]
    (is (= 1001 (:affected-count result)))
    (is (= [{:permission :invoice/read :added 1001 :removed 0}] (:permissions result)))
    (is (= [{:id 1 :email "a@example.com"}] (:affected result)) "only named users are listed")))

(deftest ^:unit users-with-a-permission-in-a-scope
  (let [[a b] [(random-uuid) (random-uuid)]
        holders [{:user-id a :scope {:domain-id 1}} {:user-id a :scope {:domain-id 2}}
                 {:user-id b :scope {:domain-id 2}}]]
    (is (= [a b] (sut/users-with-permission holders (constantly true))) "each user once")
    (is (= [a] (sut/users-with-permission holders #(= 1 (:domain-id %)))))
    (is (= [] (sut/users-with-permission [] (constantly true))))))

(deftest ^:unit what-an-import-changes
  (let [[a b c] (map (fn [_] {:id (random-uuid)}) (range 3))
        kept    {:id 1 :role :invoice/approver :scope {:domain-id 1}}
        extra   {:id 2 :role :report/viewer :scope {}}
        new     {:role :invoice/approver :scope {:domain-id 2}}
        current {:members [a b] :held [kept extra]}
        wanted  {:members [b c c] :roles [(dissoc kept :id) new new]}]
    (testing "additive: only what is missing is added"
      (is (= {:join [c] :leave [] :stay [a b] :add [new] :remove []}
             (sut/import-changes current wanted false))))
    (testing "replace: what is not listed goes too"
      (is (= {:join [c] :leave [a] :stay [b] :add [new] :remove [extra]}
             (sut/import-changes current wanted true))))
    (testing "importing what is there changes nothing"
      (is (= {:join [] :leave [] :stay [a b] :add [] :remove []}
             (sut/import-changes current {:members [a b] :roles (map #(dissoc % :id) [kept extra])} true))))))

(deftest ^:unit an-expiry-must-lie-ahead
  (let [now (java.time.Instant/parse "2026-10-27T12:00:00Z")]
    (is (nil? (sut/expiry-refusal nil now)) "none is for good")
    (is (nil? (sut/expiry-refusal (.plusSeconds now 1) now)))
    (doseq [t [now (.minusSeconds now 1)]]
      (is (= :expires-at (get-in (sut/expiry-refusal t now) [:error :field])) (str t)))))

(deftest ^:unit a-grant-keeps-when-its-assignment-ends
  (let [t (java.time.Instant/parse "2026-11-15T00:00:00Z")]
    (is (= [{:permission :a/b :role :r :scope {} :group-id 1 :expires-at t}]
           (sut/user-grants [{:role :r :scope {} :group-id 1 :expires-at t}] {:r {:permissions #{:a/b}}})))))
