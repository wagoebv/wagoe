(ns wagoe.tools.integrate-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [wagoe.tools.integrate :as integrate])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- tmp-root [] (.toFile (Files/createTempDirectory "integrate-test" (make-array FileAttribute 0))))

(defn- repo-root []
  (let [cwd (System/getProperty "user.dir")]
    (or (first (filter #(.isDirectory (io/file % "libs"))
                       [cwd (str cwd "/../..")]))
        (throw (ex-info "cannot locate repo root (no libs/ dir)" {:cwd cwd})))))

(defn- touch! [root & path-segs]
  (let [f (apply io/file root path-segs)]
    (io/make-parents f)
    (spit f "")
    f))

;; =============================================================================
;; base-ns-path
;; =============================================================================

(deftest ^:unit base-ns-path-test
  (is (= "wagoe" (integrate/base-ns-path nil)))
  (is (= "acme" (integrate/base-ns-path "acme")))
  (is (= "myapp" (integrate/base-ns-path "myapp")))
  (is (= "com/acme" (integrate/base-ns-path "com.acme"))))

;; =============================================================================
;; discover-module — reads src/<base-ns-path>/<module>/ (where generate writes)
;; =============================================================================

(deftest ^:unit discover-module-finds-module-under-src
  (testing "default base-ns -> src/wagoe/<module>/"
    (let [root (tmp-root)]
      (touch! root "src" "wagoe" "product" "schema.clj")
      (touch! root "src" "wagoe" "product" "shell" "http.clj")
      (let [m (integrate/discover-module "product" nil root)]
        (is (some? m))
        (is (= "wagoe.product" (:module-ns m)))
        (is (= "src/wagoe/product" (:src-path m)))
        (is (true? (:has-routes? m)))          ; shell/http.clj present
        (is (false? (:has-wiring? m))))))       ; no module_wiring.clj

  (testing "custom base-ns -> src/<base-ns-path>/<module>/"
    (let [root (tmp-root)]
      (touch! root "src" "myapp" "product" "schema.clj")
      (let [m (integrate/discover-module "product" "myapp" root)]
        (is (some? m))
        (is (= "myapp.product" (:module-ns m)))
        (is (= "src/myapp/product" (:src-path m)))
        (is (false? (:has-routes? m))))))

  (testing "dotted base-ns -> nested path"
    (let [root (tmp-root)]
      (touch! root "src" "com" "acme" "product" "schema.clj")
      (is (= "com.acme.product" (:module-ns (integrate/discover-module "product" "com.acme" root))))))

  (testing "module absent -> nil (and NOT found under the old libs/ location)"
    (let [root (tmp-root)]
      (touch! root "libs" "product" "src" "wagoe" "product" "schema.clj") ; old layout
      (is (nil? (integrate/discover-module "product" nil root))))))

(deftest ^:unit discover-module-defaults-to-the-projects-own-namespace
  ;; With no --base-ns, a module belongs under the application's namespace —
  ;; the whole point of BOU-360. src/shop/main.clj is what says "shop".
  (let [root (tmp-root)]
    (touch! root "src" "shop" "main.clj")
    (touch! root "src" "shop" "product" "shell" "module_wiring.clj")
    (let [m (integrate/discover-module "product" nil root)]
      (is (some? m))
      (is (= "shop.product" (:module-ns m)))
      (is (= "src/shop/product" (:src-path m))))))

(deftest ^:unit discover-module-still-finds-a-module-left-under-wagoe
  ;; A project generated before the move has src/wagoe/<module>/ and a
  ;; src/<project>/main.clj beside it. Integrating it must keep working.
  (let [root (tmp-root)]
    (touch! root "src" "shop" "main.clj")
    (touch! root "src" "wagoe" "product" "shell" "module_wiring.clj")
    (let [m (integrate/discover-module "product" nil root)]
      (is (some? m) "the old location must still be found")
      (is (= "wagoe.product" (:module-ns m))
          "and reported under the namespace it is actually in"))))

;; =============================================================================
;; round-trip: the path generate writes is the path integrate discovers
;; =============================================================================

(deftest ^:unit generate-integrate-round-trip-path-contract
  ;; `bb scaffold generate [--base-ns NS]` writes files at
  ;; src/<base-ns-path>/<module>/... (scaffolder shell/service.clj). Recreate that
  ;; exact layout and assert integrate discovers it — the two halves now agree.
  (doseq [base-ns [nil "myapp" "com.acme"]]
    (let [root (tmp-root)
          bnp  (integrate/base-ns-path base-ns)]
      (touch! root "src" bnp "product" "schema.clj")
      (touch! root "src" bnp "product" "ports.clj")
      (touch! root "src" bnp "product" "shell" "service.clj")
      (touch! root "test" bnp "product" "shell" "service_test.clj")
      (let [m (integrate/discover-module "product" base-ns root)]
        (is (some? m) (str "discovered for base-ns " (pr-str base-ns)))
        (is (= (str "src/" bnp "/product") (:src-path m)))
        (is (= (str "test/" bnp "/product") (:test-path m)))))))

;; =============================================================================
;; generate-config-snippet
;; =============================================================================

