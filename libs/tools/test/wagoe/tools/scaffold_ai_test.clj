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
            [babashka.fs :as fs]
            [babashka.process :as process]
            [wagoe.tools.ai :as ai]
            [wagoe.tools.scaffold :as scaffold]))

(def ^:private spec-json
  (str "{\"module-name\":\"product\",\"entity\":\"Product\","
       "\"fields\":[{\"name\":\"name\",\"type\":\"string\",\"required\":true,\"unique\":false},"
       "{\"name\":\"price\",\"type\":\"decimal\",\"required\":true,\"unique\":false}],"
       "\"http\":true,\"web\":true}"))

(defn- run-wizard
  "Runs `wizard-ai` with every shell-out stubbed. `responses` is a seq of return
   values, one per shell call, in order. Returns {:calls [...] :out s :exit n}."
  [description yes? responses]
  (let [calls     (atom [])
        remaining (atom (vec responses))
        exit      (atom nil)
        out       (with-out-str
                    (binding [scaffold/*exit!* #(reset! exit %)]
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
                 "{\"entity\":\"Product\"}"
                 "{\"module-name\":\"Product Module\",\"entity\":\"Product\"}"]]
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
