(ns wagoe.scaffolder.workflow-test
  "`--workflow 'status:entered>delivered>paid'`: an entity whose status is a
   workflow rather than a field anyone may set.

   Without it a user hand-wrote the definition, its registration, the start of
   an instance for each new row — reachable only through admin events, so the
   API's rows got none — and the admin's :workflow key (BOU-569)."
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [clojure.walk :as walk]
            [integrant.core :as ig]
            [muuntaja.core :as muuntaja]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [wagoe.admin.schema :as admin-schema]
            [wagoe.events.core.event :as event]
            [wagoe.events.ports :as events]
            [wagoe.events.shell.module-wiring]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.platform.shell.database.migrations :as migrations]
            [wagoe.platform.shell.http.reitit-router :as reitit-router]
            [wagoe.scaffolder.cli :as cli]
            [wagoe.scaffolder.ports :as ports]
            [wagoe.scaffolder.shell.service :as service]
            [wagoe.workflow.ports :as workflow]
            [wagoe.workflow.shell.http :as workflow-http]
            [wagoe.workflow.shell.module-wiring]))

(def ^:private svc (service/create-scaffolder-service))

;; =============================================================================
;; The spec
;; =============================================================================

(deftest ^:unit the-workflow-spec-names-a-field-and-its-states-in-order
  (is (= {:field :status :states [:entered :delivered :paid]}
         (cli/parse-workflow-spec "status:entered>delivered>paid")))
  (doseq [bad ["status" "status:entered" "Status:a>b" "status:a>a" "status:a>B" "status:a>>b" ":a>b"]]
    (is (:error (cli/parse-workflow-spec bad)) bad)))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "wagoe-workflow" (make-array java.nio.file.attribute.FileAttribute 0))))

(def ^:private admin-config
  (str "{:active\n"
       " {:wagoe/settings {:name \"app\"}\n"
       "  :wagoe/admin\n"
       "  {:enabled?         true\n"
       "   :entity-discovery {:mode :allowlist :allowlist #{:users}}\n"
       "   :entities         #merge [#include \"admin/users.edn\"]}}\n"
       " :inactive {}}\n"))

(defn- project!
  "A project whose dev profile has the admin on."
  []
  (let [dir (temp-dir)
        f   (io/file dir "resources/conf/dev/config.edn")
        u   (io/file dir "resources/conf/dev/admin/users.edn")]
    (io/make-parents u)
    (spit f admin-config)
    (spit u "{:users {:label \"Users\"}}\n")
    dir))

(defn- files-under [dir]
  (let [root (.toPath (.getCanonicalFile (io/file dir)))]
    (into (sorted-map)
          (for [f (file-seq (io/file dir)) :when (.isFile f)]
            [(str (.relativize root (.toPath (.getCanonicalFile f)))) (slurp f)]))))

(defn- generate! [dir base & args]
  (let [out (with-out-str
              (binding [*err* *out*]
                (is (= 0 (cli/run-cli! svc (into ["generate" "--module-name" "billing" "--entity" "Invoice"
                                                  "--field" "number:string:required"
                                                  "--workflow" "status:entered>delivered>paid"
                                                  "--base-ns" base "--output-dir" (.getPath dir)]
                                                 args))))))]
    out))

