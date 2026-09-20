(ns wagoe.platform.shell.system.route-contribution-test
  "A library can serve HTTP without platform knowing it exists.

   `:wagoe/http-handler` destructured six named route slots — user, admin,
   tenant, membership, workflow, search — each with its own copy of the same
   prefix-and-normalise block. Mount prefix, doc visibility and versioning for
   every module lived in platform, so a 31st library could not contribute a
   route without editing platform. Middleware was given injection in BOU-131;
   routes get it here (BOU-330).

   `acme.widgets` is the proof: a module platform has never heard of, with a
   mount prefix of its own."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.platform.shell.system.config :as sys]
            [integrant.core]
            [wagoe.platform.shell.http.reitit-router :as reitit-router]
            [wagoe.platform.shell.system.wiring :as wiring]))

(def ^:private platform-sources
  ["libs/platform/src/wagoe/platform/shell/system/wiring.clj"
   "libs/platform/src/wagoe/platform/shell/system/config.clj"
   "libs/platform/src/wagoe/platform/shell/modules.clj"])

(deftest ^:unit a-module-mounts-its-web-routes-where-it-says
  (let [{:keys [static web api]}
        (#'wiring/module-route-contributions
         [{:api [["/a" {}]] :web [["/a" {}]] :static [["/s.css" {}]]}
          {:web [["/b" {}]] :web-prefix "/web/admin"}])]

    (testing "the default is /web"
      (is (= "/web/a" (first (first web)))))

    (testing "and a module that says otherwise gets what it said"
      (is (= "/web/admin/b" (first (second web)))))

    (testing "static and api are mounted as-is — versioning is the caller's"
      (is (= ["/s.css"] (mapv first static)))
      (is (= ["/a"] (mapv first api))))

    (testing "web routes stay out of the API docs unless the module says so"
      (is (every? (comp :no-doc second) web)))))

(deftest ^:unit a-module-can-list-a-web-route-in-the-api-docs
  (let [{:keys [web]} (#'wiring/module-route-contributions
                       [{:web [["/x" {:no-doc false :summary "listed"}]]}])
        data          (second (first web))]
    (is (= false (:no-doc data)) "the module's own :no-doc wins over the default")
    (is (= "listed" (:summary data)))))

(deftest ^:unit already-pathed-routes-are-not-prefixed-again
  ;; admin's slash redirect: "/web/admin" must not become "/web/admin/web/admin".
  (let [{:keys [web]} (#'wiring/module-route-contributions
                       [{:web        [["/" {}]]
                         :web-prefix "/web/admin"
                         :extra-web  [["/web/admin" {:no-doc true}]]}])]
    (is (= ["/web/admin/" "/web/admin"] (mapv first web)))))

(deftest ^:unit contributions-fold-in-the-order-given
  (let [{:keys [api]} (#'wiring/module-route-contributions
                       [{:api [["/first" {}]]}
                        nil
                        {:api [["/second" {}]]}])]
    (is (= ["/first" "/second"] (mapv first api))
        "and a nil contribution — a module that is off — is skipped")))

;; =============================================================================
;; Specificity ordering (BOU-477)
;; =============================================================================

(defn- marker-route
  "A route at `path` whose handler answers with `body`, on `method`."
  ([path body] (marker-route path :get body))
  ([path method body]
   [path {method {:handler (fn [_] {:status 200 :body body})}}]))

(def ^:private admin-contribution
  "The shapes `libs/admin/src/wagoe/admin/shell/http.clj` contributes."
  {:web-prefix "/web/admin"
   :web        [(marker-route "/:entity/new" "admin-new")
                (marker-route "/:entity/:id/:field" :patch "admin-inline")
                (marker-route "/:entity" "admin-list")
                (marker-route "/:entity/:id" "admin-detail")]})

(def ^:private workflow-contribution
  "What the workflow module mounts under the same prefix."
  {:web-prefix "/web/admin"
   :web        [(marker-route "/workflows" "workflow-list")
                (marker-route "/workflows/:id" "workflow-detail")]})

(def ^:private app-contribution
  "An application's own page below an admin entity — the case that 405'd."
  {:web-prefix "/web/admin"
   :web        [(marker-route "/invoices/:id/workflow" "app-page")]})

