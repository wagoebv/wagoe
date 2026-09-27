(ns wagoe.admin.shell.entity-config-keys-test
  "An entity config key the admin does not know fails at startup, with its
   path (BOU-563). A misplaced `:has-many` used to be ignored, and the admin
   quietly showed a read-only detected panel instead."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.admin.shell.module-wiring]
            [wagoe.admin.shell.schema-repository :as schema-repo]
            [integrant.core :as ig]))

(def ^:private has-many
  [{:entity :invoice-lines :table :invoice_lines :foreign-key :invoice-id
    :label "Lines" :fields [:description] :editable true :min 1}])

(defn- config [entity-config]
  {:entity-discovery {:mode :allowlist :allowlist #{:invoices :invoice-lines}}
   :entities         {:invoices entity-config}})

(defn- startup-error [entity-config]
  (try (schema-repo/create-schema-repository nil (config entity-config))
       nil
       (catch clojure.lang.ExceptionInfo e e)))

(deftest ^:unit a-known-config-starts
  (is (nil? (startup-error {:label           "Invoices"
                            :soft-delete     true
                            :list-fields     [:number]
                            :fields          {:number {:type :string :label "Number" :width 2}}
                            :field-groups    [{:id :main :label "Main" :fields [:number]}]
                            :has-many        has-many
                            :parent-context  {:label "Customer" :fields [:name]}
                            :permissions     {:create false :create-hint "elsewhere"}
                            :workflow        {:entity-type :invoice}
                            :ui              {:field-grouping {:other-label "More"}}}))))

(deftest ^:unit an-unknown-key-fails-at-startup-with-its-path
  (testing "a misplaced :has-many inside :fields"
    (let [e (startup-error {:label "Invoices" :fields {:has-many has-many}})]
      (is (some? e))
      (is (= :configuration-error (:type (ex-data e))))
      (is (str/includes? (ex-message e) "[:entities :invoices :fields :has-many]"))))

  (testing "a misplaced :has-many inside :parent-context"
    (let [e (startup-error {:parent-context {:label "X" :fields [] :has-many has-many}})]
      (is (str/includes? (ex-message e) "[:entities :invoices :parent-context :has-many]"))))

  (testing "a typo at the top of an entity"
    (let [e (startup-error {:has-manny has-many})]
      (is (str/includes? (ex-message e) "[:entities :invoices :has-manny]"))))

  (testing "a typo inside a has-many entry"
    (let [e (startup-error {:has-many [(assoc (first has-many) :foreign-kye :invoice-id)]})]
      (is (str/includes? (ex-message e) "[:entities :invoices :has-many 0 :foreign-kye]")))))

(deftest ^:unit the-schema-provider-component-refuses-to-start
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"\[:entities :invoices :fields :has-many\]"
                        (ig/init-key :wagoe/admin-schema-provider
                                     {:db-ctx nil
                                      :config (config {:fields {:has-many has-many}})}))))

(deftest ^:unit a-missing-include-names-the-file
  ;; The uberjar could not resolve #include, and the placeholder Aero leaves
  ;; reached this schema as an entity called :aero/missing-include.
  (let [e (try (schema-repo/create-schema-repository
                nil {:entity-discovery {:mode :allowlist :allowlist #{}}
                     :entities {:aero/missing-include "admin/users.edn"}})
               nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= :configuration-error (:type (ex-data e))))
    (is (str/includes? (ex-message e) "include not found: admin/users.edn"))))
