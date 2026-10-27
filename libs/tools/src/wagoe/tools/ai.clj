#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/ai.clj
;;
;; Framework-aware AI tooling for the Wagoe framework.
;;
;; Usage (via bb.edn task):
;;   bb ai                           -- show help
;;   bb ai explain                   -- error explainer (reads from stdin)
;;   bb ai explain --file path       -- error explainer from file
;;   bb ai gen-tests <file>          -- generate test namespace
;;   bb ai sql <description>         -- SQL copilot (HoneySQL)
;;   bb ai docs --module <path> [--type agents|openapi|readme]

(ns wagoe.tools.ai
  (:require [wagoe.tools.ansi :refer [bold red yellow]]
            [wagoe.tools.text :as text]
            [wagoe.tools.help :as help]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.process :refer [shell]]))

;; =============================================================================
;; Run Clojure AI CLI
;; =============================================================================

;; The wagoe-ai a generated project runs. `bb bump` rewrites it and
;; `check:versions` gates it as an "injected pin" — it used to ask a human to
;; update it alongside scaffolder-version and the two in new.clj, and that is
;; how it shipped 1.0.0-beta-5 inside the 1.0.0-beta-6 release.
(def ^:private ai-version "1.1.0-rc-1")

;; Match libs/ai/deps.edn and the monorepo's own pin.
(def ^:private tools-cli-version "1.4.256")

(defn- ai-deps
  "The -Sdeps argument that makes wagoe.ai.shell.cli-entry resolvable.

   Generated projects carry com.wagoe/wagoe-ai only in their :mcp alias, never
   in :deps, so a plain `clojure -M` could not find it and every `bb ai`
   subcommand died with a FileNotFoundException (BOU-272). Injecting the
   dependency here rather than expecting it in deps.edn matches what
   wagoe.tools.scaffold already does.

   WAGOE_AI_ROOT overrides the pin with a local checkout — the only way to
   exercise unreleased AI code from a generated project, since this dependency
   is injected here and a :local/root rewrite of deps.edn has no effect on it.

   The root is a parameter as well as an env lookup so a test can reach both
   branches; reading the environment inline would leave the override arm
   testable only by whatever the test runner happened to have set."
  ([] (ai-deps (System/getenv "WAGOE_AI_ROOT")))
  ([root]
   ;; tools.cli is named explicitly as well as being declared by libs/ai. The
   ;; library declaration is the real fix, but it only reaches users on the next
   ;; release — the already-published version this pins has a POM without it, so
   ;; injecting wagoe-ai alone still failed with
   ;;   Could not locate clojure/tools/cli
   ;; Naming it here makes `bb ai` work against the currently published
   ;; artifact, and is harmless once the POM carries it.
   (let [ai-coord (if root
                    (str "{:local/root \"" root "\"}")
                    (str "{:mvn/version \"" ai-version "\"}"))]
     (str "{:deps {com.wagoe/wagoe-ai " ai-coord " "
          "org.clojure/tools.cli {:mvn/version \"" tools-cli-version "\"}}}"))))

(defn ai-command
  "The command vector that runs the AI CLI with `args`.

   In generated projects (no libs/ai directory) the dependency is injected via
   -Sdeps. In the monorepo libs/ai/src is already on the classpath, so -Sdeps is
   skipped to avoid forcing Maven resolution of an artifact that may not yet be
   published.

   Public because `bb ai` is not the only caller: `bb scaffold ai`, `bb setup ai`
   and `bb admin-entity` shell the same CLI, and each of them hardcoded a plain
   `clojure -M -m wagoe.ai.shell.cli-entry` that could not resolve the namespace
   in a generated project (BOU-401). One builder, so the next call site cannot
   miss the injection.

   `in-monorepo?` is a parameter as well as a directory probe so a test can
   reach the injecting branch from inside this repository, where the probe is
   always true."
  ([args] (ai-command args (.exists (io/file "libs/ai"))))
  ([args in-monorepo?]
   (let [base-cmd (if in-monorepo?
                    ["clojure" "-M" "-m" "wagoe.ai.shell.cli-entry"]
                    ["clojure" "-Sdeps" (ai-deps)
                     "-M" "-m" "wagoe.ai.shell.cli-entry"])]
     (vec (concat base-cmd args)))))

