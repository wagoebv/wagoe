(ns wagoe.scaffolder.get-children-test
  "GET answers with what POST took (BOU-581): an invoice's line items under the
   key its create takes them, and, for an entity with a workflow, its instance
   and state. The list embeds children only on ?include=, reading each child
   entity once for the page."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [integrant.core :as ig]
            [malli.core :as m]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.scaffolder.cli :as cli]
            [wagoe.scaffolder.multi-entity-test :as multi]
            [wagoe.scaffolder.shell.service :as service]
            [wagoe.workflow.shell.module-wiring]))

(def ^:private svc (service/create-scaffolder-service))

(defn- module!
  "Module billing under `base`: an Invoice with a workflow, line items it
   keeps at least one of, and payments, which it need not have."
  [base]
  (let [dir (#'multi/temp-dir)
        run #(with-out-str (cli/run-cli! svc (into % ["--base-ns" base "--output-dir" (.getPath dir)])))]
    (run ["generate" "--module-name" "billing" "--entity" "Invoice" "--field" "number:string:required"
          "--workflow" "status:draft>sent>paid"])
    (run ["entity" "--module-name" "billing" "--entity" "InvoiceLineItem" "--belongs-to" "invoice" "--min" "1"
          "--field" "description:string:required"])
    (run ["entity" "--module-name" "billing" "--entity" "Payment" "--belongs-to" "invoice"
          "--field" "amount:int:required"])
    ;; After the entity: the field reaches the invoice's response schema too.
    (run ["field" "--module-name" "billing" "--entity" "InvoiceLineItem" "--name" "quantity" "--type" "int"])
    (let [{:keys [fail error]} (#'multi/load-and-test! dir)]
      (is (= 0 fail))
      (is (= 0 error)))
    dir))

(defn- boot [dir base ctx]
  (doseq [[path sql] (#'multi/files-under dir)
          :when (str/ends-with? path ".up.sql")
          s (#'multi/statements sql)]
    (jdbc/execute! (:datasource ctx) [s]))
  (let [build (ns-resolve (symbol (str base ".billing.shell.module-wiring")) 'ig-config)
        graph (:components (build {:enabled? true} {:config {:active {:wagoe/workflow {}}}}))]
    (ig/init (merge (walk/postwalk-replace {(ig/ref :wagoe/db-context) ctx} graph)
                    {:wagoe/workflow-db-schema {:ctx ctx :profile :test}
                     :wagoe/workflow {:db-ctx ctx :db-schema (ig/ref :wagoe/workflow-db-schema)
                                      :guard-registry {}}}))))

(defn- route-data
  "The data of the route at `path` in `routes`."
  [routes path]
  (some (fn [[p data]] (when (= p path) data)) routes))

(deftest ^:integration get-answers-with-the-children-and-the-workflow
  (let [base   "bou581a"
        dir    (module! base)
        ctx    (db-factory/db-context {:adapter :h2 :database-path (str "mem:bou581a" (System/nanoTime))
                                       :pool {:minimum-idle 1 :maximum-pool-size 4}})
        system (boot dir base ctx)
        routes (:api (:wagoe/billing-routes system))
        call   (#'multi/http-caller routes)
        post!  (fn [number lines]
                 (let [resp (call :post "/invoices" {:number number :invoice-line-items lines})]
                   (is (= 201 (:status resp)) (pr-str resp))
                   (:body resp)))]
    (try
      (let [created (post! "A-1" [{:description "Consulting" :quantity 2} {:description "Travel" :quantity 1}])
            id      (:id created)
            resp    (call :get (str "/invoices/" id) nil)
            body    (:body resp)]
        (is (= 200 (:status resp)) (pr-str resp))

        (testing "GET has the line items POST created, under the same key"
          (is (= (set (map :id (:invoice-line-items created)))
                 (set (map :id (:invoice-line-items body)))))
          (is (= #{"Consulting" "Travel"} (set (map :description (:invoice-line-items body)))))
          (is (= #{2 1} (set (map :quantity (:invoice-line-items body))))))

        (testing "and a child it has none of as an empty list"
          (is (= [] (:payments body))))

        (testing "and its workflow: the instance and the state"
          (let [row (jdbc/execute-one! (:datasource ctx)
                                       ["SELECT id, current_state FROM workflow_instances WHERE entity_id = ?" id]
                                       {:builder-fn rs/as-unqualified-lower-maps})]
            (is (some? row))
            (is (= {:instance-id (str (:id row)) :state "draft"} (:workflow body))))))

      (testing "GET writes nothing: without an instance, workflow is null and none is started"
        ;; A row the admin wrote while the event bus was down has no instance.
        (let [id        (:id (post! "A-0" [{:description "Zero" :quantity 1}]))
              instances #(:n (jdbc/execute-one! (:datasource ctx)
                                                ["SELECT COUNT(*) AS n FROM workflow_instances WHERE entity_id = ?" id]
                                                {:builder-fn rs/as-unqualified-lower-maps}))]
          (jdbc/execute! (:datasource ctx) ["DELETE FROM workflow_instances WHERE entity_id = ?" id])
          (let [resp (call :get (str "/invoices/" id) nil)]
            (is (= 200 (:status resp)) (pr-str resp))
            (is (contains? (:body resp) :workflow))
            (is (nil? (:workflow (:body resp)))))
          (is (= 0 (instances)) "the GET started no workflow")
          (call :delete (str "/invoices/" id) nil))
        (testing "and a seeded row whose status the workflow does not have still reads"
          (let [id (java.util.UUID/randomUUID)]
            (jdbc/execute! (:datasource ctx) ["INSERT INTO invoices (id, number, status, created_at) VALUES (?, 'S-1', 'void', CURRENT_TIMESTAMP)" id])
            (let [resp (call :get (str "/invoices/" id) nil)]
              (is (= 200 (:status resp)) (pr-str resp))
              (is (nil? (:workflow (:body resp)))))
            (jdbc/execute! (:datasource ctx) ["DELETE FROM invoices WHERE id = ?" id]))))

      (post! "A-2" [{:description "One" :quantity 1}])

      (testing "the list leaves the children out"
        (let [resp (call :get "/invoices" nil)]
          (is (= 200 (:status resp)))
          (is (= 2 (count (:body resp))))
          (is (not-any? #(contains? % :invoice-line-items) (:body resp)))))

      (testing "and embeds the ones ?include= names"
        (let [resp  (call :get "/invoices" nil "include=invoice-line-items")
              lines (into {} (map (juxt :number #(map :description (:invoice-line-items %)))) (:body resp))]
          (is (= 200 (:status resp)) (pr-str resp))
          (is (= {"A-1" #{"Consulting" "Travel"} "A-2" #{"One"}} (update-vals lines set)))
          (is (not-any? #(contains? % :payments) (:body resp)))
          (is (every? #(contains? % :payments)
                      (:body (call :get "/invoices" nil "include=invoice-line-items,payments"))))))

      (testing "one it does not have is a 400"
        (let [resp (call :get "/invoices" nil "include=invoice-line-items,nope")]
          (is (= 400 (:status resp)) (pr-str resp))
          (is (str/includes? (pr-str (:body resp)) "nope"))))

      (testing "each child entity is read once for the page, not once per invoice"
        (let [calls   (atom [])
              service (update (:wagoe/billing-service system) :children update-vals
                              (fn [child] (update child :find (fn [f] (fn [ids] (swap! calls conj ids) (f ids))))))
              listed  ((ns-resolve (symbol (str base ".billing.ports")) 'list-invoices)
                       service {:limit 20 :offset 0 :include #{:invoice-line-items :payments}})]
          (is (= 2 (count listed)))
          (is (= 2 (count @calls)) "one read per child entity")
          (is (every? #(= 2 (count %)) @calls) "each for both invoices")))

      (testing "the response schema and the swagger say so"
        (let [invoice (deref (ns-resolve (symbol (str base ".billing.schema")) 'Invoice))
              entries (into {} (map (fn [[k props s]] [k [props (m/form s)]])) (m/children invoice))]
          (is (= {:optional true} (first (entries :invoice-line-items))))
          (is (str/includes? (pr-str (second (entries :invoice-line-items))) "[:quantity")
              "a field added to the line items reaches it")
          (is (= {:optional true} (first (entries :workflow)))))
        (let [by-id (get-in (route-data routes "/invoices/:id") [:get :swagger :responses 200 :schema :properties])
              list  (route-data routes "/invoices")]
          (is (contains? by-id :invoice-line-items))
          (is (contains? by-id :workflow))
          (is (some #(= "include" (:name %)) (get-in list [:get :swagger :parameters])))))

      (finally
        (ig/halt! system)
        (db-factory/close-db-context! ctx)))))

(deftest ^:unit a-child-added-to-a-new-module-leaves-its-service-and-http-alone
  ;; The parent's service reads its children through the wiring, so adding one
  ;; touches neither file.
  (let [base "bou581b"
        dir  (#'multi/temp-dir)
        run  #(with-out-str (cli/run-cli! svc (into % ["--base-ns" base "--output-dir" (.getPath dir)])))
        read #(slurp (io/file dir "src" base "billing/shell" %))]
    (run ["generate" "--module-name" "billing" "--entity" "Invoice" "--field" "number:string:required"])
    (let [before (mapv read ["service.clj" "http.clj"])]
      (run ["entity" "--module-name" "billing" "--entity" "InvoiceLineItem" "--belongs-to" "invoice"
            "--field" "description:string:required"])
      (is (= before (mapv read ["service.clj" "http.clj"]))))))
