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
            [wagoe.platform.shell.system.wiring :as wiring]))

(def ^:private platform-sources
  ["libs/platform/src/wagoe/platform/shell/system/wiring.clj"
   "libs/platform/src/wagoe/platform/shell/system/config.clj"
   "libs/platform/src/wagoe/platform/shell/modules.clj"])

(deftest ^:unit a-module-mounts-its-web-routes-where-it-says
  (let [{:keys [static web api]}
        (#'wiring/module-route-contributions
         [{:api [{:path "/a"}] :web [{:path "/a"}] :static [{:path "/s.css"}]}
          {:web [{:path "/b"}] :web-prefix "/web/admin"}])]

    (testing "the default is /web"
      (is (= "/web/a" (:path (first web)))))

    (testing "and a module that says otherwise gets what it said"
      (is (= "/web/admin/b" (:path (second web)))))

    (testing "static and api are mounted as-is — versioning is the caller's"
      (is (= ["/s.css"] (mapv :path static)))
      (is (= ["/a"] (mapv :path api))))

    (testing "web routes stay out of the API docs unless the module says so"
      (is (every? :no-doc web)))))

(deftest ^:unit a-module-can-override-no-doc-through-meta
  (let [{:keys [web]} (#'wiring/module-route-contributions
                       [{:web [{:path "/x" :meta {:no-doc false :summary "listed"}}]}])]
    (is (= false (:no-doc (first web))))
    (is (= "listed" (:summary (first web))))
    (is (not (contains? (first web) :meta)) ":meta is merged into the root, not kept")))

(deftest ^:unit already-pathed-routes-are-not-prefixed-again
  ;; admin's slash redirect: "/web/admin" must not become "/web/admin/web/admin".
  (let [{:keys [web]} (#'wiring/module-route-contributions
                       [{:web        [{:path "/"}]
                         :web-prefix "/web/admin"
                         :extra-web  [{:path "/web/admin" :no-doc true}]}])]
    (is (= ["/web/admin/" "/web/admin"] (mapv :path web)))))

(deftest ^:unit contributions-fold-in-the-order-given
  (let [{:keys [api]} (#'wiring/module-route-contributions
                       [{:api [{:path "/first"}]}
                        nil
                        {:api [{:path "/second"}]}])]
    (is (= ["/first" "/second"] (mapv :path api))
        "and a nil contribution — a module that is off — is skipped")))

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
