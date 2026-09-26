#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/integrate.clj
;;
;; Module Integration — wire a scaffolded module into the running system.
;;
;; Usage (via bb.edn task):
;;   bb scaffold integrate product                 # Guide integration of "product"
;;   bb scaffold integrate product --base-ns myapp # Module under myapp.product.*
;;
;; It writes the module's Integrant config into every resources/conf/<profile>/config.edn
;; and reports what it did. `--dry-run` shows the same and writes nothing.
;;
;; It used to only print, while bb.edn.tmpl, the generated AGENTS.md and
;; `bb scaffold --help` all said it wired things up, and --dry-run was parsed and
;; never read (BOU-310).
;;
;; A module scaffolded by `bb scaffold generate` lands in
;; `src/<base-ns-path>/<module>/` (BOU-205). Because `src`/`test` are already on
;; the project's paths, the module is on the classpath and its tests are picked
;; up by the standard suites — so no deps.edn/tests.edn wiring is required. What
;; remains is registering the module's Integrant components, which this command
;; guides: it prints the config snippet and, when the module ships a
;; `shell/module_wiring.clj`, the require to add to the app's config namespace.

(ns wagoe.tools.integrate
  (:require [wagoe.tools.ansi :refer [bold green red cyan dim]]
            [wagoe.tools.config-edn :as config-edn]
            [wagoe.tools.project :as project]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; =============================================================================
;; Discovery
;; =============================================================================

(defn- root-dir [] (System/getProperty "user.dir"))

(defn base-ns-path
  "Filesystem path segment for a base namespace: dots become slashes, hyphens
   underscores — the rule Clojure uses to find a namespace's file.

   Defaults to the project's own namespace, which is where `bb scaffold
   generate` writes a module (BOU-360)."
  ([base-ns] (base-ns-path base-ns (project/base-ns)))
  ([base-ns default] (-> (or base-ns default)
                         (str/replace "." "/")
                         (str/replace "-" "_"))))

(defn module-dir-name
  "Directory a module's sources live in.

   `invoice-line-item` is the namespace segment; `invoice_line_item` is the
   directory. Looking for the hyphenated name meant integrate could not find a
   correctly-named module at all (BOU-447)."
  [module-name]
  (str/replace module-name "-" "_"))

(defn module-ns-name
  "Namespace segment for a module, given either spelling of its name."
  [module-name]
  (str/replace module-name "_" "-"))

(defn discover-module
  "Discover a scaffolded module under `<root>/src/<base-ns-path>/<module>/` —
   where `bb scaffold generate` writes it. `root` defaults to the project root;
   it is injectable for tests. Returns a map describing the module, or nil if it
   does not exist there."
  ([module-name base-ns] (discover-module module-name base-ns (root-dir)))
  ([module-name base-ns root]
   ;; The project's own namespace first, then wagoe: a module generated before
   ;; BOU-360 is still under wagoe.<module>, and integrating it must keep
   ;; working. When they are the same there is one place to look.
   (let [candidates (distinct [(or base-ns (project/base-ns root)) "wagoe"])
         dir-name   (module-dir-name module-name)
         ns-name    (module-ns-name module-name)
         found      (first (filter #(.exists (io/file root "src" (base-ns-path % "wagoe") dir-name))
                                   candidates))
         resolved   (or found (first candidates))
         bnp        (base-ns-path resolved "wagoe")
         src-dir    (io/file root "src" bnp dir-name)
         test-dir   (io/file root "test" bnp dir-name)]
     (when (.exists src-dir)
       {:name        module-name
        :base-ns     resolved
        :module-ns   (str resolved "." ns-name)
        :src-path    (str "src/" bnp "/" dir-name)
        :test-path   (str "test/" bnp "/" dir-name)
        :src-dir     (.getPath src-dir)
        :test-dir    (.getPath test-dir)
        :has-routes? (.exists (io/file src-dir "shell" "http.clj"))
        :has-wiring? (.exists (io/file src-dir "shell" "module_wiring.clj"))}))))

;; =============================================================================
;; Config snippet
;; =============================================================================

(defn generate-config-snippet
  "The module's entry for `resources/conf/<env>/config.edn`.

   Settings only. The Integrant components and the refs between them are built
   by `wagoe.platform.shell.modules/discover-module-config` from this one key
   (BOU-311) — an earlier version printed those too, which was right when you
   pasted it into `ig-config` and wrong now that it is written into config.edn,
   where `(ig/ref …)` is a list and `config` is a bare symbol."
  [module-name has-routes?]
  (let [ns-name (module-ns-name module-name)]
    (str "  ;; " (str/capitalize ns-name) " module (bb scaffold integrate)\n"
         "  :wagoe/" ns-name "\n"
         "  {:enabled? true"
         (when has-routes?
           (str "\n   :base-path \"/api/" ns-name "\""))
         "}")))

;; =============================================================================
;; Writing
;; =============================================================================

(def dev-only-keys
  "`wagoe.platform.shell.modules/dev-only-modules`, which throws when one of
   these is active outside :dev. A test keeps the two in step."
  #{":wagoe/dashboard"})

(defn profiles
  "The profile directories under `<root>/resources/conf`, sorted."
  [root]
  (->> (.listFiles (io/file root "resources" "conf"))
       (filter #(.isDirectory ^java.io.File %))
       (map #(.getName ^java.io.File %))
       sort))

(def ^:private ok-results #{:written :already-present :dev-only :no-file})

(defn blocking
  "The [env result] pairs that stop a write."
  [results]
  (remove (comp ok-results second) results))

(defn- inject-each [root key-str snippet dry-run?]
  (vec (for [env (profiles root)]
         [env (if (and (dev-only-keys key-str) (not= "dev" env))
                :dev-only
                (config-edn/inject-key! (str root "/resources/conf/" env "/config.edn")
                                        key-str (str "\n" snippet "\n")
                                        {:dry-run? dry-run?}))])))

(defn write-config!
  "Add the module's key to the config.edn of every profile under `root`, or to
   none: a profile that cannot take it blocks them all, since a key in some
   profiles and not others boots in one environment only (BOU-529). Returns
   [env result] pairs — the plan when nothing was written."
  [root key-str snippet {:keys [dry-run?]}]
  (let [plan (inject-each root key-str snippet true)]
    (if (or dry-run? (seq (blocking plan)))
      plan
      (inject-each root key-str snippet false))))

(defn blocked-message [blocked]
  (str "Nothing was written: "
       (str/join ", " (for [[env result] blocked]
                        (str env (case result
                                   :no-active-section      " has no :active section"
                                   :insert-would-unbalance " would be unbalanced by the insertion"))))
       "."))

;; =============================================================================
;; Orchestration
;; =============================================================================

(defn integrate-module
  "Guide integration of a scaffolded module. `opts` may carry :base-ns and
   :dry-run?."
  [module-name {:keys [base-ns dry-run?]}]
  (let [module (discover-module module-name base-ns)]
    (when-not module
      (println (red (str "Module not found: src/" (base-ns-path base-ns) "/"
                         (module-dir-name module-name) "/")))
      (println (dim (str "Run `bb scaffold generate --module-name " module-name
                         (when base-ns (str " --base-ns " base-ns)) " ...` first.")))
      (System/exit 1))

    (println)
    (println (bold (str "Wagoe Module Integration — " module-name)))
    (println)
    (println (str "Discovered: " (cyan (:src-path module))))
    (println (str "  Namespace: " (dim (:module-ns module)) ".*"))
    (println (str "  Tests:     " (dim (:test-path module))))
    (println (str "  HTTP:      " (if (:has-routes? module) (green "yes") (dim "no"))))
    (println (str "  Wiring:    " (if (:has-wiring? module) (green "yes") (dim "no"))))
    (println)

    ;; A src/ module is already on the classpath and covered by the standard
    ;; test suites — nothing to patch into deps.edn/tests.edn.
    (println (green "✓") "On the classpath — src/ and test/ are already on the project paths;")
    (println "  the module's tests run with" (cyan "clojure -M:test") "(no deps.edn/tests.edn changes).")
    (println)

    ;; Discovery resolves <project-ns>.<module>.shell.module-wiring and, for
    ;; projects generated before BOU-360, wagoe.<module>.… A module somewhere
    ;; else gets a config key that throws at boot. This used to refuse outright,
    ;; back when the project namespace was not one of the two.
    (let [known #{(project/base-ns) "wagoe"}]
      (when-not (known (:base-ns module))
        (println (red "!") (str "This module is under " (:base-ns module)
                                ", which module discovery does not look in."))
        (println (dim (str "  It resolves " (project/base-ns) ".<module>.shell.module-wiring")))
        (println (dim "  and wagoe.<module>.shell.module-wiring. Pass :base-ns to"))
        (println (dim "  system-config yourself, or regenerate without --base-ns."))
        (println)))

    (when-not (:has-wiring? module)
      (println (red "✗") (str "No " (cyan "shell/module_wiring.clj") " in this module."))
      (println (dim "  Regenerate it with `bb scaffold generate` — without it the config"))
      (println (dim "  key resolves to nothing and the boot fails."))
      (System/exit 1))

    (let [snippet (generate-config-snippet module-name (:has-routes? module))
          key-str (str ":wagoe/" (module-ns-name module-name))]

      (println (bold (if dry-run? "Would write:" "Writing:")))
      (println)
      (println (dim snippet))
      (println)

      (let [results (write-config! (root-dir) key-str snippet {:dry-run? dry-run?})
            blocked (seq (blocking results))]
        (doseq [[env result] results]
          (println (str "  " (case result
                               :written           (str (green "✓") " " (if (or dry-run? blocked) "would add to" "added to"))
                               :already-present   (str (green "✓") " already in")
                               :dev-only          (str (dim "–") " dev-only key, skipped")
                               :no-active-section (str (red "✗") " no :active section in")
                               :insert-would-unbalance (str (red "✗") " insertion would unbalance")
                               :no-file           (str (dim "–") " not found:"))
                        " " (cyan (str "resources/conf/" env "/config.edn")))))

        (println)
        (cond
          ;; A run that wrote nothing must not look like success — to a person
          ;; or to a CI wrapper reading $?.
          blocked
          (do (println (red (blocked-message blocked)))
              (System/exit 1))

          (every? #{:no-file :dev-only} (map second results))
          (do (println (red "No config files found — is this a Wagoe project?"))
              (System/exit 1))

          dry-run?
          (println (dim "Nothing written (--dry-run)."))

          :else
          (do
            (println (str "Next: " (cyan "clojure -M:test") " then " (cyan "(go)")))
            (when (:has-routes? module)
              (println (dim (str "  Routes are mounted under /api/v1 — see "
                                 (:module-ns module) ".shell.http for the paths."))))
            (println (dim "  No require to add: ig-config loads the wiring namespace by convention."))))))))

