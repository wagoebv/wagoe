(ns wagoe.tools.tutorial-matches-scaffolder-test
  "The getting-started tutorial against the generator it describes.

   `your-first-module.adoc` showed code the scaffolder does not produce: a
   `prepare-product` that validated its input (the real one is
   `prepare-new-product` and merges), repository methods `find-product-by-id`
   and `list-products` (really `find-by-id` and `find-all`), and a
   `ProductInput` schema (really `CreateProductRequest`). A reader following it
   met a compile error on their first edit, in the framework's flagship
   tutorial (BOU-327).

   So this generates the module the page describes and checks that what the page
   names is what came out.

   It matches identifiers, not formatting. A page that had to be byte-identical
   to generator output would fail on a rewrapped docstring, and the fix for that
   is to stop reading the page — which is how it drifted in the first place."
  (:require [babashka.fs :as fs]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.tools.scaffold :as scaffold]))

(def ^:private tutorial-path
  ["docs/modules/getting-started/pages/your-first-module.adoc"
   "../../docs/modules/getting-started/pages/your-first-module.adoc"])

(defn- tutorial []
  (or (some #(when (fs/exists? %) (slurp %)) tutorial-path)
      (throw (ex-info "your-first-module.adoc not found — cannot check it"
                      {:cwd (System/getProperty "user.dir")}))))

(defn- generate!
  "Generate the tutorial's product module into `dir`, returning its files as
   {relative-path content}."
  [dir]
  (binding [scaffold/*exit!* (fn [_])]
    (with-out-str
      (scaffold/-main "generate"
                      "--module-name" "product"
                      "--entity" "Product"
                      "--field" "name:string:required"
                      "--field" "sku:string:required"
                      "--field" "price:decimal"
                      "--output-dir" (str dir))))
  (into {}
        (for [f (fs/glob dir "**/*.{clj,sql}")]
          [(str (fs/relativize dir f)) (slurp (fs/file f))])))

(defn- defined-names
  "Every top-level name the generated sources define — defn, def, defprotocol
   and its methods, defrecord."
  [files]
  (into #{}
        (mapcat (fn [[_ content]]
                  (concat
                   ;; The optional metadata (`^:private`, `^{...}`) is matched
                   ;; as a whole token. An earlier version wrote it as
                   ;; `\^?:?[a-z]*`, which greedily ate the first six characters
                   ;; of every name — `prepare-new-product` was read as
                   ;; `e-new-product`, and the test failed against reality.
                   (map second (re-seq #"\(def(?:n|record|protocol)?-?\s+(?:\^\S+\s+)?([A-Za-z][\w?!*<>=+-]*)" content))
                   ;; Protocol methods are indented forms inside defprotocol, so
                   ;; the regex above does not see them.
                   (map second (re-seq #"(?m)^\s{2,}\(([a-z][\w?!*<>=-]*)\s+\[this" content)))))
        files))

(defn- code-blocks
  "The Clojure source blocks of an AsciiDoc page, with string literals blanked.

   Docstrings are prose, and prose is not a promise about the code. Without
   this, \"Schema for create product API requests.\" contributed the word
   `product` as a name the generator had to define — the same false positive
   `check:error-shape` learned to strip."
  [adoc]
  (->> (re-seq #"(?s)\[source,clojure\]\n----\n(.*?)\n----" adoc)
       (map second)
       (map #(str/replace % #"\"(?:[^\"\\\\]|\\\\.)*\"" "\"\""))))

(deftest ^:integration the-tutorial-names-what-the-generator-writes
  (let [dir (fs/create-temp-dir {:prefix "bou327-"})]
    (try
      (let [files (generate! dir)
            names (defined-names files)
            page  (tutorial)
            blocks (code-blocks page)]

        (testing "the generator ran and the page was read — otherwise this is vacuous"
          (is (<= 10 (count files)) (str "only generated " (count files) " files"))
          (is (contains? names "prepare-new-product")
              (str "read no names from the generated sources: " (pr-str (sort names))))
          (is (<= 4 (count blocks)) (str "found " (count blocks) " clojure blocks in the page")))

        (testing "every function and schema the page shows really exists"
          ;; Names the page mentions that look like ours and are not there is the
          ;; failure mode: `find-product-by-id` for `find-by-id`.
          (let [shown   (into #{}
                              (comp (mapcat #(map second (re-seq #"(?:[\w.-]+/)?([a-zA-Z][\w?!*<>=-]*)" %)))
                                    ;; Only names in the generated module's own
                                    ;; vocabulary. Clojure core, Malli keywords
                                    ;; and prose words are not this test's business.
                                    (filter #(or (str/includes? % "product")
                                                 (str/includes? % "Product"))))
                              blocks)
                missing (set/difference shown names)]
            (is (empty? missing)
                (str "the tutorial names these, and `bb scaffold generate` writes no such thing: "
                     (pr-str (sort missing))))))

        (testing "and the repository protocol's methods are the generic ones"
          ;; The page explains why: two protocols in one namespace may not share
          ;; a method name, so the repository's are generic.
          (is (every? names ["find-by-id" "find-all" "create" "update-entity" "delete"]))
          (is (not (contains? names "find-product-by-id"))
              "if the generator ever emits this, the page's explanation is wrong too"))

        (testing "the schemas are named as the page names them"
          (is (contains? names "Product"))
          (is (contains? names "CreateProductRequest"))
          (is (not (contains? names "ProductInput"))))

        (testing "the page does not promise validation the generator omits"
          ;; The generated create-product persists without validating, and the
          ;; page says so. If that changes, the page's IMPORTANT block is wrong.
          (let [service (get files "src/wagoe/product/shell/service.clj")]
            (is (some? service))
            (is (not (str/includes? service "validate-product"))
                (str "the generated service now validates — the tutorial still tells the "
                     "reader to wire it in themselves")))))
      (finally (fs/delete-tree dir)))))

(deftest ^:integration the-tutorial-describes-files-that-are-generated
  (let [dir (fs/create-temp-dir {:prefix "bou327-tree-"})]
    (try
      (let [files (generate! dir)
            page  (tutorial)]
        (testing "every file named in the page's tree is written"
          (doseq [f ["core/product.clj" "core/ui.clj" "shell/persistence.clj"
                     "shell/service.clj" "shell/http.clj" "shell/web_handlers.clj"
                     "shell/module_wiring.clj" "ports.clj" "schema.clj"]
                  :when (str/includes? page f)]
            (is (contains? files (str "src/wagoe/product/" f))
                (str "the page's file tree lists " f ", which was not generated"))))

        (testing "and migrations and tests, which the page also claims"
          (is (some #(str/starts-with? % "migrations/") (keys files)))
          (is (= 3 (count (filter #(str/starts-with? % "test/") (keys files))))
              "the page says one test namespace per layer")))
      (finally (fs/delete-tree dir)))))
