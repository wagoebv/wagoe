(ns wagoe.scaffolder.interfaces-test
  "`:interfaces` decides which interfaces a generated module gets.

   It decided nothing. The CLI declared `--http`, `--cli` and `--web`, threaded
   them into the request and into the template context, and no generator ever
   read them — so every module got every file, and `core/ui.clj` and
   `shell/web_handlers.clj` sat in projects that wanted no web UI with nothing
   mounting them (BOU-479). The same shape as the `--force` flag nothing read
   (BOU-308).

   Absent means yes, because that is what every existing caller means by
   leaving it out. Only an explicit `false` drops anything."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.cli]
            [wagoe.scaffolder.cli :as cli]
            [wagoe.scaffolder.core.generators :as gen]
            [wagoe.scaffolder.core.template :as template]
            [wagoe.scaffolder.ports :as ports]
            [wagoe.scaffolder.shell.service :as service]))

(defn- generated-paths
  "The paths `generate-module` reports for `interfaces`, as a set."
  [interfaces]
  (let [result (ports/generate-module
                (service/create-scaffolder-service)
                (cond-> {:module-name "widget"
                         :entities    [{:name "Widget"
                                        :fields [{:name :label :type :string}]}]
                         :dry-run     true}
                  (some? interfaces) (assoc :interfaces interfaces)))]
    (is (true? (:success result)) (pr-str (:errors result)))
    (set (map :path (:files result)))))

(defn- has-file? [paths suffix]
  (boolean (some #(str/ends-with? % suffix) paths)))

;; =============================================================================
;; Which files are written
;; =============================================================================

(deftest ^:unit web-false-writes-no-web-files
  (let [paths (generated-paths {:http true :web false})]
    (testing "the web UI files are the ones that go"
      (is (not (has-file? paths "core/ui.clj")))
      (is (not (has-file? paths "shell/web_handlers.clj"))))

    (testing "and nothing else does"
      (doseq [kept ["schema.clj" "ports.clj" "core/widget.clj"
                    "shell/service.clj" "shell/persistence.clj"
                    "shell/http.clj" "shell/module_wiring.clj"]]
        (is (has-file? paths kept) (str kept " should survive --no-web"))))))

(deftest ^:unit the-default-is-every-interface
  ;; Absent and explicit-true have to agree, or turning the flag live would
  ;; silently change what `bb scaffold generate` writes for everyone.
  (let [absent   (generated-paths nil)
        explicit (generated-paths {:http true :web true})]
    (is (= absent explicit))
    (is (has-file? absent "core/ui.clj"))
    (is (has-file? absent "shell/web_handlers.clj"))))

;; =============================================================================
;; What the route contribution carries
;; =============================================================================

(defn- contribution
  "Load the generated http.clj for `interfaces` and call its route function."
  [interfaces]
  (let [ctx (template/build-module-context
             (cond-> {:module-name "gadget"
                      :entities    [{:name "Gadget" :fields []}]}
               (some? interfaces) (assoc :interfaces interfaces)))]
    ;; ports/ui/web-handlers first: with :web on, http.clj requires the
    ;; module's web-handlers namespace (BOU-484).
    (doseq [generate [gen/generate-schema-file
                      gen/generate-ports-file
                      gen/generate-ui-file
                      gen/generate-web-handlers-file
                      gen/generate-http-file]]
      (binding [*ns* *ns*]
        (doseq [form (read-string (str "[" (generate ctx) "]"))]
          (eval form))))
    ((resolve 'wagoe.gadget.shell.http/gadget-routes) nil {})))

(deftest ^:unit the-route-contribution-follows-the-interfaces
  ;; Dropping the files while still mounting routes that serve them would be a
  ;; module that fails to load, so the contribution has to move with them.
  (testing "no web UI, no web routes"
    (let [c (contribution {:http true :web false})]
      (is (seq (:api c)))
      (is (= [] (:web c)))))

  (testing "no HTTP API, no api routes"
    (let [c (contribution {:http false :web true})]
      (is (= [] (:api c)))
      (is (seq (:web c)))))

  (testing "the contribution keeps all three parts either way"
    (doseq [interfaces [{:http false :web false} {:http true :web true} nil]]
      (is (= #{:api :web :static} (set (keys (contribution interfaces))))))))

;; =============================================================================
;; The CLI flags
;; =============================================================================

(deftest ^:unit the-web-flag-can-actually-be-switched-off
  ;; `[nil "--web" ...]` with `:default true` is a boolean flag: there is no
  ;; value to give it, so `--web false` set it to true and left "false" as a
  ;; stray argument. `--[no-]web` is the form that can say no.
  (let [parse (fn [args] (:options (clojure.tools.cli/parse-opts args cli/generate-options)))]
    (is (false? (:web (parse ["--no-web"]))))
    (is (true? (:web (parse ["--web"]))))
    (is (true? (:web (parse []))) "the default is still every interface")

    (testing "and the same for the API"
      (is (false? (:http (parse ["--no-http"]))))
      (is (true? (:http (parse [])))))))

(deftest ^:unit the-cli-interface-flag-is-gone
  ;; `--cli` promised an interface the scaffolder has never had a generator
  ;; for: true or false, no CLI file was ever written. A flag that cannot
  ;; change anything is worse than no flag (BOU-479).
  (is (not-any? #(= "--cli" (second %)) cli/generate-options)
      "--cli is still a declared option")
  (is (not (str/includes? cli/generate-help "--cli"))
      "--cli is still in the help text"))
