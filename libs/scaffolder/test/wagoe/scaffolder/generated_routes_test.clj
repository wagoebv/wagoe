(ns wagoe.scaffolder.generated-routes-test
  "The routes `bb scaffold generate` writes, given to Reitit.

   The generator emitted two route sets: a flat Reitit vector nothing called,
   and a `{:path .. :methods {..}}` map that the platform translated at boot.
   When the translation layer was deleted (ADR-037), the generated module kept
   emitting the dead format — every check still passed, because every check
   read the source as text (BOU-331).

   Reitit is the reader that matters now, so this test is Reitit: build a
   router from what the generator writes. A wrong shape throws, and two routes
   that can match the same request throw."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reitit.core :as r]
            [wagoe.scaffolder.core.generators :as gen]))

(def ^:private ctx
  {:module-name "product"
   :entities    [{:entity-name "Product" :fields []}]})

(defn- routes-fn
  "Load the generated http.clj and return its `<module>-routes` var.

   Evaluating the file is the point: `str/includes?` on the source cannot tell
   a route vector from a route map, which is how the old format survived."
  []
  (let [source (gen/generate-http-file ctx)]
    (binding [*ns* *ns*]
      (doseq [form (read-string (str "[" source "]"))]
        (eval form)))
    (resolve 'wagoe.product.shell.http/product-routes)))

(deftest ^:unit the-generated-routes-build-a-reitit-router
  (let [contribution ((routes-fn) nil {})]

    (testing "the contribution has the three parts the platform folds"
      (is (= #{:api :web :static} (set (keys contribution)))))

    (testing "each part compiles, and the routes survive compiling"
      ;; Both halves matter. `r/router` throws on data it cannot read, but a
      ;; vector of `{:path .. :methods ..}` maps is not unreadable to Reitit —
      ;; it compiles to a router with no routes in it. Building without
      ;; counting is a check that passes on the exact defect it is here for.
      (doseq [part [:api :web]]
        (let [routes (r/routes (r/router (get contribution part)))]
          (is (seq routes)
              (str part " compiled to a router serving nothing — wrong shape")))))

    (testing "the API routes are the CRUD five, at relative paths"
      ;; Relative because the platform adds /api/v1. A generator that wrote
      ;; "/api/products" would produce /api/v1/api/products.
      (let [router (r/router (:api contribution))
            paths  (map first (r/routes router))]
        (is (= ["/products" "/products/:id"] (vec paths)))
        (is (= #{:get :post}
               (set (keys (:data (r/match-by-path router "/products"))))))
        (is (= #{:get :put :delete}
               (set (keys (:data (r/match-by-path router "/products/42"))))))))

    (testing "the web routes carry no /web prefix — the platform adds it"
      (doseq [path (map first (r/routes (r/router (:web contribution))))]
        (is (not (str/starts-with? path "/web"))
            (str path " — /web is added when the contribution is mounted"))))))

(deftest ^:unit the-generated-wiring-calls-a-function-the-generated-http-file-defines
  ;; The two files are written by two generators, and `:wagoe/product-routes`
  ;; is where they meet. Renaming the route function on one side leaves a
  ;; module that compiles and dies at boot on an unresolved var; asserting the
  ;; name as a literal on both sides would just move the drift into the test.
  (let [http   (gen/generate-http-file ctx)
        wiring (gen/generate-module-wiring-file ctx)
        called (second (re-find #"\(http/([a-z0-9-]+) service" wiring))]
    (is (some? called) "the wiring calls nothing in the http namespace")
    (is (str/includes? http (str "(defn " called "\n"))
        (str "wiring calls http/" called ", and http.clj defines no such function"))))
