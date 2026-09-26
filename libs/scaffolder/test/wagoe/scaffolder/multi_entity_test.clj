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
            [muuntaja.core :as muuntaja]
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

;; =============================================================================
;; The entity API, decoded and refused (BOU-497 review)
;; =============================================================================

(defn- entity-api
  "A caller for the generated line-item API under `dir`, on the stand-in service."
  [base]
  (let [svc    (reify-service (symbol (str base ".billing.ports") "IInvoiceLineItemService"))
        routes ((ns-resolve (symbol (str base ".billing.shell.invoice-line-item-http")) 'api-routes) svc)
        router (r/router routes)]
    (fn [method path body]
      (let [m (r/match-by-path router path)]
        ((get-in m [:data method :handler])
         {:request-method method :path-params (:path-params m) :body-params body})))))

(deftest ^:integration a-decimal-field-takes-a-json-number-or-a-numeric-string
  ;; Muuntaja parses 9.99 as a Double, and malli has no BigDecimal decoder, so
  ;; `decimal?` refused both 9.99 and "9.99": every POST answered 400.
  (let [dir (invoice-module! (temp-dir) "bou497dec")]
    (add-line-item! dir "bou497dec" (update line-item :fields conj {:name :price :type :decimal}))
    (load-and-test! dir)
    (reset! seen [])
    (let [call (entity-api "bou497dec")
          inv  (str (java.util.UUID/randomUUID))]
      (doseq [price [9.99 "9.99" 10]]
        (reset! seen [])
        (is (= 201 (:status (call :post "/invoice-line-items"
                                  {:invoice-id inv :description "x" :quantity 1 :price price})))
            (pr-str price))
        (is (= (bigdec price) (:price (second (first @seen)))) (pr-str price)))
      (is (= 400 (:status (call :post "/invoice-line-items"
                                {:invoice-id inv :description "x" :quantity 1 :price "cheap"}))))
      (is (not= 400 (:status (call :put (str "/invoice-line-items/" (java.util.UUID/randomUUID))
                                   {:price 9.99})))
          "PUT decodes it too"))))

(deftest ^:integration an-update-with-nothing-to-set-is-a-400
  ;; `UPDATE t SET  WHERE ...` — a 500 from the database.
  (let [dir (invoice-module! (temp-dir) "bou497put")]
    (add-line-item! dir "bou497put")
    (load-and-test! dir)
    (let [call (entity-api "bou497put")
          path (str "/invoice-line-items/" (java.util.UUID/randomUUID))]
      (is (= 400 (:status (call :put path {}))))
      (is (= 400 (:status (call :put path {:sneaky "dropped"})))))))

(deftest ^:unit belonging-to-a-parent-and-declaring-its-id-is-refused
  ;; Both become the column invoice_id: a duplicate column in CREATE TABLE and
  ;; :malli.core/duplicate-keys in schema.clj.
  (doseq [clash [{:name :invoice-id :type :uuid :required true}
                 {:name :invoice :type :string}]]
    (let [dir    (invoice-module! (temp-dir) "bou497dup")
          before (files-under dir)
          entity (update line-item :fields conj clash)
          r      (add-line-item! dir "bou497dup" entity)]
      (is (false? (:success r)))
      (is (some #(str/includes? % (name (:name clash))) (:errors r)) (pr-str (:errors r)))
      (is (= before (files-under dir)) "nothing is written")
      (testing "generate-module — the MCP tool's path — refuses it too"
        (let [r (ports/generate-module svc {:module-name "billing"
                                            :entities    [{:name "Invoice" :fields [{:name :number :type :string}]}
                                                          entity]
                                            :dry-run     true})]
          (is (false? (:success r)))
          (is (some #(str/includes? % (name (:name clash))) (:errors r)) (pr-str (:errors r))))))))

(deftest ^:integration create-returns-the-row-with-its-column-defaults
  ;; It returned its input, so a DEFAULT the database filled in was missing.
  (let [dir (temp-dir)
        r   (ports/generate-module svc {:module-name "billing" :base-ns "bou497cr"
                                        :entities    [{:name "Invoice"
                                                       :fields [{:name :number :type :string}
                                                                {:name :state :type :string
                                                                 :required false :default "draft"}]}
                                                      (update line-item :fields conj
                                                              {:name :note :type :string
                                                               :required false :default "n/a"})]
                                        :output-dir  (.getPath dir)})]
    (is (:success r) (pr-str (:errors r)))
    (load-and-test! dir)
    (let [ctx  (db-factory/db-context {:adapter :h2
                                       :database-path (str "mem:bou497cr" (System/nanoTime) ";DB_CLOSE_DELAY=-1")
                                       :pool {:minimum-idle 1 :maximum-pool-size 2}})
          _    (doseq [[path sql] (files-under dir)
                       :when (str/ends-with? path ".up.sql")
                       st (statements sql)]
                 (jdbc/execute! (:datasource ctx) [st]))
          at   (fn [n s] @(ns-resolve (symbol (str "bou497cr.billing." n)) s))
          invs ((at "shell.service" 'create-service) ((at "shell.persistence" 'create-repository) ctx))
          line ((at "shell.invoice-line-item-service" 'create-service)
                ((at "shell.invoice-line-item-persistence" 'create-repository) ctx))
          inv  ((at "ports" 'create-invoice) invs {:number "A-1"})
          item ((at "ports" 'create-invoice-line-item) line {:invoice-id (:id inv) :description "x" :quantity 2})]
      (is (= "draft" (:state inv)) "the first entity")
      (is (= "n/a" (:note item)) "a further entity")
      (is (= inv ((at "ports" 'get-invoice) invs (:id inv))) "what create returns is what get returns"))))

(deftest ^:integration a-module-without-http-gets-no-entity-api
  (let [dir (temp-dir)
        r   (ports/generate-module svc {:module-name "billing" :base-ns "bou497nh"
                                        :interfaces  {:http false}
                                        :entities    [{:name "Invoice" :fields [{:name :number :type :string}]}
                                                      line-item]
                                        :output-dir  (.getPath dir)})]
    (is (:success r) (pr-str (:errors r)))
    (is (not-any? #(str/ends-with? % "_http.clj") (keys (files-under dir))))
    (testing "and adding one later with :http false writes none either"
      (let [dir2 (invoice-module! (temp-dir) "bou497nh2")
            r2   (ports/add-entity svc {:module-name "billing" :base-ns "bou497nh2"
                                        :entity line-item :interfaces {:http false}
                                        :output-dir (.getPath dir2)})]
        (is (:success r2) (pr-str (:errors r2)))
        (is (not-any? #(str/ends-with? % "_http.clj") (keys (files-under dir2))))
        (let [system (boot-module dir2 "bou497nh2")]
          (is (contains? (:wagoe/billing-entities system) :invoice-line-item))
          (is (not (contains? (api-paths system) "/invoice-line-items"))))))
    (testing "the module boots, with the entity and no API for it"
      (let [system (boot-module dir "bou497nh")]
        (is (contains? (:wagoe/billing-entities system) :invoice-line-item))
        (is (empty? (api-paths system)))))
    (testing "the CLI's entity command takes --no-http"
      (let [dir3 (invoice-module! (temp-dir) "bou497nh3")]
        (with-out-str
          (is (= 0 (cli/run-cli! svc ["entity" "--module-name" "billing" "--entity" "InvoiceLineItem"
                                      "--belongs-to" "invoice" "--field" "quantity:int"
                                      "--no-http" "--base-ns" "bou497nh3"
                                      "--output-dir" (.getPath dir3)]))))
        (is (not-any? #(str/ends-with? % "_http.clj") (keys (files-under dir3))))))))

;; =============================================================================
;; The first entity's API reaches its service (BOU-539)
;; =============================================================================

(defn- h2-migrated [dir tag]
  (let [ctx (db-factory/db-context {:adapter :h2
                                    :database-path (str "mem:" tag (System/nanoTime) ";DB_CLOSE_DELAY=-1")
                                    :pool {:minimum-idle 1 :maximum-pool-size 2}})]
    (doseq [[path sql] (files-under dir)
            :when (str/ends-with? path ".up.sql")
            st (statements sql)]
      (jdbc/execute! (:datasource ctx) [st]))
    ctx))

(defn- api-caller [routes]
  (let [router (r/router routes)]
    (fn [method path body]
      (let [m (r/match-by-path router path)]
        ((get-in m [:data method :handler])
         {:request-method method :path-params (:path-params m) :body-params body})))))

(deftest ^:integration the-first-entity-api-reaches-its-service
  ;; Its handlers answered canned bodies: POST gave 201 and {}, and nothing was
  ;; ever written.
  (let [dir (temp-dir)
        r   (ports/generate-module svc {:module-name "billing" :base-ns "bou539"
                                        :entities    [{:name "Invoice"
                                                       :fields [{:name :number :type :string :required true}
                                                                {:name :total :type :decimal :required true}]}]
                                        :output-dir  (.getPath dir)})]
    (is (:success r) (pr-str (:errors r)))
    (load-and-test! dir)
    (let [db      (h2-migrated dir "bou539")
          at      (fn [n s] @(ns-resolve (symbol (str "bou539.billing." n)) s))
          svc     ((at "shell.service" 'create-service) ((at "shell.persistence" 'create-repository) db))
          contrib ((at "shell.http" 'billing-routes) svc {})
          call    (api-caller (:api contrib))
          created (call :post "/invoices" {:number "A-1" :total 9.99 :sneaky "dropped"})
          id      (get-in created [:body :id])]
      (is (= #{:api :web :static} (set (keys contrib))))
      (testing "POST creates a row, and GET shows it"
        (is (= 201 (:status created)))
        (is (uuid? id))
        (let [got (call :get (str "/invoices/" id) nil)]
          (is (= 200 (:status got)))
          (is (= "A-1" (get-in got [:body :number])))
          (is (== 9.99 (get-in got [:body :total]))))
        (is (= [id] (map :id (:body (call :get "/invoices" nil))))))
      (testing "PUT updates the row"
        (is (= "A-2" (get-in (call :put (str "/invoices/" id) {:number "A-2"}) [:body :number]))))
      (testing "bad bodies are a 400"
        (is (= 400 (:status (call :post "/invoices" {:number "A-3" :total "cheap"}))))
        (is (= 400 (:status (call :post "/invoices" {}))))
        (is (= 400 (:status (call :put (str "/invoices/" id) {})))))
      (testing "an unknown id is a 404"
        (is (= 404 (:status (call :get (str "/invoices/" (java.util.UUID/randomUUID)) nil))))
        (is (= 404 (:status (call :put (str "/invoices/" (java.util.UUID/randomUUID)) {:number "x"}))))))))

;; =============================================================================
;; Repository update (BOU-547)
;; =============================================================================

(defn- ->instant [x]
  (cond (instance? java.time.Instant x)        x
        (instance? java.time.OffsetDateTime x) (.toInstant ^java.time.OffsetDateTime x)
        (instance? java.util.Date x)           (.toInstant ^java.util.Date x)
        (string? x)                            (java.time.Instant/parse x)))

(deftest ^:integration a-repository-update-bumps-updated-at-and-refuses-an-empty-one
  (let [dir (invoice-module! (temp-dir) "bou547u")]
    (add-line-item! dir "bou547u")
    (load-and-test! dir)
    (let [db   (h2-migrated dir "bou547u")
          at   (fn [n s] @(ns-resolve (symbol (str "bou547u.billing." n)) s))
          t0   (java.time.Instant/parse "2020-01-01T00:00:00Z")
          inv  (random-uuid)
          line (random-uuid)
          ;; [repository create update row-without-audit-columns]
          repos {"the first entity"
                 [((at "shell.persistence" 'create-repository) db)
                  (at "ports" 'create) (at "ports" 'update-entity)
                  {:id inv :number "A-1"} {:number "A-2"}]
                 "a further entity"
                 [((at "shell.invoice-line-item-persistence" 'create-repository) db)
                  (at "ports" 'create-invoice-line-item-entity) (at "ports" 'update-invoice-line-item-entity)
                  {:id line :invoice-id inv :description "x" :quantity 1} {:quantity 2}]}]
      (doseq [[label [repo create! update! row change]] repos]
        (testing label
          (create! repo (assoc row :created-at t0 :updated-at t0))
          (testing "an update moves updated-at"
            (let [updated (update! repo (merge {:id (:id row)} change))]
              (is (= (val (first change)) (get updated (key (first change)))))
              (is (.isAfter ^java.time.Instant (->instant (:updated-at updated)) t0)
                  (pr-str (:updated-at updated)))))
          (testing "an update with nothing to set is a typed error, not UPDATE t SET WHERE"
            (let [e (try (update! repo {:id (:id row)}) nil
                         (catch clojure.lang.ExceptionInfo e e))]
              (is (= :validation-error (:type (ex-data e))) (pr-str e)))))))))

(deftest ^:integration a-date-field-round-trips-as-a-date
  ;; `due:date` was an instant: a TIMESTAMP WITH TIME ZONE column, and a
  ;; 2026-01-01 posted came back as 2026-01-01T00:00:00Z (BOU-547).
  (let [dir (temp-dir)
        due (cli/parse-field-spec "due:date:required")
        r   (ports/generate-module svc {:module-name "billing" :base-ns "bou547d"
                                        :entities    [{:name "Invoice"
                                                       :fields [{:name :number :type :string :required true}
                                                                due]}]
                                        :output-dir  (.getPath dir)})]
    (is (:success r) (pr-str (:errors r)))
    (load-and-test! dir)
    (doseq [[label db] [["H2" (h2-migrated dir "bou547d")]
                        ["SQLite" (let [f (java.io.File/createTempFile "bou547d" ".db")
                                        ctx (db-factory/db-context {:adapter :sqlite :database-path (.getPath f)})]
                                    (doseq [[path sql] (files-under dir)
                                            :when (str/ends-with? path ".up.sql")
                                            st (statements sql)]
                                      (jdbc/execute! (:datasource ctx) [st]))
                                    ctx)]]]
      (testing label
        (let [at      (fn [n s] @(ns-resolve (symbol (str "bou547d.billing." n)) s))
              svc     ((at "shell.service" 'create-service) ((at "shell.persistence" 'create-repository) db))
              call    (api-caller (:api ((at "shell.http" 'billing-routes) svc {})))
              json    (fn [body] (slurp (muuntaja/encode muuntaja/instance "application/json" body)))
              created (call :post "/invoices" {:number "A-1" :due "2026-01-01"})
              id      (get-in created [:body :id])]
          (is (= 201 (:status created)) (pr-str created))
          (is (str/includes? (json (:body (call :get (str "/invoices/" id) nil))) "\"due\":\"2026-01-01\""))
          (is (str/includes? (json (:body (call :put (str "/invoices/" id) {:due "2026-02-01"})))
                             "\"due\":\"2026-02-01\""))
          (is (= 400 (:status (call :post "/invoices" {:number "A-2" :due "2026-13-45"})))))))))

(deftest ^:integration an-indexed-field-added-later-migrates-up-and-down-on-sqlite
  ;; BOU-535. SQLite refuses to drop an indexed column, so the down migration
  ;; drops the index first.
  (let [dir (invoice-module! (temp-dir) "bou535")
        r   (ports/add-field svc {:module-name "billing" :base-ns "bou535" :entity "Invoice"
                                  :field {:name :sku :type :string :indexed true}
                                  :output-dir (.getPath dir)})
        f   (java.io.File/createTempFile "bou535" ".db")
        ds  (jdbc/get-datasource {:jdbcUrl (str "jdbc:sqlite:" (.getPath f))})
        run (fn [suffix] (doseq [[path sql] (files-under dir)
                                 :when (str/ends-with? path suffix)
                                 st (statements sql)]
                           (jdbc/execute! ds [st])))
        idx (fn [] (jdbc/execute! ds ["SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'idx_invoices_sku'"]))]
    (is (:success r) (pr-str (:errors r)))
    (try
      (run ".up.sql")
      (is (= 1 (count (idx))) "the up migration creates the index")
      (run "-add-sku-to-invoices.down.sql")
      (is (empty? (idx)))
      (finally (.delete f)))))
