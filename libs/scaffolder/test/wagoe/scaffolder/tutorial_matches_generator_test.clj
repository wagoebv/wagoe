(ns wagoe.scaffolder.tutorial-matches-generator-test
  "The getting-started tutorial against the generator it describes.

   `your-first-module.adoc` showed code the scaffolder does not produce: a
   `prepare-product` that validated its input (the real one is
   `prepare-new-product` and merges), repository methods `find-product-by-id`
   and `list-products` (really `find-by-id` and `find-all`), and a
   `ProductInput` schema (really `CreateProductRequest`). A reader following the
   framework's flagship tutorial met a compile error on their first edit
   (BOU-327).

   Calls the generators rather than shelling out to `bb scaffold`. A first
   version did shell out and was green locally and red in CI, because the
   wagoe-tools job installs Babashka and no JVM on purpose — a check that cannot
   run where it is needed is not a check. Here the generator is already on the
   classpath.

   Matches identifiers, not formatting: a page required to be byte-identical to
   generated code fails on a rewrapped docstring, and the fix for that is to
   stop reading the page, which is how it drifted."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.scaffolder.core.generators :as gen]))

;; The tutorial's own command:
;;   --field name:string:required --field sku:string:required --field price:decimal
(def ^:private ctx
  {:module-name "product"
   :entities
   [{:entity-name  "Product"
     :entity-lower "product"
     :entity-kebab "product"
     :entity-table "products"
     :fields [{:field-name-kebab "name" :field-name-snake "name"
               :malli-type ":string" :field-required true
               :field-unique false :sql-type "VARCHAR(255)"}
              {:field-name-kebab "sku" :field-name-snake "sku"
               :malli-type ":string" :field-required true
               :field-unique false :sql-type "VARCHAR(255)"}
              {:field-name-kebab "price" :field-name-snake "price"
               :malli-type ":double" :field-required false
               :field-unique false :sql-type "DECIMAL(10,2)"}]}]})

(defn- generated
  "The source the tutorial quotes, by the generator that writes it."
  []
  {"schema.clj"            (gen/generate-schema-file ctx)
   "ports.clj"             (gen/generate-ports-file ctx)
   "core/product.clj"      (gen/generate-core-file ctx)
   "shell/service.clj"     (gen/generate-service-file ctx)
   "shell/persistence.clj" (gen/generate-persistence-file ctx)
   "shell/http.clj"        (gen/generate-http-file ctx)})

(defn- defined-names
  "Every top-level name the generated sources define, plus protocol methods."
  [files]
  (into #{}
        (mapcat (fn [[_ content]]
                  (concat
                   ;; The optional metadata (`^:private`) is matched as a whole
                   ;; token. Writing it as `\^?:?[a-z]*` ate the first six
                   ;; characters of every name — `prepare-new-product` came out
                   ;; as `e-new-product`, and the test failed against reality.
                   (map second (re-seq #"\(def(?:n|record|protocol)?-?\s+(?:\^\S+\s+)?([A-Za-z][\w?!*<>=+-]*)" content))
                   ;; Protocol methods are indented forms inside defprotocol.
                   (map second (re-seq #"(?m)^\s{2,}\(([a-z][\w?!*<>=-]*)\s+\[this" content)))))
        files))

(defn- tutorial []
  (or (some #(let [f (io/file %)] (when (.exists f) (slurp f)))
            ["docs/modules/getting-started/pages/your-first-module.adoc"
             "../../docs/modules/getting-started/pages/your-first-module.adoc"])
      (throw (ex-info "your-first-module.adoc not found — cannot check it"
                      {:cwd (System/getProperty "user.dir")}))))

(defn- code-blocks
  "The Clojure blocks of an AsciiDoc page, with string literals blanked.

   Docstrings are prose, and prose is not a promise about the code. Without
   this, \"Schema for create product API requests.\" contributed the word
   `product` as a name the generator had to define."
  [adoc]
  (->> (re-seq #"(?s)\[source,clojure\]\n----\n(.*?)\n----" adoc)
       (map second)
       (map #(str/replace % #"\"(?:[^\"\\]|\\.)*\"" "\"\""))))

(deftest ^:unit the-tutorial-names-what-the-generator-writes
  (let [files  (generated)
        names  (defined-names files)
        page   (tutorial)
        blocks (code-blocks page)]

    (testing "the generator ran and the page was read — otherwise this is vacuous"
      (is (contains? names "prepare-new-product")
          (str "read no names from the generated sources: " (pr-str (sort names))))
      (is (<= 4 (count blocks)) (str "found " (count blocks) " clojure blocks in the page")))

    (testing "every function and schema the page shows really exists"
      (let [shown   (into #{}
                          (comp (mapcat #(map second (re-seq #"(?:[\w.-]+/)?([a-zA-Z][\w?!*<>=-]*)" %)))
                                ;; Only the generated module's own vocabulary.
                                ;; Clojure core and prose are not this test's business.
                                (filter #(str/includes? (str/lower-case %) "product")))
                          blocks)
            missing (set/difference shown names)]
        (is (empty? missing)
            (str "the tutorial names these, and the generator writes no such thing: "
                 (pr-str (sort missing))))))

    (testing "the repository protocol's methods are the generic ones"
      ;; The page explains why: two protocols in one namespace may not share a
      ;; method name, so the repository's are generic.
      (is (every? names ["find-by-id" "find-all" "create" "update-entity" "delete"]))
      (is (not (contains? names "find-product-by-id"))
          "if the generator ever emits this, the page's explanation is wrong too"))

    (testing "the schemas are named as the page names them"
      (is (contains? names "Product"))
      (is (contains? names "CreateProductRequest"))
      (is (not (contains? names "ProductInput"))))

    (testing "the page does not promise validation the generator omits"
      ;; The generated create-product persists without validating, and the page
      ;; says so with the edit that wires it in. If that changes, the page's
      ;; IMPORTANT block is telling the reader to do something already done.
      (is (not (str/includes? (get files "shell/service.clj") "validate-product"))
          "the generated service now validates — the tutorial still says it does not"))))
