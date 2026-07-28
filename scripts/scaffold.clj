#!/usr/bin/env bb
;; scripts/scaffold.clj
;;
;; Interactive scaffolding wizard for Wagoe modules.
;;
;; Usage (via bb.edn task):
;;   bb scaffold                     -- show help
;;   bb scaffold generate            -- interactive wizard
;;   bb scaffold generate [args...]  -- non-interactive passthrough
;;   bb scaffold new                 -- interactive wizard
;;   bb scaffold field               -- interactive wizard
;;   bb scaffold endpoint            -- interactive wizard
;;   bb scaffold adapter             -- interactive wizard
;;   bb scaffold ai "<description>" [--yes] -- AI-assisted module generation
;;
;; Usage (direct):
;;   bb scripts/scaffold.clj generate

(ns scaffold
  (:require [clojure.string :as str]
            [babashka.process :refer [shell]]))

;; =============================================================================
;; ANSI helpers
;; =============================================================================

(defn bold   [s] (str "\033[1m"  s "\033[0m"))
(defn green  [s] (str "\033[32m" s "\033[0m"))
(defn cyan   [s] (str "\033[36m" s "\033[0m"))
(defn red    [s] (str "\033[31m" s "\033[0m"))
(defn yellow [s] (str "\033[33m" s "\033[0m"))
(defn dim    [s] (str "\033[2m"  s "\033[0m"))

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

(defn run-clojure!
  "Shell out to the Clojure scaffolder CLI with given args. Streams output to terminal."
  [args]
  (println)
  (println (bold "Running scaffolder..."))
  (println)
  (try
    (apply shell "clojure" "-M" "-m" "wagoe.scaffolder.shell.cli-entry" args)
    (catch Exception e
      (println (red (str "Scaffolder exited with error: " (.getMessage e))))
      (System/exit 1))))

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

(defn wizard-new []
  (println)
  (println (bold "\u2746 Wagoe Scaffolder \u2014 New Project"))
  (println)

  (let [name-val (loop []
                   (let [s (prompt "Project name (kebab-case)")]
                     (cond
                       (empty? s)            (do (println (red "Project name is required")) (recur))
                       (not (valid-kebab? s)) (do (println (red "Must be kebab-case, e.g. my-project")) (recur))
                       :else s)))
        output-dir (prompt "Output directory" ".")
        dry-run    (confirm "Dry run (preview only)?" false)
        args       (cond-> ["new" "--name" name-val "--output-dir" output-dir]
                     dry-run (conj "--dry-run"))]

    (println)
    (println (dim (str "Command: clojure -M -m wagoe.scaffolder.shell.cli-entry "
                       (str/join " " args))))
    (println)
    (if (confirm "Proceed?" true)
      (run-clojure! args)
      (println "Aborted."))))

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
;; Help text
;; =============================================================================

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
      (System/exit 1))))

;; =============================================================================
;; Help text
;; =============================================================================

(def help-text
  (str (bold "Wagoe Scaffolder \u2014 Interactive Wizard") "\n"
       "\n"
       "Usage:\n"
       "  bb scaffold                     Show this help\n"
       "  bb scaffold generate            Interactive wizard for module generation\n"
       "  bb scaffold new                 Interactive wizard for new project\n"
       "  bb scaffold field               Interactive wizard for adding a field\n"
       "  bb scaffold endpoint            Interactive wizard for adding an endpoint\n"
       "  bb scaffold adapter             Interactive wizard for adding an adapter\n"
       "  bb scaffold ai <description> [--yes]    AI-powered module generation from NL description\n"
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

      (= sub "new")
      (if (seq rest-args)
        (run-clojure! (into ["new"] rest-args))
        (wizard-new))

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

      :else
      (do
        (println (red (str "Unknown subcommand: " sub)))
        (println)
        (println help-text)))))

;; Run when executed directly (not via bb.edn task)
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
