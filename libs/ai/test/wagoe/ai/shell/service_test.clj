(ns wagoe.ai.shell.service-test
  (:require [wagoe.ai.ports :as ports]
            [wagoe.ai.shell.service :as svc]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

;; =============================================================================
;; Mock provider
;; =============================================================================

(defn- mock-provider
  "Create a mock IAIProvider for integration tests.

   Args:
     complete-fn      - fn called by (complete ...) returning a result map
     complete-json-fn - fn called by (complete-json ...) returning a result map"
  [complete-fn complete-json-fn]
  (reify ports/IAIProvider
    (complete [_ messages opts]
      (complete-fn messages opts))
    (complete-json [_ messages schema opts]
      (complete-json-fn messages schema opts))
    (provider-name [_] :mock)))

(defn- ok-service
  "Return a service map backed by a mock provider that returns success."
  [text]
  {:provider (mock-provider
              (fn [_ _] {:text text :tokens 10 :provider :mock :model "mock"})
              (fn [_ _ _] {:data {:module-name "product"
                                  :entity "Product"
                                  :fields [{:name "price" :type "decimal" :required true :unique false}]
                                  :http true :web true}
                           :tokens 10 :provider :mock :model "mock"}))})

(defn- error-service
  "Return a service map backed by a mock provider that returns errors."
  []
  {:provider (mock-provider
              (fn [_ _] {:error "mock provider error" :provider :mock :model "mock"})
              (fn [_ _ _] {:error "mock provider error" :provider :mock :model "mock"}))})

(defn- fallback-service
  "Return a service with a failing primary and a succeeding fallback."
  [text]
  {:provider (mock-provider
              (fn [_ _] {:error "primary failed" :provider :mock :model "mock"})
              (fn [_ _ _] {:error "primary failed" :provider :mock :model "mock"}))
   :fallback (mock-provider
              (fn [_ _] {:text text :tokens 5 :provider :fallback :model "fallback"})
              (fn [_ _ _] {:data {:module-name "product" :entity "Product" :fields [] :http true :web true}
                           :tokens 5 :provider :fallback :model "fallback"}))})

;; =============================================================================
;; explain-error tests
;; =============================================================================

(deftest ^:integration explain-error-test
  (testing "returns AI response text on success"
    (let [service (ok-service "Root cause: nil pointer")
          result  (svc/explain-error service "ExceptionInfo: schema failed" ".")]
      (is (= "Root cause: nil pointer" (:text result)))
      (is (= :mock (:provider result)))))

  (testing "returns error map on provider failure"
    (let [service (error-service)
          result  (svc/explain-error service "ExceptionInfo: ..." ".")]
      (is (contains? result :error))))

  (testing "falls back to secondary provider on primary failure"
    (let [service (fallback-service "fallback explanation")
          result  (svc/explain-error service "ExceptionInfo: ..." ".")]
      (is (= "fallback explanation" (:text result)))
      (is (= :fallback (:provider result)))))

  (testing "BOU-512: the known fix reaches the prompt, not the provider's opts"
    (let [seen    (atom nil)
          service {:provider (mock-provider
                              (fn [messages opts]
                                (reset! seen {:messages messages :opts opts})
                                {:text "ok" :provider :mock :model "mock"})
                              (fn [_ _ _] nil))}]
      (svc/explain-error service "ExceptionInfo: boom" "."
                         {:known-fix "Give it :enabled? true" :max-tokens 10})
      (is (str/includes? (:content (second (:messages @seen))) "Give it :enabled? true"))
      (is (= {:max-tokens 10} (:opts @seen))))))

;; =============================================================================
;; scaffold-from-description tests
;; =============================================================================

(deftest ^:integration scaffold-from-description-test
  (testing "returns parsed module spec from provider JSON data"
    (let [service (ok-service "ignored")
          result  (svc/scaffold-from-description service "product module with name" ".")]
      (is (= "product" (:module-name result)))
      (is (= "Product" (:entity result)))
      (is (= [{:name "price" :type "decimal" :required true :unique false}]
             (:fields result)))))

  (testing "returns error map on provider failure"
    (let [service (error-service)
          result  (svc/scaffold-from-description service "product module with name" ".")]
      (is (contains? result :error)))))

;; =============================================================================
;; generate-tests tests
;; =============================================================================

(deftest ^:integration generate-tests-test
  (testing "returns error when source file does not exist"
    (let [service (ok-service "(deftest foo-test ...)")
          result  (svc/generate-tests service "/nonexistent/path/file.clj")]
      (is (contains? result :error))))

  (testing "returns generated test text on success"
    (let [tmp     (java.io.File/createTempFile "test-gen" ".clj")
          _       (spit tmp "(ns wagoe.foo.core.bar) (defn my-fn [x] x)")
          service (ok-service "(deftest my-fn-test (is (= 1 (my-fn 1))))")
          result  (svc/generate-tests service (.getPath tmp))]
      (.delete tmp)
      (is (string? (:text result))))))

;; The three repairs below are unit-tested in wagoe.ai.core.parsing-test. What
;; these check is that generate-tests *calls* them: measured, the model omits
;; the metadata and the requires on almost every run, so a repair that is
;; written but not wired leaves the tool exactly as broken as before.

(defn- generate-from
  "Run generate-tests against a throwaway source file, with `answer` as the
   model's reply. Returns the result map."
  [answer]
  (let [tmp (java.io.File/createTempFile "gen" ".clj")]
    (try
      (spit tmp "(ns x) (defn my-fn [a] a)")
      (svc/generate-tests (ok-service answer) (.getPath tmp))
      (finally (.delete tmp)))))

(deftest ^:integration generate-tests-applies-the-repairs
  (testing "metadata is stamped by the code, not left to the model"
    ;; Kaocha selects suites on it; without it the namespace runs in no suite.
    ;; The test touches no I/O, so it is a unit test wherever the source lives.
    (let [result (generate-from "(ns x-test)\n(deftest my-fn-test\n  (is (= 1 1)))")]
      (is (= #{:unit} (:test-tags result)))
      (is (str/includes? (:text result) "(deftest ^:unit my-fn-test"))))

  (testing "a namespace used but not required is repaired"
    (let [result (generate-from
                  (str "(ns x-test\n  (:require [clojure.test :refer [deftest is]]))\n"
                       "(deftest a-test (is (str/blank? \"\")))"))]
      (is (str/includes? (:text result) "[clojure.string :as str]"))))

  (testing "an answer cut off mid-form is an error, not a file to write"
    (let [result (generate-from "(ns x-test)\n(deftest a-test\n  (is (= 1 (my-fn")]
      (is (contains? result :error))
      (is (str/includes? (:error result) "cut off"))
      ;; The partial answer is kept for diagnosis but not offered as :text,
      ;; which is what the CLI writes to disk.
      (is (nil? (:text result)))
      (is (string? (:raw result)))))

  (testing "the conventional destination travels with the result"
    ;; The CLI's --write has nowhere to look if the service does not report it.
    (let [root (java.io.File/createTempFile "genroot" "")
          src  (io/file root "libs/demo/src/wagoe/demo/core/thing.clj")]
      (.delete root)
      (io/make-parents src)
      (spit src "(ns wagoe.demo.core.thing) (defn my-fn [a] a)")
      (let [result (svc/generate-tests (ok-service "(ns t-test)\n(deftest a-test (is true))")
                                       (.getPath src))]
        (is (str/ends-with? (:test-path result)
                            "libs/demo/test/wagoe/demo/core/thing_test.clj")
            (str "got " (pr-str (:test-path result))))
        (is (= #{:unit} (:test-tags result))))))

  (testing "a source path with no src segment reports no destination"
    ;; Better than inventing one: --write refuses and asks for -o.
    (is (nil? (:test-path (generate-from "(ns x-test)\n(deftest a-test (is true))"))))))

;; BOU-572 -------------------------------------------------------------------

(defn- scripted-service
  "A service whose provider gives `answers` in turn, recording each call's
   messages."
  [answers]
  (let [calls (atom [])]
    [{:provider (mock-provider
                 (fn [msgs _]
                   (let [n (count (swap! calls conj msgs))]
                     {:text (nth answers (min (dec n) (dec (count answers))))
                      :tokens 1 :provider :mock :model "mock"}))
                 (fn [_ _ _] nil))}
     calls]))

(defn- with-source
  "Call `f` with the path of a throwaway source file holding `src`."
  [src f]
  (let [tmp (java.io.File/createTempFile "gen" ".clj")]
    (try (spit tmp src) (f (.getPath tmp))
         (finally (.delete tmp)))))

(def ^:private ports-source
  "(ns wagoe.demo.shell.thing (:require [wagoe.ai.ports :as ports]))
(defn name-of [p] (ports/provider-name p))")

(deftest ^:integration generate-tests-shows-the-real-api
  (let [[svc calls] (scripted-service ["(ns t-test)\n(deftest a-test (is (= 1 1)))"])]
    (with-source ports-source #(svc/generate-tests svc %))
    (let [prompt (:content (second (first @calls)))]
      (is (str/includes? prompt "protocol ports/IAIProvider"))
      (is (str/includes? prompt "(complete-json [this messages schema opts])")))))

(deftest ^:integration generate-tests-retries-once-on-a-failed-check
  (testing "the errors go back to the model, and a fixed answer is kept"
    (let [[svc calls] (scripted-service ["(ns t-test)\n(deftest a-test (is (= 1 (bad))))"
                                         "(ns t-test)\n(deftest a-test (is (= 1 1)))"])
          check  (fn [text] (when (str/includes? text "bad") ["[t-test] Unable to resolve symbol: bad"]))
          result (with-source ports-source #(svc/generate-tests svc % {:check check}))]
      (is (= 2 (count @calls)))
      (is (str/includes? (:content (last (second @calls))) "Unable to resolve symbol: bad"))
      (is (nil? (:check-errors result)))
      (is (str/includes? (:text result) "(is (= 1 1))"))))

  (testing "a second failure is returned with its errors, and nothing more is asked"
    (let [[svc calls] (scripted-service ["(ns t-test)\n(deftest a-test (is (= 1 (bad))))"])
          result (with-source ports-source
                   #(svc/generate-tests svc % {:check (constantly ["still broken"])}))]
      (is (= 2 (count @calls)))
      (is (= ["still broken"] (:check-errors result)))
      (is (string? (:text result)) "--force can still write it")))

  (testing "without a check there is one call"
    (let [[svc calls] (scripted-service ["(ns t-test)\n(deftest a-test (is (= 1 1)))"])]
      (with-source ports-source #(svc/generate-tests svc %))
      (is (= 1 (count @calls))))))

(deftest ^:integration generate-tests-shows-the-schemas-it-requires
  ;; BOU-511: with only the var name the model invented the entity's keys.
  (let [[svc calls] (scripted-service ["(ns t-test)\n(deftest a-test (is (= 1 1)))"])]
    (with-source "(ns wagoe.demo.core.msg (:require [wagoe.ai.schema :as schema]))\n(defn role [m] (:role m))"
      #(svc/generate-tests svc %))
    (let [prompt (:content (second (first @calls)))]
      (is (str/includes? prompt "schema schema/Message\n    [:map [:role [:enum :system :user :assistant]] [:content :string]]")
          prompt))))

(deftest ^:integration generate-tests-says-the-tests-run-only-with-run
  (doseq [[opts expected] [[{} false] [{:run? true} true]]]
    (let [[svc calls] (scripted-service ["(ns t-test)\n(deftest a-test (is (= 1 1)))"])]
      (with-source ports-source #(svc/generate-tests svc % opts))
      (is (= expected (str/includes? (:content (first (first @calls))) "The tests are run"))
          (pr-str opts)))))

(deftest ^:integration generate-tests-shows-the-modules-schema-unrequired
  ;; Most core namespaces never require their module's schema namespace.
  (let [[svc calls] (scripted-service ["(ns t-test)\n(deftest a-test (is (= 1 1)))"])]
    (with-source "(ns wagoe.ai.core.msg-roles)\n(defn role [m] (:role m))"
      #(svc/generate-tests svc %))
    (is (str/includes? (:content (second (first @calls))) "schema wagoe.ai.schema/Message"))))

;; =============================================================================
;; sql-from-description tests
;; =============================================================================

(defn- json-service
  "A service whose provider gives the JSON `answers` in turn, recording each
   call's messages."
  [answers]
  (let [calls (atom [])]
    [{:provider (mock-provider
                 (fn [_ _] nil)
                 (fn [msgs _ _]
                   (let [n (count (swap! calls conj msgs))]
                     {:data (nth answers (min (dec n) (dec (count answers))))
                      :tokens 1 :provider :mock :model "mock"})))}
     calls]))

(defn- with-project
  "Call `f` with the root of a throwaway project whose migrations hold `sql`,
   and no library tables."
  [sql f]
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "sql" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (when sql
        (io/make-parents (io/file root "migrations" "x"))
        (spit (io/file root "migrations" "20260101000001-invoices.up.sql") sql))
      (with-redefs [svc/library-migrations (constantly []) svc/boot-tables (constantly [])]
        (f (.getPath root)))
      (finally (run! io/delete-file (reverse (file-seq root)))))))

(def ^:private ledger-sql
  "CREATE TABLE invoices (id UUID PRIMARY KEY, status VARCHAR(20) NOT NULL);
   CREATE TABLE invoice_line_items (id UUID, invoice_id UUID, line_total DECIMAL(10,2));")

(def ^:private invented
  {:honeysql    (pr-str {:select [:*] :from [:invoice] :where [:= :invoice/status "entered"]})
   :explanation "guessed"})

(def ^:private correct
  {:honeysql    (pr-str {:select [:*] :from [:invoices] :where [:= :invoices/status "entered"]})
   :explanation "entered invoices"})

(deftest ^:integration sql-from-description-shows-the-schema
  (let [[svc calls] (json-service [correct])
        result      (with-project ledger-sql #(svc/sql-from-description svc "entered invoices" %))
        prompt      (:content (second (first @calls)))]
    (is (str/includes? prompt "invoices: id UUID, status VARCHAR(20)"))
    (is (str/includes? prompt "invoice_line_items: id UUID, invoice_id UUID, line_total DECIMAL(10,2)"))
    (is (= {:honeysql    (:honeysql correct)
            :explanation "entered invoices"
            :raw-sql     "SELECT * FROM invoices WHERE invoices.status = 'entered'"}
           result))))

(deftest ^:integration sql-from-description-refuses-invented-tables
  (testing "BOU-513: an invented table is sent back once, then refused"
    (let [[svc calls] (json-service [invented])
          result      (with-project ledger-sql #(svc/sql-from-description svc "entered invoices" %))]
      (is (= 2 (count @calls)))
      (is (str/includes? (:content (last (second @calls))) "table invoice does not exist"))
      (is (str/includes? (:error result) "table invoice does not exist"))
      (is (nil? (:raw-sql result)))))

  (testing "a corrected second answer is returned"
    (let [[svc calls] (json-service [invented correct])
          result      (with-project ledger-sql #(svc/sql-from-description svc "entered invoices" %))]
      (is (= 2 (count @calls)))
      (is (= (:honeysql correct) (:honeysql result))))))

(deftest ^:integration sql-from-description-needs-a-schema
  (let [[svc calls] (json-service [correct])
        result      (with-project nil #(svc/sql-from-description svc "entered invoices" %))]
    (is (str/includes? (:error result) "cannot generate SQL without a schema"))
    (is (empty? @calls) "the model is not asked to guess")))

(deftest ^:integration tables-created-in-code-are-known-by-name
  ;; users comes from a Clojure migration: there is no SQL to read columns from.
  (let [[svc calls] (json-service [{:honeysql (pr-str {:select [:users/email] :from [:users]})}])
        result      (with-redefs [svc/boot-tables (constantly ["users"])]
                      (svc/sql-from-description svc "user emails" "/nonexistent"))]
    (is (contains? (set (#'svc/boot-tables)) "users") "read from the user library's manifest")
    (is (str/includes? (:content (second (first @calls))) "users: (columns not listed)"))
    (is (= "SELECT users.email FROM users" (:raw-sql result)))))

(deftest ^:integration library-migrations-are-read-from-jars
  (let [jar (java.io.File/createTempFile "lib" ".jar")
        put (fn [^java.util.jar.JarOutputStream out ^String entry ^String content]
              (.putNextEntry out (java.util.jar.JarEntry. entry))
              (when content (.write out (.getBytes content "UTF-8")))
              (.closeEntry out))
        th  (Thread/currentThread)
        old (.getContextClassLoader th)]
    (try
      (with-open [out (java.util.jar.JarOutputStream. (io/output-stream jar))]
        (doseq [d ["wagoe/" "wagoe/migration-paths/" "wagoe/demo/" "wagoe/demo/migrations/"]]
          (put out d nil))
        (put out "wagoe/migration-paths/demo.edn" "{:paths [\"wagoe/demo/migrations/\"]}")
        (put out "wagoe/demo/migrations/20260101000001-demo.up.sql" "CREATE TABLE demos (id UUID);")
        (put out "wagoe/demo/migrations/20260101000001-demo.down.sql" "DROP TABLE demos;"))
      (.setContextClassLoader th (java.net.URLClassLoader. (into-array [(.toURL (.toURI jar))]) nil))
      (is (= [["20260101000001-demo.up.sql" "CREATE TABLE demos (id UUID);"]]
             (#'svc/library-migrations)))
      (finally
        (.setContextClassLoader th old)
        (.delete jar)))))

;; =============================================================================
;; generate-docs tests
;; =============================================================================

(deftest ^:integration generate-docs-test
  (testing "returns documentation text for :agents type"
    (let [service (ok-service "# Module Docs\n\n## Purpose\nDoes stuff.")
          result  (svc/generate-docs service "libs/core" :agents)]
      (is (= "# Module Docs\n\n## Purpose\nDoes stuff." (:text result))))))

;; =============================================================================
;; generate-admin-entity tests
;; =============================================================================

(deftest ^:integration generate-admin-entity-test
  (testing "BOU-493: an ```edn-fenced answer is accepted and written unfenced"
    (let [result (svc/generate-admin-entity
                  (ok-service "```edn\n{:products {:label \"Products\" :table-name :products}}\n```")
                  "products with name" "/nonexistent")]
      (is (nil? (:error result)))
      (is (= "products" (:entity-name result)))
      (is (= "{:products {:label \"Products\" :table-name :products}}" (:text result))))))

;; BOU-567 -------------------------------------------------------------------

(defn- recording-service
  "A service whose provider answers `text` (merged with `extra`) and records
   the messages and opts it was called with."
  [text & [extra]]
  (let [seen (atom nil)]
    [{:provider (mock-provider
                 (fn [msgs opts]
                   (reset! seen {:msgs msgs :opts opts})
                   (merge {:text text :tokens 1 :provider :mock :model "mock"} extra))
                 (fn [_ _ _] nil))}
     seen]))

(defn- temp-project
  "A project root holding `migrations`, a map of file name to SQL."
  [migrations]
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "admin-entity" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (doseq [[fname sql] migrations]
      (let [f (io/file root "migrations" fname)]
        (io/make-parents f)
        (spit f sql)))
    (.getPath root)))

(def ^:private invoices-sql
  "CREATE TABLE IF NOT EXISTS invoices (
  id UUID PRIMARY KEY,
  issue_date DATE NOT NULL,
  total_in_cents INTEGER NOT NULL,
  status VARCHAR(50) NOT NULL
);")

(def ^:private invoices-edn
  "{:invoices {:label \"Invoices\" :table-name :invoices
              :fields {:issue-date {:type :instant :label \"Issue Date\"}
                       :total-in-cents {:type :decimal :label \"Total\"}
                       :status {:type :enum :label \"Status\" :options [[:open \"Open\"]]}}}}")

(deftest ^:integration admin-entity-truncation-is-reported-as-such
  (testing "the provider says the limit stopped it"
    (let [[svc] (recording-service "{:invoices {:label \"Invoices\"" {:truncated? true})
          r     (svc/generate-admin-entity svc "invoices" "/nonexistent")]
      (is (str/includes? (:error r) "cut off"))
      (is (= :mock (:provider r)))
      (is (nil? (:text r)))))

  (testing "no stop reason, but the braces never close"
    (let [[svc] (recording-service "{:invoices {:label \"Invoices\" :table-name :invoices")
          r     (svc/generate-admin-entity svc "invoices" "/nonexistent")]
      (is (str/includes? (:error r) "cut off"))))

  (testing "the budget asked for fits a parent-and-child config"
    (let [[svc seen] (recording-service invoices-edn)]
      (svc/generate-admin-entity svc "invoices" "/nonexistent")
      (is (<= 8192 (get-in @seen [:opts :max-tokens]))))))

(deftest ^:integration admin-entity-types-follow-the-table
  (testing "with a migration for the table, its column types win"
    (let [root       (temp-project {"20260101000000-create-invoices.up.sql" invoices-sql
                                    "20260101000000-create-invoices.down.sql" "DROP TABLE invoices;"})
          [svc seen] (recording-service invoices-edn)
          r          (svc/generate-admin-entity svc "invoices" root)
          fields     (get-in (edn/read-string (:text r)) [:invoices :fields])]
      (is (nil? (:error r)))
      (is (= :date (get-in fields [:issue-date :type])))
      (is (= :int (get-in fields [:total-in-cents :type])))
      (is (= :enum (get-in fields [:status :type])))
      (is (= {:source :migrations :table "invoices"}
             (:type-source (first (:entities r)))))
      (testing "and the model was shown the columns"
        (is (str/includes? (:content (first (:msgs @seen))) "issue_date DATE")))))

  (testing "without one, dates and counts are named by the field"
    (let [[svc] (recording-service invoices-edn)
          r     (svc/generate-admin-entity svc "invoices" "/nonexistent")
          fields (get-in (edn/read-string (:text r)) [:invoices :fields])]
      (is (= :date (get-in fields [:issue-date :type])))
      (is (= :int (get-in fields [:total-in-cents :type])))
      (is (= {:source :description} (:type-source (first (:entities r))))))))

(def ^:private parent-and-child
  "{:invoices {:label \"Invoices\" :table-name :invoices
              :has-many [{:entity :invoice-line-items :table :invoice_line_items
                          :foreign-key :invoice-id :label \"Lines\" :fields [:description]}]}
    :invoice-line-items {:label \"Lines\" :table-name :invoice-line-items
                         :sidebar-hidden true
                         :parent-context {:label \"Invoice\" :fields [:number]}}}")

(deftest ^:integration admin-entity-keeps-requested-relations
  (let [description "invoices; an invoice has many line items, which are sidebar-hidden with a parent-context"]
    (testing "a parent and its child come back as one file each"
      (let [[svc] (recording-service parent-and-child)
            r     (svc/generate-admin-entity svc description "/nonexistent")
            by-name (into {} (map (juxt :entity-name :text)) (:entities r))]
        (is (nil? (:error r)))
        (is (= #{"invoices" "invoice-line-items"} (set (keys by-name))))
        (is (contains? (get-in (edn/read-string (by-name "invoices")) [:invoices]) :has-many))
        (is (= #{:sidebar-hidden :parent-context}
               (-> (edn/read-string (by-name "invoice-line-items"))
                   :invoice-line-items
                   (select-keys [:sidebar-hidden :parent-context])
                   keys
                   set)))))

    (testing "an answer that drops one is refused, naming it"
      (let [[svc] (recording-service invoices-edn)
            r     (svc/generate-admin-entity svc description "/nonexistent")]
        (is (str/includes? (:error r) ":has-many"))
        (is (str/includes? (:error r) ":parent-context"))))

    (testing "the prompt teaches the keys"
      (let [[svc seen] (recording-service parent-and-child)]
        (svc/generate-admin-entity svc description "/nonexistent")
        (is (str/includes? (:content (first (:msgs @seen))) ":sidebar-hidden"))
        (is (str/includes? (:content (first (:msgs @seen))) ":parent-context"))))))

(deftest ^:integration admin-entity-is-validated-before-it-is-returned
  (let [[svc] (recording-service "{:x {:label \"X\" :table-name :x :fields {:mail {:type :email}}}}")
        r     (svc/generate-admin-entity svc "x" "/nonexistent")]
    (is (str/includes? (:error r) "entity config"))
    (is (str/includes? (:error r) ":mail"))))
