(ns wagoe.scaffolder.multi-entity-test
  "A module holds more than one entity: an invoice and its line items.

   `bb scaffold entity` adds one to a module that exists, and `generate-module`
   takes several at once. Either way the result has to load, pass its own
   generated tests, and migrate with a foreign key to the parent (BOU-497,
   BOU-514)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [clojure.walk :as walk]
            [integrant.core :as ig]
            [next.jdbc :as jdbc]
            [reitit.core :as r]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.scaffolder.cli :as cli]
            [wagoe.scaffolder.ports :as ports]
            [wagoe.scaffolder.shell.service :as service]))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "wagoe-multi-entity"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- files-under
  "{relative-path content} for every file under `dir`."
  [dir]
  (let [root (.toPath (.getCanonicalFile (io/file dir)))]
    (into (sorted-map)
          (for [f (file-seq (io/file dir)) :when (.isFile f)]
            [(str (.relativize root (.toPath (.getCanonicalFile f)))) (slurp f)]))))

(def ^:private svc (service/create-scaffolder-service))

(defn- invoice-module!
  "Generate module `billing` with entity Invoice under `dir`, namespace `base`."
  [dir base]
  (let [r (ports/generate-module svc {:module-name "billing"
                                      :base-ns     base
                                      :entities    [{:name "Invoice"
                                                     :fields [{:name :number :type :string}]}]
                                      :output-dir  (.getPath dir)})]
    (assert (:success r) (pr-str (:errors r)))
    dir))

(def ^:private line-item
  {:name "InvoiceLineItem"
   :belongs-to "invoice"
   :fields [{:name :description :type :string}
            {:name :quantity :type :int}]})

(defn- add-line-item! [dir base & [entity]]
  (ports/add-entity svc {:module-name "billing"
                         :base-ns     base
                         :entity      (or entity line-item)
                         :output-dir  (.getPath dir)}))

