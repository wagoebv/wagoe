(ns wagoe.mcp.shell.tools-tier1-test
  "Tier 1 generate tools (BOU-101): scaffold via a stub IScaffolderService into
   a temp dir, then assert the closed verify loop's structured report and the
   audited soft-guardrail override."
  (:require [wagoe.mcp.shell.audit :as audit]
            [wagoe.mcp.shell.tools :as tools]
            [wagoe.mcp.core.tools :as tools-catalog]
            [wagoe.scaffolder.ports :as scaffold]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import (java.io File)))

(def ^:dynamic *tmp* nil)

;; Source files live under src/wagoe/tmp/core/ so clj-kondo's path→namespace
;; inference matches the (ns wagoe.tmp.core.thing) form.
(def ^:private core-dir-segments ["src" "wagoe" "tmp" "core"])

(use-fixtures :each
  (fn [t]
    (let [dir (File/createTempFile "mcp-tier1" "")]
      (.delete dir)
      (.mkdirs (apply io/file dir core-dir-segments))
      (.mkdirs (io/file dir "migrations"))
      (binding [*tmp* dir]
        (try (t) (finally (run! #(.delete %) (reverse (file-seq dir)))))))))

(def ^:private clean-core "(ns wagoe.tmp.core.thing)\n(defn add [a b] (+ a b))\n")
;; Requiring clojure.java.io from a core ns is an FC/IS violation (BND-806).
(def ^:private fcis-core "(ns wagoe.tmp.core.thing (:require [clojure.java.io :as io]))\n(defn f [] :ok)\n")

(defn- core-path [] (.getPath (apply io/file *tmp* (conj core-dir-segments "thing.clj"))))
(defn- migration-path [] (.getPath (io/file *tmp* "migrations" "006_create_things.sql")))

(defn- stub-scaffolder
  "A scaffolder that writes `module-content` to a temp core file on a real run,
   and emits a migration file in the plan. Honors :dry-run (no writes)."
  [module-content]
  (reify scaffold/IScaffolderService
    (generate-module [_ req]
      (let [path (core-path)]
        (when-not (:dry-run req)
          (spit path module-content))
        {:success     true
         :module-name (:module-name req)
         :files       [{:path path :content module-content :action :create}
                       {:path (migration-path) :content "CREATE TABLE things ();" :action :create}]}))
    (add-field [_ req]
      {:success true :module-name (:module-name req)
       :files [{:path "migrations/007_add_x.sql" :content "ALTER TABLE things ADD COLUMN x;" :action :create}]
       :warnings ["Manual schema update required"]})
    (add-entity [_ _] {:success true :files []})
    (add-endpoint [_ _] {:success true :files []})
    (add-adapter [_ _] {:success true :files []})))

(def ^:private passing-runner (fn [_m] {:status :passed :passed 1 :failed 0}))

(defn- deps [scaffolder]
  {:scaffolder  scaffolder
   :test-runner passing-runner
   :audit       (audit/in-memory-audit-log)})

(deftest ^:unit scaffold-module-clean-passes
  (let [r (tools/run (deps (stub-scaffolder clean-core)) "scaffold-module"
                     {:module "tmp" :entities [{:name "Thing" :fields [{:name "title" :type "string"}]}]})]
    (is (= :pass (:status r)))
    (is (= "tmp" (:module r)))
    (is (some #(= (core-path) (:path %)) (:files r)))))

(deftest ^:unit scaffold-module-preview-does-not-write
  (let [r (tools/run (deps (stub-scaffolder clean-core)) "scaffold-module"
                     {:module "tmp" :entities [{:name "Thing" :fields [{:name "title" :type "string"}]}]
                      :preview true})]
    (is (= :preview (:status r)))
    (is (vector? (:plan r)))
    (is (false? (.exists (io/file (core-path)))) "preview must not write files")))

(deftest ^:unit scaffold-module-fcis-violation-blocks-then-overrides
  (testing "soft FC/IS violation blocks by default"
    (let [r (tools/run (deps (stub-scaffolder fcis-core)) "scaffold-module"
                       {:module "tmp" :entities [{:name "Thing" :fields [{:name "title" :type "string"}]}]})]
      (is (= :fail (:status r)))
      (is (true? (:overridable? r)))))
  (testing "audited override proceeds and records a :guardrail-override event"
    (let [d (deps (stub-scaffolder fcis-core))
          r (tools/run d "scaffold-module"
                       {:module "tmp" :entities [{:name "Thing" :fields [{:name "title" :type "string"}]}]
                        :allow true})]
      (is (= :overridden (:status r)))
      (is (some #(= :guardrail-override (:event %)) (audit/events (:audit d)))))))

(deftest ^:unit add-field-returns-report
  (let [r (tools/run (deps (stub-scaffolder clean-core)) "add-field"
                     {:module "tmp" :entity "Thing" :field {:name "amount" :type "decimal"}})]
    (is (contains? #{:pass :overridden} (:status r)))
    (is (= "tmp" (:module r)))
    (is (seq (:warnings r)))))

(deftest ^:unit gen-migration-writes-sql
  (let [r (tools/run (deps (stub-scaffolder clean-core)) "gen-migration"
                     {:module "tmp" :entity "Thing" :fields [{:name "title" :type "string"}]})]
    (is (= :pass (:status r)))
    (is (.exists (io/file (migration-path))))
    (is (some #(= (migration-path) (:path %)) (:files r)))))

(deftest ^:unit gen-tests-without-provider-is-unavailable
  (let [r (tools/run (deps (stub-scaffolder clean-core)) "gen-tests" {:source-path "src/wagoe/tmp/core/thing.clj"})]
    (is (= :unavailable (:status r)))))

(deftest ^:unit names-are-validated-against-path-traversal
  (let [d (deps (stub-scaffolder clean-core))]
    (testing "a traversing module name is rejected before any codegen"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid module name"
                            (tools/run d "scaffold-module"
                                       {:module "../../../tmp/evil"
                                        :entities [{:name "Thing" :fields [{:name "t" :type "string"}]}]})))
      (is (false? (.exists (io/file (core-path)))) "nothing was written"))
    (testing "a bad entity name is rejected"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid entity name"
                            (tools/run d "scaffold-module"
                                       {:module "tmp" :entities [{:name "../x" :fields []}]}))))
    (testing "add-field and gen-migration validate too"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid module name"
                            (tools/run d "add-field" {:module "../x" :entity "Thing" :field {:name "a" :type "int"}})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid entity name"
                            (tools/run d "gen-migration" {:module "tmp" :entity "../x" :fields [{:name "t" :type "string"}]}))))))

;; ===========================================================================
;; Relation fields through the adapter (BOU-480 review)
;; ===========================================================================

(defn- recording-scaffolder
  "Captures the request each port method is handed, so a test can see what the
   MCP adapter forwarded rather than what it was given."
  [seen]
  (reify scaffold/IScaffolderService
    (generate-module [_ req]
      (reset! seen req)
      {:success true :module-name (:module-name req)
       :files [{:path (core-path) :content clean-core :action :create}]})
    (add-field [_ req]
      (reset! seen req)
      {:success true :module-name (:module-name req) :files []})
    (add-entity [_ _] {:success true :files []})
    (add-endpoint [_ _] {:success true :files []})
    (add-adapter [_ _] {:success true :files []})))

(deftest ^:unit relation-metadata-survives-the-mcp-adapter
  ;; `field->scaffolder` allowlisted the keys it forwarded, and the relation
  ;; keys were not on the list. Module generation then refused the field for a
  ;; missing :references, and add-field wrote a bare UUID column with no
  ;; foreign key — the whole relation type, unreachable through MCP.
  (let [seen (atom nil)
        d    (deps (recording-scaffolder seen))
        relation {:name "invoice" :type "relation" :references "invoice"
                  :references-table "invoices" :on-delete "restrict"
                  :required true}]

    (testing "scaffold-module forwards the target, the table and the action"
      (tools/run d "scaffold-module"
                 {:module "tmp" :entities [{:name "Thing" :fields [relation]}]})
      (let [field (first (:fields (first (:entities @seen))))]
        (is (= :invoice (:name field)))
        (is (= :relation (:type field)))
        (is (= "invoice" (:references field)))
        (is (= "invoices" (:references-table field)))
        (is (= :restrict (:on-delete field))
            "on-delete arrives as a string over JSON and must reach the schema as a keyword")))

    (testing "add-field forwards them too"
      (reset! seen nil)
      (tools/run d "add-field" {:module "tmp" :entity "Thing" :field relation})
      (let [field (:field @seen)]
        (is (= "invoice" (:references field)))
        (is (= "invoices" (:references-table field)))
        (is (= :restrict (:on-delete field)))))

    (testing "a relation with no on-delete gets the default rather than nil"
      (reset! seen nil)
      (tools/run d "add-field"
                 {:module "tmp" :entity "Thing"
                  :field {:name "invoice" :type "relation" :references "invoice"}})
      (is (= :cascade (:on-delete (:field @seen)))))))

(deftest ^:unit the-mcp-schema-advertises-the-relation-keys
  ;; The inputSchema is what a client reads before it calls. Advertising a
  ;; field type whose required argument is not in the schema is the same
  ;; defect as advertising `cli` was (BOU-484 review).
  (let [field-props (fn [tool-name path]
                      (->> tools-catalog/catalog
                           (filter #(= tool-name (:name %)))
                           first
                           :inputSchema
                           (#(get-in % path))
                           keys
                           set))]
    (testing "scaffold-module's entity fields"
      (let [props (field-props "scaffold-module"
                               [:properties "entities" :items :properties "fields" :items :properties])]
        (is (contains? props "references") props)
        (is (contains? props "references-table") props)
        (is (contains? props "on-delete") props)))

    (testing "add-field's field"
      (let [props (field-props "add-field" [:properties "field" :properties])]
        (is (contains? props "references") props)
        (is (contains? props "references-table") props)
        (is (contains? props "on-delete") props)))))
