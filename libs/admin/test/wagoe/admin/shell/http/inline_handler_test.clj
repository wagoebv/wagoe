(ns wagoe.admin.shell.http.inline-handler-test
  "An inline save or cancel answers with the cell the list rendered (ADR-040)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.admin.ports :as ports]
            [wagoe.admin.shell.http.handlers.inline :as inline]
            [wagoe.admin.shell.http.handlers.list :as list]))

(def ^:private id #uuid "00000000-0000-0000-0000-0000000000a1")
(def ^:private client-id #uuid "00000000-0000-0000-0000-0000000000c1")

(def ^:private entity-config
  {:label         "Invoices"
   :primary-key   :id
   :list-fields   [:number :client-id :status :amount]
   :search-fields [:number]
   :fields        {:id        {:type :uuid}
                   :number    {:type :string :label "Number"}
                   :client-id {:type :uuid :label "Client"}
                   :status    {:type    :enum :label "Status"
                               :options [[:sent "Sent"] [:paid "Paid"]]
                               :tones   {:paid :success}}
                   :amount    {:type :decimal :label "Amount"}
                   :secret    {:type :string}}
   :hide-fields   #{:secret}
   :relationships {:belongs-to [{:field :client-id :entity :clients}]}})

(def ^:private admin {:id #uuid "00000000-0000-0000-0000-000000000001" :role :admin :active true})

(def ^:private schema
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify ports/ISchemaProvider
    (validate-entity-exists [_ _] true)
    (get-entity-config [_ _] entity-config)
    (list-available-entities [_] [:invoices :clients])))

(defn- service
  "A service holding one invoice; `update-entity-field` writes to it."
  []
  (let [record (atom {:id id :number "INV-001" :client-id client-id
                      :status :sent :amount 1234.5M :secret "hash"})]
    #_{:clj-kondo/ignore [:missing-protocol-method]}
    (reify
      ports/IAdminService
      (list-entities [_ _ _] {:records [@record] :total-count 1 :page-size 20 :page-number 1})
      (get-entity [_ _ _] @record)
      (update-entity-field [_ _ _ field value] (swap! record assoc field value))

      ports/IListOverview
      (list-overview [_ _ _ _]
        {:now    (java.time.Instant/parse "2026-10-22T12:00:00Z")
         :titles {:client-id {(str client-id) "Veldhuis"}}}))))

(defn- list-body [svc]
  (:body ((list/entity-table-fragment-handler svc schema {})
          {:request-method :get
           :uri            "/web/admin/invoices/table"
           :headers        {"hx-request" "true" "hx-target" "entity-table-container"}
           :user           admin
           :path-params    {:entity "invoices"}
           :query-params   {}})))

(defn- cell
  "The `<td>` of `field` in `html`."
  [html field]
  (re-find (re-pattern (str "(?s)<td class=\"field-" (name field) " .*?</td>")) html))

(defn- inline-request [method field form]
  {:request-method method
   :headers        {"hx-request" "true"}
   :user           admin
   :path-params    {:entity "invoices" :id (str id) :field (name field)}
   :form-params    form})

(deftest ^:unit inline-cancel-answers-with-the-list-cell-test
  (let [svc (service)
        listed (list-body svc)]
    (doseq [field [:status :amount :client-id]]
      (testing (name field)
        (let [resp ((inline/cancel-inline-edit-handler svc schema {})
                    (inline-request :get field nil))]
          (is (some? (cell listed field)))
          (is (= (cell listed field) (str/trim (:body resp)))))))

    (testing "the cells carry the role, the label and the overview"
      (is (str/includes? (cell listed :client-id) "Veldhuis"))
      (is (str/includes? (cell listed :client-id) "c-relation"))
      (is (str/includes? (cell listed :status) ">Sent<"))
      (is (str/includes? (cell listed :status) "data-label=\"Status\""))
      (is (str/includes? (cell listed :amount) "c-money")))))

(deftest ^:unit inline-save-answers-with-the-list-cell-test
  (doseq [[field value] [[:status "paid"] [:amount "99.95"] [:client-id (str client-id)]]]
    (testing (name field)
      (let [svc  (service)
            resp ((inline/update-field-handler svc schema {})
                  (inline-request :patch field {(name field) value}))]
        (is (= 200 (:status resp)))
        (is (= "entityUpdated" (get-in resp [:headers "HX-Trigger"])))
        (is (= (cell (list-body svc) field) (str/trim (:body resp))))))))

(deftest ^:unit inline-refusal-stays-inside-the-cell-test
  (let [resp ((inline/update-field-handler (service) schema {})
              (inline-request :patch :amount {"amount" "forty"}))]
    (is (= 422 (:status resp)))
    (is (str/includes? (:body resp) "inline-edit-error"))
    (is (= "innerHTML" (get-in resp [:headers "HX-Reswap"]))
        "the form targets closest td; replacing the td leaves it none")))

(deftest ^:unit inline-title-cell-links-back-to-the-list-test
  (let [svc    (service)
        listed (list-body svc)
        list-url "/web/admin/invoices?sort=number&dir=desc"
        resp   ((inline/cancel-inline-edit-handler svc schema {})
                (assoc-in (inline-request :get :number nil)
                          [:headers "hx-current-url"] (str "http://localhost:3157" list-url)))]
    (is (str/includes? (cell listed :number) (str "/web/admin/invoices/" id)))
    (is (str/includes? (:body resp)
                       (str "/web/admin/invoices/" id "?return_to="
                            (java.net.URLEncoder/encode list-url "UTF-8"))))
    (testing "another page is not a list to return to"
      (let [resp ((inline/cancel-inline-edit-handler svc schema {})
                  (assoc-in (inline-request :get :number nil)
                            [:headers "hx-current-url"] "http://localhost/web/admin/clients"))]
        (is (not (str/includes? (:body resp) "return_to")))))))

(deftest ^:unit inline-never-shows-a-hidden-field-test
  (let [svc (service)]
    (doseq [handler [inline/cancel-inline-edit-handler inline/inline-edit-widget-handler]]
      (is (= :not-found
             (try ((handler svc schema {}) (inline-request :get :secret nil))
                  nil
                  (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))))
