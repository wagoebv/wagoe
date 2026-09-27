(ns wagoe.admin.shell.create-with-children-test
  "BOU-570: a parent whose editable has-many has a :min is created with its
   first children, in one form and one transaction; fewer than :min is
   refused. A parent without one keeps the create-then-add flow."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [integrant.core :as ig]
            [wagoe.admin.core.forms :as forms]
            [wagoe.admin.ports :as ports]
            [wagoe.admin.shell.http.handlers.crud :as crud]
            [wagoe.admin.shell.http.handlers.detail :as detail]
            [wagoe.admin.shell.schema-repository :as schema-repo]
            [wagoe.admin.shell.service :as service]
            [wagoe.events.ports :as events]
            [wagoe.events.shell.module-wiring]
            [wagoe.i18n.shell.catalogue :as catalogue]
            [wagoe.platform.database :as db]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.test.logging :refer [with-silent-logging]]))

^{:kaocha.testable/meta {:integration true :admin true}}

(def ^:private lines-rel
  {:entity :nc-lines :table :nc_lines :foreign-key :nc-invoice-id
   :label "Lines" :fields [:description] :editable true :on-delete :cascade :min 1})

(def ^:private config
  {:entity-discovery {:mode :allowlist :allowlist #{:nc-invoices :nc-lines :nc-drafts}}
   :entities         {:nc-invoices {:label "Invoices" :has-many [lines-rel]}
                      :nc-lines    {:label "Line"}
                      ;; The same table, without a :min: today's flow.
                      :nc-drafts   {:label "Drafts" :table-name :nc-invoices
                                    :has-many [(dissoc lines-rel :min)]}}
   :pagination       {:default-page-size 20 :max-page-size 200}})

(def ^:private ^:dynamic *db* nil)

(use-fixtures :once
  (fn [f]
    (let [ctx (db-factory/db-context {:adapter       :h2
                                      :database-path "mem:admin_nested_create;DB_CLOSE_DELAY=-1"
                                      :pool          {:minimum-idle 1 :maximum-pool-size 3}})]
      (db/execute-update! ctx {:raw "CREATE TABLE nc_invoices (id UUID PRIMARY KEY, number VARCHAR(20) NOT NULL)"})
      (db/execute-update! ctx {:raw "CREATE TABLE nc_lines (id UUID PRIMARY KEY,
                                                           nc_invoice_id UUID NOT NULL REFERENCES nc_invoices(id),
                                                           description VARCHAR(50) NOT NULL,
                                                           qty INT DEFAULT 1 NOT NULL)"})
      (try (binding [*db* ctx] (f))
           (finally
             (db/execute-update! ctx {:raw "DROP TABLE nc_lines"})
             (db/execute-update! ctx {:raw "DROP TABLE nc_invoices"})
             (db-factory/close-db-context! ctx))))))

(use-fixtures :each
  (fn [f]
    (db/execute-update! *db* {:raw "DELETE FROM nc_lines"})
    (db/execute-update! *db* {:raw "DELETE FROM nc_invoices"})
    (f)))

(defn- system [& [bus]]
  (let [sp (schema-repo/create-schema-repository *db* config)]
    {:sp sp :svc (service/create-admin-service *db* sp nil nil config bus)}))

(defn- count-rows [table]
  (:total (db/execute-one! *db* {:select [[:%count.* :total]] :from [table]})))

(def ^:private i18n-catalogue
  (catalogue/create-map-catalogue (catalogue/load-catalogue "wagoe/i18n/translations")))

(def ^:private admin {:id (random-uuid) :role :admin :active true})

(defn- request [method entity & [form path]]
  {:request-method      method
   :uri                 (str "/web/admin/" (name entity))
   :user                admin
   :headers             {}
   :path-params         (merge {:entity (name entity)} path)
   :form-params         form
   :i18n/catalogue      i18n-catalogue
   :i18n/default-locale :en})

(defn- line [index field]
  (forms/child-param :nc-lines index field))

(deftest ^:integration the-service-creates-parent-and-children-together
  (let [{:keys [svc]} (system)
        {:keys [record children]} (ports/create-entity-with-children
                                   svc :nc-invoices {:number "INV-1"}
                                   {:nc-lines [{:description "Hours"} {:description "Travel" :qty 2}]})]
    (is (= "INV-1" (:number record)))
    (is (= 2 (count children)))
    (is (every? #(= (:id record) (get-in % [:record :nc-invoice-id])) children))
    (is (= 1 (:qty (:record (first children)))) "a child's column default applies")))

(deftest ^:integration the-service-refuses-fewer-than-min
  (let [{:keys [svc]} (system)
        e (try (ports/create-entity-with-children svc :nc-invoices {:number "INV-2"} {:nc-lines []})
               nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= :validation-error (:type (ex-data e))))
    (is (= {:nc-lines {:min 1 :count 0 :label "Lines"}} (:too-few (ex-data e))))
    (is (zero? (count-rows :nc_invoices)))))

(deftest ^:integration a-failing-child-rolls-the-parent-back
  (let [{:keys [svc]} (system)
        e (with-silent-logging
            (try (ports/create-entity-with-children svc :nc-invoices {:number "INV-3"}
                                                    {:nc-lines [{:description "ok"} {:qty 3}]})
                 nil
                 (catch clojure.lang.ExceptionInfo e e)))]
    (is (= :validation-error (:type (ex-data e))))
    (is (= {:entity :nc-lines :index 1} (:child (ex-data e))) "names the row")
    (is (zero? (count-rows :nc_invoices)))
    (is (zero? (count-rows :nc_lines)))))

(deftest ^:integration each-child-publishes-its-created-event
  (let [bus  (ig/init-key :wagoe/events {:provider :memory})
        seen (atom [])]
    (try
      (events/subscribe! bus :admin #(swap! seen conj %))
      (let [{:keys [svc]} (system bus)]
        (ports/create-entity-with-children svc :nc-invoices {:number "INV-4"}
                                           {:nc-lines [{:description "a"} {:description "b"}]})
        (loop [n 0]
          (when (and (< n 100) (< (count @seen) 3)) (Thread/sleep 20) (recur (inc n))))
        (is (= {:nc-invoices 1 :nc-lines 2}
               (frequencies (map #(get-in % [:payload :entity])
                                 (filter #(= :admin/entity-created (:type %)) @seen))))))
      (finally (ig/halt-key! :wagoe/events bus)))))

(deftest ^:integration the-create-form-has-min-child-rows
  (let [{:keys [sp svc]} (system)
        body (str (:body ((detail/new-entity-handler svc sp config) (request :get :nc-invoices))))]
    (is (str/includes? body (str "name=\"" (line 0 :description) "\"")))
    (is (not (str/includes? body (line 1 :description))) "as many as :min")
    (is (not (str/includes? body (str "name=\"" (line 0 :nc-invoice-id) "\""))) "the parent sets the foreign key")
    (is (str/includes? body "hx-get=\"/web/admin/nc-invoices/new/rows/nc-lines\"") "more are added through HTMX")
    (testing "a parent without :min keeps today's form"
      (let [body (str (:body ((detail/new-entity-handler svc sp config) (request :get :nc-drafts))))]
        (is (not (str/includes? body "__child.")))))))

(deftest ^:integration the-row-endpoint-answers-a-new-row
  (let [{:keys [sp svc]} (system)
        handler (detail/new-child-row-handler svc sp config)
        row     #(str (:body (handler (request :get :nc-invoices nil {:child "nc-lines"}))))
        index   #(second (re-find #"__child\.nc-lines\.(\d+)\.description" %))
        [a b]   [(row) (row)]]
    (is (some? (index a)))
    (is (not= (index a) (index b)) "each row its own index")
    (is (str/includes? a "child-row"))))

(deftest ^:integration the-form-creates-parent-and-children
  (let [{:keys [sp svc]} (system)
        response ((crud/create-entity-handler svc sp config)
                  (request :post :nc-invoices {"number" "INV-5"
                                               (line 0 :description) "Hours"
                                               (line 0 :qty) "4"
                                               (line 7 :description) "Travel"
                                               (line 7 :qty) ""}))]
    (is (< (:status response 200) 400) (str "status was " (:status response)))
    (is (= 1 (count-rows :nc_invoices)))
    (is (= #{["Hours" 4] ["Travel" 1]}
           (set (map (juxt :description :qty)
                     (db/execute-query! *db* {:select [:*] :from [:nc_lines]})))))))

(deftest ^:integration the-form-refuses-fewer-than-min
  (let [{:keys [sp svc]} (system)
        response ((crud/create-entity-handler svc sp config)
                  (request :post :nc-invoices {"number" "INV-6" (line 0 :description) "  "}))
        body     (str (:body response))]
    (is (= 422 (:status response)))
    (is (str/includes? body "Lines: at least 1 required, 0 given"))
    (is (str/includes? body (str "name=\"" (line 0 :description) "\"")) "the row is still there to fill in")
    (is (zero? (count-rows :nc_invoices)))))

(deftest ^:integration a-bad-child-row-is-marked-and-nothing-is-written
  (let [{:keys [sp svc]} (system)
        response ((crud/create-entity-handler svc sp config)
                  (request :post :nc-invoices {"number" "INV-7"
                                               (line 0 :description) "Hours"
                                               (line 3 :description) ""
                                               (line 3 :qty) "forty"}))
        body     (str (:body response))]
    (is (= 422 (:status response)))
    (is (re-find (re-pattern (str "has-errors\"><label for=\"" (java.util.regex.Pattern/quote (line 3 :qty)) "\"")) body)
        "the field in its row carries the error")
    (is (str/includes? body "value=\"forty\"") "as typed")
    (is (zero? (count-rows :nc_invoices)))
    (is (zero? (count-rows :nc_lines)))))
