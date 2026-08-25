#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/scaffold.clj
;;
;; Interactive scaffolding wizard for Wagoe modules.
;;
;; Usage (via bb.edn task):
;;   bb scaffold                     -- show help
;;   bb scaffold generate            -- interactive wizard
;;   bb scaffold generate [args...]  -- non-interactive passthrough
;;   bb scaffold field               -- interactive wizard
;;   bb scaffold endpoint            -- interactive wizard
;;   bb scaffold adapter             -- interactive wizard
;;   bb scaffold ai "<description>" [--yes] -- AI-assisted module generation

(ns wagoe.tools.scaffold
  (:require [wagoe.tools.ansi :as ansi :refer [bold green cyan red yellow dim]]
            [wagoe.tools.project :as project]
            [clojure.java.io :as io]
            [clojure.string :as str]
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

(defn confirm
  "Y/n or y/N prompt. Returns boolean."
  ([label] (confirm label true))
  ([label default-yes?]
   (let [hint (if default-yes? "Y/n" "y/N")]
     (print (str label " [" hint "]: "))
     (flush)
     (let [input (str/trim (str/lower-case (or (read-line) "")))]
       (if (empty? input)
         default-yes?
         (= input "y"))))))

;; =============================================================================
;; Menu selection
;; =============================================================================

(def field-types
  ["string" "text" "int" "decimal" "boolean" "email" "uuid" "enum" "date" "json"])

(def http-methods ["GET" "POST" "PUT" "DELETE" "PATCH"])

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
  "Convert a field map to CLI spec: name:type[:required][:unique]"
  [{:keys [name type required unique]}]
  (str/join ":" (filter some? [name type
                               (when required "required")
                               (when unique "unique")])))

(defn build-generate-args [{:keys [module entity fields http web]}]
  (let [base       ["generate" "--module-name" module "--entity" entity]
        field-args (mapcat #(vector "--field" (field->spec %)) fields)
        no-http    (when-not http ["--no-http"])
        no-web     (when-not web  ["--no-web"])]
    (vec (concat base field-args no-http no-web))))

;; =============================================================================
;; Run Clojure scaffolder
;; =============================================================================

;; Must match libs/tools/build.clj version and libs/scaffolder/build.clj version.
;; Update all three together on each release.
(def ^:private scaffolder-version "1.0.0-beta-5")

;; Match libs/scaffolder/deps.edn and the monorepo's own pin.
(def ^:private rewrite-clj-version "1.2.55")

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
  (let [module (second (drop-while #(not= "--module-name" %) args))
        root   (or (second (drop-while #(not= "--output-dir" %) args))
                   (System/getProperty "user.dir"))]
    (cond
      (some #{"--base-ns"} args) args
      module (into (vec args) ["--base-ns" (project/module-base-ns module root)])
      :else  (into (vec args) ["--base-ns" (project/base-ns root)]))))

(defn run-clojure!
  "Shell out to the Clojure scaffolder CLI with given args. Streams output to terminal.

   In generated projects (no libs/scaffolder directory), injects wagoe-scaffolder
   via -Sdeps so the namespace is resolvable. In the monorepo, libs/scaffolder/src is
   already on the classpath, so -Sdeps is skipped to avoid forcing Maven resolution of
   an artifact that may not yet be published."
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
                          "-M" "-m" "wagoe.scaffolder.shell.cli-entry"])]
      (apply shell (concat base-cmd (with-base-ns args))))
    (catch Exception e
      (println (red (str "Scaffolder exited with error: " (.getMessage e))))
      (*exit!* 1))))

;; =============================================================================
;; Summary display
;; =============================================================================

(defn display-generate-summary [module entity fields http web]
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
          (println (str (cyan "│") "   " (format "%-14s" name)
                        (format "%-10s" type) mods-str))))))
  (println (str (cyan "│") " Interfaces:  "
                "HTTP " (if http (green "\u2713") (red "\u2717"))
                "  Web UI " (if web (green "\u2713") (red "\u2717"))))
  (println (cyan "└───────────────────────────────────────────────────────┘")))

