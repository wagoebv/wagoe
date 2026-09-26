(ns wagoe.scaffolder.generated-web-ui-test
  "The web UI `bb scaffold generate` writes, actually served.

   `shell/web_handlers.clj` and `core/ui.clj` were a working pair that nothing
   called: `http.clj`'s `web-routes` mounted an inline stub returning
   `\"<html><body>Web UI</body></html>\"`, so the two files sat in every module
   as dead namespaces (BOU-484). Mounting them exposed the second half of the
   defect — the handler returned a Hiccup *vector* as `:body`, which Ring
   cannot serve.

   Both halves need the routes to be run rather than read, so this test builds
   a Reitit router from the generated contribution and asks it for the page."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hiccup2.core :as h]
            [reitit.ring :as ring]
            [wagoe.scaffolder.core.generators :as gen]
            [wagoe.scaffolder.core.template :as template]))

(def ^:private ctx
  (template/build-module-context
   {:module-name "gizmo"
    :base-ns     "wagoe"
    :entities    [{:name "Gizmo" :fields [{:name :label :type :string}]}]}))

(defn- eval-source! [source]
  (binding [*ns* *ns*]
    (doseq [form (read-string (str "[" source "]"))]
      (eval form))))

(def ^:private stub-service-source
  "A service that answers `list-gizmos` with two rows — what the web handler
   asks its service for."
  "(ns wagoe.gizmo.test-service
     (:require [wagoe.gizmo.ports :as ports]))

   (defrecord StubGizmoService []
     ports/IGizmoService
     (create-gizmo [_ data] data)
     (get-gizmo [_ id] {:id id})
     (list-gizmos [_ _opts]
       [{:id \"gizmo-one\" :label \"first\"} {:id \"gizmo-two\" :label \"second\"}])
     (update-gizmo [_ id data] (assoc data :id id))
     (delete-gizmo [_ _id] true))")

(defn- serve-web-routes
  "Load the generated module and return a Ring handler over its :web routes."
  []
  (doseq [generate [gen/generate-schema-file
                    gen/generate-ports-file
                    gen/generate-core-file
                    gen/generate-ui-file
                    gen/generate-web-handlers-file
                    gen/generate-http-file]]
    (eval-source! (generate ctx)))
  (eval-source! stub-service-source)
  (let [service      ((ns-resolve 'wagoe.gizmo.test-service '->StubGizmoService))
        contribution ((resolve 'wagoe.gizmo.shell.http/gizmo-routes) service {})]
    (ring/ring-handler (ring/router (:web contribution)))))

(deftest ^:unit the-generated-web-page-lists-what-the-service-returns
  (let [response ((serve-web-routes) {:request-method :get :uri "/gizmos"})]

    (testing "the route answers"
      (is (= 200 (:status response)))
      (is (str/starts-with? (get-in response [:headers "Content-Type"]) "text/html")))

    (testing "the body is HTML, not a Hiccup vector"
      ;; Ring cannot serve a vector. The generated handler returned
      ;; `(ui/gizmo-list-page items {})` directly, so mounting it would have
      ;; produced a response no adapter could write.
      (is (string? (:body response))
          (str "body is a " (type (:body response)) ", which Ring cannot write")))

    (testing "and it is the service's rows, rendered"
      (let [body (:body response)]
        (is (str/includes? body "first"))
        (is (str/includes? body "second"))
        (is (not (str/includes? body "Web UI"))
            "still the inline stub — web-routes does not call the generated handler")))))

(deftest ^:unit the-generated-web-ui-requires-no-optional-wagoe-library
  ;; It rendered through `wagoe.i18n.shell.render`, and `wagoe-i18n` is a
  ;; module a project is invited to drop — libs/i18n/AGENTS.md says an app that
  ;; translates nothing need not ship it, and the generated deps.edn says each
  ;; of those modules is independent. So the default `--web` output could not
  ;; load in a project that took that advice, to render a page with no
  ;; translation markers in it (BOU-484 review).
  ;;
  ;; The rule is the one a generated module can actually keep: its web layer
  ;; requires its own namespaces and third-party libraries, nothing else from
  ;; the framework.
  (doseq [[label source] [["web-handlers" (gen/generate-web-handlers-file ctx)]
                          ["ui"           (gen/generate-ui-file ctx)]
                          ["http"         (gen/generate-http-file ctx)]]]
    (let [foreign (->> (re-seq #"\[(wagoe\.[a-z0-9.-]+)" source)
                       (map second)
                       (remove #(str/starts-with? % "wagoe.gizmo."))
                       set)]
      (is (empty? foreign)
          (str label " requires " (vec foreign)
               " — a generated module must not depend on a droppable framework library")))))

(deftest ^:unit the-web-route-is-wired-to-the-generated-handler
  ;; The assertion above could be satisfied by inlining the whole page into
  ;; http.clj, which would leave web_handlers.clj just as dead. The point of
  ;; the ticket is that the generated file is the one doing the work.
  (let [http (gen/generate-http-file ctx)]
    (is (str/includes? http "web-handlers")
        "http.clj does not require the module's web-handlers namespace")
    (is (not (str/includes? http "<html><body>Web UI</body></html>"))
        "the inline stub is still there")))

(deftest ^:unit the-list-page-shows-the-declared-fields
  ;; It rendered `[:p (str (:id item))]` per row, so a module scaffolded with
  ;; name and price served a page of bare UUIDs (BOU-486).
  (eval-source! (gen/generate-ui-file
                 (template/build-module-context
                  {:module-name "catalog"
                   :base-ns     "wagoe"
                   :entities    [{:name   "Product"
                                  :fields [{:name :name :type :string}
                                           {:name :unit-price :type :decimal}]}]})))
  (let [page ((resolve 'wagoe.catalog.core.ui/product-list-page)
              [{:id "p-1" :name "Kettle" :unit-price 12.50M}] {})
        html (str (h/html page))]
    (testing "one header per declared field"
      (is (str/includes? html "<th>Name</th>") html)
      (is (str/includes? html "<th>Unit price</th>") html))
    (testing "and each row's values under them"
      (is (str/includes? html "<td>Kettle</td>") html)
      (is (str/includes? html "<td>12.50</td>") html))
    (testing "not the id in place of the data"
      (is (not (str/includes? html "p-1")) html))))