(defn- answers
  "What the mounted handler returns for a GET of each path in `paths`."
  [contributions paths]
  (let [handler (reitit-router/compile-routes
                 (:web (#'wiring/module-route-contributions contributions))
                 {:swagger-enabled false})]
    (into {} (for [p paths]
               [p (:body (handler {:request-method :get :uri p}))]))))

(deftest ^:unit a-literal-route-wins-over-a-sibling-modules-wildcard
  ;; Reitit prefers a literal segment over a parameter only in its
  ;; `:segment-router`. The moment any pair of routes conflicts it builds a
  ;; `:quarantine-router` instead, and that one matches the quarantined set
  ;; linearly, in declaration order. Three modules mount under /web/admin and
  ;; admin contributes `/:entity`, so whichever module folded first answered
  ;; everything: /web/admin/workflows 500'd as an unknown entity, and an
  ;; application page below an entity got admin's PATCH-only inline-edit route
  ;; and a 405 (BOU-477).
  (let [paths ["/web/admin/workflows"
               "/web/admin/workflows/7"
               "/web/admin/invoices/9/workflow"
               "/web/admin/users"
               "/web/admin/users/new"]
        expected {"/web/admin/workflows"           "workflow-list"
                  "/web/admin/workflows/7"         "workflow-detail"
                  "/web/admin/invoices/9/workflow" "app-page"
                  "/web/admin/users"               "admin-list"
                  "/web/admin/users/new"           "admin-new"}]

    (testing "admin folded first"
      (is (= expected (answers [admin-contribution workflow-contribution
                                app-contribution]
                               paths))))

    (testing "and the answer does not depend on which module folded first"
      (is (= expected (answers [app-contribution workflow-contribution
                                admin-contribution]
                               paths))))))

(deftest ^:unit a-catch-all-beside-a-deeper-literal-still-fails-the-boot
  ;; Ordering settles pairs where one segment is literal and the other a
  ;; parameter. A catch-all is not that case — it matches at every depth below
  ;; its prefix — so it stays what it was: a boot failure, not a silent win.
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"can both match the same request"
       (answers [{:web-prefix "/static"
                  :web        [(marker-route "/*path" "the-catch-all")
                               (marker-route "/css/app.css" "the-stylesheet")]}]
                ["/static/css/app.css"]))))

(deftest ^:integration a-library-platform-never-heard-of-serves-http
  (let [cfg {:wagoe/profile :test
             :active {:wagoe/settings {:name "t"}
                      :wagoe/h2       {:memory true}
                      :wagoe/widgets  {:enabled? true}}}
        m   (sys/system-config cfg)]

    (testing "its components are built"
      (is (contains? m :wagoe/widgets))
      (is (contains? m :wagoe/widgets-routes)))

    (testing "and its routes reach the handler, without a slot of their own"
      (is (some #{(integrant.core/ref :wagoe/widgets-routes)}
                (get-in m [:wagoe/http-handler :module-routes]))))

    (testing "with no mention of it anywhere in platform"
      ;; The whole claim. If this fails, the coupling came back.
      (doseq [f platform-sources]
        (is (not (str/includes? (slurp f) "widget"))
            (str f " names the module, so platform still holds the list"))))))

(deftest ^:integration a-module-with-no-routes-is-not-referenced-as-if-it-had-some
  ;; `discovered-route-refs` refs `:wagoe/<name>-routes` by convention. That was
  ;; safe while every discovered module went through the scaffolder's four-key
  ;; shape, which always builds one. A module with a graph of its own need not
  ;; have routes at all — a background-jobs library is exactly the 31st library
  ;; this ticket is meant to welcome — and referencing a component it never
  ;; built is a dangling ref, which Integrant refuses (BOU-330).
  (let [m (sys/system-config {:wagoe/profile :test
                              :active {:wagoe/settings {}
                                       :wagoe/h2       {:memory true}
                                       :wagoe/gadgets  {:enabled? true}}})]
    (testing "its component is built"
      (is (contains? m :wagoe/gadgets)))

    (testing "and nothing refers to routes it does not have"
      (is (not (some #{(integrant.core/ref :wagoe/gadgets-routes)}
                     (get-in m [:wagoe/http-handler :module-routes])))
          "the handler would fail to build: no such key in the config")
      (is (not (contains? m :wagoe/gadgets-routes))))))
