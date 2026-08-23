(ns wagoe.tools.integrate-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [wagoe.tools.integrate :as integrate])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- tmp-root [] (.toFile (Files/createTempDirectory "integrate-test" (make-array FileAttribute 0))))

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
