(ns wagoe.admin.core.display-test
  "Display roles for the admin overview (ADR-040)."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [wagoe.admin.core.display :as display]
            [wagoe.admin.schema :as schema]))

(defn- entity
  "An entity config from {field type}, with optional extra keys."
  [fields & {:as extra}]
  (merge {:primary-key :id
          :fields      (into {} (for [[f t] fields] [f {:name f :type t}]))}
         extra))

(deftest ^:unit every-field-type-has-a-role
  (doseq [type (m/children (m/schema schema/FieldType))]
    (let [cfg (entity {:id :uuid :x type})]
      (is (contains? display/roles (display/display-role cfg :x))
          (str type " maps to a role")))))

(deftest ^:unit roles-follow-the-precedence
  (let [cfg (entity {:id          :uuid
                     :name        :string
                     :client-id   :uuid
                     :status      :enum
                     :amount      :decimal
                     :vat-pct     :decimal
                     :quantity    :int
                     :email       :string
                     :website     :string
                     :code        :string
                     :notes       :text
                     :paid        :boolean
                     :due-on      :date
                     :created-at  :instant
                     :payload     :json}
                    :relationships {:belongs-to [{:field :client-id :entity :clients}]}
                    :has-many [{:entity :invoices :foreign-key :order-id :label "Invoices"}])]
    (doseq [[field role source] [[:name       :title      "title field"]
                                 [:client-id  :relation   "belongs-to clients"]
                                 [:invoices   :count      "has-many invoices"]
                                 [:status     :enum       "type :enum"]
                                 [:amount     :money      "name rule: amount"]
                                 [:vat-pct    :percent    "name rule: pct"]
                                 [:quantity   :number     "type default"]
                                 [:email      :email      "name rule: mail"]
                                 [:website    :url        "name rule: website"]
                                 [:code       :identifier "name rule: code"]
                                 [:id         :identifier "type default"]
                                 [:notes      :text       "type default"]
                                 [:paid       :boolean    "type default"]
                                 [:due-on     :date       "type default"]
                                 [:created-at :datetime   "type default"]
                                 [:payload    :text       "type default"]]]
      (is (= [role source] (display/role-with-source cfg field)) (name field))))

  (testing "an explicit :display wins over everything"
    (let [cfg (entity {:id :uuid :name :string :rate :decimal})]
      (is (= [:number "explicit"]
             (display/role-with-source (assoc-in cfg [:fields :rate :display] :number) :rate)))
      (is (= [:text "explicit"]
             (display/role-with-source (assoc-in cfg [:fields :name :display] :text) :name)))))

  (testing "a name rule applies only to the type it names"
    (let [cfg (entity {:id :uuid :amount :string :total-url :int})]
      (is (= :text (display/display-role cfg :amount)))
      (is (= :number (display/display-role cfg :total-url)))))

  (testing "name rules match whole words"
    (let [cfg (entity {:id :uuid :pricelist-name :decimal :unit-price :decimal})]
      (is (= :number (display/display-role cfg :pricelist-name)))
      (is (= :money (display/display-role cfg :unit-price))))))

(deftest ^:unit the-title-field-is-what-a-record-is-called
  (is (= :name (display/title-field (entity {:id :uuid :email :string :name :string}))))
  (is (= :email (display/title-field (entity {:id :uuid :email :string}))))
  (is (= :sku (display/title-field (entity {:id :uuid :sku :string :hm-order-id :uuid})))
      "a string identifier before the primary key")
  (is (= :id (display/title-field (entity {:id :uuid :count :int}))))
  (is (= :label (display/title-field (entity {:id :uuid :name :string :label :string}
                                             :title-field :label))))
  (is (= :name (display/title-field (entity {:id :uuid :name :string} :title-field :missing)))
      "an explicit title field the entity lacks is ignored")
  (is (= :title (display/title-field (entity {:id :uuid :name :string :title :string}
                                             :hide-fields #{:name})))
      "a hidden field is not a title"))

(deftest ^:unit the-facet-is-the-status-enum
  (is (= :status (display/facet-field (entity {:id :uuid :status :enum}))))
  (is (= :state (display/facet-field (entity {:id :uuid :state :enum}))))
  (is (nil? (display/facet-field (entity {:id :uuid :status :string})))
      "a free-text status is not a facet")
  (is (= :kind (display/facet-field (entity {:id :uuid :kind :enum :status :enum} :facet :kind))))
  (is (= :status (display/facet-field (entity {:id :uuid :status :enum :note :string} :facet :note)))
      "an explicit facet that is not an enum is ignored"))

(deftest ^:unit default-list-fields-follow-the-roles
  (let [cfg   (entity {:id :uuid :notes :text :created-at :instant :updated-at :instant
                       :amount :decimal :status :enum :kind :enum :client-id :uuid
                       :number :string :paid :boolean :payload :json}
                      :relationships {:belongs-to [{:field :client-id :entity :clients}]})
        order [:id :notes :created-at :updated-at :amount :status :kind :client-id :number :paid :payload]]
    (is (= [:number :client-id :status :kind :amount :created-at :paid]
           (display/default-list-fields cfg order))))

  (testing "at most seven columns, and hidden fields never"
    (let [fields (into {:id :uuid :name :string} (for [i (range 10)] [(keyword (str "n" i)) :int]))
          cfg    (entity fields :hide-fields #{:n0})
          order  (into [:id :name] (for [i (range 10)] (keyword (str "n" i))))
          cols   (display/default-list-fields cfg order)]
      (is (= 7 (count cols)))
      (is (= :name (first cols)))
      (is (not (some #{:n0} cols))))))

(deftest ^:unit with-display-adds-the-derived-keys
  (let [cfg (display/with-display (entity {:id :uuid :name :string :status :enum :amount :decimal}
                                          :list-fields [:name :amount])
                                  {:derive-list-fields? false})]
    (is (= :name (:title-field cfg)))
    (is (= :status (:facet cfg)))
    (is (= [:name :amount] (:list-fields cfg)) "configured list fields are kept")
    (is (= {:name :title :amount :money :status :enum :id :identifier} (:display-roles cfg))))
  (let [cfg (display/with-display (entity {:id :uuid :name :string :status :enum})
                                  {:derive-list-fields? true :field-order [:id :name :status]})]
    (is (= [:name :status] (:list-fields cfg)))))

(deftest ^:unit explain-says-where-each-role-came-from
  (let [cfg       (display/with-display (entity {:id :uuid :name :string :total :decimal})
                                        {:derive-list-fields? true :field-order [:id :name :total]})
        explained (display/explain cfg)
        text      (display/format-explain :orders explained)]
    (is (= :name (:title-field explained)))
    (is (= {:field :total :type :decimal :role :money :source "name rule: total" :list? true}
           (some #(when (= :total (:field %)) %) (:fields explained))))
    (is (re-find #"\* total\s+money\s+name rule: total" text))))
