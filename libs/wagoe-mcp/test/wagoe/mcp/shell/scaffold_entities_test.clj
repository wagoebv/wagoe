(ns wagoe.mcp.shell.scaffold-entities-test
  "`scaffold-module` advertises an `entities` array and generated only the
   first entry (BOU-514). Run against the real scaffolder, into a temp dir."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.mcp.shell.audit :as audit]
            [wagoe.mcp.shell.tools :as tools]
            [wagoe.scaffolder.ports :as scaffold]
            [wagoe.scaffolder.shell.service :as scaffolder]))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "mcp-entities" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- into-dir
  "The real scaffolder, writing under `dir`. The tool writes relative to the
   project it runs in; a test must not write into this repository."
  [dir]
  (let [real (scaffolder/create-scaffolder-service)
        at   #(assoc % :output-dir (.getPath dir))]
    (reify scaffold/IScaffolderService
      (generate-module [_ req] (scaffold/generate-module real (at req)))
      (add-entity [_ req] (scaffold/add-entity real (at req)))
      (add-field [_ req] (scaffold/add-field real (at req)))
      (add-endpoint [_ req] (scaffold/add-endpoint real (at req)))
      (add-adapter [_ req] (scaffold/add-adapter real (at req)))
      (add-subscriber [_ req] (scaffold/add-subscriber real (at req))))))

(deftest ^:integration scaffold-module-generates-every-entity
  (let [dir (temp-dir)
        r   (tools/run {:scaffolder  (into-dir dir)
                        :test-runner (fn [_] {:status :passed :passed 1 :failed 0})
                        :audit       (audit/in-memory-audit-log)}
                       "scaffold-module"
                       {:module   "billing"
                        :entities [{:name "Invoice" :fields [{:name "number" :type "string"}]}
                                   {:name "InvoiceLineItem" :belongs-to "invoice"
                                    :fields [{:name "quantity" :type "int"}]}]})
        on-disk (fn [p] (.isFile (io/file dir p)))]
    (testing "both entities are on disk"
      (is (on-disk "src/wagoe/billing/core/invoice.clj"))
      (is (on-disk "src/wagoe/billing/core/invoice_line_item.clj"))
      (is (on-disk "src/wagoe/billing/shell/invoice_line_item_service.clj")))
    (testing "the report lists the files of both"
      (let [paths (map :path (:files r))]
        (is (some #(str/ends-with? % "core/invoice.clj") paths))
        (is (some #(str/ends-with? % "core/invoice_line_item.clj") paths))))
    (testing "belongs-to reaches the scaffolder"
      (let [up (->> (file-seq (io/file dir "migrations"))
                    (filter #(str/includes? (.getName %) "create-invoice-line-items.up"))
                    first
                    slurp)]
        (is (str/includes? up "invoice_id UUID NOT NULL REFERENCES invoices(id)"))))))

(deftest ^:integration a-preview-writes-nothing
  ;; BOU-490: `bb scaffold ai --dry-run` wrote every file. This path is one
  ;; generate-module call, so one :dry-run covers every entity.
  (let [dir (temp-dir)
        r   (tools/run {:scaffolder  (into-dir dir)
                        :test-runner (fn [_] {:status :passed :passed 1 :failed 0})
                        :audit       (audit/in-memory-audit-log)}
                       "scaffold-module"
                       {:module   "billing"
                        :preview  true
                        :entities [{:name "Invoice" :fields [{:name "number" :type "string"}]}
                                   {:name "InvoiceLineItem" :belongs-to "invoice"
                                    :fields [{:name "quantity" :type "int"}]}]})]
    (is (= :preview (:status r)))
    (is (some #(str/ends-with? (:path %) "core/invoice_line_item.clj") (:plan r))
        "the plan covers the second entity")
    (is (empty? (.listFiles dir)) "a preview must not write files")))
