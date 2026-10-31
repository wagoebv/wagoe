(ns wagoe.cli.add
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [wagoe.cli.catalogue :as cat]
            [wagoe.cli.config-edn :as config-edn]
            [wagoe.cli.profile :as profile]
            [wagoe.cli.templates :as templates]))

;; ─── Project detection ────────────────────────────────────────────────────────

(defn wagoe-project?
  "True if dir contains a deps.edn depending on a Wagoe lib. One that does not
   read counts when it names one, so `patch-deps!` gets to say it is unreadable."
  [dir]
  (let [f (io/file dir "deps.edn")]
    (boolean
     (or (seq (cat/wagoe-deps f))
         (and (.exists f)
              (let [text (slurp f)]
                (and (str/includes? text "wagoe/")
                     (try (cat/read-clj text) false (catch Exception _ true)))))))))

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
   deps.edn with a duplicate key does not load at all. A local checkout's
   `wagoe/<lib> {:local/root ..}` counts as the module (BOU-662)."
  [content clojars scope]
  (try
    (let [parsed (cat/read-clj content)
          short  (cat/wagoe-lib-name clojars nil)]
      (if short
        (some (fn [[lib coord]]
                (when (= short (cat/wagoe-lib-name lib coord)) coord))
              (get-in parsed (if (= :dev scope) [:aliases :repl :extra-deps] [:deps])))
        (get-in parsed (if (= :dev scope) [:aliases :repl :extra-deps clojars] [:deps clojars]))))
    (catch Exception _ :unreadable)))

(defn dep-entry
  "The deps.edn entry to write for `clojars`. A project on a local checkout
   gets the module from the same checkout, named like its other Wagoe deps,
   rather than a Maven pin (BOU-664). Given `dir`, only when the checkout has
   that lib: a :local/root that is not there stops deps.edn resolving at all."
  ([content clojars version] (dep-entry content clojars version nil))
  ([content clojars version dir]
   (let [short   (cat/wagoe-lib-name clojars nil)
         exists? (fn [path]
                   (let [p (io/file path)]
                     (or (nil? dir) (.isDirectory (if (.isAbsolute p) p (io/file dir path))))))]
     (or (when short
           (some (fn [[lib coord]]
                   (when-let [n (cat/wagoe-lib-name lib coord)]
                     (when-let [[_ prefix] (some->> (:local/root coord)
                                                    (re-matches (re-pattern (str "(.*/|)\\Q" n "\\E/?"))))]
                       (when (exists? (str prefix short))
                         (str (if (= "wagoe" (namespace lib)) (symbol "wagoe" short) clojars)
                              " {:local/root \"" prefix short "\"}")))))
                 (:deps (cat/read-clj content))))
         (str clojars " {:mvn/version \"" version "\"}")))))

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
        existing  (dep-coords content clojars scope)
        entry     (when-not existing (dep-entry content clojars version dir))]
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

(defn- snippet-entries
  "The snippet's entries as {:k key :text text}. Read by the config editor, so
   reports' opening comment, which names `(ig/ref :wagoe/reports)`, is not
   taken for a key. One it cannot read is a single entry with no key, which is
   never found under :active, so it is refused rather than reported present."
  [snippet]
  (if-let [es (config-edn/snippet-entries snippet)]
    (for [{:keys [key text]} es]
      {:k (keyword (subs key 1)) :text text})
    [{:k nil :text snippet}]))

(defn- active-keys
  "The keys of `config`'s :active map, or nil when it has none."
  [config]
  (let [active (:active config)]
    (when (map? active) (set (keys active)))))

(defn- absent
  "The `entries` whose key is not in the :active of `profile`, as `profile/profile`
   returns it. A key parked under :inactive does not count. Text that does
   not read as EDN falls back to the text editor's reading of :active."
  [{:keys [text config]} entries]
  (let [ks (active-keys @config)]
    (remove (fn [{:keys [k]}]
              (and k
                   (if ks
                     (contains? ks k)
                     (= :already-present (config-edn/key-status text (str k))))))
            entries)))

(def ^:private additions ":wagoe/additions")

