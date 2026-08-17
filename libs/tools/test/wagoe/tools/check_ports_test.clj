(ns wagoe.tools.check-ports-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [wagoe.tools.check-ports :as ports]))

;; ---------------------------------------------------------------------------
;; Classification helpers
;; ---------------------------------------------------------------------------

(deftest ^:unit web-layer?-detects-delivery-layers
  (testing "web/HTTP namespaces are recognised"
    (is (ports/web-layer? "wagoe.license.web.http"))
    (is (ports/web-layer? "wagoe.license.web.api"))
    (is (ports/web-layer? "wagoe.license.monitoring.shell.http"))
    (is (ports/web-layer? "wagoe.user.shell.handlers")))
  (testing "pure domain namespaces are not web layers"
    (is (not (ports/web-layer? "wagoe.license.billing.shell.service")))
    (is (not (ports/web-layer? "wagoe.license.billing.core.invoice")))))

(deftest ^:unit shell-ns?-detects-shell-namespaces
  (is (ports/shell-ns? "wagoe.license.billing.shell.service"))
  (is (ports/shell-ns? "wagoe.user.shell"))
  (is (not (ports/shell-ns? "wagoe.license.billing.core.invoice")))
  (is (not (ports/shell-ns? "wagoe.license.web.http"))))

(deftest ^:unit ns->module-extracts-module-prefix
  (is (= "wagoe.license.billing" (ports/ns->module "wagoe.license.billing.shell.service")))
  (is (= "wagoe.license.billing" (ports/ns->module "wagoe.license.billing.core.invoice")))
  (is (= "wagoe.license.billing" (ports/ns->module "wagoe.license.billing.ports")))
  (is (nil? (ports/ns->module "wagoe.license.web.http"))))

(deftest ^:unit persistence+service-require-detection
  (is (ports/persistence-require? "wagoe.license.catalog.shell.persistence"))
  (is (not (ports/persistence-require? "wagoe.license.catalog.shell.service")))
  (is (ports/service-require? "wagoe.license.catalog.shell.service"))
  (is (not (ports/service-require? "wagoe.license.catalog.ports"))))

;; ---------------------------------------------------------------------------
;; Rule 2 — cross-module shell coupling
;; ---------------------------------------------------------------------------

(deftest ^:unit cross-module-flags-foreign-shell-requires
  (testing "billing shell requiring catalog/customer shell is a violation"
    (let [v (ports/cross-module-violations
             "wagoe.license.billing.shell.service"
             ["wagoe.license.catalog.shell.service"
              "wagoe.license.customer.shell.service"
              "wagoe.license.billing.shell.persistence"])
          reqs (set (map :req v))]
      (is (contains? reqs "wagoe.license.catalog.shell.service"))
      (is (contains? reqs "wagoe.license.customer.shell.service"))
      (testing "same-module persistence require is allowed"
        (is (not (contains? reqs "wagoe.license.billing.shell.persistence")))))))

(deftest ^:unit cross-module-ignores-non-shell-namespaces
  (is (empty? (ports/cross-module-violations
               "wagoe.license.billing.core.invoice"
               ["wagoe.license.catalog.shell.service"]))))

(deftest ^:unit cross-module-covers-any-foreign-shell-namespace
  ;; BOU-307. The rule matched exactly two suffixes — `.shell.persistence` and
  ;; `.shell.service` — and every real coupling in the tree went around it:
  ;; database adapters, i18n render, auth middleware. Named suffixes cannot keep
  ;; up with names.
  (testing "an adapter is as much a reach into another module as a service is"
    (is (seq (ports/cross-module-violations
              "wagoe.tenant.shell.persistence"
              ["wagoe.platform.shell.adapters.database.protocols"]))))

  (testing "so is middleware, and a render helper"
    (is (seq (ports/cross-module-violations
              "wagoe.admin.shell.http" ["wagoe.user.shell.middleware"])))
    (is (seq (ports/cross-module-violations
              "wagoe.admin.shell.http.support" ["wagoe.i18n.shell.render"]))))

  (testing "a foreign core or ports namespace is not a violation"
    ;; Ports are the sanctioned way across a module boundary, and core is pure.
    (is (empty? (ports/cross-module-violations
                 "wagoe.tenant.shell.persistence"
                 ["wagoe.platform.ports" "wagoe.core.utils.type-conversion"])))))

(deftest ^:unit a-composition-root-may-name-implementations
  ;; Wiring is the one job that has to know concrete types. Flagging it would
  ;; report 39 of the 62 cross-module requires in this tree — every adapter the
  ;; system wiring assembles — and an allowlist that large stops being read.
  (testing "module wiring and system wiring are exempt"
    (is (empty? (ports/cross-module-violations
                 "wagoe.platform.shell.system.wiring"
                 ["wagoe.observability.logging.shell.adapters.stdout"])))
    (is (empty? (ports/cross-module-violations
                 "wagoe.user.shell.module-wiring"
                 ["wagoe.platform.shell.adapters.database.factory"]))))

  (testing "so are entry points, which compose to boot"
    (is (empty? (ports/cross-module-violations
                 "wagoe.user.shell.cli-entry"
                 ["wagoe.platform.shell.adapters.database.factory"]))))

  (testing "an ordinary shell namespace is not exempt for having 'wiring' nearby"
    ;; The exemption is the namespace's job, not a substring anywhere in it.
    (is (seq (ports/cross-module-violations
              "wagoe.tenant.shell.wiring-helpers"
              ["wagoe.platform.shell.adapters.database.config"])))))

