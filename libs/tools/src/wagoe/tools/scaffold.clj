#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/scaffold.clj
;;
;; Interactive scaffolding wizard for Wagoe modules.
;;
;; Usage (via bb.edn task):
;;   bb scaffold                     -- show help
;;   bb scaffold generate            -- interactive wizard
;;   bb scaffold generate [args...]  -- non-interactive passthrough
;;   bb scaffold entity [args...]    -- add an entity to an existing module
;;   bb scaffold field               -- interactive wizard
;;   bb scaffold endpoint            -- interactive wizard
;;   bb scaffold adapter             -- interactive wizard
;;   bb scaffold subscriber [args...] -- add an event subscriber to a module
;;   bb scaffold ai "<description>" [--yes] -- AI-assisted module generation

(ns wagoe.tools.scaffold
  (:require [wagoe.tools.ansi :as ansi :refer [bold green cyan red yellow dim]]
            [wagoe.tools.ai :as ai]
            [wagoe.tools.project :as project]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [babashka.process :refer [shell]]))

;; =============================================================================
;; Process exit
;; =============================================================================

(def ^:dynamic *exit!*
  "Terminates the process with `code`. Indirection so tests can observe the exit
   code of a command instead of killing the test JVM."
  (fn [code] (System/exit code)))

;; =============================================================================
;; Validation helpers
;; =============================================================================

