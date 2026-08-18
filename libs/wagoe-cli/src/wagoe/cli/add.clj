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
  "Coordinate of `clojars` where the module belongs, or `:unreadable`, or nil.

   Where matters, and a substring search over the file gets it wrong. The
   generated deps.edn lists wagoe-devtools, wagoe-ai, wagoe-scaffolder,
   wagoe-tools and wagoe-jobs inside the `:mcp` alias, which is a launcher for
   the MCP server and not the app's classpath. `wagoe add ai` read the file as
   text, found the name in that alias, and treated the module as installed —
   printing success and writing nothing.

   deps.edn is plain EDN, so this reads it. `:unreadable` when it will not
   parse: writing into a file we cannot read risks a duplicate key, and a
   deps.edn with a duplicate key does not load at all."
  [content clojars scope]
  (try
    (let [parsed (edn/read-string content)]
      (if (= :dev scope)
        (get-in parsed [:aliases :repl :extra-deps clojars])
        (get-in parsed [:deps clojars])))
    (catch Exception _ :unreadable)))

(defn- code-only
  "`text` with strings and comments blanked, positions and newlines intact.

   Brace counting without this is not brace counting: deps.edn is hand-edited
   and the generated one carries `;;` comments above half its aliases."
  [text]
  (let [n (count text)]
    (loop [i 0, state :code, out (transient [])]
      (if (>= i n)
        (apply str (persistent! out))
        (let [c (nth text i)
              keep (fn [ch] (conj! out ch))
              blank (fn [] (conj! out (if (= c \newline) \newline \space)))]
          (case state
            :code    (cond
                       (= c \") (recur (inc i) :string (blank))
                       (= c \;) (recur (inc i) :comment (blank))
                       :else    (recur (inc i) :code (keep c)))
            :string  (cond
                       (= c \\) (recur (+ i 2) :string (-> (blank) (conj! \space)))
                       (= c \") (recur (inc i) :code (blank))
                       :else    (recur (inc i) :string (blank)))
            :comment (if (= c \newline)
                       (recur (inc i) :code (keep c))
                       (recur (inc i) :comment (blank)))))))))

(defn- map-extent
  "[open close] of the map opening at or after `from` in `code`, or nil."
  [code from]
  (when-let [open (str/index-of code "{" from)]
    (loop [i (inc open), depth 1]
      (cond
        (>= i (count code)) nil
        (zero? depth)       [open (dec i)]
        :else (recur (inc i) (case (nth code i) \{ (inc depth) \} (dec depth) depth))))))

(defn repl-extra-deps-insertion-point
  "Index just after the `:extra-deps {` of the `:repl` alias, or nil.

   Scoped to the alias, which a regex is not. `(?s):repl\\s*\\{.*?:extra-deps\\s*\\{`
   anchors on :repl and then takes the NEXT :extra-deps in the file, which need
   not be inside it — with a :repl alias that has none, devtools landed in
   whatever alias came next (:test in a generated project). It reported the
   :repl alias while writing to :test."
  [content]
  (let [code (code-only content)]
    (when-let [[aliases-open aliases-close] (some->> (str/index-of code ":aliases")
                                                     (map-extent code))]
      (when-let [repl-idx (loop [pos aliases-open]
                            (when-let [i (str/index-of code ":repl" pos)]
                              (cond
                                (>= i aliases-close) nil
                                ;; :repl-clj and :repl/foo are other aliases.
                                (re-matches #"[A-Za-z0-9*+!?<>=_/-]" (str (nth code (+ i 5)))) (recur (+ i 5))
                                :else i)))]
        (when-let [[alias-open alias-close] (map-extent code repl-idx)]
          (let [ed (str/index-of code ":extra-deps" alias-open)]
            (when (and ed (< ed alias-close))
              (when-let [[open _] (map-extent code ed)]
                (inc open)))))))))

(defn patch-deps!
  "Add clojars coordinate to deps.edn if not already present.

   A module with `:scope :dev` goes into the `:repl` alias instead of `:deps`.
   devtools is the first: it pulls a dashboard and a Jetty adapter, and putting
   it in `:deps` would ship all of that in the uberjar. Returns `:deps`,
   `:repl-alias`, `:no-repl-extra-deps` or `:unreadable` (nothing written), or
   nil when the dep was already there."
  [dir {:keys [clojars version scope]}]
  (let [f         (io/file dir "deps.edn")
        content   (slurp f)
        coord-str (str clojars)
        entry     (str coord-str " {:mvn/version \"" version "\"}")
        existing  (dep-coords content clojars scope)]
    (cond
      (= :unreadable existing) :unreadable
      existing                 nil

      (= :dev scope)
      (if-let [at (repl-extra-deps-insertion-point content)]
        (do (spit f (str (subs content 0 at)
                         "\n                          " entry
                         "\n                         "
                         (subs content at)))
            :repl-alias)
        :no-repl-extra-deps)

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
              ;; Every outcome says what happened to deps.edn. "added" over an
              ;; untouched file is how `wagoe add ai` looked while doing
              ;; nothing, and a fresh project already ships devtools in :repl,
              ;; so the no-op branch is the common one for it.
              (let [by-hand (str "  Add " (:clojars module) " {:mvn/version \""
                                 (:version module) "\"} by hand.")]
                (case (patch-deps! dir module)
                  :repl-alias         (println "  deps.edn: added to the :repl alias — dev-only, not on the production classpath")
                  :deps               (println "  deps.edn: added to :deps")
                  :no-repl-extra-deps (do (println "  deps.edn: no :extra-deps in the :repl alias, so nothing was written there.")
                                          (println by-hand))
                  :unreadable         (do (println "  deps.edn: could not be read as EDN, so nothing was written.")
                                          (println by-hand))
                  (println (str "  deps.edn: unchanged — " (:clojars module) " is already there"))))
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