(deftest ^:unit an-exemption-covers-a-target-prefix-and-must-say-why
  ;; 43 sites reach into 8 target groups. Exempting per site would make the list
  ;; unreadable and hide that they are eight decisions, not forty-three.
  (testing "a prefix entry exempts everything under it"
    (let [allow (ports/parse-shell-allowlist
                 {:allow-cross-module-shell
                  [{:target-prefix "wagoe.platform.shell.adapters.database"
                    :why "BOU-303 — platform has no database port yet."}]})]
      (is (ports/shell-target-allowed? allow "wagoe.platform.shell.adapters.database.protocols"))
      (is (ports/shell-target-allowed? allow "wagoe.platform.shell.adapters.database.common.core"))
      (is (not (ports/shell-target-allowed? allow "wagoe.user.shell.middleware")))))

  (testing "an entry without :why is rejected, not honoured"
    (is (thrown? clojure.lang.ExceptionInfo
                 (ports/parse-shell-allowlist
                  {:allow-cross-module-shell [{:target-prefix "wagoe.platform.shell"}]}))))

  (testing "and so is a typo in the :target-prefix key"
    ;; It used to yield #{nil}, and the nil only surfaced as an NPE from
    ;; starts-with? once some finding existed to test against — so a repo with
    ;; none passed green on a structurally broken allowlist.
    (is (thrown? clojure.lang.ExceptionInfo
                 (ports/parse-shell-allowlist
                  {:allow-cross-module-shell [{:targt-prefix "x" :why "typo in the key"}]})))))

(deftest ^:unit a-generated-project-passes-without-an-allowlist-file
  ;; A scaffolded project ships no .wagoe/check-ports.edn, and its persistence
  ;; layer requires platform's database adapters because platform exposes no
  ;; port. Failing `bb check` on a freshly scaffolded module for a gap in the
  ;; framework is the BOU-267 shape — and it is what the first-run smoke caught
  ;; when this rule was broadened without the builtins.
  (testing "the framework gaps a generated project cannot close are built in"
    (doseq [req ["wagoe.platform.shell.adapters.database.common.core"
                 "wagoe.platform.shell.persistence-interceptors"
                 "wagoe.i18n.shell.render"]]
      (is (ports/shell-target-allowed? ports/builtin-allow-cross-module-shell req)
          (str req " must not fail a scaffolded project"))))

  (testing "this repository's own couplings are not built in"
    ;; They belong on the burn-down list, where they can be seen and removed.
    (doseq [req ["wagoe.user.shell.middleware"
                 "wagoe.external.shell.adapters.smtp"]]
      (is (not (ports/shell-target-allowed? ports/builtin-allow-cross-module-shell req))))))

(deftest ^:unit an-exemption-that-stops-exempting-is-reported
  ;; Without this the list is a drawer. A prefix is worse than a per-site
  ;; exemption when it goes stale, because it pre-approves the next module that
  ;; reaches for the same target.
  (testing "a prefix with nothing left under it is stale"
    (is (= ["wagoe.gone.shell"]
           (ports/stale-shell-exemptions
            #{"wagoe.user.shell.middleware" "wagoe.gone.shell"}
            ["wagoe.user.shell.middleware"]))))

  (testing "a prefix still covering something is not"
    (is (empty? (ports/stale-shell-exemptions
                 #{"wagoe.platform.shell.adapters.database"}
                 ["wagoe.platform.shell.adapters.database.protocols"])))))

;; ---------------------------------------------------------------------------
;; Rule 3 — web layer requiring persistence
;; ---------------------------------------------------------------------------

(deftest ^:unit web-persistence-flags-direct-persistence-requires
  (let [v (ports/web-persistence-violations
           "wagoe.license.web.http"
           ["wagoe.license.customer.shell.persistence"
            "wagoe.license.catalog.shell.persistence"
            "wagoe.license.billing.shell.service"])
        reqs (set (map :req v))]
    (is (contains? reqs "wagoe.license.customer.shell.persistence"))
    (is (contains? reqs "wagoe.license.catalog.shell.persistence"))
    (testing "service requires from web are not flagged by rule 3"
      (is (not (contains? reqs "wagoe.license.billing.shell.service"))))))

(deftest ^:unit web-persistence-ignores-non-web-namespaces
  (is (empty? (ports/web-persistence-violations
               "wagoe.license.billing.core.invoice"
               ["wagoe.license.customer.shell.persistence"]))))

(deftest ^:unit web-persistence-exempts-core-namespaces-with-web-segment
  (testing "a core namespace is never treated as a web/HTTP delivery layer,
            even when a path segment (api/http) matches"
    (is (empty? (ports/web-persistence-violations
                 "wagoe.license.billing.core.api"
                 ["wagoe.license.customer.shell.persistence"])))))

