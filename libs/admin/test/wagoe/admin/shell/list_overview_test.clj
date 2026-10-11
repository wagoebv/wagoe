(ns wagoe.admin.shell.list-overview-test
  "The list overview on H2 (ADR-040): titles, counts, facets and summaries in
   a number of queries that does not grow with the page."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [wagoe.admin.ports :as ports]
            [wagoe.admin.shell.http.handlers.list :as list]
            [wagoe.admin.shell.schema-repository :as schema-repo]
            [wagoe.admin.shell.service :as service]
            [wagoe.observability.errors.shell.adapters.no-op :as error-reporting-no-op]
            [wagoe.observability.logging.shell.adapters.no-op :as logging-no-op]
            [wagoe.platform.database :as db]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]))

(def ^:private config
  {:enabled?         true
   :base-path        "/web/admin"
   :require-role     :admin
   :entity-discovery {:mode :allowlist :allowlist #{:ov-invoices :ov-clients :ov-lines}}
   :entities         {:ov-invoices {:label       "Invoices"
                                    :list-fields [:number :ov-client-id :status :amount :ov-lines]
                                    :search-fields [:number]
                                    :fields      {:status {:type    :enum
                                                           :options [[:draft "Draft"] [:sent "Sent"] [:paid "Paid"]]
                                                           :tones   {:sent :info :paid :success}}
                                                  :amount {:total true}}
                                    :summary     [{:label "Outstanding" :agg :sum :field :amount
                                                   :where {:status {:op :in :values [:sent]}}}
                                                  {:label "Invoices" :agg :count}]
                                    :has-many    [{:entity      :ov-lines
                                                   :table       :ov_lines
                                                   :foreign-key :ov-invoice-id
                                                   :label       "Lines"}]}
                      :ov-clients  {:label "Clients"}
                      :ov-lines    {:label "Lines"}}
   :pagination       {:default-page-size 20 :max-page-size 200}})

(def ^:private admin {:id #uuid "00000000-0000-0000-0000-000000000001" :role :admin :active true})

(def ^:private sys (atom nil))

(defn- exec! [sql] (db/execute-update! (:db @sys) {:raw sql}))

(use-fixtures :once
  (fn [f]
    (let [db-ctx (db-factory/db-context {:adapter       :h2
                                         :database-path "mem:admin_list_overview;DB_CLOSE_DELAY=-1"
                                         :pool          {:minimum-idle 1 :maximum-pool-size 2}})]
      (doseq [sql ["CREATE TABLE ov_clients (id UUID PRIMARY KEY, name VARCHAR(100) NOT NULL)"
                   "CREATE TABLE ov_invoices (id UUID PRIMARY KEY, number VARCHAR(20) NOT NULL,
                      ov_client_id UUID, status VARCHAR(20), amount DECIMAL(12,2))"
                   "CREATE TABLE ov_lines (id UUID PRIMARY KEY, ov_invoice_id UUID NOT NULL)"]]
        (db/execute-update! db-ctx {:raw sql}))
      (let [sp  (schema-repo/create-schema-repository db-ctx config)
            svc (service/create-admin-service db-ctx sp
                                              (logging-no-op/create-logging-component {})
                                              (error-reporting-no-op/create-error-reporting-component {})
                                              config)]
        (reset! sys {:db db-ctx :sp sp :svc svc})
        (try (f)
             (finally
               (doseq [t ["ov_lines" "ov_invoices" "ov_clients"]]
                 (db/execute-update! db-ctx {:raw (str "DROP TABLE " t)}))
               (db-factory/close-db-context! db-ctx)))))))

(defn- reset-data!
  "`n` invoices for two clients, cycling draft/sent/paid, each with i mod 3
   lines. Returns the client ids."
  [n]
  (exec! "DELETE FROM ov_lines")
  (exec! "DELETE FROM ov_invoices")
  (exec! "DELETE FROM ov_clients")
  (let [a (random-uuid) b (random-uuid)]
    (exec! (str "INSERT INTO ov_clients VALUES ('" a "', 'Veldhuis'), ('" b "', 'Kramer')"))
    (doseq [i (range n)
            :let [id     (random-uuid)
                  status (["draft" "sent" "paid"] (mod i 3))]]
      (exec! (format "INSERT INTO ov_invoices VALUES ('%s', 'INV-%03d', '%s', '%s', %d.50)"
                     id i (if (even? i) a b) status (* 10 (inc i))))
      (dotimes [_ (mod i 3)]
        (exec! (format "INSERT INTO ov_lines VALUES ('%s', '%s')" (random-uuid) id))))
    {:a a :b b}))

(defn- page [options]
  (:records (ports/list-entities (:svc @sys) :ov-invoices (merge {:limit 200} options))))

(defn- overview [options]
  (ports/list-overview (:svc @sys) :ov-invoices options (page options)))

(defn- counting-queries
  "The overview for `options` and the number of queries it ran."
  [options]
  (let [records (page options)
        n       (atom 0)
        q       db/execute-query!
        one     db/execute-one!]
    (with-redefs [db/execute-query! (fn [& args] (swap! n inc) (apply q args))
                  db/execute-one!   (fn [& args] (swap! n inc) (apply one args))]
      (ports/list-overview (:svc @sys) :ov-invoices options records)
      @n)))

(deftest ^:contract the-overview-costs-the-same-for-any-page-size
  ;; one relation, one has-many, the facet, two summaries
  (doseq [n [1 50 200]]
    (reset-data! n)
    (is (= 5 (counting-queries {})) (str n " invoices"))))

(deftest ^:contract the-overview-holds-what-the-cells-need
  (let [{:keys [a b]} (reset-data! 9)
        ov            (overview {})
        records       (page {})]
    (testing "relation titles, keyed by id string"
      (is (= {(str a) "Veldhuis" (str b) "Kramer"} (get-in ov [:titles :ov-client-id]))))

    (testing "has-many counts per parent"
      (let [lines (get-in ov [:counts :ov-lines])]
        (is (= 9 (count records)))
        (is (= (reduce + (vals lines)) (+ 0 1 2 0 1 2 0 1 2)))
        (is (every? pos? (vals lines)) "parents without children are simply absent")))

    (testing "facet counts over the filtered set"
      (is (= {"draft" 3 "sent" 3 "paid" 3} (get-in ov [:facets :status]))))

    (testing "summaries"
      (let [[outstanding total] (:summary ov)]
        (is (= "Outstanding" (:label outstanding)))
        (is (= :money (:role outstanding)))
        ;; sent are i = 1, 4, 7: amounts 20.50 + 50.50 + 80.50
        (is (== 151.50M (:value outstanding)))
        (is (= {:label "Invoices" :value 9 :role :number} (select-keys total [:label :value :role])))))

    (is (instance? java.time.Instant (:now ov)))))

(deftest ^:contract facet-counts-leave-out-their-own-filter-only
  (reset-data! 9)
  (testing "filtering on the facet keeps every tab's count"
    (is (= {"draft" 3 "sent" 3 "paid" 3}
           (get-in (overview {:filters {:status "sent"}}) [:facets :status]))))
  (testing "search and other filters narrow them"
    (is (= {"sent" 1} (get-in (overview {:search "INV-001"}) [:facets :status])))
    (is (= {"draft" 1}
           (get-in (overview {:filters {:status "sent"
                                        :number {:op :starts-with :value "INV-000"}}})
                   [:facets :status])))))

(deftest ^:contract summaries-follow-the-list-filters
  (reset-data! 9)
  (let [[outstanding total] (:summary (overview {:filters {:status "sent"}}))]
    (is (== 151.50M (:value outstanding)))
    (is (= 3 (:value total)) "the count is the filtered list's")))

(deftest ^:contract the-list-page-shows-the-overview
  (reset-data! 3)
  (let [body (:body ((list/entity-list-handler (:svc @sys) (:sp @sys) config)
                     {:request-method :get
                      :uri            "/web/admin/ov-invoices"
                      :headers        {}
                      :user           admin
                      :session        {:user admin}
                      :path-params    {:entity "ov-invoices"}
                      :query-params   {}}))]
    (is (str/includes? body ">Veldhuis</a>") "relation title instead of the UUID")
    (is (str/includes? body "c-money"))
    (is (str/includes? body "facet-tab"))
    (is (str/includes? body "Outstanding"))
    (is (str/includes? body "totals-row"))))