;; =============================================================================
;; Argument parsing
;; =============================================================================

(defn parse-args [args]
  (loop [[arg & more :as remaining] args
         opts {:dry-run? false :module nil :base-ns nil}]
    (cond
      (empty? remaining)         opts
      (#{"--help" "-h"} arg)     (assoc opts :help true)
      (= arg "--dry-run")        (recur more (assoc opts :dry-run? true))
      (= arg "--base-ns")        (recur (rest more) (assoc opts :base-ns (first more)))
      (nil? (:module opts))      (recur more (assoc opts :module arg))
      :else                      (recur more opts))))

(defn- print-help []
  (println (bold "bb scaffold integrate") " — wire a scaffolded module into the system")
  (println)
  (println "Usage:")
  (println "  bb scaffold integrate <module>              Write the config key")
  (println "  bb scaffold integrate <module> --dry-run    Show it, write nothing")
  (println "  bb scaffold integrate <module> --base-ns NS Module under NS.<module>.*")
  (println)
  (println "What it does:")
  (println "  1. Locates the module under src/<base-ns>/<module>/")
  (println "  2. Confirms it is on the classpath + covered by the test suites")
  (println "  3. Writes :wagoe/<module> into every resources/conf/<profile>/config.edn")
  (println)
  (println "Running it twice is a no-op — an existing key is left alone."))

;; =============================================================================
;; Main entry point
;; =============================================================================

(defn -main [& args]
  (let [opts (parse-args args)]
    (cond
      (:help opts)
      (print-help)

      (nil? (:module opts))
      (do (println (red "Module name required."))
          (println)
          (print-help)
          (System/exit 1))

      :else
      (integrate-module (:module opts) opts))))

;; Run when executed directly (not via bb.edn task)
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
