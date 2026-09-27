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

(defn- config-key-of [snippet]
  (second (re-find #":(\S+)" snippet)))

(defn patch-config!
  "Inject snippet into the :active map of a config file unless its key is there.
   Returns :added, :present, or :no-active when there is no :active map to
   write into."
  [dir relative-path snippet]
  (let [f          (io/file dir relative-path)
        content    (slurp f)
        config-key (config-key-of snippet)]
    (if (str/includes? content (str ":" config-key))
      :present
      (let [active-idx (str/index-of content ":active")
            open-idx   (when active-idx (str/index-of content "{" (+ active-idx 7)))
            close-idx  (when open-idx
                         (loop [i (inc open-idx) depth 1]
                           (cond
                             (>= i (count content)) nil
                             (zero? depth)          (dec i)
                             :else
                             (let [c (nth content i)]
                               (recur (inc i) (case c \{ (inc depth) \} (dec depth) depth))))))]
        (if close-idx
          (do (spit f (str (subs content 0 close-idx)
                           "\n" snippet
                           (subs content close-idx)))
              :added)
          :no-active)))))

(defn- snippet-for
  "What `module` writes into profile `env`. Dev may differ from prod: events
   runs in memory there, with no Redis to start (BOU-564)."
  [{:keys [config-snippet dev-config-snippet test-config-snippet]} env]
  (case env
    "test" test-config-snippet
    "dev"  (or dev-config-snippet config-snippet)
    config-snippet))

(defn project-name
  "The project's name: dev's :wagoe/settings :name less its profile suffix,
   as `bb setup` reads it, else the directory's name."
  [dir]
  (or (try
        (some-> (get-in (edn/read-string {:default (fn [_ v] v)}
                                         (slurp (io/file dir "resources/conf/dev/config.edn")))
                        [:active :wagoe/settings :name])
                (str/replace #"-(dev|development|test)$" "")
                not-empty)
        (catch Exception _ nil))
      (.getName (.getAbsoluteFile (io/file dir)))))

(defn- target-profiles
  "The existing profiles `module` belongs in, sorted, each with its snippet. A
   dev-scoped module goes in dev only; no profile is ever created."
  [dir {:keys [scope] :as module}]
  (for [env   (sort (.list (io/file dir "resources/conf")))
        :when (and (.exists (io/file dir "resources/conf" env "config.edn"))
                   (or (not= :dev scope) (= "dev" env)))
        :let  [snippet (some-> (snippet-for module env)
                               (str/replace "{{project-name}}" (project-name dir)))]
        :when (seq snippet)]
    [env snippet]))

(defn patch-configs!
  "Patch every existing resources/conf/<profile>/config.edn. Returns
   [[env result]], result as `patch-config!` returns it. Only dev and test
   were written, so a module was missing under WAG_ENV=prod (BOU-529)."
  [dir module]
  (vec (for [[env snippet] (target-profiles dir module)]
         [env (patch-config! dir (str "resources/conf/" env "/config.edn") snippet)])))

(defn installed?
  "Whether `module` needs nothing more: its dep is there and every profile it
   belongs in has its key. Dev alone was checked, so a prod created later
   never got a module added before it (BOU-564)."
  [dir module dep-present?]
  (and dep-present?
       (every? (fn [[env snippet]]
                 (str/includes? (slurp (io/file dir "resources/conf" env "config.edn"))
                                (str ":" (config-key-of snippet))))
               (target-profiles dir module))))

(defn patch-env-example!
  "Append to .env.example each of the module's :prod-env-vars it lacks, once
   `results` show a profile other than dev and test runs the module. Lines
   already there are never changed. Returns the variables added."
  [dir {:keys [prod-env-vars]} results]
  (let [f (io/file dir ".env.example")]
    (when (and (seq prod-env-vars) (.exists f)
               (some (fn [[env r]] (and (not (#{"dev" "test"} env)) (#{:added :present} r)))
                     results))
      (let [text    (slurp f)
            have    (set (map second (re-seq #"(?m)^\s*([A-Z][A-Z0-9_]*)=" text)))
            missing (remove have prod-env-vars)]
        (when (seq missing)
          (spit f (str text
                       (when-not (or (str/blank? text) (str/ends-with? text "\n")) "\n")
                       (when-not (str/blank? text) "\n")
                       (str/join "\n" (map #(str % "=") missing)) "\n"))
          (vec missing))))))

;; ─── AGENTS.md module blocks ──────────────────────────────────────────────

(defn module-states
  "Each catalogue module with where the project has it: :enabled (in deps.edn
   and configured, or nothing to configure), :configurable (in deps.edn, config
   key missing) or :absent. Core modules count as enabled once present."
  [dir]
  (let [deps (slurp (io/file dir "deps.edn"))]
    (for [m (:modules (cat/load-catalogue))
          :let [dep (dep-coords deps (:clojars m) (:scope m))]]
      [m (cond
           (or (nil? dep) (= :unreadable dep))            :absent
           (or (= :core (:category m)) (installed? dir m true)) :enabled
           :else                                          :configurable)])))

(defn- module-table [modules]
  (str "| Module | Description | Command |\n"
       "|--------|-------------|---------|\n"
       (apply str (for [{:keys [name description]} modules]
                    (str "| " name " | " description " | `wagoe add " name "` |\n")))))

(defn render-module-blocks
  "`content` with the available- and installed-modules blocks rendered from
   `states`, as `module-states` returns them."
  [content states]
  (let [of        (fn [s] (map first (filter #(= s (second %)) states)))
        available (str "\n"
                       (when-let [ms (seq (of :configurable))]
                         (str "In deps.edn but not switched on. `wagoe add <module>` writes its config key.\n\n"
                              (module-table ms) "\n"))
                       (when-let [ms (seq (of :absent))]
                         (str "Not in deps.edn. `wagoe add <module>` adds the dependency and its config.\n\n"
                              (module-table ms))))
        installed (str "\n## Installed Modules\n\n"
                       (apply str (for [{:keys [name clojars docs-url]} (of :enabled)]
                                    (str "- " name " (`" clojars "`) — [docs](" docs-url ")\n"))))]
    (-> content
        (templates/replace-block "wagoe:available-modules" available)
        (templates/replace-block "wagoe:installed-modules" installed))))

(defn sync-agents-md!
  "Rewrite AGENTS.md's module blocks to match deps.edn and config.edn."
  [dir]
  (let [f (io/file dir "AGENTS.md")]
    (when (.exists f)
      (let [content (slurp f)]
        (if-not (templates/block-content content "wagoe:available-modules")
          (println "  Warning: AGENTS.md sentinel comments not found — skipping AGENTS.md update")
          (let [synced (render-module-blocks content (module-states dir))]
            (when (not= synced content)
              (spit f synced))))))))

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
              ;; A module is "installed" when its dep is present AND its config key is
              ;; in every profile it belongs in. Requiring dep-present? prevents false
              ;; positives when two modules share a config key (e.g. email and external
              ;; both use :wagoe.external/smtp).
              wired?       (installed? dir module dep-present?)]
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
              (let [results (patch-configs! dir module)]
                (doseq [[env result] results]
                  (println (str "  " env ": " (case result
                                                 :added     "added to config.edn"
                                                 :present   "already in config.edn"
                                                 :no-active "no :active map in config.edn, nothing written"))))
                (when-let [vs (patch-env-example! dir module results)]
                  (println (str "  .env.example: added " (str/join ", " vs)))))
              (sync-agents-md! dir)
              (println (str "\n" module-name " added"))
              ;; Said at install time, not left on a page the user reads later:
              ;; an incubating library is published and usable but outside the
              ;; breaking-change guarantee (BOU-432).
              (when (= :incubating (:tier module))
                (println (str "  Tier: incubating — usable and published, but its API may "
                              "break in a minor release.\n"
                              "        https://wagoe.org/docs/stability.html#tiers")))
              ;; Module-specific next steps, from the catalogue rather than
              ;; special-cased here, so any module can carry them.
              (when-let [lines (seq (:post-install module))]
                (println)
                (doseq [line lines] (println (str "  " line))))
              (println (str "\nDocs: " (:docs-url module))))))))))