(defn json-line
  "The JSON object in `out`, which carries more than the answer.

   The AI CLI runs in a JVM that logs to the console — logback announces its own
   configuration on stdout before the CLI prints anything, and a generated
   project ships no logback config at all, so its application logs land there
   too. Parsing the whole capture fails on the first line and throws away a
   correct answer (BOU-401). Providers also wrap JSON in ``` fences.

   Returns the last line that looks like an object, or nil."
  [out]
  (->> (str/split-lines (str out))
       (map #(-> % str/trim
                 (str/replace #"^```json\s*" "")
                 (str/replace #"\s*```$" "")))
       (filter #(and (str/starts-with? % "{") (str/ends-with? % "}")))
       last))

(defn failure-message
  "What to print when the AI CLI fails. The process's stderr is the terminal,
   so the exception's message is empty (BOU-567): a non-zero exit has already
   printed its cause, and one that never ran has only the exception's."
  [e]
  (if-let [exit (:exit (ex-data e))]
    (str "AI CLI exited with status " exit "; the cause is printed above.")
    (str "AI CLI could not run: " (or (not-empty (ex-message e)) (.getName (class e))))))

(defn- run-clojure!
  "Shell out to the Clojure AI CLI with given args. Streams output to terminal.
   `opts` goes to babashka.process, e.g. {:in text} to feed stdin."
  [args & [opts]]
  (try
    (apply shell (or opts {}) (ai-command args))
    (catch Exception e
      (println (red (failure-message e)))
      (System/exit 1))))

;; =============================================================================
;; explain
;; =============================================================================

(defn- strip-control
  "`s` without ANSI escape sequences or control characters, newlines kept."
  [s]
  (-> (str s)
      (str/replace #"\u001B\][^\u0007\u001B]*(?:\u0007|\u001B\\)?" "")
      (str/replace #"\u001B\[[0-?]*[ -/]*[@-~]" "")
      (str/replace #"\u001B[@-_]?" "")
      (str/replace #"[\p{Cc}&&[^\n]]" "")))

(defn- wge-fix-lines
  "The `Fix:` lines inside a WGE block, which devtools opens with a
   `━━━ WGE-301: Title ━━━` header and closes with a rule of `━`."
  [lines]
  (:fixes (reduce (fn [{:keys [in-block?] :as acc} line]
                    (cond
                      (re-find #"(?:WGE|BND)-\d{3}:" line)     (assoc acc :in-block? true)
                      (re-matches #"\u2501+" line)     (assoc acc :in-block? false)
                      (and in-block? (str/starts-with? line "Fix:"))
                      (update acc :fixes conj line)
                      :else                            acc))
                  {:in-block? false :fixes []}
                  lines)))

(def ^:private cli-header
  "Clojure CLI's report header: `Execution error (ExceptionInfo) at ns/fn (f.clj:1).`"
  #"^Execution error(?: \(\w+\))? at ([^/\s]+)/\S+ \(.*\)\.$")

(defn- frame-ns
  "The namespace of a stack frame line, `at wagoe.a.b$f.invoke(b.clj:1)`."
  [line]
  (second (re-find #"^at ([^$\s(]+)\$" (str/trim line))))

(defn- some-tail
  "The first truthy `(f [line & more])` over the tails of `lines`."
  [f lines]
  (some f (take-while seq (iterate rest lines))))

(defn- cli-message
  "The message under a Clojure CLI report header thrown in a wagoe namespace.
   It ends at `Full report at:`; a REPL prints none, so there it ends at the
   first blank line rather than taking whatever was pasted after it."
  [lines]
  (some-tail (fn [[line & more]]
               (when (some->> (re-find cli-header line) second (re-find #"^wagoe\."))
                 (let [[msg tail] (split-with #(not (str/starts-with? (str/trim %) "Full report at:"))
                                              more)]
                   {:message (if (seq tail) msg (take-while (complement str/blank?) msg))})))
             lines))

(defn- whole-map?
  "Whether `s` reads as one EDN map and nothing after it."
  [s]
  (try
    (let [r (java.io.PushbackReader. (java.io.StringReader. s))]
      (and (map? (edn/read {:default (fn [_ v] v)} r))
           (str/blank? (slurp r))))
    (catch Exception _ false)))

(defn- split-data
  "The lines of a printed `ExceptionInfo: msg {data}` as message and data,
   which follows the message's last line. The message may hold a map itself,
   so the data is the first ` {:` whose rest reads as one whole map."
  [lines]
  (let [last-line (str (last lines))
        starts    (take-while some? (iterate #(str/index-of last-line " {:" (inc %))
                                             (str/index-of last-line " {:")))
        i         (or (some #(when (whole-map? (subs last-line (inc %))) %) starts)
                      (first starts))]
    (if i
      {:message (concat (butlast lines) [(subs last-line 0 i)])
       :data    (subs last-line i)}
      {:message lines})))

(defn- unescape
  "A printed string's body without its escapes, or as-is when it does not read."
  [s]
  (try (edn/read-string (str "\"" s "\"")) (catch Exception _ s)))

(defn- trace-message
  "The message and data of a printed `ExceptionInfo: msg {data}` whose first
   frame outside clojure.* and java.* is in a wagoe namespace."
  [lines]
  (some-tail (fn [[line & more]]
               (when-let [[_ first-line] (re-find #"ExceptionInfo: (.*)$" line)]
                 (let [[msg frames] (split-with #(not (frame-ns %)) more)
                       ;; The JVM prefixes of wagoe.devtools.core.stacktrace,
                       ;; which bb cannot load.
                       thrower      (->> (keep frame-ns frames)
                                         (remove #(re-find #"^(?:clojure|javax?|jdk|sun)\." %))
                                         first)]
                   (when (some->> thrower (re-find #"^wagoe\."))
                     (split-data (cons first-line msg))))))
             lines))

(defn- own-words
  "What a Wagoe-thrown error says, verbatim: its message, then any `:fix` or
   `:hint` its printed ex-data carries. Nil for anyone else's error — that text
   is not ours to vouch for."
  [raw-lines]
  (let [{:keys [message data]} (or (cli-message raw-lines) (trace-message raw-lines))
        body (str/trimr (str/join "\n" message))]
    (when-not (str/blank? body)
      (concat ["The error says:"]
              (map #(if (str/blank? %) "" (str "  " %)) (str/split-lines body))
              (for [[_ k v] (re-seq #":(fix|hint) \"((?:[^\"\\]|\\.)*)\"" (str data))]
                (str (text/capitalize k) ": " (unescape v)))))))

(defn known-remedy
  "What the error already says about fixing itself, or nil.

   The catalogue title and fix for each WGE code in `input`, then the `Fix:`
   lines inside its WGE blocks, then the message of an error a wagoe namespace
   threw. Other `Fix:` lines are pasted text, not ours, and are not printed
   under our header. The model's summary is printed after this, never instead
   of it: it has been confidently wrong (BOU-512)."
  [input catalog]
  (let [raw-lines (str/split-lines (strip-control input))
        lines     (map str/trim raw-lines)
        codes     (->> lines
                       (mapcat #(re-seq #"(?:WGE|BND)-\d{3}" %))
                       ;; Output from before 1.0.1 says BND-; same codes.
                       (map #(str/replace % #"^BND-" "WGE-"))
                       distinct)
        from-code (mapcat (fn [code]
                            (when-let [{:keys [title fix]} (get catalog code)]
                              (cond-> [(str code ": " title)]
                                fix (conj (str "Fix: " fix)))))
                          codes)
        own-fixes (wge-fix-lines lines)
        fixes     (distinct (concat from-code own-fixes))
        words     (own-words raw-lines)]
    (some->> [fixes words] (remove empty?) (map #(str/join "\n" %)) seq (str/join "\n\n"))))

(defn- file-arg
  "The value of -f/--file in `args`, or nil."
  [args]
  (some (fn [[a b]]
          (cond
            (#{"-f" "--file"} a)            b
            (str/starts-with? a "--file=") (subs a 7)))
        (partition-all 2 1 args)))

(defn- explain!
  "Print the error's own remedy, then hand the same input to the model."
  [args]
  (let [file  (file-arg args)
        input (if file
                (when (.exists (io/file file)) (slurp file))
                (slurp *in*))
        remedy (known-remedy input @help/error-catalog)]
    (when remedy
      (println)
      (println (bold "=== From the error itself ==="))
      (println remedy)
      (println)
      (println (yellow "The explanation below is AI-generated and experimental.")))
    ;; The model is told this is the answer, so it explains it rather than
    ;; inventing another. Ahead of the user's args, so a `--` or a dangling
    ;; `--file` there cannot swallow it.
    (run-clojure! (-> ["explain"]
                      (cond-> remedy (into ["--known-fix" remedy]))
                      (into args))
                  (when-not file {:in input}))))

;; =============================================================================
;; Help text
;; =============================================================================

(def ^:private help-text
  (str (bold "Wagoe AI \u2014 Framework-aware AI Tooling") "\n"
       "\n"
       "Usage:\n"
       "  bb ai                               Show this help\n"
       "  bb ai explain                       Explain error from stdin (experimental)\n"
       "  bb ai explain --file <path>         Explain error from file (experimental)\n"
       "  bb ai gen-tests <file>              Generate test namespace, stdout (experimental)\n"
       "  bb ai gen-tests <file> --write      Write to the conventional test path (experimental)\n"
       "  bb ai gen-tests <file> -o <output>  Write tests to a named file (experimental)\n"
       "  bb ai gen-tests <file> --run        Also run the generated tests first (runs model code)\n"
       "  bb ai sql <description>             Generate HoneySQL from description (experimental)\n"
       "  bb ai docs --module <path>          Generate all docs (agents, openapi, readme)\n"
       "  bb ai docs --module <path> --type agents|openapi|readme\n"
       "  bb ai admin-entity <description>    Generate admin entity EDN config\n"
       "\n"
       "Experimental: the answer can be confidently wrong. Review it before you use it.\n"
       "\n"
       ;; wagoe.ai.shell.cli-entry/provider-env, which a test holds this to.
       "Provider, when config.edn names none (the first variable set wins):\n"
       "  ANTHROPIC_API_KEY   \u2192 Anthropic (Claude)\n"
       "  OPENAI_BASE_URL     \u2192 an OpenAI-compatible endpoint (oMLX, LM Studio)\n"
       "  OPENAI_API_KEY      \u2192 OpenAI (GPT)\n"
       "  REPLICATE_API_TOKEN \u2192 Replicate-hosted models\n"
       "  OLLAMA_URL          \u2192 Ollama (default http://localhost:11434)\n"
       "  AI_MODEL            \u2192 Override the default model\n"
       "\n"
       "Examples:\n"
       "  cat stacktrace.txt | bb ai explain\n"
       "  bb ai explain --file errors.txt\n"
       "  bb ai gen-tests libs/user/src/wagoe/user/core/validation.clj\n"
       "  bb ai sql \"find active users with orders in the last 7 days\"\n"
       "  bb ai docs --module libs/user --type agents\n"
       "  bb ai admin-entity \"products with name, price, status\"\n"
       "\n"
       "For NL module scaffolding:\n"
       "  bb scaffold ai \"product module with name, price, stock\"\n"
       "\n"
       "The tool delegates to:\n"
       "  clojure -M -m wagoe.ai.shell.cli-entry <subcommand> [opts]"))

;; =============================================================================
;; Main entry point
;; =============================================================================

(defn -main [& raw-args]
  (let [args (vec raw-args)
        [sub & rest-args] args]
    (cond
      (or (nil? sub)
          (contains? #{"-h" "--help" "help"} sub))
      (println help-text)

      (= sub "explain")
      (explain! rest-args)

      (= sub "gen-tests")
      (run-clojure! (into ["gen-tests"] rest-args))

      (= sub "sql")
      (run-clojure! (into ["sql"] rest-args))

      (= sub "docs")
      (run-clojure! (into ["docs"] rest-args))

      (= sub "admin-entity")
      (run-clojure! (into ["admin-entity"] rest-args))

      (= sub "setup-parse")
      (run-clojure! (into ["setup-parse"] rest-args))

      :else
      (do
        (println (red (str "Unknown subcommand: " sub)))
        (println)
        (println help-text)
        (System/exit 1)))))

;; Run when executed directly (not via bb.edn task)
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