(defn- statements [sql]
  (->> (str/split sql #"--;;")
       (map #(str/join "\n" (remove (fn [l] (str/starts-with? (str/trim l) "--")) (str/split-lines %))))
       (map str/trim)
       (remove str/blank?)))

(defn- load-generated!
  "Load every generated source and test namespace under `dir`, in dependency
   order. Returns the test namespaces."
  [dir]
  (let [files (files-under dir)
        rank  (fn [p] (cond (str/ends-with? p "schema.clj") 0
                            (str/ends-with? p "ports.clj") 1
                            (str/includes? p "/core/") 2
                            (str/ends-with? p "_workflow.clj") 3
                            (str/ends-with? p "web_handlers.clj") 5
                            (re-find #"http\.clj$" p) 6
                            (str/ends-with? p "module_wiring.clj") 7
                            :else 4))]
    (doseq [prefix ["src/" "test/"]
            [path src] (sort-by (fn [[p _]] [(rank p) p]) files)
            :when (and (str/starts-with? path prefix) (str/ends-with? path ".clj"))]
      (binding [*ns* *ns*] (load-string src)))
    (for [[path src] files :when (str/starts-with? path "test/")]
      (symbol (second (re-find #"^\(ns (\S+)" src))))))

(defn- run-generated-tests!
  "Run the generated tests, their migrations read from `dir` alone."
  [dir test-nss]
  (let [copy (str "target/migrations-" (System/nanoTime))]
    (doseq [^java.io.File m (.listFiles (io/file dir "migrations"))]
      (io/make-parents (io/file copy (.getName m)))
      (io/copy m (io/file copy (.getName m))))
    (with-redefs [migrations/discover-migration-dirs (constantly [copy])
                  migrations/refuse-shadowed-migration-dirs! (constantly nil)]
      (binding [t/*test-out* (java.io.StringWriter.)]
        (apply t/run-tests test-nss)))))

(defn- migrated-ctx [dir]
  (let [ctx (db-factory/db-context {:adapter :h2
                                    :database-path (str "mem:wf" (System/nanoTime) ";DB_CLOSE_DELAY=-1")
                                    :pool {:minimum-idle 1 :maximum-pool-size 4}})]
    (doseq [[path sql] (files-under dir)
            :when (str/ends-with? path ".up.sql")
            s (statements sql)]
      (jdbc/execute! (:datasource ctx) [s]))
    ctx))

(defn- boot!
  "The generated module's graph from its own `ig-config`, with the workflow,
   events and admin modules switched on, next to a real workflow component and
   an in-memory bus, on H2."
  [base ctx]
  (let [build (ns-resolve (symbol (str base ".billing.shell.module-wiring")) 'ig-config)
        graph (:components (build {:enabled? true}
                                  {:config {:active {:wagoe/workflow {} :wagoe/events {}
                                                     :wagoe/admin {:enabled? true}}}}))]
    (ig/init (merge (walk/postwalk-replace {(ig/ref :wagoe/db-context) ctx} graph)
                    {:wagoe/workflow-db-schema {:ctx ctx :profile :test}
                     :wagoe/workflow {:db-ctx ctx :db-schema (ig/ref :wagoe/workflow-db-schema)
                                      :guard-registry {}}
                     :wagoe/events {:provider :memory}}))))

(defn- http-caller [routes]
  (let [handler (reitit-router/compile-routes routes {:swagger-enabled false})]
    (fn [method path body]
      (let [resp (handler (cond-> {:request-method method :uri path
                                   :headers {"accept" "application/json"}
                                   :user {:id (random-uuid) :email "a@example.com" :role :user}}
                            body (-> (assoc-in [:headers "content-type"] "application/json")
                                     (assoc :body (muuntaja/encode muuntaja/instance "application/json" body)))))]
        (cond-> resp
          (some? (:body resp)) (update :body #(let [s (if (string? %) % (slurp %))]
                                                (when-not (str/blank? s)
                                                  (muuntaja/decode muuntaja/instance "application/json" s)))))))))

;; =============================================================================
;; generate --workflow
;; =============================================================================

(deftest ^:integration generate-writes-the-workflow-and-its-wiring
  (let [dir (project!)
        out (generate! dir "bou569g")
        on-disk (files-under dir)
        at  #(get on-disk %)]
    (is (str/includes? out "wagoe add workflow") "the next steps say what it needs")

    (testing "the definition, forward only, in its own namespace"
      (let [src (at "src/bou569g/billing/shell/invoice_workflow.clj")]
        (is (some? src))
        (is (str/includes? src ":initial-state :entered"))
        (is (str/includes? src "{:from :entered :to :delivered"))
        (is (str/includes? src "{:from :delivered :to :paid"))
        (is (not (str/includes? src "{:from :entered :to :paid")))
        (is (str/includes? src "register-workflow!") "registered through the port")))

    (testing "status is a column, and no request can set it"
      (let [schema (at "src/bou569g/billing/schema.clj")
            section (fn [nm] (second (re-find (re-pattern (str "(?s)\\(def " nm "\n(.*?)\\]\\)")) schema)))]
        (is (str/includes? (section "Invoice") ":status"))
        (is (not (str/includes? (section "CreateInvoiceRequest") ":status")))
        (is (not (str/includes? (section "UpdateInvoiceRequest") ":status"))))
      (is (re-find #"status VARCHAR\(50\) DEFAULT 'entered' NOT NULL"
                   (some (fn [[p c]] (when (str/includes? p "create-invoices.up") c)) on-disk))))

    (testing "the admin shows the workflow and cannot edit the status"
      (let [admin (:invoices (aero/read-config (io/file dir "resources/conf/dev/admin/invoices.edn")))]
        (is (= {:entity-type :invoice} (:workflow admin)))
        (is (contains? (:readonly-fields admin) :status))
        (is (empty? (admin-schema/entity-config-errors
                     {:entities {:invoices admin}}))
            "the admin's closed schema takes every key")))

    (testing "it loads, and its own tests pass, the transitions among them"
      (let [test-nss (load-generated! dir)
            {:keys [test fail error]} (run-generated-tests! dir test-nss)]
        (is (some #(str/ends-with? (str %) "invoice-workflow-test") test-nss))
        (is (< 3 test))
        (is (= 0 fail))
        (is (= 0 error))))))

(deftest ^:integration the-api-and-the-admin-both-get-a-workflow
  (let [dir    (project!)
        _      (generate! dir "bou569b")
        _      (load-generated! dir)
        ctx    (migrated-ctx dir)
        system (boot! "bou569b" ctx)
        call   (http-caller (:api (:wagoe/billing-routes system)))
        store  (get-in system [:wagoe/workflow :store])]
    (try
      (let [created (call :post "/invoices" {:number "A-1" :status "paid"})
            id      (get-in created [:body :id])
            move    #(call :post (str "/invoices/" id "/transition") {:transition %})]
        (testing "an invoice created through the API starts at the first state"
          (is (= 201 (:status created)) (pr-str created))
          (is (= "entered" (get-in created [:body :status])) "a status in the request is ignored")
          (is (= :entered (:current-state (workflow/find-instance-by-entity store :invoice (parse-uuid id))))))

        (testing "the status is not edited, only transitioned"
          (is (= 400 (:status (call :put (str "/invoices/" id) {:status "paid"}))))
          (is (= "entered" (get-in (call :get (str "/invoices/" id) nil) [:body :status]))))

        (testing "a skip is refused"
          (is (= 422 (:status (move "paid"))))
          (is (= "entered" (get-in (call :get (str "/invoices/" id) nil) [:body :status]))))

        (testing "a step forward moves the workflow, and the status follows"
          (let [resp (move "delivered")]
            (is (= 200 (:status resp)) (pr-str resp))
            (is (= "delivered" (get-in resp [:body :status])))
            (testing "and says what the caller may do next, as the workflow API does (BOU-589)"
              (is (= {:state "delivered"
                      :available-transitions [{:id "paid" :to "paid" :enabled true :label "Paid"}]}
                     (select-keys (get-in resp [:body :workflow]) [:state :available-transitions]))))))

        (testing "going back is refused"
          (is (= 422 (:status (move "entered")))))

        (testing "from the last state, nothing follows"
          (is (= [] (get-in (move "paid") [:body :workflow :available-transitions]))))

        (testing "deleting the invoice removes its workflow"
          (is (= 204 (:status (call :delete (str "/invoices/" id) nil))))
          (is (nil? (workflow/find-instance-by-entity store :invoice (parse-uuid id))))))

      (testing "an invoice the admin creates gets one through its event"
        (let [id (random-uuid)]
          (jdbc/execute! (:datasource ctx) ["INSERT INTO invoices (id, number, created_at) VALUES (?, 'A-2', CURRENT_TIMESTAMP)" id])
          ;; As the admin publishes it.
          (is (string? (events/publish! (:wagoe/events system) :admin
                                        (event/event {:id (random-uuid) :type :admin/entity-created
                                                      :source :admin :published-at (java.time.Instant/now)
                                                      :payload {:entity :invoices :id id}}))))
          (is (= :entered (:current-state
                           (loop [n 50]
                             (or (workflow/find-instance-by-entity store :invoice id)
                                 (when (pos? n) (Thread/sleep 20) (recur (dec n))))))))))
      (finally
        (ig/halt! system)
        (db-factory/close-db-context! ctx)))))

(defn- with-booted
  "Call `f` with {:call :ctx :store :system} for module bou569<suffix>."
  [suffix f]
  (let [base   (str "bou569" suffix)
        dir    (project!)
        _      (generate! dir base)
        _      (load-generated! dir)
        ctx    (migrated-ctx dir)
        system (boot! base ctx)]
    (try
      (f {:call   (http-caller (:api (:wagoe/billing-routes system)))
          :ctx    ctx
          :store  (get-in system [:wagoe/workflow :store])
          :system system})
      (finally
        (ig/halt! system)
        (db-factory/close-db-context! ctx)))))

(defn- sql! [ctx s & params]
  (jdbc/execute! (:datasource ctx) (into [s] params)))

(defn- column [ctx id]
  (:status (jdbc/execute-one! (:datasource ctx) ["SELECT status FROM invoices WHERE id = ?" id]
                              {:builder-fn rs/as-unqualified-lower-maps})))

(deftest ^:integration a-failed-status-write-fails-the-request-and-is-repaired
  (with-booted "m"
    (fn [{:keys [call ctx store]}]
      (let [id   (get-in (call :post "/invoices" {:number "A-1"}) [:body :id])
            uuid (parse-uuid id)
            move #(call :post (str "/invoices/" id "/transition") {:transition %})]
        (sql! ctx "ALTER TABLE invoices ADD CONSTRAINT no_delivered CHECK (status <> 'delivered')")
        (testing "the column write fails, so the request does"
          (is (<= 500 (:status (move "delivered")))))
        (testing "the workflow moved and the column did not: that is not hidden"
          (is (= :delivered (:current-state (workflow/find-instance-by-entity store :invoice uuid))))
          (is (= "entered" (column ctx uuid))))
        (sql! ctx "ALTER TABLE invoices DROP CONSTRAINT no_delivered")
        (testing "the next transition repairs the column first"
          (let [resp (move "paid")]
            (is (= 200 (:status resp)) (pr-str resp))
            (is (= "paid" (get-in resp [:body :status])))
            (is (= "paid" (column ctx uuid)))))))))

(deftest ^:integration a-create-whose-workflow-cannot-start-leaves-no-row
  (with-booted "c"
    (fn [{:keys [call ctx store]}]
      ;; The instance's insert fails: its table is gone.
      (sql! ctx "ALTER TABLE workflow_instances RENAME TO workflow_instances_away")
      (is (<= 500 (:status (call :post "/invoices" {:number "A-1"}))))
      (is (empty? (sql! ctx "SELECT id FROM invoices")) "the row is removed again")
      (sql! ctx "ALTER TABLE workflow_instances_away RENAME TO workflow_instances")
      (testing "a row that got no workflow gets one on its first transition"
        (let [id (random-uuid)]
          (sql! ctx "INSERT INTO invoices (id, number, created_at) VALUES (?, 'A-2', CURRENT_TIMESTAMP)" id)
          (is (nil? (workflow/find-instance-by-entity store :invoice id)))
          (let [resp (call :post (str "/invoices/" id "/transition") {:transition "delivered"})]
            (is (= 200 (:status resp)) (pr-str resp))
            (is (= "delivered" (get-in resp [:body :status])))))))))

(deftest ^:integration a-seeded-row-gets-its-workflow-in-the-state-it-holds
  ;; BOU-578: `bb db:seed` inserts rows without the service, and a delivered
  ;; or paid invoice got a workflow in `entered`.
  (with-booted "s"
    (fn [{:keys [call ctx store system]}]
      (let [[a b c] (repeatedly 3 random-uuid)
            state   #(:current-state (workflow/find-instance-by-entity store :invoice %))
            seed    (:wagoe/billing-seed system)]
        (is (isa? :wagoe/billing-seed :wagoe/seed-hook) "bb db:seed finds it by what it derives from")
        (sql! ctx "INSERT INTO invoices (id, number, created_at) VALUES (?, 'A-1', CURRENT_TIMESTAMP)" a)
        (sql! ctx "INSERT INTO invoices (id, number, status, created_at) VALUES (?, 'A-2', 'delivered', CURRENT_TIMESTAMP)" b)
        (sql! ctx "INSERT INTO invoices (id, number, status, created_at) VALUES (?, 'A-3', 'paid', CURRENT_TIMESTAMP)" c)
        ;; As bb db:seed hands it over: rows by table, ids as the file wrote them.
        (seed {"invoices" [{:id a} {:id (str b)} {:id c}] "other_table" [{:id 1}]})
        (is (= [:entered :delivered :paid] (map state [a b c])))
        (is (= ["entered" "delivered" "paid"] (map #(column ctx %) [a b c])))
        (testing "a second run changes nothing"
          (seed {"invoices" [{:id a} {:id b}]})
          (is (= [:entered :delivered] (map state [a b]))))
        (testing "a seeded invoice moves on from where it was put"
          (let [resp (call :post (str "/invoices/" b "/transition") {:transition "paid"})]
            (is (= 200 (:status resp)) (pr-str resp))
            (is (= "paid" (get-in resp [:body :status])))))
        (testing "a status its workflow does not have is refused, by name"
          (let [d (random-uuid)]
            (sql! ctx "INSERT INTO invoices (id, number, status, created_at) VALUES (?, 'A-4', 'shipped', CURRENT_TIMESTAMP)" d)
            (let [e (try (seed {"invoices" [{:id d}]}) nil (catch Exception e e))]
              (is (some? e))
              (is (str/includes? (ex-message e) "shipped") (ex-message e))
              (is (nil? (state d))))))))))

(deftest ^:integration a-refused-delete-keeps-the-workflow
  (with-booted "d"
    (fn [{:keys [call ctx store]}]
      (let [id (get-in (call :post "/invoices" {:number "A-1"}) [:body :id])]
        (sql! ctx "CREATE TABLE holds (id UUID PRIMARY KEY, invoice_id UUID REFERENCES invoices(id) ON DELETE RESTRICT)")
        (sql! ctx "INSERT INTO holds (id, invoice_id) VALUES (?, ?)" (random-uuid) (parse-uuid id))
        (is (<= 400 (:status (call :delete (str "/invoices/" id) nil))))
        (is (some? (workflow/find-instance-by-entity store :invoice (parse-uuid id)))
            "the row is still there, and so is its workflow")))))

(deftest ^:integration the-entity-and-the-workflow-api-list-transitions-alike
  ;; One shape for what may follow: the same key and items, on the entity's
  ;; `workflow` and on the workflow API's instance (BOU-590).
  (with-booted "t"
    (fn [{:keys [call system]}]
      (let [id       (get-in (call :post "/invoices" {:number "A-1"}) [:body :id])
            moved    (get-in (call :post (str "/invoices/" id "/transition") {:transition "delivered"})
                             [:body :workflow])
            wf-call  (http-caller (workflow-http/workflow-routes (get-in system [:wagoe/workflow :engine])))
            instance (:body (wf-call :get (str "/workflow/instances/" (:instance-id moved)) nil))]
        (is (seq (:available-transitions moved)) (pr-str moved))
        (is (= (:available-transitions instance) (:available-transitions moved)))
        (is (= (:current-state instance) (:state moved)))))))

(deftest ^:unit the-next-steps-name-only-the-modules-that-are-off
  (let [dir (project!)
        cfg (io/file dir "resources/conf/dev/config.edn")]
    (spit cfg (str/replace admin-config " {:wagoe/settings" " {:wagoe/workflow {}\n  :wagoe/events {:provider :memory}\n  :wagoe/settings"))
    (let [out (generate! dir "bou569n")]
      (is (not (str/includes? out "wagoe add workflow")) out)
      (is (not (str/includes? out "wagoe add events")) out))))

(deftest ^:unit a-field-of-the-same-name-is-refused
  (let [dir (project!)
        err (with-out-str
              (binding [*err* *out*]
                (is (= 1 (cli/run-cli! svc ["generate" "--module-name" "billing" "--entity" "Invoice"
                                            "--field" "status:enum:values=a,b"
                                            "--workflow" "status:a>b"
                                            "--base-ns" "bou569c" "--output-dir" (.getPath dir)])))))]
    (is (str/includes? err "status") err)
    (is (not (.exists (io/file dir "src"))) "nothing is written")))

;; =============================================================================
;; entity --workflow
;; =============================================================================

(deftest ^:integration a-further-entity-gets-its-own-workflow
  (let [dir (project!)]
    (is (= 0 (cli/run-cli! svc ["generate" "--module-name" "billing" "--entity" "Invoice"
                                "--field" "number:string:required"
                                "--base-ns" "bou569e" "--output-dir" (.getPath dir)])))
    (is (= 0 (cli/run-cli! svc ["entity" "--module-name" "billing" "--entity" "Shipment"
                                "--belongs-to" "invoice" "--field" "carrier:string:required"
                                "--workflow" "state:packed>sent"
                                "--base-ns" "bou569e" "--output-dir" (.getPath dir)])))
    (is (.isFile (io/file dir "src/bou569e/billing/shell/shipment_workflow.clj")))
    (let [test-nss (load-generated! dir)
          {:keys [fail error]} (run-generated-tests! dir test-nss)
          ctx      (migrated-ctx dir)
          system   (boot! "bou569e" ctx)
          call     (http-caller (:api (:wagoe/billing-routes system)))]
      (is (= 0 fail))
      (is (= 0 error))
      (try
        (let [invoice (call :post "/invoices" {:number "A-1"})
              created (call :post "/shipments" {:invoice-id (get-in invoice [:body :id]) :carrier "x"})
              id      (get-in created [:body :id])]
          (is (= 201 (:status created)) (pr-str created))
          (is (= "packed" (get-in created [:body :state])))
          (is (= "sent" (get-in (call :post (str "/shipments/" id "/transition") {:transition "sent"})
                                [:body :state]))))
        (finally
          (ig/halt! system)
          (db-factory/close-db-context! ctx))))))

(deftest ^:unit an-entity-workflow-needs-the-current-wiring
  ;; A module_wiring.clj generated before workflows builds entity services
  ;; with one argument; wiring a workflow into it would fail at boot.
  (let [dir    (project!)
        _      (cli/run-cli! svc ["generate" "--module-name" "billing" "--entity" "Invoice"
                                  "--field" "number:string:required"
                                  "--base-ns" "bou569o" "--output-dir" (.getPath dir)])
        _      (cli/run-cli! svc ["entity" "--module-name" "billing" "--entity" "Note"
                                  "--field" "text:string" "--base-ns" "bou569o" "--output-dir" (.getPath dir)])
        wiring (io/file dir "src/bou569o/billing/shell/module_wiring.clj")
        ;; What the seam looked like before BOU-569.
        _      (spit wiring (str/replace (slurp wiring) #"(?s)\(defmethod ig/halt-key! :wagoe/billing-entities.*?\n\n" ""))
        before (files-under dir)
        r      (ports/add-entity svc {:module-name "billing" :base-ns "bou569o" :output-dir (.getPath dir)
                                      :entity {:name "Shipment" :fields [{:name :carrier :type :string}]
                                               :workflow {:field :state :states [:packed :sent]}}})]
    (is (false? (:success r)))
    (is (some #(str/includes? % "module_wiring.clj") (:errors r)) (pr-str (:errors r)))
    (is (= before (files-under dir)))))
