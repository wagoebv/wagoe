(ns wagoe.cli.add
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [wagoe.cli.catalogue :as cat]
            [wagoe.cli.templates :as templates]))

;; ─── Project detection ────────────────────────────────────────────────────────

(defn boundary-project?
  "True if dir contains a deps.edn referencing org.boundary-app."
  [dir]
  (let [f (io/file dir "deps.edn")]
    (and (.exists f)
         (str/includes? (slurp f) "org.boundary-app"))))

;; ─── deps.edn patching ───────────────────────────────────────────────────────

(defn patch-deps!
  "Add clojars coordinate to deps.edn if not already present."
  [dir {:keys [clojars version]}]
  (let [f         (io/file dir "deps.edn")
        content   (slurp f)
        coord-str (str clojars)]
    (when-not (str/includes? content coord-str)
      (let [new-content (str/replace-first
                         content
                         #"(:deps\s*\{)"
                         (str "$1\n         " coord-str " {:mvn/version \"" version "\"}\n         "))]
        (spit f new-content)))))

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
        (if-not (str/includes? content "<!-- boundary:available-modules -->")
          (println "  Warning: AGENTS.md sentinel comments not found — skipping AGENTS.md update")
          (let [without-row  (templates/update-block
                              content "boundary:available-modules"
                              #(str/replace % (templates/module-row-pattern name) ""))
                install-line (str "- " name " — [docs](" docs-url ")\n")
                with-install (str/replace without-row
                                          "<!-- /boundary:installed-modules -->"
                                          (str install-line "<!-- /boundary:installed-modules -->"))]
            (spit f with-install)))))))

;; ─── Main ────────────────────────────────────────────────────────────────────

(defn -main [args]
  (let [[module-name] args]
    (when-not module-name
      (println "Usage: boundary add <module>")
      (println "Run 'boundary list modules' to see available modules.")
      (System/exit 1))
    (let [dir (System/getProperty "user.dir")]
      (when-not (boundary-project? dir)
        (println "Error: No boundary project found in current directory.")
        (println "Run 'boundary new <name>' first, then cd into the project.")
        (System/exit 1))
      (let [module (cat/find-module module-name)]
        (when-not module
          (println (str "Error: Unknown module '" module-name "'."))
          (println "Available modules:")
          (doseq [m (cat/optional-modules)]
            (println (str "  " (:name m))))
          (System/exit 1))
        (let [deps-content (slurp (io/file dir "deps.edn"))
              coord-str    (str (:clojars module))
              dep-present? (str/includes? deps-content coord-str)
              existing-ver (when dep-present?
                             (second (re-find
                                      (re-pattern (str (java.util.regex.Pattern/quote coord-str)
                                                       "[\\s\\S]*?:mvn/version\\s+\"([^\"]+)\""))
                                      deps-content)))
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
                             ;; false positives from pre-installed deps (e.g. boundary-external).
                             (let [agents-f (io/file dir "AGENTS.md")]
                               (if (.exists agents-f)
                                 (let [content          (slurp agents-f)
                                       installed-start  (str/index-of content "<!-- boundary:installed-modules -->")
                                       installed-end    (str/index-of content "<!-- /boundary:installed-modules -->")]
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
              (patch-deps! dir module)
              (patch-config! dir "resources/conf/dev/config.edn" (:config-snippet module))
              (patch-config! dir "resources/conf/test/config.edn" (:test-config-snippet module))
              (patch-agents-md! dir module)
              (println (str "\n" module-name " added"))
              (println (str "\nDocs: " (:docs-url module))))))))))
