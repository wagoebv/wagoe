(ns wagoe.scaffolder.seed-skeleton-test
  "`bb scaffold` writes resources/seeds/dev.edn with a commented example per
   entity, and the examples, uncommented, seed the tables it generated
   (BOU-588)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [wagoe.platform.shell.database.seed :as seed]
            [wagoe.scaffolder.cli :as cli]
            [wagoe.scaffolder.core.generators :as generators]
            [wagoe.scaffolder.shell.service :as service]))

(def ^:private svc (service/create-scaffolder-service))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "wagoe-seed-skeleton" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- scaffold! [dir & args]
  (with-out-str (cli/run-cli! svc (into (vec args) ["--base-ns" "acme" "--output-dir" (.getPath dir)]))))

(defn- seeds [dir] (io/file dir "resources/seeds/dev.edn"))

(defn- uncomment
  "The seed file as a user makes it: every `;; ` example line uncommented."
  [s]
  (str/join "\n" (map #(str/replace % #"^(\s*);; " "$1") (str/split-lines s))))

(defn- migrate-h2! [dir]
  (let [ds (jdbc/get-datasource {:jdbcUrl (str "jdbc:h2:mem:seedskel" (System/nanoTime) ";DB_CLOSE_DELAY=-1")})]
    (doseq [f (sort-by #(.getName %) (file-seq (io/file dir "migrations")))
            :when (str/ends-with? (.getName f) ".up.sql")
            s (str/split (slurp f) #"--;;")
            :let [s (str/join "\n" (remove #(str/starts-with? (str/trim %) "--") (str/split-lines s)))]
            :when (not (str/blank? s))]
      (jdbc/execute! ds [s]))
    ds))

(deftest ^:integration generate-and-entity-write-examples-that-seed
  (let [dir (temp-dir)]
    (scaffold! dir "generate" "--module-name" "billing" "--entity" "Invoice"
          "--field" "number:string:required:unique" "--field" "total:decimal"
          "--workflow" "status:entered>delivered>paid")
    (let [after-generate (slurp (seeds dir))]
      (testing "the file is all comments until someone uncomments an example"
        (is (= [] (edn/read-string after-generate))))
      (is (str/includes? after-generate ":invoices"))
      (is (str/includes? after-generate ":status :entered") "the workflow's first state")
      (is (str/includes? after-generate "entered, delivered, paid")))
    (scaffold! dir "entity" "--module-name" "billing" "--entity" "InvoiceLineItem" "--belongs-to" "invoice"
          "--field" "description:string:required" "--field" "quantity:int:required")
    (let [content (slurp (seeds dir))]
      (is (str/includes? content ":invoice-line-items"))
      (is (re-find #":invoice-id\s+:invoice/example" content) "the child names its parent")
      (testing "uncommented, the examples seed the generated tables"
        (let [ds     (migrate-h2! dir)
              result (seed/seed! ds (edn/read-string (uncomment content)))]
          (is (nil? (:error result)) (pr-str result))
          (is (= 2 (get-in result [:ok :rows])))
          (is (= 1 (count (jdbc/execute! ds ["SELECT * FROM invoice_line_items l JOIN invoices i ON i.id = l.invoice_id"])))))))))

(deftest ^:integration existing-seeds-are-never-overwritten
  (let [dir  (temp-dir)
        mine "; mine\n[[:customers\n  [{:name \"Acme\"}]]]\n"]
    (io/make-parents (seeds dir))
    (spit (seeds dir) mine)
    (scaffold! dir "generate" "--module-name" "billing" "--entity" "Invoice" "--field" "number:string:required")
    (let [content (slurp (seeds dir))]
      (is (str/starts-with? content "; mine\n[[:customers\n  [{:name \"Acme\"}]]"))
      (is (= [[:customers [{:name "Acme"}]]] (edn/read-string content)) "the data is unchanged")
      (is (str/includes? content ";; [:invoices") "the example goes inside the vector")
      (is (= [[:customers [{:name "Acme"}]] [:invoices [{:id :invoice/example :number "Example number"}]]]
             (edn/read-string (uncomment content)))))
    (testing "a second run adds nothing for an entity already there"
      (let [before (slurp (seeds dir))]
        (scaffold! dir "generate" "--module-name" "billing" "--entity" "Invoice" "--field" "number:string:required" "--force")
        (is (= before (slurp (seeds dir))))))))

(deftest ^:unit a-map-seed-file-gets-map-entries
  (let [r (generators/add-seed-examples "{:customers [{:name \"Acme\"}]}\n"
                                        [{:entity-kebab "invoice" :entity-name "Invoice" :entity-plural "invoices"
                                          :fields [{:field-name-kebab "number" :field-type :string}]}])]
    (is (= {:customers [{:name "Acme"}]} (edn/read-string (:content r))))
    (is (= {:customers [{:name "Acme"}] :invoices [{:id :invoice/example :number "Example number"}]}
           (edn/read-string (uncomment (:content r)))))))