;; =============================================================================
;; Interactive wizards
;; =============================================================================

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
                               required (confirm "  Required?" true)
                               unique   (confirm "  Unique?" false)]
                           (println)
                           (recur (conj acc {:name fname :type ftype
                                             :required required :unique unique}))))))))

        _ (display-generate-summary module entity fields http web)

        args (build-generate-args {:module module :entity entity
                                   :fields fields :http http :web web})]

    (println)
    (println (dim (str "Command: clojure -M -m wagoe.scaffolder.shell.cli-entry "
                       (str/join " " args))))
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
        required (confirm "Required?" true)
        unique   (confirm "Unique?" false)
        dry-run  (confirm "Dry run?" false)

        args (cond-> ["field" "--module-name" module "--entity" entity
                      "--name" fname "--type" ftype]
               required (conj "--required")
               unique   (conj "--unique")
               dry-run  (conj "--dry-run"))]

    (println)
    (println (dim (str "Command: clojure -M -m wagoe.scaffolder.shell.cli-entry "
                       (str/join " " args))))
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
    (println (dim (str "Command: clojure -M -m wagoe.scaffolder.shell.cli-entry "
                       (str/join " " args))))
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
    (println (dim (str "Command: clojure -M -m wagoe.scaffolder.shell.cli-entry "
                       (str/join " " args))))
    (println)
    (if (confirm "Proceed?" true)
      (run-clojure! args)
      (println "Aborted."))))

;; =============================================================================
;; AI-powered NL scaffolding
;; =============================================================================

(defn wizard-ai [description yes?]
  (println)
  (println (bold "\u2746 Wagoe AI Scaffolder \u2014 Natural Language Module Generation"))
  (println)
  (println (dim (str "Parsing: " description)))
  (println)
  ;; Delegate to the AI CLI which previews, confirms, and runs scaffolder generation.
  (try
    (if yes?
      (shell "clojure" "-M" "-m" "wagoe.ai.shell.cli-entry" "scaffold-ai" "--yes" description)
      (shell "clojure" "-M" "-m" "wagoe.ai.shell.cli-entry" "scaffold-ai" description))
    (catch Exception e
      (println (red (str "AI scaffolder exited with error: " (.getMessage e))))
      (*exit!* 1))))

;; =============================================================================
;; Help text
;; =============================================================================

(def help-text
  (str (bold "Wagoe Scaffolder \u2014 Interactive Wizard") "\n"
       "\n"
       "Usage:\n"
       "  bb scaffold                     Show this help\n"
       "  bb scaffold generate            Interactive wizard for module generation\n"
       "  bb scaffold field               Interactive wizard for adding a field\n"
       "  bb scaffold endpoint            Interactive wizard for adding an endpoint\n"
       "  bb scaffold adapter             Interactive wizard for adding an adapter\n"
       "  bb scaffold ai <description> [--yes]    AI-powered module generation from NL description\n"
       "  bb scaffold integrate <module> [--base-ns NS]  Guide integration of a scaffolded module\n"
       "\n"
       "`bb scaffold` works inside an existing project. To create a new one:\n"
       "  wagoe new my-app\n"
       "  install: curl -fsSL https://raw.githubusercontent.com/wagoebv/wagoe/main/scripts/install.sh | bash\n"
       "\n"
       "Non-interactive passthrough (when args are provided directly):\n"
       "  bb scaffold generate --module-name foo --entity Foo --field bar:string\n"
       "  bb scaffold field --module-name foo --entity Foo --name bar --type string\n"
       "\n"
       "The wizard delegates to:\n"
       "  clojure -M -m wagoe.scaffolder.shell.cli-entry <command> [opts]\n"
       "\n"
       "For AI scaffolding, set one of:\n"
       "  ANTHROPIC_API_KEY, OPENAI_API_KEY, or start Ollama locally\n"
       "\n"
       "For full CLI documentation:\n"
       "  clojure -M -m wagoe.scaffolder.shell.cli-entry --help\n"
       "  clojure -M -m wagoe.scaffolder.shell.cli-entry generate --help"))

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
      (let [yes?        (boolean (some #{"--yes" "-y"} rest-args))
            description (->> rest-args
                             (remove #{"--yes" "-y"})
                             (str/join " "))]
        (if (seq description)
          (wizard-ai description yes?)
          (do (println (red "Please provide a module description."))
              (println "  Example: bb scaffold ai \"product module with name, price, stock\""))))

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
