(ns wagoe.devtools.core.guidance
  "Pure functions for the guidance engine.
   Renders contextual help messages, startup dashboards, and tips.
   No I/O, no logging — all pure data transformations."
  (:require [clojure.string]))

;; =============================================================================
;; Guidance levels
;; =============================================================================

(def levels
  "Available guidance levels in order of verbosity."
  #{:full :minimal :off})

(defn valid-level? [level]
  (contains? levels level))

;; =============================================================================
;; Startup dashboard rendering
;; =============================================================================

(defn- pad-right [s width]
  (let [s (str s)
        len (count s)]
    (if (>= len width)
      s
      (str s (apply str (repeat (- width len) " "))))))

(defn format-startup-dashboard
  "Renders the startup dashboard box as a string.
   `system-info` is a map with keys:
     :components  - number of running components
     :errors      - number of component errors
     :database    - database description string (e.g. \"PostgreSQL @ localhost:5432/mydb\")
     :web-url     - web URL (e.g. \"http://localhost:3000\")
     :admin-url   - admin URL or nil
     :nrepl-port  - nREPL port number
     :modules     - seq of active module name strings
     :guidance-level - current guidance level keyword"
  [{:keys [components errors database web-url admin-url
           nrepl-port modules guidance-level]
    :or   {components 0 errors 0 guidance-level :full}}]
  (let [width     53
        border-h  (apply str (repeat width "\u2500"))
        line      (fn [content]
                    (str "\u2502 " (pad-right content (- width 2)) " \u2502"))
        mod-str   (if (seq modules)
                    (str (clojure.string/join ", " (take 5 modules))
                         (when (> (count modules) 5)
                           (str " (+" (- (count modules) 5) " more)")))
                    "none")
        lines     (cond-> [(str "\u250C\u2500 Boundary Dev " (apply str (repeat (- width 15) "\u2500")) "\u2510")
                           (line (str "System:    running (" components " components, " errors " errors)"))
                           (when database (line (str "Database:  " database)))
                           (when web-url (line (str "Web:       " web-url)))
                           (when admin-url (line (str "Admin:     " admin-url)))
                           (when nrepl-port (line (str "nREPL:     port " nrepl-port)))
                           (line "")
                           (line (str "Modules:   " mod-str))
                           (line (str "Guidance:  " (name guidance-level)
                                      (when (= guidance-level :full)
                                        " (set :guidance-level :minimal to quiet this)")))]
                    true (conj (line ""))
                    true (conj (line "Try: (status) | (routes) | (modules) | (commands)"))
                    true (conj (str "\u2514" border-h "\u2518")))]
    (clojure.string/join "\n" (remove nil? lines))))

;; =============================================================================
;; Post-scaffold guidance
;; =============================================================================

(defn format-post-scaffold-guidance
  "Renders guidance shown after scaffolding a module.
   `module-name` is the module name string."
  [module-name]
  (str "\u2713 Module '" module-name "' generated at libs/" module-name "/\n"
       "\n"
       "Next steps:\n"
       "1. Review schema:  libs/" module-name "/src/boundary/" module-name "/schema.clj\n"
       "2. Wire module:    bb scaffold integrate " module-name "\n"
       "3. Add migration:  bb migrate create add-" module-name "-table\n"
       "4. Run tests:      clojure -M:test:db/h2 :" module-name))

;; =============================================================================
;; Contextual tips
;; =============================================================================

(def tips
  "Curated tips mapped to action contexts."
  {:scaffold  ["You can run (simulate :post \"/api/{module}\" {:body {...}}) to test your new endpoint from the REPL."
               "Use (schema :{module}/create) to see the Malli schema for your new entity."
               "Run (routes :{module}) to see all routes registered for your module."]
   :test      ["Use (watch :{module}) to auto-run tests when files change."
               "Run (test :{module} :unit) from the REPL to run only unit tests."]
   :migrate   ["Use bb db:status to see all pending and applied migrations."
               "Run (query :{table}) from the REPL to inspect your new table."]
   :start     ["Try (routes) to see all registered HTTP routes."
               "Use (simulate :get \"/api/...\") to test endpoints from the REPL."
               "Run (commands) to see all available REPL commands."]})

(defn pick-tip
  "Pick a non-repeating tip for the given context.
   `context` is a keyword from the tips map.
   `shown-tips` is a set of previously shown tip strings.
   Returns [tip-string updated-shown-tips] or nil if all tips shown."
  [context shown-tips]
  (when-let [available (seq (remove shown-tips (get tips context)))]
    (let [tip (first available)]
      [tip (conj shown-tips tip)])))

(defn format-tip
  "Format a tip string for display."
  [tip guidance-level]
  (when (= guidance-level :full)
    (str "\n\u2728 Tip: " tip
         "\n   (Set :guidance-level :minimal to see fewer tips)\n")))

;; =============================================================================
;; Command palette
;; =============================================================================

(def command-groups
  "All REPL commands grouped by category."
  {:system    [{:name "(go)"        :desc "Start the system"}
               {:name "(reset)"     :desc "Reload code and restart"}
               {:name "(halt)"      :desc "Stop the system"}
               {:name "(status)"    :desc "Full system health"}
               {:name "(config)"    :desc "View running config"}
               {:name "(config :k)" :desc "Drill into config section"}
               {:name "(modules)"   :desc "List active modules"}
               {:name "(routes)"    :desc "Show all HTTP routes"}
               {:name "(routes :m)" :desc "Filter routes by module"}
               {:name "(restart-component k)"  :desc "Hot-swap single component"}
               {:name "(defroute! m p fn)"     :desc "Add route at runtime"}
               {:name "(remove-route! m p)"    :desc "Remove dynamic route"}
               {:name "(dynamic-routes)"       :desc "List dynamic routes"}]
   :data      [{:name "(query :table)"         :desc "Quick SELECT (limit 20)"}
               {:name "(query :t {:where ..})" :desc "With conditions"}
               {:name "(count-rows :table)"    :desc "Count rows in table"}
               {:name "(schema s)"             :desc "Pretty-print Malli schema"}
               {:name "(schema-diff a b)"      :desc "Compare two schemas"}
               {:name "(validate s data)"      :desc "Validate against schema"}
               {:name "(generate s)"           :desc "Generate example from schema"}]
   :debug     [{:name "(simulate :get path)"   :desc "Simulate HTTP request"}
               {:name "(simulate :post p opts)" :desc "POST with body"}
               {:name "(fix!)"                  :desc "Auto-fix last error"}
               {:name "(recording :start)"      :desc "Start request capture"}
               {:name "(recording :list)"       :desc "List captured requests"}
               {:name "(recording :replay N)"   :desc "Replay a captured request"}
               {:name "(recording :diff M N)"   :desc "Diff two captured entries"}
               {:name "(tap-handler! k fn)"     :desc "Intercept handler"}
               {:name "(taps)"                  :desc "List active taps"}]
   :generate  [{:name "(scaffold! n opts)"             :desc "Generate module files"}
               {:name "(prototype! n spec)"           :desc "Scaffold + migrate + reset"}
               {:name "(new-feature! \"name\" \"desc\")" :desc "Full feature workflow"}]
   :ai        [{:name "(ai/review \"path\")"           :desc "AI code review"}
               {:name "(ai/test-ideas \"path\")"       :desc "Suggest missing tests"}
               {:name "(ai/refactor-fcis 'ns)"         :desc "FC/IS refactoring guide"}]
   :quality   [{:name "(test-module :mod)"     :desc "Run module tests"}
               {:name "(test-module :m :unit)" :desc "Run with tier filter"}
               {:name "(lint)"                 :desc "Run clj-kondo"}
               {:name "(check-all)"            :desc "Run all quality checks"}]
   :help      [{:name "(commands)"       :desc "Show this list"}
               {:name "(guide :topic)"   :desc "Boundary topic guide"}
               {:name "(guide :topics)"  :desc "List available topics"}
               {:name "(next-steps)"     :desc "What should you do next?"}
               {:name "(guidance lv)"    :desc "Set guidance level"}]})

(defn format-commands
  "Format the command palette as a string."
  []
  (let [format-group (fn [[group cmds]]
                       (str "  " (clojure.string/upper-case (name group)) ":\n"
                            (clojure.string/join
                             "\n"
                             (map (fn [{:keys [name desc]}]
                                    (str "    " (format "%-26s" name) " " desc))
                                  cmds))))]
    (str "Available REPL commands:\n\n"
         (clojure.string/join "\n\n" (map format-group command-groups))
         "\n")))
