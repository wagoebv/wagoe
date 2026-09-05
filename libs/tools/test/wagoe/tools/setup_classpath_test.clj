(ns wagoe.tools.setup-classpath-test
  "BOU-414: `bb setup` may only switch on modules the project can actually load.

   `bb setup --ai-provider replicate` wrote `:wagoe/ai-service` into `:active`
   while `com.wagoe/wagoe-ai` was declared only in the generated project's `:mcp`
   alias. `(go)` runs under `:repl`, so the wiring namespace was not on the
   classpath and the boot threw:

       Module :wagoe/ai-service is enabled but wagoe.ai.shell.module-wiring is
       not on the classpath.

   Nothing caught it. `bb doctor` and `bb quickstart` both pass on that project
   — quickstart deliberately keeps an existing dev config (BOU-228), so the key
   survives to the one step that fails. Every gate was green on a project that
   could not start.

   The invariant these tests hold is the one the deps template already states in
   a comment: the modules a fresh project can switch on by editing config.edn
   alone must be on its *default* classpath. Anything `bb setup` can write is by
   definition such a module."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.tools.setup :as setup]))

(def ^:private deps-template-path
  "libs/wagoe-cli/resources/wagoe/cli/templates/deps.edn.tmpl")

(defn- deps-template []
  (let [f (io/file deps-template-path)]
    (assert (.exists f)
            (str deps-template-path " not found — run this from the repository root"))
    (slurp f)))

(defn- default-classpath
  "The template's top-level `:deps` section, with `:aliases` onwards cut off.

   That cut is the whole point: both halves of BOU-414 were artifacts present in
   the file all along but declared in an alias — `wagoe-ai` in `:mcp`, the
   PostgreSQL and MySQL drivers in `:user-cli`. A test that grepped the template
   would have passed while both bugs were live. An alias is not the classpath
   `(go)` runs on."
  [template]
  (subs template 0 (str/index-of template "\n :aliases")))

(defn- default-classpath-libs
  "The `com.wagoe/wagoe-*` artifacts on the default classpath, by library name."
  [template]
  (set (map second (re-seq #"com\.wagoe/wagoe-([a-z0-9-]+)" (default-classpath template)))))

(defn- on-default-classpath?
  "Whether `coord` — a `group/artifact` string — is declared outside the aliases."
  [template coord]
  (str/includes? (default-classpath template) coord))

(def ^:private module-key->lib
  "Which library owns each `:active` key `bb setup` can write.

   Restated from `wagoe.platform.shell.modules/framework-modules` rather than
   read from it: platform is a JVM library and this suite runs on babashka, and
   `bb check:isolation` requires tools to depend on none of these. A test
   restating the expectation is the ordinary arrangement — if platform's map
   moves, this is the line that should be reviewed with it.

   Only the keys the setup templates emit are listed. The database keys are
   handled separately — they are not modules, but they have the same classpath
   consequence; see `database-driver`."
  {:wagoe/ai-service       "ai"
   :wagoe/payment-provider "payments"
   :wagoe/cache            "cache"
   :wagoe/admin            "admin"
   :wagoe.external/smtp    "external"})

(def ^:private database-driver
  "The JDBC artifact each `bb setup --database` value needs at boot.

   The other half of BOU-414, and the half a module-shaped rule misses. These
   keys are not modules — platform reads `:wagoe/postgresql` itself — so nothing
   above would ever look at them, while the failure is identical: the config
   names an adapter whose driver is not on the classpath, and the app dies on
   `Class org.postgresql.Driver not found` instead of on a missing wiring
   namespace. Same cause, different exception."
  {:postgresql "org.postgresql/postgresql"
   :sqlite     "org.xerial/sqlite-jdbc"
   :h2         "com.h2database/h2"
   :mysql      "com.mysql/mysql-connector-j"})

(defn- template-for
  "The config fragment `bb setup` writes for `flag` set to `choice`."
  [flag choice env]
  (case flag
    :ai-provider (#'setup/ai-template choice env)
    :payment     (#'setup/payment-template choice env)
    :cache       (#'setup/cache-template choice env)
    :email       (#'setup/email-template choice env)))

(defn- module-keys-in
  "Every `:active` module key `fragment` declares."
  [fragment]
  (->> (re-seq #"(?m)^\s{2}(:wagoe(?:\.[a-z]+)?/[a-z-]+)$" fragment)
       (map (comp keyword #(subs % 1) second))
       set))

;; =============================================================================
;; The regression itself
;; =============================================================================

(deftest ^:unit every-database-setup-accepts-has-its-driver
  ;; The other half of BOU-414. `bb setup --database postgresql` switches the
  ;; adapter in config.edn alone, but the PostgreSQL and MySQL drivers were
  ;; declared only in the :user-cli alias — so `-M:run` and `(go)` died on
  ;; `Class org.postgresql.Driver not found`. sqlite and h2 happened to be on
  ;; the default classpath, which is why the default path never showed it.
  (let [template (deps-template)]
    (doseq [choice (:database setup/valid-choices)]
      (testing (str "bb setup --database " (name choice))
        (let [coord (get database-driver choice)]
          (is (some? coord)
              (str "bb setup --database accepts " choice " and this test does not "
                   "know which driver it needs. Add it to database-driver."))
          (when coord
            ;; `true?` on a precomputed boolean, so a failure prints the claim
            ;; rather than the whole deps template.
            (is (true? (boolean (on-default-classpath? template coord)))
                (str "bb setup --database " (name choice) " selects a driver, "
                     coord ", that is not on the generated project's default "
                     "classpath. The app would start and then fail to connect."))))))))

(deftest ^:unit ai-is-on-the-default-classpath-not-only-in-the-mcp-alias
  ;; The one-line version of BOU-414. Kept separate from the general rule below
  ;; so a failure names the actual defect rather than a set difference.
  (let [libs (default-classpath-libs (deps-template))]
    (testing "a generated project can load the AI module it may be configured for"
      (is (contains? libs "ai")
          (str "com.wagoe/wagoe-ai is not in the generated project's top-level :deps. "
               "`bb setup --ai-provider <any non-none>` writes :wagoe/ai-service into "
               ":active, and (go) resolves module wiring on the default classpath — "
               "so the project it produces cannot boot (BOU-414).")))

    (testing "the libraries the deps template calls switchable are all there"
      ;; Guards the reverse mistake: satisfying the assertion above by deleting
      ;; something else that setup can also switch on.
      (doseq [lib ["cache" "admin" "payments" "external" "i18n"]]
        (is (contains? libs lib)
            (str "com.wagoe/wagoe-" lib " left the top-level :deps"))))))

;; =============================================================================
;; The general rule, so the next module cannot repeat it
;; =============================================================================

(deftest ^:unit every-module-setup-can-enable-is-loadable
  ;; The rule BOU-414 broke, applied to every flag rather than to AI alone.
  ;; `bb setup` writing a key the project cannot resolve is always a project
  ;; that fails at (go) with every earlier step green.
  (let [libs (default-classpath-libs (deps-template))]
    (doseq [[flag choices] setup/valid-choices
            :when          (contains? #{:ai-provider :payment :cache :email} flag)
            choice         choices
            :when          (not= :none choice)
            env            ["dev" "test"]
            module-key     (module-keys-in (template-for flag choice env))]
      (testing (str "bb setup --" (name flag) " " (name choice) " (" env ")")
        (let [lib (get module-key->lib module-key)]
          (is (some? lib)
              (str module-key " is written into :active by a setup template but this "
                   "test does not know which library owns it. Add it to "
                   "module-key->lib — and check the generated deps.edn ships that "
                   "library, which is the thing being asserted."))
          (when lib
            (is (contains? libs lib)
                (str "bb setup --" (name flag) " " (name choice) " enables " module-key
                     ", which needs com.wagoe/wagoe-" lib " on the default classpath. "
                     "It is not in the generated project's top-level :deps, so the "
                     "project this produces would throw at (go)."))))))))

;; =============================================================================
;; The assumptions the two tests above rest on
;; =============================================================================

(deftest ^:unit the-gate-is-looking-at-something
  ;; A parse that silently found nothing would make both tests vacuous — the
  ;; BOU-250 shape, a check reporting clean because it could not look.
  (let [template (deps-template)
        libs     (default-classpath-libs template)]
    (testing "the template parses to a plausible library set"
      (is (< 10 (count libs))
          "expected the full top-level :deps, so a passing run means something"))

    (testing "the alias section really is excluded"
      ;; scaffolder appears only in :mcp. If it shows up here the cut moved and
      ;; every assertion above became trivially true.
      (is (not (contains? libs "scaffolder"))
          "libraries from :aliases leaked into the top-level set"))

    (testing "the AI templates still emit the key this is all about"
      (doseq [p [:ollama :anthropic :openai :replicate]]
        (is (contains? (module-keys-in (#'setup/ai-template p "dev")) :wagoe/ai-service)
            (str "ai-template " p " no longer writes :wagoe/ai-service — if the key "
                 "was renamed, module-key->lib needs the new one"))))))
