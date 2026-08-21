(ns wagoe.cli.catalogue-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
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

  (testing "every :scope is one the CLI knows"
    ;; :scope routes the dep: :dev goes to the :repl alias, anything else to
    ;; :deps. A typo (:development) silently ships devtools in the uberjar, and
    ;; nothing else in the pipeline reads this key (BOU-318).
    (doseq [m (:modules (cat/load-catalogue))]
      (is (contains? #{nil :dev} (:scope m))
          (str "unknown :scope " (pr-str (:scope m)) " in " (:name m)))))

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

        ;; The "two deploy registries stay in sync" check that lived here is
        ;; gone: scripts/deploy.clj is a shim over the canonical namespace as of
        ;; BOU-250, so there is only one registry. Keeping a comparison against
        ;; a file that no longer has an all-libs vector would be a check that
        ;; cannot fail — see wagoe.tools.deploy-test/deploy-has-one-registry,
        ;; which asserts the shim stays a shim.

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

(deftest ^:unit every-catalogue-module-is-wired-by-the-assembler
  ;; `wagoe add <module>` adds the dependency and the config snippet. If nothing
  ;; then builds the Integrant key, the command succeeds, the config is written,
  ;; and the app ignores it — no error, no component, and nothing to suggest
  ;; where the events went.
  ;;
  ;; The events module shipped exactly that way: a catalogue entry, a config
  ;; snippet, and a template that had never heard of it.
  ;;
  ;; What builds the key moved out of config.clj.tmpl and into
  ;; `wagoe.platform.shell.modules/framework-modules`, so that is what this
  ;; reads. As source text, not as a var: wagoe-cli depends on no Wagoe library,
  ;; and this test is not a reason to give it one.
  (let [modules-src (or (some #(when (.exists (io/file %)) (slurp %))
                              ["../platform/src/wagoe/platform/shell/modules.clj"
                               "libs/platform/src/wagoe/platform/shell/modules.clj"])
                        (throw (ex-info "platform's modules.clj not found — cannot check" {})))
        table       (subs modules-src
                          (str/index-of modules-src "(def framework-modules")
                          (str/index-of modules-src "(def always-on-modules"))
        ;; Both halves of the table: the keys spelled out, and the module names
        ;; the rest are derived from.
        assembled   (into (set (re-seq #":wagoe[.a-z-]*/[a-z0-9-]+" table))
                          (map #(str ":wagoe/" (subs % 1 (dec (count %)))))
                          (re-seq #"\"[a-z0-9-]+\"" table))
        ;; Known-unwired, with the reason. Not a way to make this quiet: an
        ;; entry here is a module whose `wagoe add` still does nothing, and it
        ;; has to name the ticket that will fix it.
        known-unwired {}
        ;; The Integrant keys a module's config snippet tells a project to add.
        snippet-keys (fn [m]
                       (set (re-seq #":wagoe[.a-z-]*/[a-z-]+"
                                    (str (:config-snippet m)))))]

    (testing "the table was read — otherwise this passes vacuously"
      (is (str/includes? table "framework-modules"))
      (is (< 15 (count assembled))
          (str "only found " (count assembled) " modules in the table")))

    (doseq [m (:modules (cat/load-catalogue))
            :let [ks (snippet-keys m)
                  ;; The primary key is the one named after the module itself.
                  primary (first (filter #(= (str ":wagoe/" (:name m)) %) ks))]
            :when primary]
      (testing (str "`" (:add-command m) "` produces a working app")
        (is (or (contains? assembled primary) (contains? known-unwired (:name m)))
            (str "catalogue offers " (:name m) " with config key " primary
                 ", but framework-modules never builds it — `" (:add-command m)
                 "` would report success and do nothing")))

      (testing "and nothing is on the known-unwired list once it works"
        ;; So the list shrinks rather than rots.
        (when (contains? known-unwired (:name m))
          (is (not (contains? assembled primary))
              (str (:name m) " is wired now — remove it from known-unwired: "
                   (get known-unwired (:name m)))))))))