(defn- own-file
  "Text for a profile that was only `#include target`: what it includes, with
   :wagoe/additions merged into :active. Aero's #merge is shallow, so :active
   is merged by itself; the include's other keys are referred to. Not
   :secret-sources, which must be a literal map."
  [included target]
  (str ";; All of " target ", with :wagoe/additions merged into its :active.\n"
       "{:wagoe/included #include " (pr-str target) "\n"
       " " additions "\n {\n }\n"
       " :active #merge [#ref [:wagoe/included :active] #ref [" additions "]]"
       (apply str (for [k (sort-by pr-str (remove #{:active :secret-sources} (keys included)))]
                    (str "\n " (pr-str k) " #ref [:wagoe/included " (pr-str k) "]")))
       "}\n"))

(defn patch-config!
  "Inject each of the snippet's keys missing from the :active map of a config
   file, after the last entry, at that entry's column, with the closing brace
   right after them. Returns {:result r :added ks :present ks}, r being
   :added, :present when every key is there, :no-active when there is no
   :active map to write into, or :not-written when the result would not have
   every key under :active.

   A file that is only an `#include` gets its own: it keeps the include and
   adds the keys to :wagoe/additions, merged into :active. `:own-file` is then
   what it includes (BOU-910)."
  [dir relative-path snippet]
  (let [f        (io/file dir relative-path)
        content  (slurp f)
        current  (profile/profile dir relative-path content)
        entries  (snippet-entries snippet)
        missing  (absent current entries)
        target   (:include current)
        base     (if target (own-file @(:config current) target) content)
        section  (if (config-edn/section base additions) additions ":active")
        result   (fn [r] (cond-> {:result  r
                                  :added   (if (= :added r) (mapv :k missing) [])
                                  :present (mapv :k (remove (set missing) entries))}
                           (and target (= :added r)) (assoc :own-file target)))]
    (if (empty? missing)
      (result :present)
      (let [out    (reduce #(config-edn/append-entry %1 section (:text %2)) base missing)
            out-ks (active-keys (profile/read-config dir relative-path out))]
        (cond
          ;; append-entry returns the text unchanged when there is no :active map.
          (= out base) (result :no-active)
          ;; Checked by reading, not trusted: a key written into the wrong map
          ;; boots nothing, and the next run writes it again. Text that stopped
          ;; reading as EDN is refused; text that never did must show every key
          ;; among the editor's entries, not merely somewhere in :active's text.
          (if out-ks
            (every? #(contains? out-ks (:k %)) entries)
            (and (nil? @(:config current))
                 (let [written (set (map :key (config-edn/entries out ":active")))]
                   (every? #(written (str (:k %))) entries))))
          (do (spit f out) (result :added))
          :else (result :not-written))))))

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
  ([dir] (project-name dir (profile/reader dir)))
  ([dir read]
   (or (some-> (get-in @(:config (read (profile/profile-path dir "dev" read))) [:active :wagoe/settings :name])
               (#(when (string? %) %))
               (str/replace #"-(dev|development|test)$" "")
               not-empty)
       (.getName (.getAbsoluteFile (io/file dir))))))

(defn- target-profiles
  "The existing profiles `module` belongs in, sorted, each with its snippet and
   the config file to read: the one it includes when the profile is only an
   `#include`. A profile including another profile is that profile, so it is
   left out. A dev-scoped module goes in dev only, and in dev's own file,
   since what dev includes may be prod's. No profile is ever created."
  ([dir module] (target-profiles dir module (profile/reader dir)))
  ([dir {:keys [scope] :as module} read]
   (let [envs  (filter #(.exists (io/file dir (profile/profile-file %)))
                       (sort (.list (io/file dir "resources/conf"))))
         own   (set (map profile/profile-file envs))
         pname (delay (project-name dir read))]
     (for [env   envs
           :let  [path (if (= :dev scope) (profile/profile-file env) (profile/profile-path dir env read))]
           :when (and (or (not= :dev scope) (= "dev" env))
                      (or (= path (profile/profile-file env)) (not (own path))))
           :let  [snippet (when-let [s (snippet-for module env)]
                            (cond-> s
                              (str/includes? s "{{project-name}}") (str/replace "{{project-name}}" @pname)))]
           :when (seq snippet)]
       [env snippet path]))))

(def ^:private database-keys
  [:wagoe/postgresql :wagoe/sqlite :wagoe/h2 :wagoe/mysql])

(defn database-warnings
  "One line per profile `module` goes in whose active database it cannot boot
   on, per the catalogue's :databases. H2 passes where the module's snippet
   says `:allow-h2? true`, as tenant's test snippet does (BOU-576)."
  ([dir module] (database-warnings dir module (profile/reader dir)))
  ([dir {:keys [databases] :as module} read]
   (when (seq databases)
     (vec (for [[env snippet path] (target-profiles dir module read)
                :let  [active (:active @(:config (read path)))
                       db     (first (filter #(contains? active %) database-keys))]
                :when (and db
                           (not (contains? databases (keyword (name db))))
                           (not (and (= :wagoe/h2 db) (str/includes? snippet ":allow-h2? true"))))]
            (str env " runs on " db ", and " (:name module) " needs "
                 (str/join " or " (map #(str ":wagoe/" (name %)) (sort databases)))
                 ": the app will refuse to boot in " env " until " path
                 " switches its :active database."))))))

(defn- write-config-files!
  "Write the module's :config-files beside `path`, and beside `env`'s own
   config.edn, which the snippet may land in instead. A file already there is
   the app's, and is left alone. First, so the patched config reads."
  [dir {:keys [config-files]} env path]
  (doseq [parent (distinct (map #(.getParent (io/file %)) [path (profile/profile-file env)]))
          [rel tmpl] config-files
          :let [f (io/file dir parent rel)]
          :when (not (.exists f))]
    (io/make-parents f)
    (spit f (templates/read-template tmpl))))

(defn patch-configs!
  "Patch every existing resources/conf/<profile>/config.edn. Returns
   [[env result]], result as `patch-config!` returns it. Only dev and test
   were written, so a module was missing under WAG_ENV=prod (BOU-529)."
  [dir module]
  (vec (for [[env snippet path] (target-profiles dir module)]
         (do (write-config-files! dir module env path)
             [env (patch-config! dir path snippet)]))))

(defn installed?
  "Whether `module` needs nothing more: its dep is there and every profile it
   belongs in has its key. Dev alone was checked, so a prod created later
   never got a module added before it (BOU-564)."
  ([dir module dep-present?] (installed? dir module dep-present? (profile/reader dir)))
  ([dir module dep-present? read]
   (and dep-present?
        (every? (fn [[_ snippet path]]
                  (empty? (absent (read path) (snippet-entries snippet))))
                (target-profiles dir module read)))))

(defn patch-env-example!
  "Append to .env.example each of the module's :prod-env-vars it lacks, once
   `results` show a profile other than dev and test runs the module. Lines
   already there are never changed. Returns the variables added."
  [dir {:keys [prod-env-vars]} results]
  (let [f (io/file dir ".env.example")]
    (when (and (seq prod-env-vars) (.exists f)
               (some (fn [[env r]] (and (not (#{"dev" "test"} env)) (#{:added :present} (:result r))))
                     results))
      (let [text    (slurp f)
            have    (set (map second (re-seq #"(?m)^\s*(?:export\s+)?([A-Z][A-Z0-9_]*)=" text)))
            missing (remove have prod-env-vars)]
        (when (seq missing)
          (spit f (str text
                       (when-not (or (str/blank? text) (str/ends-with? text "\n")) "\n")
                       (when-not (str/blank? text) "\n")
                       (str/join "\n" (map #(str "export " % "=") missing)) "\n"))
          (vec missing))))))

;; ─── AGENTS.md module blocks ──────────────────────────────────────────────

(defn- extra-modules
  "The keys in :extra-modules of the project's system_config.clj files, where
   projects generated before BOU-667 switch user on."
  [dir]
  (let [src (io/file dir "src")]
    (set (for [f     (when (.isDirectory src) (file-seq src))
               :when (= "system_config.clj" (.getName ^java.io.File f))
               [_ ks] (re-seq #":extra-modules\s+#\{([^}]*)\}" (slurp f))
               k     (re-seq #":[\w.-]+/[\w.-]+" ks)]
           k))))

(defn- switched-on?
  "Whether `module-key` is in dev's :active or in the code's :extra-modules."
  [module-key read extra]
  (or (contains? extra (str module-key))
      ;; Dev's own file: it reads what it includes, and its :wagoe/additions.
      (let [dev (read (profile/profile-file "dev"))]
        (and (:text dev) (empty? (absent dev [{:k module-key}]))))))

(defn module-states
  "Each catalogue module with where the project has it: :enabled, :configurable
   (in deps.edn, not switched on) or :absent. A module with a :module-key is on
   when that key is in config or :extra-modules; the other core modules and the
   :always-on ones are wired whatever config says; the rest need their config
   key in every profile."
  [dir]
  (let [deps  (slurp (io/file dir "deps.edn"))
        read  (profile/reader dir)
        extra (delay (extra-modules dir))]
    (for [m (:modules (cat/load-catalogue))
          :let [dep (dep-coords deps (:clojars m) (:scope m))]]
      [m (cond
           (or (nil? dep) (= :unreadable dep)) :absent
           (:module-key m)                     (if (switched-on? (:module-key m) read @extra) :enabled :configurable)
           (or (= :core (:category m))
               (:always-on m)
               (installed? dir m true read))   :enabled
           :else                               :configurable)])))

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
              ;; positives when two modules share a config key.
              ;; Nothing is written before patch-configs!, so one reader serves both.
              read         (profile/reader dir)
              wired?       (installed? dir module dep-present? read)]
          (cond
            ;; A core module's version moves with the rest of Wagoe, and
            ;; switching it on touches config only (BOU-667).
            (and dep-present? existing-ver (not= existing-ver (:version module))
                 (not (:module-key module)))
            (do (println (str "Warning: " module-name " is already in deps.edn at version " existing-ver
                              " (catalogue version: " (:version module) ")."))
                (println "Resolve the version conflict manually — no changes made."))

            wired?
            (println (str "Module '" module-name "' is already installed."))

            :else
            (do
              ;; Before anything is written, so the boot refusal is no surprise.
              (when-let [lines (seq (database-warnings dir module read))]
                (println (str "Warning: " module-name " runs on PostgreSQL only. Installing anyway."))
                (doseq [line lines] (println (str "  " line)))
                (println))
              (println (str "Adding " module-name "..."))
              ;; Every outcome says what happened to deps.edn. "added" over an
              ;; untouched file is how `wagoe add ai` looked while doing
              ;; nothing, and a fresh project already ships devtools in :repl,
              ;; so the no-op branch is the common one for it.
              (let [by-hand (str "  Add "
                                 (try (dep-entry deps-content (:clojars module) (:version module) dir)
                                      (catch Exception _
                                        (str (:clojars module) " {:mvn/version \"" (:version module) "\"}")))
                                 " by hand.")]
                (case (patch-deps! dir module)
                  :repl-alias         (println "  deps.edn: added to the :repl alias — dev-only, not on the production classpath")
                  :deps               (println "  deps.edn: added to :deps")
                  :no-repl-extra-deps (do (println "  deps.edn: no :extra-deps in the :repl alias, so nothing was written there.")
                                          (println by-hand))
                  :unreadable         (do (println "  deps.edn: could not be read as EDN, so nothing was written.")
                                          (println by-hand))
                  (println (str "  deps.edn: unchanged — " (:clojars module) " is already there"))))
              (let [results (patch-configs! dir module)]
                (doseq [[env {:keys [result added present own-file]}] results]
                  (println (str "  " env ": " (case result
                                                :added     (str "added " (str/join ", " added) " to config.edn"
                                                                (when (seq present)
                                                                  (str "; " (str/join ", " present) " already there")))
                                                :present   "already in config.edn"
                                                :no-active "no :active map in config.edn, nothing written"
                                                :not-written "could not place it under :active, nothing written; add it by hand")))
                  (when own-file
                    (println (str "  " env " now has its own config.edn: it still includes " own-file
                                  ", and adds " (str/join ", " added) " under :wagoe/additions."))))
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
