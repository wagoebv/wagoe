(ns wagoe.tools.scaffold-ai-test
  "BOU-401: `bb scaffold ai` could not run in a generated project.

     $ bb scaffold ai \"product module with name, price\" --yes
     Could not locate wagoe/ai/shell/cli_entry.clj on classpath

   It shelled a plain `clojure -M -m wagoe.ai.shell.cli-entry`, and a generated
   deps.edn carries com.wagoe/wagoe-ai only inside the :mcp alias. Two more call
   sites had the same line — `bb setup ai`, which swallowed the failure and fell
   back to the interactive wizard, and `bb ai admin-entity`.

   Clearing that alone still generated nothing: the AI CLI then shelled the
   scaffolder itself, without rewrite-clj and without --base-ns, so the run
   either failed to load or wrote the module under `wagoe.*` (BOU-360). The AI
   CLI now only parses; `bb scaffold ai` previews, confirms and generates
   through the same `run-clojure!` every other scaffold command uses.

   These tests drive command construction, not the AI code — the library was
   fine throughout."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.tools.cli]
            [babashka.fs :as fs]
            [babashka.process :as process]
            [wagoe.tools.ai :as ai]
            [wagoe.tools.scaffold :as scaffold]
            [wagoe.tools.setup :as setup]))

(def ^:private spec-json
  (str "{\"module-name\":\"product\",\"entity\":\"Product\","
       "\"fields\":[{\"name\":\"name\",\"type\":\"string\",\"required\":true,\"unique\":false},"
       "{\"name\":\"price\",\"type\":\"decimal\",\"required\":true,\"unique\":false}],"
       "\"http\":true,\"web\":true}"))

