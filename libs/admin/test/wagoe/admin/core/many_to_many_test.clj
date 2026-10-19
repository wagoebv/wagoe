(ns wagoe.admin.core.many-to-many-test
  "BOU-778: which table is a join table, the relations it gives both sides,
   and how a form, a list and a filter name them."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hiccup2.core :as h]
            [wagoe.admin.core.display :as display]
            [wagoe.admin.core.forms :as forms]
            [wagoe.admin.core.schema-introspection :as introspection]
            [wagoe.admin.core.ui.filters :as filters]))

(defn- cfg
  "A detected config: `fields`, belongs-to from their names, `unique` keys."
  [label fields unique & [extra]]
  (introspection/detect-relationships
   (merge {:label       label
           :primary-key (if (contains? (set fields) :id) :id (first fields))
           :fields      (into {} (map (fn [f] [f {:name f :type :string}])) fields)
           :unique-keys unique}
          extra)))

(def ^:private products (cfg "Products" [:id :name] [#{:id}]))
(def ^:private tags (cfg "Tags" [:id :name] [#{:id}]))
(def ^:private product-tags
  (cfg "Product tags" [:product-id :tag-id :created-at] [#{:product-id :tag-id}]))

(deftest ^:unit a-join-table-has-two-keys-unique-together-and-nothing-else
  (is (= #{:product-id :tag-id} (set (introspection/join-keys product-tags))))
  (testing "a surrogate id is allowed"
    (is (some? (introspection/join-keys
                (cfg "J" [:id :product-id :tag-id] [#{:id} #{:product-id :tag-id}])))))
  (testing "not unique together: a child"
    (is (nil? (introspection/join-keys (cfg "J" [:id :product-id :tag-id] [#{:id}])))))
  (testing "a role column: a child"
    (is (nil? (introspection/join-keys
               (cfg "J" [:id :product-id :tag-id :role] [#{:id} #{:product-id :tag-id}])))))
  (testing "one key or three: no join table"
    (is (nil? (introspection/join-keys (cfg "J" [:id :product-id] [#{:id}]))))
    (is (nil? (introspection/join-keys
               (cfg "J" [:a-id :b-id :c-id] [#{:a-id :b-id :c-id}])))))
  (testing "the config declares it, or switches it off"
    (is (some? (introspection/join-keys
                (cfg "J" [:id :product-id :tag-id :role] [#{:id}] {:join-table true}))))
    (is (nil? (introspection/join-keys (assoc product-tags :join-table false))))))

(deftest ^:unit both-sides-get-a-many-to-many
  (let [all {:products products :tags tags :product-tags product-tags}]
    (is (= [{:key :tags :entity :tags :through :product-tags :table :product_tags
             :foreign-key :product-id :other-key :tag-id :label "Tags"}]
           (introspection/many-to-many :products all)))
    (is (= [:products] (map :key (introspection/many-to-many :tags all))))
    (testing "the join table is no child of either"
      (is (= [] (introspection/inverse-has-many :products all))))
    (testing "the join table is hidden from the sidebar, unless its config says otherwise"
      (is (true? (:sidebar-hidden (introspection/with-many-to-many :product-tags product-tags all))))
      (is (false? (:sidebar-hidden (introspection/with-many-to-many
                                     :product-tags (assoc product-tags :sidebar-hidden false) all)))))
    (testing "a join to an entity the admin does not show stays a child"
      (let [partial {:products products :product-tags product-tags}]
        (is (= [] (introspection/many-to-many :products partial)))
        (is (= [:product-tags] (map :entity (introspection/inverse-has-many :products partial))))))
    (testing "a key that is also a field's name is the join's"
      (let [all (assoc all :products (cfg "Products" [:id :name :tags] [#{:id}]))]
        (is (= [:product-tags-by-tag-id] (map :key (introspection/many-to-many :products all))))))))

(deftest ^:unit a-many-to-many-is-a-links-column
  (let [config (assoc products :many-to-many [{:key :tags :entity :tags :label "Tags"}])]
    (is (= :links (display/display-role config :tags)))
    (testing "and a default list column"
      (is (= [:name :tags :id] (display/default-list-fields config [:id :name]))))))

(deftest ^:unit the-form-names-its-links
  (let [config {:many-to-many [{:key :tags} {:key :authors}]}]
    (testing "ticked boxes, and one named with nothing ticked"
      (is (= [{"title" "x"} {:tags ["1" "2"] :authors []}]
             (forms/split-link-params config {"title" "x" "tags" ["1" "" "2"] "__links" ["tags" "authors"]}))))
    (testing "a key sent without __links, as JSON does"
      (is (= [{:title "x"} {:tags ["1"]}] (forms/split-link-params config {:title "x" :tags "1"}))))
    (testing "a relation not on the form is left alone"
      (is (= [{"title" "x"} {}] (forms/split-link-params config {"title" "x"}))))))

(deftest ^:unit a-link-filter-is-has-or-has-not
  (let [config (assoc products :list-fields [:name]
                      :many-to-many [{:key :tags :label "Tags" :options [["t1" "Red"]]}])
        html   #(str (h/html %))]
    (is (= [:name :tags] (vec (filters/filterable-fields config))))
    (is (= [:has :has-not] (map first (filters/get-operators-for-field-type :link))))
    (testing "the drawer picks a record by its title"
      (let [page (html (filters/filter-drawer :products config {}))]
        (is (str/includes? page "name=\"filters[tags][op]\""))
        (is (re-find #"<option[^>]*value=\"t1\"[^>]*>Red</option>" page))))
    (testing "the chip names it"
      (is (str/includes? (html (filters/filter-summary :products config {} {:tags {:op :has :value "t1"}}))
                         "Tags <t>filter-op-has</t> Red")))))