(deftest ^:unit generate-config-snippet-test
  (testing "basic config snippet"
    (let [snippet (integrate/generate-config-snippet "product" false)]
      (is (re-find #":wagoe/product" snippet))
      (is (re-find #":enabled\? true" snippet))
      (is (not (re-find #":base-path" snippet)))))

  (testing "includes base-path for modules with routes"
    (is (re-find #":base-path \"/api/product\"" (integrate/generate-config-snippet "product" true)))))

;; =============================================================================
;; arg parsing
;; =============================================================================

(deftest ^:unit parse-args-test
  (is (= "product" (:module (integrate/parse-args ["product"]))))
  (is (= "myapp" (:base-ns (integrate/parse-args ["product" "--base-ns" "myapp"]))))
  (is (true? (:dry-run? (integrate/parse-args ["product" "--dry-run"]))))
  (is (true? (:help (integrate/parse-args ["--help"])))))

;; =============================================================================
;; BOU-447: namespace segment vs directory name
;; =============================================================================

(deftest ^:unit discover-module-finds-a-kebab-case-module
  ;; `bb scaffold generate --module-name invoice-line-item` writes
  ;; src/<ns>/invoice_line_item/, because that is where Clojure loads
  ;; <ns>.invoice-line-item.* from. Integrate looked for the hyphenated path and
  ;; so could not find a correctly-named module at all.
  (let [root (tmp-root)]
    (touch! root "src" "wagoe" "invoice_line_item" "schema.clj")
    (touch! root "src" "wagoe" "invoice_line_item" "shell" "module_wiring.clj")
    (let [m (integrate/discover-module "invoice-line-item" nil root)]
      (is (some? m))
      (is (= "wagoe.invoice-line-item" (:module-ns m)))
      (is (= "src/wagoe/invoice_line_item" (:src-path m)))
      (is (= "test/wagoe/invoice_line_item" (:test-path m)))
      (is (true? (:has-wiring? m))))))

(deftest ^:unit base-ns-path-munges-hyphens
  ;; A project whose own namespace has a hyphen loads from an underscored path
  ;; for the same reason.
  (is (= "my_app" (integrate/base-ns-path "my-app")))
  (is (= "com/my_app" (integrate/base-ns-path "com.my-app"))))

(deftest ^:unit config-key-keeps-the-namespace-spelling
  ;; The config key is read as a keyword, not as a path, so it stays kebab.
  (is (re-find #":wagoe/invoice-line-item"
               (integrate/generate-config-snippet "invoice-line-item" false))))

;; =============================================================================
;; write-config! — every profile under resources/conf, prod included (BOU-529)
;; =============================================================================

(def ^:private minimal-config "{:active\n {:wagoe/settings {:name \"x\"}}\n\n :inactive\n {}}\n")

(defn- conf-root [& envs]
  (let [root (tmp-root)]
    (doseq [env envs]
      (let [f (io/file root "resources" "conf" env "config.edn")]
        (io/make-parents f)
        (spit f minimal-config)))
    root))

(defn- config-text [root env]
  (slurp (io/file root "resources" "conf" env "config.edn")))

(def ^:private snippet (integrate/generate-config-snippet "product" false))

(deftest ^:unit write-config-writes-every-profile
  (let [root    (conf-root "dev" "test" "prod")
        results (integrate/write-config! root ":wagoe/product" snippet {})]
    (is (= {"dev" :written "test" :written "prod" :written} (into {} results)))
    (doseq [env ["dev" "test" "prod"]]
      (is (str/includes? (config-text root env) ":wagoe/product") env))))

(deftest ^:unit write-config-does-not-create-a-missing-prod
  (let [root    (conf-root "dev" "test")
        results (integrate/write-config! root ":wagoe/product" snippet {})]
    (is (= {"dev" :written "test" :written} (into {} results)))
    (is (not (.exists (io/file root "resources" "conf" "prod"))))))

(deftest ^:unit write-config-dry-run-lists-every-profile-and-writes-nothing
  (let [root    (conf-root "dev" "test" "prod")
        results (integrate/write-config! root ":wagoe/product" snippet {:dry-run? true})]
    (is (= {"dev" :written "test" :written "prod" :written} (into {} results)))
    (doseq [env ["dev" "test" "prod"]]
      (is (= minimal-config (config-text root env)) env))))

(deftest ^:unit write-config-keeps-a-dev-only-key-out-of-other-profiles
  ;; The platform refuses :wagoe/dashboard outside :dev, so writing it to prod
  ;; would stop the app booting.
  (let [root    (conf-root "dev" "test" "prod")
        results (integrate/write-config! root ":wagoe/dashboard"
                                         (integrate/generate-config-snippet "dashboard" false) {})]
    (is (= {"dev" :written "test" :dev-only "prod" :dev-only} (into {} results)))
    (is (not (str/includes? (config-text root "prod") ":wagoe/dashboard")))))

(deftest ^:unit dev-only-keys-match-the-platform
  (let [src  (slurp (io/file (repo-root) "libs/platform/src/wagoe/platform/shell/modules.clj"))
        form (read-string (subs src (str/index-of src "(def dev-only-modules")))]
    (is (= (set (map str (keys (last form)))) integrate/dev-only-keys))))

(deftest ^:unit write-config-writes-nothing-when-one-profile-cannot-take-it
  ;; acc, dev and test were written and prod was not, while the command said
  ;; nothing was written.
  (let [root    (conf-root "acc" "dev" "test" "prod")
        _       (spit (io/file root "resources" "conf" "prod" "config.edn") "{:inactive {}}\n")
        results (integrate/write-config! root ":wagoe/product" snippet {})
        blocked (integrate/blocking results)]
    (doseq [env ["acc" "dev" "test"]]
      (is (= minimal-config (config-text root env)) env))
    (is (= [["prod" :no-active-section]] blocked))
    (is (str/includes? (integrate/blocked-message blocked) "prod"))))
