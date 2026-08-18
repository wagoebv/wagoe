(ns wagoe.cli.add
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [wagoe.cli.catalogue :as cat]
            [wagoe.cli.templates :as templates]))

;; ─── Project detection ────────────────────────────────────────────────────────

(defn wagoe-project?
  "True if dir contains a deps.edn referencing com.wagoe."
  [dir]
  (let [f (io/file dir "deps.edn")]
    (and (.exists f)
         (str/includes? (slurp f) "com.wagoe"))))

;; ─── deps.edn patching ───────────────────────────────────────────────────────

(defn dep-coords
  "Coordinate of `clojars` where the module belongs, or nil.

   Where matters, and a substring search over the file gets it wrong. The
   generated deps.edn lists wagoe-devtools, wagoe-ai, wagoe-scaffolder,
   wagoe-tools and wagoe-jobs inside the `:mcp` alias, which is a launcher for
   the MCP server and not the app's classpath. `wagoe add ai` read the file as
   text, found the name in that alias, and treated the module as installed —
   printing success and writing nothing.

   deps.edn is plain EDN with no reader tags, so this reads it. On anything
   unreadable it returns nil and the caller falls back to writing."
  [content clojars scope]
  (try
    (let [parsed (edn/read-string content)]
      (if (= :dev scope)
        (get-in parsed [:aliases :repl :extra-deps clojars])
        (get-in parsed [:deps clojars])))
    (catch Exception _ nil)))