(defn- test-cache-dir
  "No parse cache unless a test names a directory for one: the default is the
   project's target/, which every test here would share."
  []
  (when (string? scaffold/*parse-cache-dir*) scaffold/*parse-cache-dir*))

(defn- run-wizard
  "Runs `wizard-ai` with every shell-out stubbed. `responses` is a seq of return
   values, one per shell call, in order. Returns {:calls [...] :out s :exit n}."
  [description yes? responses]
  (let [calls     (atom [])
        remaining (atom (vec responses))
        exit      (atom nil)
        out       (with-out-str
                    (binding [scaffold/*exit!* #(reset! exit %)
                              scaffold/*parse-cache-dir* (test-cache-dir)]
                      (with-redefs [process/shell
                                    (fn [& args]
                                      (let [[opts cmd] (if (map? (first args))
                                                         [(first args) (vec (rest args))]
                                                         [nil (vec args)])]
                                        (swap! calls conj {:opts opts :cmd cmd})
                                        (let [[r & more] @remaining]
                                          (reset! remaining (vec more))
                                          r)))]
                        (scaffold/wizard-ai description yes?))))]
    {:calls @calls :out out :exit @exit}))

(deftest ^:unit the-ai-cli-is-reachable-from-a-generated-project
  (testing "outside the monorepo the dependency is injected"
    (let [cmd (ai/ai-command ["scaffold-parse" "product module"] false)]
      (is (some #{"-Sdeps"} cmd)
          "without -Sdeps the namespace is not on the classpath of a generated project")
      (is (str/includes? (str/join " " cmd) "com.wagoe/wagoe-ai"))
      (is (= ["-M" "-m" "wagoe.ai.shell.cli-entry" "scaffold-parse" "product module"]
             (vec (take-last 5 cmd)))
          "the arguments follow the main class, in order")))

  (testing "inside the monorepo libs/ai is already on the classpath"
    (let [cmd (ai/ai-command ["scaffold-parse" "product module"] true)]
      (is (not (some #{"-Sdeps"} cmd))
          "injecting here would force Maven resolution of an unpublished artifact"))))

(deftest ^:unit every-call-site-builds-its-command-through-ai-command
  ;; The bug was one hardcoded command line, copied into three files. A fourth
  ;; copy would be just as invisible: it only fails in a generated project, and
  ;; `bb setup ai` does not even fail loudly there.
  (testing "no tools namespace but ai.clj names the AI CLI in a command"
    (doseq [f (fs/glob "libs/tools/src/wagoe/tools" "*.clj")
            :let [path (str f)
                  src  (slurp path)]
            :when (not (str/ends-with? path "/ai.clj"))]
      (is (not (re-find #"\"clojure\"[^)]*wagoe\.ai\.shell\.cli-entry" src))
          (str path " shells the AI CLI directly — use wagoe.tools.ai/ai-command,"
               " or the command loses its -Sdeps in a generated project")))))

(deftest ^:unit the-parse-runs-through-the-ai-cli-and-generation-through-the-scaffolder
  (let [{:keys [calls exit]} (run-wizard "product module with name, price" true
                                         [{:exit 0 :out spec-json} {:exit 0}])
        [parse generate] calls]
    (testing "the description is parsed, and only parsed, by the AI CLI"
      (is (some #{"scaffold-parse"} (:cmd parse)))
      (is (str/includes? (str/join " " (:cmd parse)) "wagoe.ai.shell.cli-entry"))
      (is (= :string (:out (:opts parse)))
          "stdout is the spec, so it is captured rather than streamed"))

    (testing "generation goes through the scaffolder, with the project namespace"
      (is (str/includes? (str/join " " (:cmd generate)) "wagoe.scaffolder.shell.cli-entry"))
      (is (some #{"generate"} (:cmd generate)))
      (is (some #{"--base-ns"} (:cmd generate))
          "without it the module lands in the framework's namespace (BOU-360)"))

    (testing "the parsed fields reach the scaffolder"
      (let [cmd (:cmd generate)]
        (is (= ["--module-name" "product"] (take 2 (drop-while #(not= "--module-name" %) cmd))))
        (is (some #{"name:string:required"} cmd))
        (is (some #{"price:decimal:required"} cmd))))

    (is (nil? exit) "a successful run does not exit non-zero")))

(deftest ^:unit typing-the-whole-word-still-confirms
  ;; The AI wizard used to run its own prompt, which took "y" or "yes". The
  ;; shared one took "y" only, so moving the confirmation here would have read
  ;; a typed "yes" as a no and cancelled the run.
  (let [answer! (fn [in default-yes?]
                  (with-in-str (str in "\n")
                    (with-out-str (print (scaffold/confirm "Generate this module?" default-yes?)))))]
    (doseq [[in expected] {"yes" "true" "y" "true" "" "true"
                           "no" "false" "n" "false" "x" "false"}]
      (is (str/ends-with? (answer! in true) expected)
          (str "answer: " (pr-str in))))

    (testing "and a no-default prompt reads the same words"
      (doseq [[in expected] {"yes" "true" "y" "true" "" "false" "no" "false"}]
        (is (str/ends-with? (answer! in false) expected)
            (str "answer: " (pr-str in)))))))

(deftest ^:unit a-declined-confirmation-generates-nothing
  (with-redefs [scaffold/confirm (fn [_ _] false)]
    (let [{:keys [calls out]} (run-wizard "product module" false
                                          [{:exit 0 :out spec-json}])]
      (is (= 1 (count calls)) "the scaffolder must not run when the answer is no")
      (is (str/includes? out "Cancelled")))))

(deftest ^:unit a-failed-parse-does-not-scaffold
  (testing "a non-zero exit from the AI CLI stops the run"
    (let [{:keys [calls exit]} (run-wizard "product module" true [{:exit 1 :out ""}])]
      (is (= 1 (count calls)))
      (is (= 1 exit) "silently continuing would scaffold a module nobody described")))

  (testing "output that is not a module spec stops the run"
    ;; A provider that answers in prose, or with a spec missing the names the
    ;; scaffolder needs. Passing that through produced a scaffolder invocation
    ;; with `--module-name null`.
    (doseq [out ["I could not determine a module from that description."
                 "{\"module-name\":\"product\"}"
                 "{\"module-name\":\"product\",\"entity\":\"Product Module\"}"]]
      (let [{:keys [calls exit]} (run-wizard "product module" true [{:exit 0 :out out}])]
        (is (= 1 (count calls)) (str "must not scaffold from: " out))
        (is (= 1 exit)))))

  (testing "a fenced JSON body is still a spec"
    ;; Providers wrap JSON in ```json fences often enough that the setup wizard
    ;; strips them too.
    (let [{:keys [calls exit]} (run-wizard "product module" true
                                           [{:exit 0 :out (str "```json\n" spec-json "\n```")}
                                            {:exit 0}])]
      (is (= 2 (count calls)))
      (is (nil? exit)))))

(deftest ^:unit the-setup-wizard-reads-the-same-noisy-stdout
  ;; `bb setup ai` reached the provider for the first time with this patch, and
  ;; landed straight on the same problem one layer down: it handed the whole
  ;; capture to the JSON parser, so a successful answer was discarded and the
  ;; wizard fell back to asking the questions by hand — the failure mode this
  ;; command has always had, now for a different reason.
  (let [noisy (str "07:00:17,575 |-INFO in ch.qos.logback.classic.LoggerContext[default]\n"
                   "{\"project-name\":\"shop\",\"database\":\"postgresql\",\"admin-ui\":true}\n")
        spec  (#'setup/parse-ai-result noisy)]
    (is (= "shop" (:project-name spec)))
    (is (= :postgresql (:database spec))))

  (testing "output holding no JSON is still a failed parse"
    ;; Not a spec of defaults: `(get nil \"database\")` is nil, so a provider
    ;; answering in prose would have produced a plausible-looking config nobody
    ;; asked for, instead of falling back to the interactive wizard.
    (is (nil? (#'setup/parse-ai-result "I need more detail about the project.")))
    (is (nil? (#'setup/parse-ai-result "")))))

(deftest ^:unit the-jvm-s-own-noise-on-stdout-is-not-the-answer
  ;; Found by running it: the subprocess is a JVM, and logback announces its
  ;; configuration on stdout before the CLI prints anything. Parsing the whole
  ;; capture failed on the first line, and a run that had reached the provider
  ;; and got a correct spec back reported "no usable module spec".
  (let [noisy (str "07:00:17,575 |-INFO in ch.qos.logback.classic.LoggerContext[default]\n"
                   "07:00:17.584 INFO  [main] n.fortuna.ical4j.util.Configurator - not found.\n"
                   spec-json "\n")
        {:keys [calls exit]} (run-wizard "product module" true
                                         [{:exit 0 :out noisy} {:exit 0}])]
    (is (= 2 (count calls)) "the spec is on the last line, not the first")
    (is (nil? exit))
    (is (some #{"product"} (:cmd (second calls))))))

(def ^:private multi-entity-json
  (str "{\"module-name\":\"billing\","
       "\"entities\":[{\"name\":\"Invoice\","
       "\"fields\":[{\"name\":\"number\",\"type\":\"string\",\"required\":true}]},"
       "{\"name\":\"InvoiceLineItem\",\"belongs-to\":\"Invoice\","
       "\"fields\":[{\"name\":\"quantity\",\"type\":\"int\",\"required\":true}]}],"
       "\"http\":true,\"web\":true}"))

(deftest ^:unit several-entities-are-one-generate-then-one-entity-each
  ;; BOU-497: an invoice and its line items is the common shape, and the spec
  ;; carried one entity.
  (let [{:keys [calls exit]} (run-wizard "invoices with line items" true
                                         [{:exit 0 :out multi-entity-json} {:exit 0} {:exit 0}])
        [_parse generate entity] calls
        after (fn [cmd flag] (second (drop-while #(not= flag %) cmd)))]
    (is (= 3 (count calls)))
    (testing "the first entity is generated as the module"
      (is (some #{"generate"} (:cmd generate)))
      (is (= "Invoice" (after (:cmd generate) "--entity")))
      (is (some #{"number:string:required"} (:cmd generate))))
    (testing "the second is added to it, belonging to the first"
      (is (some #{"entity"} (:cmd entity)))
      (is (= "invoice" (after (:cmd entity) "--module-name")))
      (is (= "InvoiceLineItem" (after (:cmd entity) "--entity")))
      (is (= "Invoice" (after (:cmd entity) "--belongs-to")))
      (is (some #{"quantity:int:required"} (:cmd entity))))
    (is (nil? exit))))

(deftest ^:unit the-singular-spec-still-renders-one-generate
  (is (= [["generate" "--module-name" "product" "--entity" "Product"
           "--field" "name:string:required" "--field" "price:decimal:required"]]
         (scaffold/build-ai-commands (#'scaffold/parse-ai-module-spec spec-json)))))

(deftest ^:unit a-lowercase-belongs-to-is-the-entity-it-names
  ;; "belongs-to": "invoice" threw the whole spec away.
  (let [spec (#'scaffold/parse-ai-module-spec
              (str/replace multi-entity-json "\"belongs-to\":\"Invoice\"" "\"belongs-to\":\"invoice\""))]
    (is (some? spec))
    (is (= "Invoice" (:belongs-to (second (:entities spec)))))))

(deftest ^:unit no-http-reaches-every-entity
  (let [[generate entity] (scaffold/build-ai-commands
                           (#'scaffold/parse-ai-module-spec
                            (str/replace multi-entity-json "\"http\":true" "\"http\":false")))]
    (is (some #{"--no-http"} generate))
    (is (some #{"--no-http"} entity)))
  (is (not-any? #{"--no-http"} (second (scaffold/build-ai-commands
                                        (#'scaffold/parse-ai-module-spec multi-entity-json))))))

(deftest ^:unit public-api-reaches-every-entity
  (let [[generate entity] (scaffold/build-ai-commands
                           (#'scaffold/parse-ai-module-spec
                            (str/replace multi-entity-json "\"http\":true" "\"http\":true,\"public-api\":true")))]
    (is (some #{"--public-api"} generate))
    (is (some #{"--public-api"} entity)))
  (is (not-any? #{"--public-api"} (apply concat (scaffold/build-ai-commands
                                                 (#'scaffold/parse-ai-module-spec multi-entity-json))))))

(deftest ^:unit the-wizard-offers-a-timestamp
  ;; `date` is a calendar day since BOU-547; without `datetime` no timestamp.
  (is (some #{"date"} scaffold/field-types))
  (is (some #{"datetime"} scaffold/field-types)))

(defn- run-main
  "`bb scaffold ai <args>` with the parse stubbed to answer `spec`. Scaffolder
   calls go to `scaffolder`, which is the real shell in the promise test."
  [args spec scaffolder]
  (let [calls (atom [])
        exit  (atom nil)
        out   (with-out-str
                (binding [scaffold/*exit!* #(reset! exit %)
                          scaffold/*parse-cache-dir* (test-cache-dir)]
                  (with-redefs [process/shell
                                (fn [opts & cmd]
                                  (swap! calls conj (vec cmd))
                                  (if (some #{"scaffold-parse"} cmd)
                                    {:exit 0 :out spec}
                                    (apply scaffolder opts cmd)))]
                    (apply scaffold/-main "ai" args))))]
    {:calls @calls :out out :exit @exit}))

(deftest ^:unit dry-run-is-a-flag-not-part-of-the-description
  ;; BOU-490: `--dry-run` was joined into the description and every file written.
  (let [{:keys [calls exit]} (run-main ["invoices with line items" "--yes" "--dry-run"]
                                       multi-entity-json (constantly {:exit 0}))
        [parse & scaffolder] calls]
    (is (nil? exit))
    (testing "the model sees the description only"
      (is (= "invoices with line items" (last parse))))
    (testing "every scaffolder command that runs is dry"
      (is (seq scaffolder))
      (is (every? #(some #{"--dry-run"} %) scaffolder)))))

(deftest ^:unit every-planned-command-carries-the-shared-flags
  (let [spec (#'scaffold/parse-ai-module-spec multi-entity-json)
        [generate entity] (scaffold/build-ai-commands
                           spec {:dry-run true :output-dir "/tmp/x" :base-ns "shop" :force true})
        after (fn [cmd flag] (second (drop-while #(not= flag %) cmd)))]
    (doseq [cmd [generate entity]]
      (is (some #{"--dry-run"} cmd))
      (is (= "/tmp/x" (after cmd "--output-dir")))
      (is (= "shop" (after cmd "--base-ns"))))
    (is (some #{"--force"} generate))
    (is (not-any? #{"--force"} entity) "`entity` has no --force")))

(deftest ^:unit a-flag-overrides-the-spec
  (let [spec (#'scaffold/parse-ai-module-spec multi-entity-json)
        cmds (scaffold/build-ai-commands spec {:http false :public-api true})]
    (is (every? #(some #{"--no-http"} %) cmds))
    (is (every? #(some #{"--public-api"} %) cmds))
    (is (some #{"--no-web"} (first (scaffold/build-ai-commands spec {:web false}))))))

(deftest ^:unit an-unknown-flag-is-refused-not-sent-to-the-model
  (let [{:keys [calls exit out]} (run-main ["product module" "--dryrun"]
                                           spec-json (constantly {:exit 0}))]
    (is (empty? calls))
    (is (= 1 exit))
    (is (str/includes? out "--dryrun"))))

(deftest ^:integration a-dry-run-writes-nothing
  ;; The promise, against the real scaffolder.
  ;; The shell cannot run in the temp dir: the scaffolder resolves from this
  ;; repo's deps.edn. So a command that is not dry and pointed at `dir` is never
  ;; run, or a regression would write into the repo.
  (let [dir  (str (fs/create-temp-dir))
        real process/shell
        safe (fn [opts & cmd]
               (if (and (some #{"--dry-run"} cmd)
                        (= dir (second (drop-while #(not= "--output-dir" %) cmd))))
                 (apply real opts cmd)
                 {:exit 1}))]
    (try
      (let [{:keys [calls exit out]} (binding [*err* (java.io.StringWriter.)]
                                       (run-main ["product module" "-y" "--dry-run" "--output-dir" dir]
                                                 spec-json safe))
            scaffolder (second calls)]
        (is (= 2 (count calls)))
        (is (some #{"--dry-run"} scaffolder))
        (is (= dir (second (drop-while #(not= "--output-dir" %) scaffolder))))
        (is (nil? exit) out)
        (is (empty? (fs/list-dir dir)) "a dry run must not write files"))
      (finally (fs/delete-tree dir)))))

(defn- plain [s] (str/replace s #"\u001b\[[0-9;]*m" ""))

(deftest ^:unit force-refuses-a-module-with-several-entities
  ;; `generate --force` drops the second entity from the wiring, then `entity`
  ;; refuses because its files exist: exit 1 with orphaned files.
  (let [{:keys [calls exit out]} (run-main ["invoices with line items" "-y" "--force"]
                                           multi-entity-json (constantly {:exit 0}))]
    (is (= 1 (count calls)) "only the parse runs")
    (is (= 1 exit))
    (is (str/includes? out "--force cannot regenerate a module with several entities")))
  (testing "one entity still forces"
    (let [{:keys [calls exit]} (run-main ["product module" "-y" "--force"]
                                         spec-json (constantly {:exit 0}))]
      (is (nil? exit))
      (is (some #{"--force"} (second calls))))))

(deftest ^:unit the-summary-shows-what-will-be-generated
  (let [{:keys [out]} (run-main ["product module" "-y" "--no-http" "--public-api"]
                                spec-json (constantly {:exit 0}))
        out (plain out)]
    (is (str/includes? out "HTTP ✗") "the flag, not the spec's http: true")
    (is (str/includes? out "Public API:  ✓"))))

(deftest ^:unit no-public-api-overrides-the-spec
  (let [spec (assoc (#'scaffold/parse-ai-module-spec spec-json) :public-api true)
        opts #(:options (clojure.tools.cli/parse-opts % scaffold/ai-option-specs))]
    (is (false? (:public-api (opts ["--no-public-api"]))))
    (is (nil? (:public-api (opts []))) "absent defers to the spec")
    (is (not-any? #{"--public-api"} (first (scaffold/build-ai-commands spec (opts ["--no-public-api"])))))
    (is (some #{"--public-api"} (first (scaffold/build-ai-commands spec (opts [])))))))

(deftest ^:unit a-leading-dash-gets-one-error-and-a-hint
  (let [{:keys [calls exit out]} (run-main ["-5%" "discount" "module"]
                                           spec-json (constantly {:exit 0}))]
    (is (empty? calls))
    (is (= 1 exit))
    (is (= 1 (count (re-seq #"Unknown option: \"-5\"" out))) out)
    (is (str/includes? out "put -- before")))
  (testing "and -- lets such a description through"
    (let [{:keys [calls exit]} (run-main ["-y" "--" "-5% discount module"]
                                         spec-json (constantly {:exit 0}))]
      (is (nil? exit))
      (is (= "-5% discount module" (last (first calls)))))))

(deftest ^:unit an-empty-description-fails
  (let [{:keys [calls exit]} (run-main ["--yes"] spec-json (constantly {:exit 0}))]
    (is (empty? calls))
    (is (= 1 exit))))

(deftest ^:unit the-module-is-named-after-its-first-entity
  ;; The model named the same description `invoice` on one run and `invoicing`
  ;; on the next (BOU-562).
  (doseq [named ["billing" "invoicing" "invoice"]]
    (is (= "invoice" (:module (#'scaffold/parse-ai-module-spec
                               (str/replace multi-entity-json "\"billing\"" (str "\"" named "\"")))))
        named))
  (is (= "invoice-line-item"
         (:module (#'scaffold/parse-ai-module-spec
                   "{\"module-name\":\"x\",\"entity\":\"InvoiceLineItem\",\"fields\":[]}")))))

(deftest ^:unit the-real-run-reuses-the-dry-run-s-parse
  (let [dir     (str (fs/create-temp-dir))
        answers (atom [multi-entity-json
                       (str/replace multi-entity-json "\"Invoice\"" "\"Bill\"")])
        run     (fn [& args]
                  (binding [scaffold/*parse-cache-dir* dir]
                    (run-main (into ["invoices with line items" "-y"] args)
                              nil (constantly {:exit 0}))))]
    (try
      (with-redefs [scaffold/parse-description (fn [_]
                                                 (let [[a & more] @answers]
                                                   (reset! answers (vec more))
                                                   {:exit 0 :out a}))]
        (let [module-of (fn [{:keys [calls]}]
                          (some (fn [cmd] (second (drop-while #(not= "--module-name" %) cmd))) calls))
              dry       (run "--dry-run")
              real      (run)]
          (is (= "invoice" (module-of dry)))
          (is (= "invoice" (module-of real)) "not the second answer's `bill`")
          (is (= 1 (count @answers)) "the real run did not ask again")
          (is (str/includes? (plain (:out real)) "parse from") (:out real))
          (is (str/includes? (plain (:out real)) "--fresh") "it says how to parse again")
          (testing "--fresh parses again and replaces the cached parse"
            (is (= "bill" (module-of (run "--fresh"))))
            (is (empty? @answers))
            (is (= "bill" (module-of (run))) "the next run reuses the new parse"))))
      (finally (fs/delete-tree dir)))))

(deftest ^:unit the-summary-separates-name-and-type
  (let [out (plain (with-out-str
                     (scaffold/display-generate-summary "invoice" "Invoice"
                                                        [{:name "total-in-cents" :type "int" :required true}]
                                                        true true)))]
    (is (re-find #"total-in-cents\s+int" out) out)))