;; ---------------------------------------------------------------------------
;; Escape hatch — ^:wagoe/allow-direct ns metadata
;; ---------------------------------------------------------------------------

(deftest ^:unit allow-direct-metadata-exempts-namespace
  (testing "the ns symbol's :wagoe/allow-direct metadata is detected"
    (let [form (read-string "(ns ^:wagoe/allow-direct wagoe.license.web.http (:require [x]))")]
      (is (true? (:wagoe/allow-direct (meta (second form))))))
    (let [form (read-string "(ns wagoe.license.web.http (:require [x]))")]
      (is (nil? (:wagoe/allow-direct (meta (second form))))))))

;; ---------------------------------------------------------------------------
;; Rule 1 — module completeness
;; ---------------------------------------------------------------------------

(deftest ^:unit module-completeness-detects-missing-and-empty-ports
  (testing "monorepo libs (e.g. user) define a non-empty ports.clj"
    (let [dir (io/file "libs/user/src/wagoe/user")]
      (when (.exists dir)
        (is (nil? (ports/module-completeness-violation dir))
            "user module should have a ports.clj with a defprotocol")))))

;; ---------------------------------------------------------------------------
;; Discovery sanity — the scanner finds real monorepo modules
;; ---------------------------------------------------------------------------

(deftest ^:unit module-dirs-includes-libs
  (let [dirs (map ports/dir->ns-prefix (ports/module-dirs (ports/source-roots)))]
    (is (some #{"wagoe.user"} dirs))
    (is (some #{"wagoe.tenant"} dirs))))

;; ---------------------------------------------------------------------------
;; End-to-end — collect-violations over a temp fixture tree
;; ---------------------------------------------------------------------------

(defn- spit-ns
  "Create dirs + write a .clj file under `src-root` for a namespace path."
  [src-root rel-path content]
  (let [f (io/file src-root rel-path)]
    (.mkdirs (.getParentFile f))
    (spit f content)))

(defn- build-fixture!
  "Lay out a fixture project under a temp `<tmp>/src` root:
   - billing module: core/ + shell/, NO ports.clj, shell.service requires
     catalog.shell.service (cross-module)
   - catalog module: core/ + shell/ + ports.clj with a defprotocol (complete)
   - web/http requires billing.shell.persistence (web-persistence)
   Returns the src-root File."
  []
  (let [tmp (.toFile (java.nio.file.Files/createTempDirectory
                      "check-ports-fixture"
                      (make-array java.nio.file.attribute.FileAttribute 0)))
        src (io/file tmp "src")]
    (spit-ns src "wagoe/fixture/billing/core/calc.clj"
             "(ns wagoe.fixture.billing.core.calc)")
    (spit-ns src "wagoe/fixture/billing/shell/service.clj"
             "(ns wagoe.fixture.billing.shell.service\n  (:require [wagoe.fixture.catalog.shell.service :as cat]))")
    (spit-ns src "wagoe/fixture/catalog/core/calc.clj"
             "(ns wagoe.fixture.catalog.core.calc)")
    (spit-ns src "wagoe/fixture/catalog/shell/service.clj"
             "(ns wagoe.fixture.catalog.shell.service)")
    (spit-ns src "wagoe/fixture/catalog/ports.clj"
             "(ns wagoe.fixture.catalog.ports)\n(defprotocol ICatalog (find-it [this id]))")
    (spit-ns src "wagoe/fixture/web/http.clj"
             "(ns wagoe.fixture.web.http\n  (:require [wagoe.fixture.billing.shell.persistence :as db]))")
    src))

(deftest ^:integration collect-violations-detects-all-three-classes
  (let [src   (build-fixture!)
        empty {:allow-missing-ports #{} :allow-direct #{}}
        {:keys [modules violations]} (ports/collect-violations empty [src])
        by-kind (group-by :kind violations)]
    (is (= 2 modules) "billing + catalog are modules; web is not")
    (testing "rule 1 — billing missing ports.clj, catalog is complete"
      (is (= ["wagoe.fixture.billing"]
             (map :module (:missing-ports by-kind)))))
    (testing "rule 2 — billing shell requires catalog shell service"
      (is (= [{:kind :cross-module
               :ns   "wagoe.fixture.billing.shell.service"
               :req  "wagoe.fixture.catalog.shell.service"}]
             (map #(dissoc % :file) (:cross-module by-kind)))))
    (testing "rule 3 — web requires billing persistence"
      (is (= [{:kind :web-persistence
               :ns   "wagoe.fixture.web.http"
               :req  "wagoe.fixture.billing.shell.persistence"}]
             (map #(dissoc % :file) (:web-persistence by-kind)))))))

(deftest ^:integration collect-violations-honours-allowlists
  (let [src (build-fixture!)
        cfg {:allow-missing-ports #{"wagoe.fixture.billing"}
             :allow-direct        #{"wagoe.fixture.web.http"
                                    "wagoe.fixture.billing.shell.service"}}
        {:keys [violations]} (ports/collect-violations cfg [src])]
    (is (empty? violations)
        "allowlisting the module + the two coupled namespaces clears every violation")))