(defn patch-deps!
  "Add clojars coordinate to deps.edn if not already present.

   A module with `:scope :dev` goes into the `:repl` alias instead of `:deps`.
   devtools is the first: it pulls a dashboard and a Jetty adapter, and putting
   it in `:deps` would ship all of that in the uberjar. Returns `:deps`,
   `:repl-alias`, `:no-repl-alias` (nothing written — the project has no `:repl`
   alias to patch) or nil when the dep was already there."
  [dir {:keys [clojars version scope]}]
  (let [f         (io/file dir "deps.edn")
        content   (slurp f)
        coord-str (str clojars)
        entry     (str coord-str " {:mvn/version \"" version "\"}")]
    (cond
      (dep-coords content clojars scope) nil

      (= :dev scope)
      ;; Anchored on `:extra-deps {` inside the :repl alias. Not on `:repl`
      ;; alone: an alias may declare :extra-paths first, and inserting after
      ;; the alias name would put a dep map key where a value is expected.
      (if-let [m (re-find #"(?s):repl\s*\{.*?:extra-deps\s*\{" content)]
        (do (spit f (str/replace-first content m
                                       (str m "\n                          " entry
                                            "\n                          ")))
            :repl-alias)
        :no-repl-alias)

      :else
      (do (spit f (str/replace-first
                   content
                   #"(:deps\s*\{)"
                   (str "$1\n         " entry "\n         ")))
          :deps))))

;; ─── config.edn patching ─────────────────────────────────────────────────────

(defn patch-config!
  "Inject snippet into :active section of config file if config-key not present."
  [dir relative-path snippet]
  (when (seq snippet)
    (let [f          (io/file dir relative-path)
          content    (slurp f)
          config-key (second (re-find #":(\S+)" snippet))]
      (when-not (str/includes? content (str ":" config-key))
        (let [active-idx (str/index-of content ":active")
              open-idx   (when active-idx (str/index-of content "{" (+ active-idx 7)))]
          (when open-idx
            (let [close-idx (loop [i (inc open-idx) depth 1]
                              (cond
                                (>= i (count content)) nil
                                (zero? depth)          (dec i)
                                :else
                                (let [c (nth content i)]
                                  (recur (inc i) (case c \{ (inc depth) \} (dec depth) depth)))))]
              (when close-idx
                (spit f (str (subs content 0 close-idx)
                             "\n" snippet
                             (subs content close-idx)))))))))))

;; ─── AGENTS.md patching ──────────────────────────────────────────────────────

(defn patch-agents-md!
  "Remove module row from available block; append to installed block."
  [dir {:keys [name docs-url]}]
  (let [f (io/file dir "AGENTS.md")]
    (when (.exists f)
      (let [content (slurp f)]
        (if-not (str/includes? content "<!-- wagoe:available-modules -->")
          (println "  Warning: AGENTS.md sentinel comments not found — skipping AGENTS.md update")
          (let [without-row  (templates/update-block
                              content "wagoe:available-modules"
                              #(str/replace % (templates/module-row-pattern name) ""))
                install-line (str "- " name " — [docs](" docs-url ")\n")
                with-install (str/replace without-row
                                          "<!-- /wagoe:installed-modules -->"
                                          (str install-line "<!-- /wagoe:installed-modules -->"))]
            (spit f with-install)))))))

;; ─── Main ────────────────────────────────────────────────────────────────────

(defn -main [args]
  (let [[module-name] args]
    (when-not module-name
      (println "Usage: wagoe add <module>")
      (println "Run 'wagoe list modules' to see available modules.")
      (System/exit 1))
    (let [dir (System/getProperty "user.dir")]
      (when-not (wagoe-project? dir)
        (println "Error: No wagoe project found in current directory.")
        (println "Run 'wagoe new <name>' first, then cd into the project.")
        (System/exit 1))
      (let [module (cat/find-module module-name)]
        (when-not module
          (println (str "Error: Unknown module '" module-name "'."))
          (println "Available modules:")
          (doseq [m (cat/optional-modules)]
            (println (str "  " (:name m))))
          (System/exit 1))
        (let [deps-content (slurp (io/file dir "deps.edn"))
              ;; Where the module belongs, not anywhere in the file — see
              ;; dep-coords. The :mcp alias names five wagoe libs it launches
              ;; the MCP server with, and reading those as installed made
              ;; `wagoe add ai` report success over an untouched deps.edn.
              existing     (dep-coords deps-content (:clojars module) (:scope module))
              dep-present? (boolean existing)
              existing-ver (:mvn/version existing)
              snippet      (:config-snippet module)
              ;; A module is "installed" when its dep is present AND its config key is
              ;; wired. Requiring dep-present? prevents false positives when two modules
              ;; share a config key (e.g. email and external both use :wagoe.external/smtp).
              wired?       (if (seq snippet)
                             (let [config-key     (second (re-find #":(\S+)" snippet))
                                   config-content (slurp (io/file dir "resources/conf/dev/config.edn"))]
                               (and dep-present?
                                    (str/includes? config-content (str ":" config-key))))
                             ;; No config snippet — check AGENTS.md installed section to avoid
                             ;; false positives from pre-installed deps (e.g. wagoe-external).
                             (let [agents-f (io/file dir "AGENTS.md")]
                               (if (.exists agents-f)
                                 (let [content          (slurp agents-f)
                                       installed-start  (str/index-of content "<!-- wagoe:installed-modules -->")
                                       installed-end    (str/index-of content "<!-- /wagoe:installed-modules -->")]
                                   (if (and installed-start installed-end)
                                     (str/includes? (subs content installed-start installed-end) module-name)
                                     dep-present?))
                                 dep-present?)))]
          (cond
            (and dep-present? existing-ver (not= existing-ver (:version module)))
            (do (println (str "Warning: " module-name " is already in deps.edn at version " existing-ver
                              " (catalogue version: " (:version module) ")."))
                (println "Resolve the version conflict manually — no changes made."))

            wired?
            (println (str "Module '" module-name "' is already installed."))

            :else
            (do
              (println (str "Adding " module-name "..."))
              (let [where (patch-deps! dir module)]
                (case where
                  :repl-alias    (println "  added to the :repl alias — dev-only, not on the production classpath")
                  ;; Say so rather than silently writing nothing. A project
                  ;; without a :repl alias is one generated before this existed,
                  ;; or one that dropped it.
                  :no-repl-alias (println (str "  Warning: no :repl alias in deps.edn — add "
                                               (:clojars module) " {:mvn/version \"" (:version module)
                                               "\"} to a dev alias by hand"))
                  nil))
              (patch-config! dir "resources/conf/dev/config.edn" (:config-snippet module))
              (patch-config! dir "resources/conf/test/config.edn" (:test-config-snippet module))
              (patch-agents-md! dir module)
              (println (str "\n" module-name " added"))
              ;; Module-specific next steps, from the catalogue rather than
              ;; special-cased here, so any module can carry them.
              (when-let [lines (seq (:post-install module))]
                (println)
                (doseq [line lines] (println (str "  " line))))
              (println (str "\nDocs: " (:docs-url module))))))))))