(defn valid-kebab? [s]
  (boolean (and (seq s) (re-matches #"^[a-z][a-z0-9-]*$" s))))

(defn valid-pascal? [s]
  (boolean (and (seq s) (re-matches #"^[A-Z][a-zA-Z0-9]*$" s))))

(defn kebab->pascal [s]
  (->> (str/split s #"-")
       (map str/capitalize)
       (str/join)))

(defn pascal->kebab
  "InvoiceLineItem -> invoice-line-item, as the scaffolder derives it."
  [s]
  (-> s
      (str/replace #"([A-Z]+)([A-Z][a-z])" "$1-$2")
      (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
      str/lower-case))

;; =============================================================================
;; Prompts
;; =============================================================================

(defn prompt
  "Print label with optional default hint, return trimmed input (or default on blank)."
  ([label] (prompt label nil))
  ([label default]
   (if default
     (print (str label " [" default "]: "))
     (print (str label ": ")))
   (flush)
   (let [input (str/trim (or (read-line) ""))]
     (if (and (empty? input) default)
       default
       input))))

(defn tty?
  "Whether a person can answer a prompt. A console is not enough: newer JDKs
   return one with stdin redirected."
  []
  (if-let [c (System/console)]
    (try (boolean (.isTerminal c))
         (catch Exception _ true))
    false))

(defn confirm
  "Y/n or y/N prompt. Returns boolean.

   `yes` counts as yes: only `y` did, so anyone typing the whole word had their
   answer read as a no. `bb scaffold ai` inherited this prompt from a wizard of
   its own that accepted both (BOU-401)."
  ([label] (confirm label true))
  ([label default-yes?]
   (let [hint (if default-yes? "Y/n" "y/N")]
     (print (str label " [" hint "]: "))
     (flush)
     ;; A closed stdin is a no, whatever the default (BOU-585).
     (when-let [line (read-line)]
       (let [input (str/trim (str/lower-case line))]
         (if (empty? input)
           default-yes?
           (contains? #{"y" "yes"} input)))))))

;; =============================================================================
;; Menu selection
;; =============================================================================

(def field-types
  ["string" "text" "int" "decimal" "boolean" "email" "uuid" "enum" "date" "datetime" "json"])

(def http-methods ["GET" "POST" "PUT" "DELETE" "PATCH"])

(defn prompt-enum-values
  "Ask for an enum's values until at least one is given.

   An enum without values generates `[:enum]`, which matches nothing — so the
   wizard cannot let it through unanswered (BOU-447)."
  []
  (loop []
    (let [raw    (prompt "  Values (comma-separated, e.g. draft,sent,paid)")
          values (->> (str/split (or raw "") #",")
                      (map str/trim)
                      (remove str/blank?))]
      (if (seq values)
        (vec values)
        (do (println (red "  An enum needs at least one value")) (recur))))))

(defn select-from-menu
  "Print a numbered menu of items and return the chosen item. Loops on invalid input."
  [items]
  (let [rows (partition-all 5 (map-indexed vector items))]
    (doseq [row rows]
      (println (str "    " (str/join "  "
                                     (map (fn [[i t]]
                                            (format "%2d) %-9s" (inc i) t))
                                          row))))))
  (print "  Choice [1]: ")
  (flush)
  (let [input  (str/trim (or (read-line) ""))
        choice (when (seq input) (try (Integer/parseInt input) (catch Exception _ nil)))]
    (cond
      (empty? input)                              (first items)
      (and choice (>= choice 1)
           (<= choice (count items)))      (nth items (dec choice))
      :else (do (println (red (str "  Invalid choice, enter 1\u2013" (count items))))
                (select-from-menu items)))))

;; =============================================================================
;; Command building
;; =============================================================================

(defn field->spec
  "Convert a field map to CLI spec: name:type[:values=a,b,c][:required][:unique]"
  [{:keys [name type required unique enum-values]}]
  (str/join ":" (filter some? [name type
                               (when (seq enum-values)
                                 (str "values=" (str/join "," enum-values)))
                               (when required "required")
                               (when unique "unique")])))

(defn workflow->spec
  "{:field \"status\" :states [\"entered\" \"paid\"]} as --workflow takes it."
  [{:keys [field states]}]
  (str field ":" (str/join ">" states)))

(defn build-generate-args [{:keys [module entity fields http web public-api workflow]}]
  (let [base       ["generate" "--module-name" module "--entity" entity]
        field-args (mapcat #(vector "--field" (field->spec %)) fields)
        no-http    (when-not http ["--no-http"])
        no-web     (when-not web  ["--no-web"])]
    (vec (concat base field-args
                 (when workflow ["--workflow" (workflow->spec workflow)])
                 no-http no-web (when public-api ["--public-api"])))))

(defn build-entity-args
  "`bb scaffold entity` arguments for an entity added to `module`."
  [module {:keys [name belongs-to min fields http public-api workflow] :or {http true}}]
  (vec (concat ["entity" "--module-name" module "--entity" name]
               (when belongs-to ["--belongs-to" belongs-to])
               (when (and belongs-to min) ["--min" (str min)])
               (mapcat #(vector "--field" (field->spec %)) fields)
               (when workflow ["--workflow" (workflow->spec workflow)])
               (when-not http ["--no-http"])
               (when public-api ["--public-api"]))))

(defn apply-ai-flags
  "`spec` with the interface flags given on the command line. An absent flag
   defers to the spec."
  [spec flags]
  (merge spec (into {} (remove (comp nil? val)) (select-keys flags [:http :web :public-api]))))

(defn build-ai-commands
  "The scaffolder commands an AI module spec stands for: `generate` for its
   first entity, then `entity` for each further one, in order (BOU-497).

   `flags` are the options parsed off `bb scaffold ai`. `--[no-]http`,
   `--[no-]web` and `--[no-]public-api` override the spec; the rest are
   appended to every command that accepts them (BOU-490)."
  ([spec] (build-ai-commands spec {}))
  ([spec {:keys [dry-run force output-dir base-ns] :as flags}]
   (let [{:keys [module entities http web public-api]} (apply-ai-flags spec flags)
         [{:keys [name fields workflow]} & more] entities
         shared (cond-> []
                  output-dir (into ["--output-dir" output-dir])
                  base-ns    (into ["--base-ns" base-ns])
                  dry-run    (conj "--dry-run"))]
     (into [(cond-> (into (build-generate-args {:module module :entity name :fields fields
                                                :workflow workflow
                                                :http http :web web :public-api public-api})
                          shared)
              force (conj "--force"))]
           (map #(into (build-entity-args module (assoc % :http http :public-api public-api))
                       shared)
                more)))))

(def ai-option-specs
  "The flags `bb scaffold ai` takes. Anything else is refused: an unknown flag
   used to be joined into the description and sent to the model (BOU-490)."
  [["-y" "--yes" "Generate without asking"]
   [nil "--dry-run" "Show what would be generated without creating files"]
   [nil "--fresh" "Parse the description again instead of reusing the last parse"]
   [nil "--force" "Overwrite existing files"]
   [nil "--output-dir DIR" "Output directory"]
   [nil "--base-ns NS" "Base namespace for the module"]
   [nil "--[no-]http" "Generate the REST API (overrides the description)"]
   [nil "--[no-]web" "Generate the web UI (overrides the description)"]
   [nil "--[no-]public-api" "API routes open to anyone (overrides the description)"]
   ["-h" "--help" "Show this help"]])

(defn ai-help
  "What `bb scaffold ai --help` prints."
  []
  (str "Usage: bb scaffold ai <description> [options]\n"
       "\n"
       "Parses the description with the AI provider and runs `bb scaffold generate`,\n"
       "then `bb scaffold entity` for each further entity. A status that moves through\n"
       "fixed steps becomes a workflow (--workflow), a child entity --belongs-to its parent.\n"
       "\n"
       "Options:\n"
       (:summary (cli/parse-opts [] ai-option-specs)) "\n"
       "\n"
       "Example:\n"
       "  bb scaffold ai \"invoices with a number, and a status entered, delivered, paid\" --dry-run"))

;; =============================================================================
;; Run Clojure scaffolder
;; =============================================================================

;; The wagoe-scaffolder a generated project runs. `bb bump` rewrites it and
;; `check:versions` gates it as an "injected pin"; it is no longer a pin anyone
;; has to remember, which is how it shipped a release behind.
(def ^:private scaffolder-version "1.0.0")

;; Match libs/scaffolder/deps.edn and the monorepo's own pin.
(def ^:private rewrite-clj-version "1.2.57")

(defn- scaffolder-deps
  "The -Sdeps argument used to make the scaffolder namespace resolvable.

   Normally pins the published artifact. `WAGOE_SCAFFOLDER_ROOT` overrides that
   with a local checkout, which is the only way to exercise unreleased
   scaffolder code from a generated project: this dependency is injected here
   rather than read from the project's deps.edn, so a `:local/root` rewrite of
   that file has no effect. The first-run smoke test (scripts/first-run-smoke.sh)
   relies on this, and it is equally useful when developing the scaffolder
   against a real generated project."
  ([] (scaffolder-deps (System/getenv "WAGOE_SCAFFOLDER_ROOT")))
  ([root]
   ;; rewrite-clj is named here as well as declared by libs/scaffolder. The
   ;; library declaration is the real fix, but it only reaches users on the next
   ;; release — the already-published version this pins has a POM without it, so
   ;; injecting the scaffolder alone would fail with
   ;;   Could not locate rewrite_clj/zip
   ;; the moment that release lands. Exactly the shape of BOU-272, where
   ;; tools.cli was missing from wagoe-ai's POM and every `bb ai` subcommand
   ;; died in a generated project. Harmless once the POM carries it.
   (let [coord (if root
                 (str "{:local/root \"" root "\"}")
                 (str "{:mvn/version \"" scaffolder-version "\"}"))]
     (str "{:deps {com.wagoe/wagoe-scaffolder " coord " "
          "rewrite-clj/rewrite-clj {:mvn/version \"" rewrite-clj-version "\"}}}"))))

(def base-ns-option-specs
  "The three options with-base-ns reads, specced as the scaffolder CLI declares
   them so both sides parse one command line identically (BOU-378). A test pins
   these names against libs/scaffolder's cli.clj, since libs/tools cannot
   require it. No :default on --output-dir: nil here falls back to user.dir,
   which is what the scaffolder's \".\" resolves to anyway."
  [[nil "--module-name NAME"]
   [nil "--output-dir DIR"]
   [nil "--base-ns NS"]])

(defn- parsed-base-ns-opts
  "The three options as tools.cli reads them off a full scaffolder argv.

   parse-opts flags every option outside the spec as an error but still parses
   the ones it knows — hand-scanning the vector instead is how three parser
   disagreements shipped in a row (BOU-364). Unknown-option errors are routine
   (the argv is full of options only the scaffolder specs); a missing-argument
   error can only be about one of these three, and is surfaced so the caller
   does not append into a command the scaffolder must reject."
  [args]
  (let [{:keys [options errors]} (cli/parse-opts args base-ns-option-specs)]
    (assoc options
           :malformed? (boolean (some #(str/starts-with? % "Missing required argument")
                                      errors)))))

(defn with-base-ns
  "Add `--base-ns` unless the caller named one.

   A module belongs to the application, so a new one is written under the
   application's namespace. Detected rather than asked for: `--base-ns` existed
   before this and nobody passed it, which is how every generated project ended
   up with its modules in `wagoe.*` (BOU-360).

   For a command naming an existing module — field, endpoint, adapter — the
   answer is where that module *is*, which in a project generated before the
   move is still `wagoe.<module>`. Passing the project namespace regardless
   made `bb scaffold field` write the migration, skip the schema file it could
   not find, and report success.

   Read from `--output-dir` when there is one, not from the working directory:
   the project being edited is the one whose layout answers the question.
   Deriving it from the caller sent `--base-ns wagoe` at a project whose code is
   under `shop`, and the guards added in BOU-364 then refused a module that is
   really there."
  [args]
  (let [{:keys [module-name output-dir base-ns malformed?]} (parsed-base-ns-opts args)
        root (or output-dir (System/getProperty "user.dir"))]
    (cond
      ;; A bare trailing `--base-ns` or `--output-dir` parses as an error, not a
      ;; value. Appending our pair would feed it a flag as its argument — the
      ;; scaffolder would scaffold under the namespace `--base-ns` instead of
      ;; rejecting the command. Pass it through untouched and let it reject.
      malformed?  args
      base-ns     args
      module-name (into (vec args) ["--base-ns" (project/module-base-ns module-name root)])
      :else       (into (vec args) ["--base-ns" (project/base-ns root)]))))

(defn resolution-failure?
  "True when the CLI's stderr says a dependency could not be fetched, as
   opposed to the scaffolder itself failing."
  [err]
  (boolean (and err (re-find #"Error building classpath|Could not (?:find|transfer|resolve) artifact|Failed to read artifact descriptor|Failure to find"
                             err))))

(defn- tee-writer
  "A Writer that forwards to `out` as it is written and keeps a copy in `sb`."
  ^java.io.Writer [^java.io.Writer out ^StringBuilder sb]
  (proxy [java.io.Writer] []
    (write
      ([x]
       (if (string? x)
         (do (.append sb ^String x) (.write out ^String x))
         (do (.append sb (char x)) (.write out (int x))))
       (.flush out))
      ([cbuf off len]
       (if (string? cbuf)
         (do (.append sb ^String cbuf (int off) (int (+ off len)))
             (.write out ^String cbuf (int off) (int len)))
         (do (.append sb ^chars cbuf (int off) (int len))
             (.write out ^chars cbuf (int off) (int len))))
       (.flush out)))
    (flush [] (.flush out))
    (close [] (.flush out))))

(defn- run-scaffolder-once
  "Streams stderr as it arrives, so a cold dependency download is not silent,
   and returns it for the retry decision."
  [cmd]
  (let [err (StringBuilder.)
        {:keys [exit]} (apply shell {:continue true :err (tee-writer *err* err)} cmd)]
    {:exit exit :err (str err)}))

(defn run-clojure!
  "Shell out to the Clojure scaffolder CLI with given args. Streams output to terminal.

   In generated projects (no libs/scaffolder directory), injects wagoe-scaffolder
   via -Sdeps so the namespace is resolvable. In the monorepo, libs/scaffolder/src is
   already on the classpath, so -Sdeps is skipped to avoid forcing Maven resolution of
   an artifact that may not yet be published.

   In a fresh environment this is the first JVM to fetch the scaffolder and
   rewrite-clj, so a transient fetch failure is retried once (BOU-545)."
  [args]
  (println)
  (println (bold "Running scaffolder..."))
  (println)
  (try
    (let [in-monorepo? (.exists (io/file "libs/scaffolder"))
          base-cmd     (if in-monorepo?
                         ["clojure" "-M" "-m" "wagoe.scaffolder.shell.cli-entry"]
                         ["clojure"
                          "-Sdeps"
                          (scaffolder-deps)
                          "-M" "-m" "wagoe.scaffolder.shell.cli-entry"])
          cmd          (concat base-cmd (with-base-ns args))
          first-try    (run-scaffolder-once cmd)
          result       (if (and (not (zero? (:exit first-try)))
                                (resolution-failure? (:err first-try)))
                         (do (println (yellow "Dependency fetch failed — retrying once..."))
                             (run-scaffolder-once cmd))
                         first-try)]
      ;; Truthy on success: wizard-ai stops a multi-entity run on the first nil.
      (if (zero? (:exit result))
        result
        (do (println (red (str "Scaffolder exited with code " (:exit result))))
            (*exit!* 1)
            nil)))
    (catch Exception e
      (println (red (str "Scaffolder exited with error: " (.getMessage e))))
      (*exit!* 1))))

;; =============================================================================
;; Summary display
;; =============================================================================

(defn display-generate-summary
  [module entity fields http web & [public-api {:keys [workflow belongs-to min]}]]
  (println)
  (println (cyan "┌─ Summary ─────────────────────────────────────────────┐"))
  (println (str (cyan "│") " Module:  " (bold module)))
  (println (str (cyan "│") " Entity:  " (bold entity)))
  (if (empty? fields)
    (println (str (cyan "│") " Fields:  " (yellow "(none \u2014 scaffolder requires at least one)")))
    (do
      (println (str (cyan "│") " Fields:"))
      (doseq [{:keys [name type required unique]} fields]
        (let [mods (->> [(when required "required") (when unique "unique")]
                        (filter some?)
                        (str/join ", "))
              mods-str (if (seq mods) (str " (" mods ")") "")]
          ;; The space is not padding: a 14-character name ran into its type.
          (println (str (cyan "│") "   " (format "%-14s" name) " "
                        (format "%-10s" type) mods-str))))))
  (when workflow
    (println (str (cyan "│") " Workflow:  " (:field workflow) ": " (str/join " > " (:states workflow)))))
  (when belongs-to
    (println (str (cyan "│") " belongs to " belongs-to (when min (str ", at least " min)))))
  (println (str (cyan "│") " Interfaces:  "
                "HTTP " (if http (green "\u2713") (red "\u2717"))
                "  Web UI " (if web (green "\u2713") (red "\u2717"))))
  (when (some? public-api)
    (println (str (cyan "│") " Public API:  " (if public-api (green "\u2713") (red "\u2717")))))
  (println (cyan "└───────────────────────────────────────────────────────┘")))

;; =============================================================================
;; Interactive wizards
;; =============================================================================

(defn- shell-quote
  "`s` as one shell word. Unquoted, a workflow's `>` is a redirect (BOU-585)."
  [s]
  (if (re-matches #"[A-Za-z0-9_./:=,@%+-]+" s)
    s
    (str "'" (str/replace s "'" "'\\''") "'")))

(defn command-line
  "`args` as a `bb scaffold` command line that pastes back as the same args."
  [args]
  (str "bb scaffold " (str/join " " (map shell-quote args))))

(defn- print-command
  "The command a wizard is about to run, as the user would type it."
  [args]
  (println (dim (str "Command: " (command-line args)))))

(defn wizard-generate []
  (println)
  (println (bold "\u2746 Wagoe Scaffolder \u2014 Generate Module"))
  (println)

  (let [module (loop []
                 (let [s (prompt "Module name (kebab-case)")]
                   (cond
                     (empty? s)            (do (println (red "Module name is required")) (recur))
                     (not (valid-kebab? s)) (do (println (red "Must be kebab-case, e.g. my-module")) (recur))
                     :else s)))

        entity (loop []
                 (let [s (prompt "Entity name" (kebab->pascal module))]
                   (if (valid-pascal? s)
                     s
                     (do (println (red "Must be PascalCase, e.g. MyModule")) (recur)))))

        http (confirm "Enable REST API routes?" true)
        web  (confirm "Enable Web UI?" true)

        _ (println)
        _ (println (bold "Fields") "\u2014 enter blank name when done:")

        fields (loop [acc []]
                 (print "  Name: ")
                 (flush)
                 (let [fname (str/trim (or (read-line) ""))]
                   (if (empty? fname)
                     acc
                     (if-not (valid-kebab? fname)
                       (do (println (red "  Must be kebab-case, e.g. my-field")) (recur acc))
                       (do
                         (println "  Type:")
                         (let [ftype    (select-from-menu field-types)
                               values   (when (= "enum" ftype) (prompt-enum-values))
                               required (confirm "  Required?" true)
                               unique   (confirm "  Unique?" false)]
                           (println)
                           (recur (conj acc (cond-> {:name fname :type ftype
                                                     :required required :unique unique}
                                              (seq values) (assoc :enum-values values))))))))))

        _ (display-generate-summary module entity fields http web)

        args (build-generate-args {:module module :entity entity
                                   :fields fields :http http :web web})]

    (println)
    (print-command args)
    (println)
    (if (confirm "Proceed?" true)
      (run-clojure! args)
      (println "Aborted."))))

;; BOU-259: `bb scaffold new` had its own project generator, independent of the
;; `wagoe new` templates it was supposed to mirror. It drifted until it no longer
;; emitted a Wagoe project at all \u2014 no com.wagoe deps, no main.clj/system.clj, no
;; build.clj, no tests.edn, no .env. Rather than keep two generators in sync, the
;; route is gone and this points at the one that works. A bare "unknown command"
;; would leave anyone following the old docs stuck (BOU-261/262).
(defn print-new-removed []
  (println)
  (println (yellow "`bb scaffold new` has been removed."))
  (println)
  (println "Projects are created with the Wagoe CLI:")
  (println)
  (println (str "  " (bold "wagoe new my-app")))
  (println)
  ;; The raw.githubusercontent URL, not a wagoe.org short form: wagoe.org does
  ;; not serve install.sh (404), and this message exists to get an unstuck user
  ;; unstuck. Same URL as README.md and the quickstart page.
  (println (str "Don't have it?  "
                (cyan "curl -fsSL https://raw.githubusercontent.com/wagoebv/wagoe/main/scripts/install.sh | bash")))
  (println)
  (println (dim (str "`bb scaffold` still handles modules, fields, endpoints and "
                     "adapters inside an existing project.")))
  (println))

(defn wizard-field []
  (println)
  (println (bold "\u2746 Wagoe Scaffolder \u2014 Add Field"))
  (println)

  (let [module (loop []
                 (let [s (prompt "Module name (kebab-case)")]
                   (cond
                     (empty? s)            (do (println (red "Module name is required")) (recur))
                     (not (valid-kebab? s)) (do (println (red "Must be kebab-case")) (recur))
                     :else s)))

        entity (loop []
                 (let [s (prompt "Entity name" (kebab->pascal module))]
                   (if (valid-pascal? s)
                     s
                     (do (println (red "Must be PascalCase")) (recur)))))

        fname (loop []
                (let [s (prompt "Field name (kebab-case)")]
                  (cond
                    (empty? s)            (do (println (red "Field name is required")) (recur))
                    (not (valid-kebab? s)) (do (println (red "Must be kebab-case")) (recur))
                    :else s)))

        _ (println "Field type:")
        ftype    (select-from-menu field-types)
        values   (when (= "enum" ftype) (prompt-enum-values))
        required (confirm "Required?" true)
        unique   (confirm "Unique?" false)
        dry-run  (confirm "Dry run?" false)

        args (cond-> ["field" "--module-name" module "--entity" entity
                      "--name" fname "--type" ftype]
               (seq values) (conj "--enum-values" (str/join "," values))
               required (conj "--required")
               unique   (conj "--unique")
               dry-run  (conj "--dry-run"))]

    (println)
    (print-command args)
    (println)
    (if (confirm "Proceed?" true)
      (run-clojure! args)
      (println "Aborted."))))

(defn wizard-endpoint []
  (println)
  (println (bold "\u2746 Wagoe Scaffolder \u2014 Add Endpoint"))
  (println)

  (let [module (loop []
                 (let [s (prompt "Module name (kebab-case)")]
                   (cond
                     (empty? s)            (do (println (red "Module name is required")) (recur))
                     (not (valid-kebab? s)) (do (println (red "Must be kebab-case")) (recur))
                     :else s)))

        path (loop []
               (let [s (prompt "URL path (e.g. /products/export)")]
                 (if (str/starts-with? s "/")
                   s
                   (do (println (red "Path must start with /")) (recur)))))

        _ (println "HTTP method:")
        method (select-from-menu http-methods)

        handler (loop []
                  (let [s (prompt "Handler function name (kebab-case)")]
                    (cond
                      (empty? s)            (do (println (red "Handler name is required")) (recur))
                      (not (valid-kebab? s)) (do (println (red "Must be kebab-case")) (recur))
                      :else s)))

        dry-run (confirm "Dry run?" false)

        args (cond-> ["endpoint" "--module-name" module "--path" path
                      "--method" method "--handler-name" handler]
               dry-run (conj "--dry-run"))]

    (println)
    (print-command args)
    (println)
    (if (confirm "Proceed?" true)
      (run-clojure! args)
      (println "Aborted."))))

(defn wizard-adapter []
  (println)
  (println (bold "\u2746 Wagoe Scaffolder \u2014 Add Adapter"))
  (println)

  (let [module (loop []
                 (let [s (prompt "Module name (kebab-case)")]
                   (cond
                     (empty? s)            (do (println (red "Module name is required")) (recur))
                     (not (valid-kebab? s)) (do (println (red "Must be kebab-case")) (recur))
                     :else s)))

        port (loop []
               (let [s (prompt "Port/protocol name (e.g. INotificationSender)")]
                 (if (re-matches #"^I?[A-Z][a-zA-Z0-9]*$" s)
                   s
                   (do (println (red "Must be PascalCase (optionally prefixed with I)")) (recur)))))

        adapter-name (loop []
                       (let [s (prompt "Adapter name (kebab-case, e.g. slack)")]
                         (cond
                           (empty? s)            (do (println (red "Adapter name is required")) (recur))
                           (not (valid-kebab? s)) (do (println (red "Must be kebab-case")) (recur))
                           :else s)))

        _ (println)
        _ (println (bold "Methods") "\u2014 enter blank name when done:")

        methods (loop [acc []]
                  (print "  Method name (blank to finish): ")
                  (flush)
                  (let [mname (str/trim (or (read-line) ""))]
                    (if (empty? mname)
                      acc
                      (do
                        (print "  Args (comma-separated, blank for none): ")
                        (flush)
                        (let [args-in (str/trim (or (read-line) ""))
                              spec    (if (empty? args-in)
                                        mname
                                        (str mname ":" args-in))]
                          (recur (conj acc spec)))))))

        dry-run     (confirm "Dry run?" false)
        method-args (mapcat #(vector "--method" %) methods)
        args        (vec (concat ["adapter"
                                  "--module-name" module
                                  "--port" port
                                  "--adapter-name" adapter-name]
                                 method-args
                                 (when dry-run ["--dry-run"])))]

    (println)
    (print-command args)
    (println)
    (if (confirm "Proceed?" true)
      (run-clojure! args)
      (println "Aborted."))))

;; =============================================================================
;; AI-powered NL scaffolding
;; =============================================================================

(defn- valid-workflow?
  "Whether the model's workflow is one --workflow can say."
  [{:keys [field states]}]
  (and (string? field) (valid-kebab? field)
       (sequential? states) (>= (count states) 2)
       (every? #(and (string? %) (valid-kebab? %)) states)
       (apply distinct? states)))

(defn- parse-ai-module-spec
  "Read the JSON module spec `bb ai scaffold-parse` writes to stdout.

   Returns the argument map `build-generate-args` takes, or nil if the output
   holds no spec \u2014 a provider that answered with prose, or one that named no
   module."
  [out]
  (try
    (let [data     (json/parse-string (ai/json-line out) true)
          ;; `:entities` is the multi-entity shape (BOU-497); a spec with only
          ;; `:entity` and `:fields` is one entity.
          entities (if (seq (:entities data))
                     (mapv (fn [e] (cond-> {:name (:name e) :fields (vec (:fields e))}
                                     (valid-workflow? (:workflow e)) (assoc :workflow (:workflow e))
                                     (and (:belongs-to e) (pos-int? (:min e))) (assoc :min (:min e))
                                     ;; A model writes "invoice" as often as "Invoice".
                                     (:belongs-to e) (assoc :belongs-to
                                                            (cond-> (:belongs-to e)
                                                              (valid-kebab? (:belongs-to e)) kebab->pascal))))
                           (:entities data))
                     [{:name (:entity data) :fields (vec (:fields data))}])
          ;; From the first entity, not the model's `module-name`: the same
          ;; description came back as `invoice` and then `invoicing` (BOU-562).
          spec     {:module   (some-> (:name (first entities)) pascal->kebab)
                    :entity   (:name (first entities))
                    :fields   (:fields (first entities))
                    :entities entities
                    :http     (boolean (:http data))
                    :web      (boolean (:web data))
                    :public-api (boolean (:public-api data))}]
      (when (and (valid-kebab? (:module spec))
                 (every? (comp valid-pascal? :name) entities)
                 (every? #(or (nil? (:belongs-to %)) (valid-pascal? (:belongs-to %))) entities))
        spec))
    (catch Exception _ nil)))

(def ^:dynamic *parse-cache-dir*
  "Where a description's parse is kept, so a dry run and the real run that
   follows it scaffold the same thing (BOU-562). `:project` is target/ of the
   project being written to; nil keeps nothing."
  :project)

(defn parse-description
  "The AI CLI's parse of `description`: the shell result, its spec on stdout."
  [description]
  (apply shell {:out :string :continue true}
         (ai/ai-command ["scaffold-parse" description])))

(defn- parse-cache-file [flags description]
  (when-let [dir (case *parse-cache-dir*
                   :project (str (or (:output-dir flags) (System/getProperty "user.dir"))
                                 "/target/scaffold-ai")
                   *parse-cache-dir*)]
    (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-256")
                          (.getBytes (str/trim description) "UTF-8"))]
      (io/file dir (str (subs (apply str (map #(format "%02x" %) digest)) 0 16) ".json")))))

(defn- cached-parse
  "The parse of `description`: the cached one when there is one and not
   `:fresh`, else a new one, which replaces the cache when it holds a spec."
  [flags description]
  (let [f (parse-cache-file flags description)]
    (if (and f (not (:fresh flags)) (.isFile ^java.io.File f))
      (do (println (dim (str "Using the parse from " (.getPath ^java.io.File f)
                             " — pass --fresh to parse the description again.")))
          (println)
          {:exit 0 :out (slurp f)})
      (let [result (parse-description description)]
        (when (and f result (zero? (:exit result)) (parse-ai-module-spec (:out result)))
          (io/make-parents f)
          (spit f (ai/json-line (:out result))))
        result))))

(defn wizard-ai
  "Parse a description with the AI CLI, then scaffold through the normal path.

   The AI CLI used to preview, confirm and generate on its own. It could not be
   reached at all from a generated project \u2014 `clojure -M` without the injected
   dependency (BOU-401) \u2014 and once reachable it shelled the scaffolder without
   rewrite-clj or `--base-ns`. It now parses and nothing else: generation goes
   through `run-clojure!`, the same call `bb scaffold generate` makes."
  ([description yes?] (wizard-ai description yes? {}))
  ([description yes? flags]
   (println)
   (println (bold "\u2746 Wagoe AI Scaffolder \u2014 Natural Language Module Generation"))
   (println)
   (println (dim (str "Parsing: " description)))
   (println)
   (let [result (try
                  (cached-parse flags description)
                  (catch Exception e
                    (println (red (str "AI scaffolder exited with error: " (.getMessage e))))
                    (*exit!* 1)
                    nil))
         spec   (when (and result (zero? (:exit result)))
                  (parse-ai-module-spec (:out result)))]
     (cond
       (nil? result)
       nil

       (not (zero? (:exit result)))
       (do (println (red "AI scaffolder could not parse the description."))
           ;; The CLI's own message may be on stdout, which is captured for the
           ;; spec — reprinted, or a failure explains itself to nobody.
           (when-not (str/blank? (str (:out result)))
             (println (dim (str/trim (str (:out result))))))
           (*exit!* 1))

       (nil? spec)
       (do (println (red "AI scaffolder returned no usable module spec."))
           (println (dim (str/trim (str (:out result)))))
           (*exit!* 1))

       ;; `generate --force` rewrites the module's wiring, schema and ports
       ;; without the other entities, and `entity` has no --force to put them
       ;; back: the run fails halfway and leaves orphaned files.
       (and (:force flags) (> (count (:entities spec)) 1))
       (do (println (red (str "--force cannot regenerate a module with several entities ("
                              (str/join ", " (map :name (:entities spec))) ").")))
           (println "  Remove the module's files and run again without --force.")
           (*exit!* 1))

       :else
       (let [{:keys [module entities http web public-api]} (apply-ai-flags spec flags)
             commands (build-ai-commands spec flags)
             ;; An `entity` dry run needs the module on disk, and a dry
             ;; `generate` does not put it there — so only `generate` runs.
             to-run   (if (:dry-run flags) (take 1 commands) commands)]
         (doseq [{:keys [name fields] :as e} entities]
           (display-generate-summary module name fields http web (boolean public-api) e))
         (println)
         (doseq [args commands]
           (print-command args))
         (println)
         ;; A dry run writes nothing, so there is nothing to confirm. With
         ;; nobody to ask, the prompt took its default (BOU-585).
         (cond
           (not (or yes? (:force flags) (:dry-run flags) (tty?)))
           (do (println (red "No terminal to confirm on. Nothing generated; pass --yes to generate."))
               (*exit!* 1))

           (or yes? (:force flags) (:dry-run flags) (confirm "Generate this module?" true))
           ;; In order, and no further once one fails: an entity cannot be added
           ;; to a module that was not generated.
           (let [result (reduce (fn [_ args] (or (run-clojure! args) (reduced nil))) nil to-run)]
             (when (and result (< (count to-run) (count commands)))
               (println (yellow (str "Dry run: the " (dec (count commands))
                                     " entity command(s) above add to a module that does not exist yet, so they were not run."))))
             result)

           :else
           (println (yellow "Cancelled. No files were generated."))))))))

;; =============================================================================
;; Help text
;; =============================================================================

(def help-text
  (str (bold "Wagoe Scaffolder \u2014 Interactive Wizard") "\n"
       "\n"
       "Usage:\n"
       "  bb scaffold                     Show this help\n"
       "  bb scaffold generate            Interactive wizard for module generation\n"
       "  bb scaffold entity [args]       Add an entity to an existing module (see below)\n"
       "  bb scaffold field               Interactive wizard for adding a field\n"
       "  bb scaffold endpoint            Interactive wizard for adding an endpoint\n"
       "  bb scaffold adapter             Interactive wizard for adding an adapter\n"
       "  bb scaffold subscriber [args]   Add an event subscriber to a module (see below)\n"
       "  bb scaffold ai <description> [--yes]    AI-powered module generation from NL description\n"
       "      [--dry-run] [--fresh] [--output-dir DIR] [--base-ns NS] [--force] [--no-http] [--no-web] [--[no-]public-api]\n"
       "  bb scaffold integrate <module> [--base-ns NS]  Guide integration of a scaffolded module\n"
       "\n"
       "`bb scaffold` works inside an existing project. To create a new one:\n"
       "  wagoe new my-app\n"
       "  install: curl -fsSL https://raw.githubusercontent.com/wagoebv/wagoe/main/scripts/install.sh | bash\n"
       "\n"
       "Non-interactive passthrough (when args are provided directly):\n"
       "  bb scaffold generate --module-name foo --entity Foo --field bar:string\n"
       "  bb scaffold entity --module-name foo --entity FooLine --belongs-to foo --min 1 --field qty:int\n"
       "  bb scaffold field --module-name foo --entity Foo --name bar --type string\n"
       "  bb scaffold subscriber --module-name foo --event :admin/entity-created --entity foos\n"
       "\n"
       "Field spec: name:type[:values=a,b,c][:required][:unique][:default=v]\n"
       "  --field status:enum:values=entered,paid:required:default=entered\n"
       "  default= is the column DEFAULT; a required enum without one takes its first value\n"
       "\n"
       "A status that only moves forward, as a workflow (generate and entity):\n"
       "  --workflow 'status:entered>delivered>paid'   (quoted: > is a shell redirect)\n"
       "\n"
       "For AI scaffolding, set one of:\n"
       "  ANTHROPIC_API_KEY, OPENAI_API_KEY, REPLICATE_API_TOKEN,\n"
       "  or start Ollama locally\n"
       "\n"
       "Every option of a command:\n"
       "  bb scaffold generate --help     (and entity, field, endpoint, adapter, subscriber, ai)"))

;; =============================================================================
;; Main entry point
;; =============================================================================

(defn -main [& raw-args]
  (let [args      (vec raw-args)
        [sub & rest-args] args]
    (cond
      (or (nil? sub)
          (contains? #{"-h" "--help" "help"} sub))
      (println help-text)

      (= sub "generate")
      (if (seq rest-args)
        (run-clojure! (into ["generate"] rest-args))
        (wizard-generate))

      ;; Redirects with or without args — BOU-259 removed the generator behind it.
      ;; `bb scaffold new --name x` used to be a non-interactive passthrough that
      ;; generated a project, so a script can still be invoking it; exit non-zero
      ;; (like the scaffolder CLI does) rather than let silence read as success.
      (= sub "new")
      (do (print-new-removed)
          (*exit!* 1))

      (= sub "entity")
      (if (seq rest-args)
        (run-clojure! (into ["entity"] rest-args))
        (run-clojure! ["entity" "--help"]))

      (= sub "subscriber")
      (run-clojure! (into ["subscriber"] (or (seq rest-args) ["--help"])))

      (= sub "field")
      (if (seq rest-args)
        (run-clojure! (into ["field"] rest-args))
        (wizard-field))

      (= sub "endpoint")
      (if (seq rest-args)
        (run-clojure! (into ["endpoint"] rest-args))
        (wizard-endpoint))

      (= sub "adapter")
      (if (seq rest-args)
        (run-clojure! (into ["adapter"] rest-args))
        (wizard-adapter))

      (= sub "ai")
      (let [{:keys [options arguments errors]} (cli/parse-opts rest-args ai-option-specs)
            description (str/join " " arguments)]
        (cond
          (:help options)
          (println (ai-help))

          (seq errors)
          (do (run! #(println (red %)) (distinct errors))
              (println "  Quote the description, or put -- before one that starts with -:")
              (println "  bb scaffold ai --yes -- \"-5% discount module\"")
              (*exit!* 1))

          (seq description)
          (wizard-ai description (boolean (:yes options)) (dissoc options :yes :help))

          :else
          (do (println (red "Please provide a module description."))
              (println "  Example: bb scaffold ai \"product module with name, price, stock\"")
              (*exit!* 1))))

      (= sub "integrate")
      (do (require '[wagoe.tools.integrate :as integrate])
          (apply (resolve 'integrate/-main) rest-args))

      :else
      (do
        (println (red (str "Unknown subcommand: " sub)))
        (println)
        (println help-text)))))

;; Run when executed directly (not via bb.edn task)
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
