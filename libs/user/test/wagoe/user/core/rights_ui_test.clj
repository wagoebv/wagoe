(ns wagoe.user.core.rights-ui-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.user.core.rights-ui :as sut]))

(defn- text
  "The strings and translation keys in `hiccup`, in order."
  [hiccup]
  (->> (tree-seq coll? seq hiccup)
       (keep #(cond (string? %) %
                    (and (vector? %) (= :t (first %))) (second %)))
       vec))

(deftest ^:unit a-scope-reads-as-its-keys-and-values
  (let [info {:labels {:domain-id "Domain"} :sources {:user/department "department"}}]
    (is (= [:user.rights/scope-everywhere] (text (sut/scope-view {} info))))
    (is (= ["Domain" ": " "42"] (text (sut/scope-view {:domain-id 42} info))))
    (testing "a key on an admin entity asks the admin for the record's title"
      (let [[_ attrs v] (last (last (sut/scope-view {:domain-id 42} (assoc info :entities {:domain-id :domains}))))]
        (is (= "/web/admin/domains/lookup?id=42" (:hx-get attrs)))
        (is (:data-quiet-failure attrs) "a failed lookup leaves the id, without an error toast")
        (is (= "42" v) "the id until it answers")))
    (testing "an unlabelled key shows its name"
      (is (= ["region" ": " "north"] (text (sut/scope-view {:region "north"} info)))))
    (testing "a value from the member names its source, and what it resolved to"
      (is (= ["dept" ": " :user.rights/scope-from-source "department"]
             (text (sut/scope-view {:dept :user/department} info))))
      (is (= ["dept" ": " :user.rights/scope-from-source "department" " = " "3, 7"]
             (text (sut/scope-view {:dept :user/department} (assoc info :resolved {[:dept :user/department] #{7 3}})))))
      (is (some #{:user.rights/scope-resolved-none}
                (text (sut/scope-view {:dept :user/department} (assoc info :resolved {[:dept :user/department] #{}})))))
      (is (some #{:user.rights/scope-resolve-failed}
                (text (sut/scope-view {:dept :user/department} (assoc info :resolved {[:dept :user/department] :error}))))))))

(deftest ^:unit permissions-are-grouped-by-module
  (let [registry {:orders/read  {:label "Read orders" :module :wagoe/orders}
                  :orders/write {:label "Change orders" :module :wagoe/orders}
                  :ai/read      {:label "Read ai"}}]
    (is (= [["ai" [[:ai/read "Read ai" false]]]
            ["orders" [[:orders/read "Read orders" true] [:orders/write "Change orders" false]]]]
           (sut/permission-groups registry #{:orders/read})))
    (testing "a held permission the registry lacks comes last"
      (is (= [nil [[:legacy/x nil true]]] (last (sut/permission-groups registry #{:legacy/x})))))))

(deftest ^:unit paths-carry-the-name-as-a-query-parameter
  (is (= "/web/admin/roles/view?name=orders%2Fviewer" (sut/role-path :orders/viewer)))
  (is (= "/web/admin/permissions/view?name=admin.users%2Fread" (sut/permission-path :admin.users/read))))

(defn- rows-of
  "The [:tr] rows under `hiccup`'s tbody, each as its class and its text,
   attributes left out."
  [hiccup]
  (let [shown (fn [node] (->> (tree-seq #(and (coll? %) (not (map? %))) seq node)
                              (keep #(cond (string? %) %
                                           (and (vector? %) (= :t (first %))) (second %)))))]
    (for [node (tree-seq coll? seq hiccup)
          :when (and (vector? node) (= :tbody (first node)))
          tr   (tree-seq coll? seq node)
          :when (and (vector? tr) (keyword? (first tr)) (re-find #"^tr\b" (name (first tr))))]
      [(:class (second tr)) (vec (shown tr))])))

(def ^:private roles
  [{:name :orders/clerk :description "Takes orders" :permissions #{:orders/read} :source :managed}
   {:name :admin.users/viewer :description "Reads users" :permissions #{:admin.users/read} :source :code :module :admin}
   {:name :admin.users/editor :description nil :permissions #{:admin.users/read :admin.users/write} :source :code :module :admin}])

(deftest ^:unit roles-are-listed-by-module-searched-and-sorted
  (let [rows (rows-of (sut/roles-page roles {} {:table-query {}}))]
    (is (= ["list-group-row" "role-row" "role-row" "list-group-row" "role-row"]
           (map #(re-find #"list-group-row|role-row" (first %)) rows)))
    (is (= "admin" (first (second (first rows)))) "a module heads its roles")
    (is (= "admin.users/editor" (first (second (second rows)))) "by name within it"))
  (testing "a search keeps the matching roles, and their module's heading"
    (let [rows (rows-of (sut/roles-page roles {} {:table-query {:search "ORDERS"}}))]
      (is (= [["orders"] ["orders/clerk" "Takes orders"]] (map #(take 2 (second %)) rows)))))
  (testing "a sort the list does not offer falls back to the name"
    (is (= (rows-of (sut/roles-page roles {} {:table-query {:sort :nope}}))
           (rows-of (sut/roles-page roles {} {:table-query {:sort :name :dir :asc}})))))
  (testing "descending: reversed within each module, the modules in order"
    (is (= ["admin" "admin.users/viewer" "admin.users/editor" "orders" "orders/clerk"]
           (map (comp first second) (rows-of (sut/roles-page roles {} {:table-query {:sort :name :dir :desc}}))))))
  (testing "nothing found says so"
    (is (some #{:user.lists/no-matches} (text (sut/roles-page roles {} {:table-query {:search "zzz"}}))))))

(deftest ^:unit a-list-fragment-carries-its-count-out-of-band
  (let [[table badge] (sut/list-fragment sut/roles-list roles {:search "users"})]
    (is (= "roles-table-container" (:id (second table))))
    (is (= "roles-table-container-count" (:id (second badge))))
    (is (= "true" (:hx-swap-oob (second badge))))
    (is (= [:t :user.roles/count {:count 2} 2] (last badge)))))

(deftest ^:unit the-rights-page-shows-a-group-once-per-role
  (let [grants   (for [p [:admin.users/read :admin.users/write :orders/read]]
                   {:permission p :role (if (= "orders" (namespace p)) :orders/clerk :admin.users/editor)
                    :group-id 1 :group-name "Everyone" :scope {}})
        registry {:admin.users/read {:module :admin} :admin.users/write {:module :admin}}
        rows     (rows-of (sut/rights-page {:id 1 :name "Ada"} [] grants {:registry registry}))]
    (is (= [["admin"]
            ["admin.users/read" " " "admin.users/write" "Everyone" " " :user.access/holds-role " " "admin.users/editor" " " :user.access/everywhere]
            ["orders"]
            ["orders/read" "Everyone" " " :user.access/holds-role " " "orders/clerk" " " :user.access/everywhere]]
           (map second rows))
        "by module, one row per role, not per permission")))

(deftest ^:unit an-assignment-reads-as-a-sentence
  (let [index (sut/role-index (conj roles {:name :orders/editor :permissions #{:orders/write} :source :code}))
        info  {:labels {:dept "Department" :domain-id "Domain"} :sources {:user/department "department"}
               :entities {:domain-id :domains}}
        text  (fn [node] (->> (tree-seq #(and (coll? %) (not (map? %))) seq node)
                              (keep #(cond (string? %) %
                                           (and (vector? %) (= :t (first %))) (second %)))
                              vec))
        say   #(text (sut/assignment-sentence (merge {:group-id 1 :group-name "Sales NL"} %) index info))]
    (testing "a code role's level, as a verb on its module"
      (is (= ["Sales NL" " " :user.access/may-editor " " :user.access/everywhere]
             (say {:role :orders/editor :scope {}}))))
    (testing "a fixed value by its record, looked up for its title"
      (is (= ["Sales NL" " " :user.access/may-editor " " :user.access/within " " "42"]
             (say {:role :orders/editor :scope {:domain-id 42}})))
      (is (some #(and (map? %) (= "/web/admin/domains/lookup?id=42" (:hx-get %)))
                (tree-seq coll? seq (sut/assignment-sentence {:role :orders/editor :scope {:domain-id 42}} index info)))))
    (testing "a user source, with what it resolved to"
      (is (= ["Sales NL" " " :user.access/may-editor " " :user.access/within-source]
             (say {:role :orders/editor :scope {:dept :user/department}})))
      (is (= [:user.access/within-source " (" "3, 7" ")"]
             (drop 4 (text (sut/assignment-sentence {:group-name "Sales NL" :role :orders/editor :scope {:dept :user/department}}
                                                    index (assoc info :resolved {[:dept :user/department] #{7 3}})))))))
    (testing "a managed role by its name"
      (is (= ["Sales NL" " " :user.access/holds-role " " "orders/clerk" " " :user.access/everywhere]
             (say {:role :orders/clerk :scope {}}))))
    (testing "several keys joined"
      (is (= [:user.access/within " " "7" " " :user.access/and " " :user.access/within " " "42"]
             (drop 4 (say {:role :orders/editor :scope {:domain-id 42 :dept 7}})))))
    (testing "one that ends says when (BOU-856)"
      (is (= [:user.access/everywhere " " :user.access/expires-on]
             (drop 4 (say {:role :orders/editor :scope {} :expires-at (java.time.Instant/parse "2026-11-15T00:00:00Z")}))))
      (is (= [:t :user.access/expires-on {:date "2026-11-15"}]
             (last (sut/expiry-note (java.time.Instant/parse "2026-11-15T00:00:00Z")))))
      (is (nil? (sut/expiry-note nil))))))

(deftest ^:unit detail-table-cells-are-labelled-for-a-narrow-screen
  (let [page (sut/rights-page {:id 1 :name "Ada"} [{:id 2 :name "Sales" :source :member}] [] {})
        tds  (filter #(and (vector? %) (keyword? (first %)) (re-find #"^td\b" (name (first %))))
                     (tree-seq coll? seq page))]
    (is (= [[:t :user.rights/column-group] [:t :user.roles/column-source]] (map (comp :data-label second) tds)))))

(deftest ^:unit roles-with-no-module-come-last-under-other
  (let [rows (rows-of (sut/roles-page (conj roles {:name :auditor :permissions #{} :source :managed}) {} {:table-query {}}))]
    (is (= :user.lists/group-none (first (second (last (butlast rows))))))))

(deftest ^:unit a-sort-without-a-direction-is-ascending
  (is (= ["ascending"]
         (keep #(when (map? %) (:aria-sort %))
               (tree-seq coll? seq (sut/roles-page roles {} {:table-query {:sort :name}}))))))

(deftest ^:unit the-rights-tab-is-active-on-the-rights-page
  (let [page (sut/rights-page {:id 1 :name "Ada"} [] [] {})]
    (is (some #(and (map? %) (= "tab tab-active" (:class %)) (= "/web/users/1/rights" (:href %)))
              (tree-seq coll? seq page)))))

(defn- role-rows [page]
  (filter #(re-find #"role-row" (first %)) (rows-of page)))

(deftest ^:unit a-list-filters-on-a-signal
  (let [signalled [(assoc (first roles) :signals #{:unused})
                   (assoc (second roles) :signals #{})
                   (assoc (nth roles 2) :signals #{:near-duplicate} :near-duplicates [:admin.users/viewer])]
        page      #(sut/roles-page signalled {} {:table-query %})
        filtered  {:filters {:signal {:op :eq :value "unused"}}}]
    (testing "every row with its badges, and a filter per signal"
      (is (= 3 (count (role-rows (page {})))))
      (is (some #{:user.hygiene/near-duplicate-of} (text (page {}))))
      (is (some #(and (map? %) (re-find #"signal-filter-unused" (str (:class %)))) (tree-seq coll? seq (page {})))))
    (testing "filtered: the rows carrying it, and the search sends the filter along"
      (is (= ["orders/clerk"] (map (comp first second) (role-rows (page filtered)))))
      (is (some #(and (map? %) (= "filters[signal][value]" (:name %)) (= "unused" (:value %)))
                (tree-seq coll? seq (page filtered)))))
    (testing "a signal the list does not offer is ignored"
      (is (= 3 (count (role-rows (page {:filters {:signal {:value "empty"}}}))))))
    (testing "a search in place replaces the filter links, so they keep it"
      (let [nav (some #(when (and (vector? %) (= "roles-table-container-signals" (:id (second %)))) %)
                      (tree-seq coll? seq (sut/list-fragment sut/roles-list signalled {:search "clerk"})))]
        (is (= "true" (:hx-swap-oob (second nav))))
        (is (some #(re-find #"search=clerk" (str (:href %))) (filter map? (tree-seq coll? seq nav))))))))

(deftest ^:unit the-rights-page-shows-the-changes-to-rights
  (let [user    {:id (random-uuid) :email "ada@example.com"}
        changes [{:at (java.time.Instant/parse "2026-10-03T10:00:00Z") :actor "admin@example.com"
                  :action :group-join :group-id (str (random-uuid)) :group-name "Sales"
                  :added [{:permission :orders/read :scope {:dept #{7}}}] :removed []}
                 {:at (java.time.Instant/parse "2026-10-02T10:00:00Z") :actor "admin@example.com"
                  :action :grant-add :group-name "Everyone" :added [] :removed []
                  :permissions [{:permission :orders/read :added 3 :removed 0}] :affected-count 3}]
        shown   (text (sut/rights-page user [] [] {:changes changes :scope-info {:labels {:dept "Department"}}}))]
    (is (some #{"2026-10-03 10:00:00 UTC"} shown) "the zone said (BOU-865)")
    (is (some #{:common/audit-group-join} shown))
    (is (some #{:user.preview/gains} shown))
    (is (some #{"Department"} shown))
    (is (some #{:user.timeline/everyone} shown))
    (is (some #{:user.timeline/empty} (text (sut/rights-page user [] [] {}))))))

(deftest ^:unit a-role-reads-in-the-module-it-is-declared-in
  ;; BOU-865: as the overview's column names it, not its name's namespace
  (let [index (sut/role-index [{:name :admin.users/editor :source :code :module :wagoe/admin :permissions #{}}])]
    (is (some #(= [:t :user.access/may-editor {:module "admin"}] %)
              (tree-seq coll? seq (sut/assignment-sentence {:group-name "Ops" :role :admin.users/editor :scope {}}
                                                           index {}))))))

(deftest ^:unit explain-says-the-authorization-mode
  ;; BOU-865: under :report and :off a no refuses nothing
  (let [user   {:id (random-uuid) :email "ada@example.com"}
        result {:allowed? false :paths [] :misses []}
        shown  #(text (sut/explain-page user result {:asked {:permission :orders/read} :mode %}))]
    (is (some #{:user.explain/mode-report} (shown :report)))
    (is (some #{:user.explain/mode-off} (shown :off)))
    (is (some #{:user.explain/mode-enforce} (shown :enforce)))
    (is (not-any? #{:user.explain/mode-report :user.explain/mode-off :user.explain/mode-enforce} (shown nil)))))