(defn- statements [sql]
  (->> (str/split sql #";")
       (map (fn [s] (->> (str/split-lines s)
                         (remove #(str/starts-with? (str/trim %) "--"))
                         (str/join "\n"))))
       (map str/trim)
       (remove str/blank?)))

(defn- migrate-h2!
  "Run every up migration under `dir` in id order on a fresh H2 database."
  [dir]
  (let [ds (jdbc/get-datasource {:jdbcUrl (str "jdbc:h2:mem:me" (System/nanoTime) ";DB_CLOSE_DELAY=-1")})]
    (doseq [[path sql] (files-under dir)
            :when (str/ends-with? path ".up.sql")
            s (statements sql)]
      (jdbc/execute! ds [s]))
    ds))

(defn- load-and-test!
  "Load every generated source and test file under `dir`, then run the
   generated tests. Returns the clojure.test summary."
  [dir]
  (let [files (files-under dir)
        load! (fn [prefix]
                ;; schema, then ports, then core, then shell: each only requires
                ;; what comes before it.
                (doseq [[path src] (sort-by (fn [[p _]]
                                              [(cond (str/ends-with? p "schema.clj") 0
                                                     (str/ends-with? p "ports.clj") 1
                                                     (str/includes? p "/core/") 2
                                                     (str/ends-with? p "web_handlers.clj") 4
                                                     (str/ends-with? p "http.clj") 5
                                                     (str/ends-with? p "module_wiring.clj") 6
                                                     :else 3)
                                               p])
                                            files)
                        :when (and (str/starts-with? path prefix) (str/ends-with? path ".clj"))]
                  (binding [*ns* *ns*]
                    (load-string src))))]
    (load! "src/")
    (load! "test/")
    (let [test-nss (for [[path src] files
                         :when (str/starts-with? path "test/")]
                     (symbol (second (re-find #"^\(ns (\S+)" src))))]
      (binding [t/*test-out* (java.io.StringWriter.)]
        (apply t/run-tests test-nss)))))

;; =============================================================================
;; bb scaffold entity
;; =============================================================================

(deftest ^:integration an-entity-is-appended-to-an-existing-module
  (let [dir    (invoice-module! (temp-dir) "bou497a")
        before (files-under dir)
        r      (add-line-item! dir "bou497a")
        after  (files-under dir)]
    (is (:success r) (pr-str (:errors r)))

    (testing "the module's other files are not touched"
      (doseq [[path content] before
              :when (not (re-find #"/(schema|ports|module_wiring)\.clj$" path))]
        (is (= content (get after path)) path)))

    (testing "schema.clj and ports.clj keep what they had and gain a section"
      (doseq [[path content] before
              :when (re-find #"/(schema|ports)\.clj$" path)]
        (is (str/starts-with? (get after path) (str/trimr content)) path)
        (is (str/includes? (get after path) "InvoiceLineItem") path)))

    (testing "the entity's own files are new"
      (let [added (set (remove (set (keys before)) (keys after)))]
        (is (= #{"src/bou497a/billing/core/invoice_line_item.clj"
                 "src/bou497a/billing/shell/invoice_line_item_service.clj"
                 "src/bou497a/billing/shell/invoice_line_item_persistence.clj"
                 "src/bou497a/billing/shell/invoice_line_item_http.clj"
                 "test/bou497a/billing/core/invoice_line_item_test.clj"
                 "test/bou497a/billing/shell/invoice_line_item_repository_test.clj"
                 "test/bou497a/billing/shell/invoice_line_item_service_test.clj"}
               (set (remove #(str/starts-with? % "migrations/") added))))
        (is (= 2 (count (filter #(re-find #"^migrations/\d+-create-invoice-line-items\.(up|down)\.sql$" %) added))))))

    (testing "the report names what was written and what was edited"
      (is (= #{:create :update} (set (map :action (:files r)))))
      (is (= 3 (count (filter #(= :update (:action %)) (:files r))))))

    (testing "the child migrates after its parent, with a foreign key and an index"
      (let [up (some (fn [[p c]] (when (re-find #"create-invoice-line-items\.up\.sql$" p) c)) after)]
        (is (str/includes? up "invoice_id UUID NOT NULL REFERENCES invoices(id) ON DELETE CASCADE"))
        (is (str/includes? up "CREATE INDEX IF NOT EXISTS idx_invoice_line_items_invoice_id")))
      (let [ds  (migrate-h2! dir)
            inv (java.util.UUID/randomUUID)]
        (jdbc/execute! ds ["INSERT INTO invoices (id, number, created_at) VALUES (?, 'A-1', CURRENT_TIMESTAMP)" inv])
        (jdbc/execute! ds [(str "INSERT INTO invoice_line_items (id, invoice_id, description, quantity, created_at)"
                                " VALUES (?, ?, 'x', 1, CURRENT_TIMESTAMP)")
                           (java.util.UUID/randomUUID) inv])
        (is (thrown? Exception
                     (jdbc/execute! ds [(str "INSERT INTO invoice_line_items (id, invoice_id, description, quantity, created_at)"
                                             " VALUES (?, ?, 'x', 1, CURRENT_TIMESTAMP)")
                                        (java.util.UUID/randomUUID) (java.util.UUID/randomUUID)]))
            "a line item for no invoice is refused")))

    (testing "everything loads and the generated tests pass"
      (let [{:keys [test pass fail error]} (load-and-test! dir)]
        (is (= 6 test))
        (is (pos? pass))
        (is (= 0 fail))
        (is (= 0 error))))))

(def seen
  "What the stand-in service was asked to create."
  (atom []))

(defn- reify-service
  "A stand-in for the generated entity service: records creates, lists nothing.
   Evaluated because the protocol only exists once the generated code is loaded."
  [protocol]
  (eval `(reify ~protocol
           (~'create-invoice-line-item [~'_ ~'data] (swap! seen conj [:create ~'data]) ~'data)
           (~'get-invoice-line-item [~'_ ~'_id] nil)
           (~'list-invoice-line-items [~'_ ~'_opts] [])
           (~'update-invoice-line-item [~'_ ~'_id ~'_data] nil)
           (~'delete-invoice-line-item [~'_ ~'_id] nil))))

(defn- boot-module
  "Load the generated module under `dir`, build its Integrant graph the way
   platform discovery does — through the wiring's `ig-config` — and init it.
   The database context is a stand-in: nothing here queries."
  [dir base]
  (load-and-test! dir)
  (let [wiring  (symbol (str base ".billing.shell.module-wiring"))
        build   (ns-resolve wiring 'ig-config)
        _       (assert build "the wiring defines no ig-config")
        graph   (:components (build {:enabled? true} {}))]
    (ig/init (walk/postwalk-replace {(ig/ref :wagoe/db-context) :stand-in} graph))))

(defn- api-paths [system]
  (set (map first (:api (:wagoe/billing-routes system)))))

(deftest ^:integration the-entity-is-wired-and-routed
  (let [dir (invoice-module! (temp-dir) "bou497w")
        r   (add-line-item! dir "bou497w")]
    (is (:success r) (pr-str (:errors r)))
    (let [system (boot-module dir "bou497w")
          routes (:api (:wagoe/billing-routes system))]
      (testing "the module's contribution carries both entities' API"
        (is (contains? (api-paths system) "/invoices"))
        (is (contains? (api-paths system) "/invoice-line-items"))
        (is (contains? (api-paths system) "/invoice-line-items/:id")))
      (testing "the paths are relative, and reitit takes them"
        (is (not-any? #(str/starts-with? % "/api") (api-paths system)))
        (is (some? (r/match-by-path (r/router routes) "/invoice-line-items/1"))))
      (testing "the entity's service is built on its own repository"
        (let [svc (get-in system [:wagoe/billing-entities :invoice-line-item :service])]
          (is (satisfies? @(ns-resolve 'bou497w.billing.ports 'IInvoiceLineItemService) svc))
          (is (= :stand-in (:db-ctx (:repository svc)))))))))

(deftest ^:integration the-entity-api-creates-and-lists
  (let [dir (invoice-module! (temp-dir) "bou497h")]
    (add-line-item! dir "bou497h")
    (load-and-test! dir)
    (reset! seen [])
    (let [svc     (reify-service 'bou497h.billing.ports/IInvoiceLineItemService)
          routes  ((ns-resolve 'bou497h.billing.shell.invoice-line-item-http 'api-routes) svc)
          router  (r/router routes)
          call    (fn [method path body]
                    (let [m (r/match-by-path router path)]
                      ((get-in m [:data method :handler])
                       {:request-method method :path-params (:path-params m) :body-params body})))
          inv     (str (java.util.UUID/randomUUID))]
      (testing "POST coerces the JSON body and creates"
        (let [resp (call :post "/invoice-line-items" {:invoice-id inv :description "x" :quantity 2
                                                      :sneaky "dropped"})]
          (is (= 201 (:status resp)))
          (is (= [:create {:invoice-id (parse-uuid inv) :description "x" :quantity 2}] (first @seen)))))
      (testing "an invalid body is a 400, and reaches nothing"
        (reset! seen [])
        (is (= 400 (:status (call :post "/invoice-line-items" {:invoice-id "nope" :quantity 2}))))
        (is (empty? @seen)))
      (testing "GET lists, and an unknown id is a 404"
        (is (= 200 (:status (call :get "/invoice-line-items" nil))))
        (is (= 404 (:status (call :get "/invoice-line-items/not-a-uuid" nil))))))))

(deftest ^:integration the-generated-repositories-round-trip-on-h2
  ;; The generated persistence pre-formatted ANSI SQL with `RETURNING *`, which
  ;; H2 — the test profile's database — rejects: every create answered 500.
  (let [dir (invoice-module! (temp-dir) "bou497p")]
    (add-line-item! dir "bou497p")
    (load-and-test! dir)
    (let [ctx  (db-factory/db-context {:adapter :h2
                                       :database-path (str "mem:bou497p" (System/nanoTime) ";DB_CLOSE_DELAY=-1")
                                       :pool {:minimum-idle 1 :maximum-pool-size 2}})
          _    (doseq [[path sql] (files-under dir)
                       :when (str/ends-with? path ".up.sql")
                       st (statements sql)]
                 (jdbc/execute! (:datasource ctx) [st]))
          at   (fn [n s] @(ns-resolve (symbol (str "bou497p.billing." n)) s))
          p    (fn [s] (at "ports" s))
          invs ((at "shell.service" 'create-service) ((at "shell.persistence" 'create-repository) ctx))
          line ((at "shell.invoice-line-item-service" 'create-service)
                ((at "shell.invoice-line-item-persistence" 'create-repository) ctx))
          inv  ((p 'create-invoice) invs {:number "A-1"})
          item ((p 'create-invoice-line-item) line {:invoice-id (:id inv) :description "x" :quantity 2})]
      (is (uuid? (:id item)))
      (is (= [(:id item)] (map :id ((p 'list-invoice-line-items) line {}))))
      (is (= 5 (:quantity ((p 'update-invoice-line-item) line (:id item) {:quantity 5}))))
      (is (= "x" (:description ((p 'get-invoice-line-item) line (:id item)))))
      ((p 'delete-invoice-line-item) line (:id item))
      (is (empty? ((p 'list-invoice-line-items) line {}))))))

(deftest ^:unit a-hand-edited-routes-method-is-refused
  ;; Wiring the entity replaces the routes init-key once. Replacing one the
  ;; user changed would drop their change, so it is refused by name instead.
  (let [dir    (invoice-module! (temp-dir) "bou497r")
        wiring (io/file dir "src/bou497r/billing/shell/module_wiring.clj")]
    (spit wiring (str/replace (slurp wiring) "(or config {})" "(assoc config :mine true)"))
    (let [before (files-under dir)
          r      (add-line-item! dir "bou497r")]
      (is (false? (:success r)))
      (is (some #(str/includes? % "module_wiring.clj") (:errors r)) (pr-str (:errors r)))
      (is (= before (files-under dir))))))

(deftest ^:integration a-second-entity-is-wired-next-to-the-first
  (let [dir (invoice-module! (temp-dir) "bou497s")]
    (is (:success (add-line-item! dir "bou497s")))
    (is (:success (add-line-item! dir "bou497s" {:name "Payment" :belongs-to "invoice"
                                                 :fields [{:name :amount :type :int}]})))
    (let [system (boot-module dir "bou497s")]
      (is (= #{:invoice-line-item :payment} (set (keys (:wagoe/billing-entities system)))))
      (is (contains? (api-paths system) "/payments")))))

(deftest ^:unit an-entity-whose-files-exist-is-refused-and-they-are-named
  (let [dir  (invoice-module! (temp-dir) "bou497b")
        core (io/file dir "src/bou497b/billing/core/invoice_line_item.clj")]
    (io/make-parents core)
    (spit core ";; mine")
    (let [before (files-under dir)
          r      (add-line-item! dir "bou497b")]
      (is (false? (:success r)))
      (is (some #(str/includes? % "invoice_line_item.clj") (:errors r)) (pr-str (:errors r)))
      (is (= before (files-under dir)) "nothing is written"))))

(deftest ^:unit an-entity-already-in-the-module-is-refused
  (let [dir    (invoice-module! (temp-dir) "bou497c")
        before (files-under dir)
        r      (add-line-item! dir "bou497c" {:name "Invoice" :fields [{:name :x :type :string}]})]
    (is (false? (:success r)))
    (is (some #(str/includes? % "Invoice") (:errors r)) (pr-str (:errors r)))
    (is (= before (files-under dir)))))

(deftest ^:unit belonging-to-an-entity-the-module-does-not-have-is-refused
  (let [dir    (invoice-module! (temp-dir) "bou497d")
        before (files-under dir)
        r      (add-line-item! dir "bou497d" (assoc line-item :belongs-to "order"))]
    (is (false? (:success r)))
    (is (some #(str/includes? % "Order") (:errors r)) (pr-str (:errors r)))
    (is (= before (files-under dir)))))

(deftest ^:unit the-module-has-to-exist
  (let [r (add-line-item! (temp-dir) "bou497e")]
    (is (false? (:success r)))
    (is (some #(str/includes? % "schema.clj") (:errors r)) (pr-str (:errors r)))))

(deftest ^:unit a-dry-run-writes-nothing
  (let [dir    (invoice-module! (temp-dir) "bou497f")
        before (files-under dir)
        r      (ports/add-entity svc {:module-name "billing" :base-ns "bou497f"
                                      :entity line-item :output-dir (.getPath dir)
                                      :dry-run true})]
    (is (:success r) (pr-str (:errors r)))
    (is (every? #(= :skip (:action %)) (:files r)))
    (is (= before (files-under dir)))))

(deftest ^:unit the-entity-command-is-on-the-cli
  (let [dir (invoice-module! (temp-dir) "bou497g")
        out (with-out-str
              (is (= 0 (cli/run-cli! svc ["entity" "--module-name" "billing"
                                          "--entity" "InvoiceLineItem"
                                          "--belongs-to" "invoice"
                                          "--field" "quantity:int:required:default=1"
                                          "--base-ns" "bou497g"
                                          "--output-dir" (.getPath dir)]))))]
    (is (str/includes? out "InvoiceLineItem") out)
    (is (.isFile (io/file dir "src/bou497g/billing/shell/invoice_line_item_service.clj")))
    (let [up (some (fn [[p c]] (when (re-find #"create-invoice-line-items\.up\.sql$" p) c)) (files-under dir))]
      (is (str/includes? up "invoice_id UUID NOT NULL REFERENCES invoices(id)"))
      (is (str/includes? up "quantity INTEGER DEFAULT 1 NOT NULL"))))
  (testing "help names the command"
    (is (str/includes? cli/root-help "entity"))
    (is (str/includes? (with-out-str (cli/run-cli! svc ["entity" "--help"])) "--belongs-to"))))

;; =============================================================================
;; generate-module with several entities (BOU-514)
;; =============================================================================

(deftest ^:integration generate-module-writes-every-entity
  (let [dir (temp-dir)
        r   (ports/generate-module svc {:module-name "billing"
                                        :base-ns     "bou514"
                                        :entities    [{:name "Invoice"
                                                       :fields [{:name :number :type :string}]}
                                                      line-item]
                                        :output-dir  (.getPath dir)})
        on-disk (files-under dir)]
    (is (:success r) (pr-str (:errors r)))
    (testing "both entities are on disk, and the report lists both"
      (doseq [p ["src/bou514/billing/core/invoice.clj"
                 "src/bou514/billing/core/invoice_line_item.clj"
                 "src/bou514/billing/shell/invoice_line_item_service.clj"]]
        (is (contains? on-disk p) p)
        (is (some #(str/ends-with? (:path %) p) (:files r)) p)))
    (testing "two create migrations, the parent's first"
      (let [ups (filter #(str/ends-with? % ".up.sql") (keys on-disk))]
        (is (= 2 (count ups)))
        (is (re-find #"create-invoices" (first (sort ups))))))
    (testing "everything loads, migrates and passes its tests"
      (migrate-h2! dir)
      (let [{:keys [fail error test]} (load-and-test! dir)]
        (is (= 6 test))
        (is (= 0 fail))
        (is (= 0 error))))))

(deftest ^:unit two-entities-with-one-name-are-refused
  (let [r (ports/generate-module svc {:module-name "billing"
                                      :entities    [{:name "Invoice" :fields [{:name :a :type :string}]}
                                                    {:name "Invoice" :fields [{:name :b :type :string}]}]
                                      :dry-run     true})]
    (is (false? (:success r)))))
