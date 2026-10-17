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
            [wagoe.platform.core.csrf :as csrf]
            [wagoe.platform.shell.http.interceptors :as http-interceptors]
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
  ;; requires its own namespaces and third-party libraries, and from the
  ;; framework only wagoe-shared-ui, which the generated deps.edn declares
  ;; directly rather than leaving it to a library the project may drop.
  (is (str/includes? (slurp "libs/wagoe-cli/resources/wagoe/cli/templates/deps.edn.tmpl")
                     "com.wagoe/wagoe-shared-ui")
      "a generated project does not declare wagoe-shared-ui")
  (doseq [[label source] [["web-handlers" (gen/generate-web-handlers-file ctx)]
                          ["ui"           (gen/generate-ui-file ctx)]
                          ["http"         (gen/generate-http-file ctx)]]]
    (let [foreign (->> (re-seq #"\[(wagoe\.[a-z0-9.-]+)" source)
                       (map second)
                       (remove #(str/starts-with? % "wagoe.gizmo."))
                       (remove #(str/starts-with? % "wagoe.shared.ui.core."))
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
  (let [catalog (template/build-module-context
                 {:module-name "catalog"
                  :base-ns     "wagoe"
                  :entities    [{:name   "Product"
                                 :fields [{:name :name :type :string}
                                          {:name :unit-price :type :decimal}]}]})]
    ;; The schema first: the UI builds its form from it.
    (eval-source! (gen/generate-schema-file catalog))
    (eval-source! (gen/generate-ui-file catalog)))
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

;; =============================================================================
;; The create form (BOU-465)
;; =============================================================================

(def ^:private gadget-ctx
  (template/build-module-context
   {:module-name "gadget"
    :base-ns     "wagoe"
    :entities    [{:name   "Gadget"
                   :fields [{:name :title :type :string}
                            {:name :qty :type :int}
                            {:name :contact-email :type :email :required false}]}]}))

(def ^:private gadget-service-source
  "A service that keeps what it was asked to create."
  "(ns wagoe.gadget.test-service
     (:require [wagoe.gadget.ports :as ports]))

   (def created (atom []))

   (defrecord StubGadgetService []
     ports/IGadgetService
     (create-gadget [_ data] (swap! created conj data) data)
     (get-gadget [_ id] {:id id})
     (list-gadgets [_ _opts] [])
     (update-gadget [_ id data] (assoc data :id id))
     (delete-gadget [_ _id] true))")

(defn- serve-gadgets
  "A Ring handler over the generated gadget module's :web routes, under /web."
  []
  (doseq [generate [gen/generate-schema-file
                    gen/generate-ports-file
                    gen/generate-core-file
                    gen/generate-ui-file
                    gen/generate-web-handlers-file
                    gen/generate-http-file]]
    (eval-source! (generate gadget-ctx)))
  (eval-source! gadget-service-source)
  (let [service ((ns-resolve 'wagoe.gadget.test-service '->StubGadgetService))
        routes  (:web ((resolve 'wagoe.gadget.shell.http/gadget-routes) service {}))]
    (ring/ring-handler (ring/router (into ["/web"] routes)))))

(defn- created []
  @@(resolve 'wagoe.gadget.test-service/created))

(deftest ^:unit the-create-form-carries-what-the-schema-implies
  (let [body (:body ((serve-gadgets) {:request-method :get :uri "/web/gadgets/new"}))]
    (testing "a full page, so htmx is loaded"
      (is (str/starts-with? body "<!DOCTYPE html>"))
      (is (str/includes? body "/js/htmx.min.js")))
    (testing "a plain post to the list, which works without JavaScript"
      (is (str/includes? body "<form action=\"/web/gadgets\" method=\"post\">")))
    (testing "each field with the attributes its schema implies"
      (is (re-find #"<input[^>]*name=\"title\"[^>]*required[^>]*type=\"text\"" body))
      (is (re-find #"<input[^>]*name=\"qty\"[^>]*required[^>]*type=\"number\"" body))
      (is (re-find #"<input[^>]*name=\"contact-email\"[^>]*type=\"email\"" body))
      (is (not (re-find #"<input[^>]*name=\"contact-email\"[^>]*required" body))))
    (testing "and validated on the server when it changes"
      (is (str/includes? body "hx-post=\"/web/gadgets/validate\""))
      (is (str/includes? body "hx-trigger=\"change delay:300ms\"")))))

(deftest ^:unit a-change-answers-with-the-field-and-its-errors
  (let [serve (serve-gadgets)
        field (fn [params]
                (serve {:request-method :post :uri "/web/gadgets/validate"
                        :headers {"hx-request" "true"} :form-params params}))]
    (testing "a wrong value: the field's wrapper, marked and explained"
      (let [{:keys [status body]} (field {"__field" "qty" "qty" "many" "title" ""})]
        (is (= 200 status))
        (is (str/starts-with? body "<div class=\"form-field has-errors\" id=\"qty-field\">"))
        (is (str/includes? body "value=\"many\""))
        (is (str/includes? body "aria-invalid=\"true\""))
        (is (str/includes? body "<span class=\"error\">Must be a number</span>"))
        (is (not (str/includes? body "title")) "only the field asked about")))
    (testing "a good value: the field, clean"
      (let [{:keys [body]} (field {"__field" "qty" "qty" "3"})]
        (is (str/starts-with? body "<div class=\"form-field\" id=\"qty-field\">"))
        (is (not (str/includes? body "field-errors")))))
    (testing "a field the form does not have"
      (is (= 400 (:status (field {"__field" "id"})))))
    (testing "nothing is written"
      (is (empty? (created))))))

(deftest ^:unit the-form-posted-without-javascript-still-validates
  (let [serve (serve-gadgets)
        post  (fn [params] (serve {:request-method :post :uri "/web/gadgets" :form-params params}))]
    (testing "rejected: the page again, with the values and the errors"
      (let [{:keys [status body]} (post {"title" "" "qty" "x" "contact-email" "nope"})]
        (is (= 422 status))
        (is (str/starts-with? body "<!DOCTYPE html>"))
        (is (str/includes? body "<span class=\"error\">Required</span>"))
        (is (str/includes? body "<span class=\"error\">Must be a number</span>"))
        (is (str/includes? body "<span class=\"error\">Invalid email format</span>"))
        (is (str/includes? body "value=\"nope\""))
        (is (empty? (created)))))
    (testing "accepted: created, decoded, and back to the list"
      (let [{:keys [status headers]} (post {"title" "Kettle" "qty" "3" "contact-email" ""})]
        (is (= 303 status))
        (is (= "/web/gadgets" (get headers "Location")))
        (is (= [{:title "Kettle" :qty 3}] (created)))))))

(deftest ^:unit an-error-with-no-field-is-shown-above-the-form
  ;; A :json field is a map, which the form has no field for, and is required
  ;; on create: its error would otherwise appear nowhere.
  (let [ctx (template/build-module-context
             {:module-name "crate"
              :base-ns     "wagoe"
              :entities    [{:name   "Crate"
                             :fields [{:name :label :type :string}
                                      {:name :meta :type :json}]}]})]
    (eval-source! (gen/generate-schema-file ctx))
    (eval-source! (gen/generate-ui-file ctx)))
  (let [page ((resolve 'wagoe.crate.core.ui/crate-new-page) {} {:meta [[:t :validation/required]]} {})
        html (str (h/html page))]
    (is (str/includes? html "<p>Meta: Required</p>") html)))

(deftest ^:unit the-field-endpoint-is-csrf-protected
  ;; A /web POST like any other, so the platform's CSRF check covers it.
  (let [secret  "test-secret"
        token   (csrf/generate-token secret "sess" (byte-array 16))
        handler (http-interceptors/wrap-csrf (serve-gadgets) {:enabled? true :secret secret})
        request {:request-method :post :uri "/web/gadgets/validate"
                 :headers {"hx-request" "true"}
                 :cookies {"session-token" {:value "sess"}}
                 :form-params {"__field" "qty" "qty" "3"}}]
    (is (= 403 (:status (handler request))))
    (is (= 200 (:status (handler (assoc-in request [:headers "x-csrf-token"] token)))))))
