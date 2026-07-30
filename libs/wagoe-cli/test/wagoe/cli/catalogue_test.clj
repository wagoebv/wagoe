(ns wagoe.cli.catalogue-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn]
            [clojure.java.io :as io]
            [wagoe.cli.catalogue :as cat]))

(deftest ^:unit load-catalogue-test
  (testing "catalogue loads without error"
    (let [c (cat/load-catalogue)]
      (is (map? c))
      (is (contains? c :modules))
      (is (seq (:modules c)))))

  (testing "every module has required fields"
    (doseq [m (:modules (cat/load-catalogue))]
      (is (string? (:name m))       (str "missing :name in " m))
      (is (string? (:description m)) (str "missing :description in " m))
      (is (keyword? (:category m))  (str "missing :category in " m))
      (is (string? (:version m))    (str "missing :version in " m))
      (is (string? (:add-command m)) (str "missing :add-command in " m))
      (is (string? (:config-snippet m)) (str "missing :config-snippet in " m))
      (is (string? (:test-config-snippet m)) (str "missing :test-config-snippet in " m))
      (is (string? (:docs-url m))   (str "missing :docs-url in " m))))

  (testing "every module :clojars is a symbol"
    (doseq [m (:modules (cat/load-catalogue))]
      (is (symbol? (:clojars m)) (str ":clojars is not a symbol in " (:name m))))))

(deftest ^:unit find-module-test
  (testing "finds a module by name"
    (let [m (cat/find-module "payments")]
      (is (= "payments" (:name m)))))

  (testing "returns nil for unknown module"
    (is (nil? (cat/find-module "does-not-exist"))))

  (testing "core modules are present"
    (doseq [core-name ["core" "observability" "platform" "user"]]
      (is (cat/find-module core-name) (str "core module missing: " core-name))))

  (testing "optional modules include payments and storage"
    (is (cat/find-module "payments"))
    (is (cat/find-module "storage"))))

(deftest ^:unit optional-modules-test
  (testing "optional-modules returns only :optional category"
    (let [opts (cat/optional-modules)]
      (is (every? #(= :optional (:category %)) opts))
      (is (seq opts)))))

(deftest ^:unit core-modules-test
  (testing "core-modules returns only :core category"
    (let [cores (cat/core-modules)]
      (is (every? #(= :core (:category %)) cores))
      (is (seq cores))))

  (testing "core-modules includes all 4 required core modules"
    (let [core-names (set (map :name (cat/core-modules)))]
      (is (contains? core-names "core"))
      (is (contains? core-names "observability"))
      (is (contains? core-names "platform"))
      (is (contains? core-names "user")))))

(defn- parse-deploy-all-libs
  "Extracts and parses the all-libs vector from a deploy registry file (relative
  to the monorepo root) as EDN. Returns nil if the file is absent (e.g. run
  outside the monorepo root) or the vector isn't found."
  [rel-path]
  (let [f (io/file (System/getProperty "user.dir") rel-path)]
    (when (.exists f)
      (let [content (slurp f)
            m       (re-find #"(?s)\(def all-libs\s+(\[.*?\])\)" content)]
        (when m
          (clojure.edn/read-string (second m)))))))

(defn- parse-all-libs
  "all-libs from the canonical deploy registry — libs/tools/src/wagoe/tools/
  deploy.clj, the one `bb deploy` (wagoe.tools.deploy) actually publishes from."
  []
  (parse-deploy-all-libs "libs/tools/src/wagoe/tools/deploy.clj"))

(deftest ^:integration deploy-lib-registry-drift-test
  (let [all-libs (parse-all-libs)]
    (if-not all-libs
      ;; Run outside the monorepo root (e.g. `clojure -M:test` from libs/wagoe-cli):
      ;; the deploy registry is not on this cwd. Record the skip as a passing
      ;; assertion so kaocha doesn't flag a zero-assertion test.
      (is (nil? all-libs)
          "Drift check skipped: deploy registry not found from this working directory")
      (do
        (testing "all-libs vector is parseable and non-empty"
          (is (vector? all-libs))
          (is (seq all-libs)))

        (testing "wagoe-mcp is present in the publish registry"
          (is (some #{"wagoe-mcp"} all-libs)
              "wagoe-mcp missing from wagoe.tools.deploy all-libs"))

        (testing "the two deploy registries stay in sync"
          ;; scripts/deploy.clj mirrors the canonical libs/tools registry; both
          ;; must list the same libs in the same order, or a `bb scripts/deploy.clj`
          ;; run would publish a different (drifted) set.
          (let [scripts-libs (parse-deploy-all-libs "scripts/deploy.clj")]
            (is (= all-libs scripts-libs)
                "scripts/deploy.clj all-libs has drifted from libs/tools/.../deploy.clj")))

        (testing "i18n and payments are present in all-libs"
          (is (some #{"i18n"}    all-libs) "i18n missing from deploy all-libs")
          (is (some #{"payments"} all-libs) "payments missing from deploy all-libs"))

        (testing "i18n appears after platform and before user (dependency order)"
          (let [idx #(.indexOf ^java.util.List (vec all-libs) %)]
            (is (< (idx "platform") (idx "i18n"))    "i18n must come after platform")
            (is (< (idx "i18n")     (idx "user"))     "i18n must come before user")))

        (testing "payments appears after external and before geo (dependency order)"
          (let [idx #(.indexOf ^java.util.List (vec all-libs) %)]
            (is (< (idx "external") (idx "payments")) "payments must come after external")
            (is (< (idx "payments") (idx "geo"))      "payments must come before geo")))))))
