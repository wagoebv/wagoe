#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/integrate.clj
;;
;; Module Integration — wire a scaffolded module into the running system.
;;
;; Usage (via bb.edn task):
;;   bb scaffold integrate product                 # Guide integration of "product"
;;   bb scaffold integrate product --base-ns myapp # Module under myapp.product.*
;;
;; It writes the module's Integrant config into resources/conf/{dev,test}/config.edn
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
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; =============================================================================
;; Discovery
;; =============================================================================

(defn- root-dir [] (System/getProperty "user.dir"))

(defn base-ns-path
  "Filesystem path segment for a base namespace: dots become slashes."
  [base-ns]
  (str/replace (or base-ns "wagoe") "." "/"))

(defn discover-module
  "Discover a scaffolded module under `<root>/src/<base-ns-path>/<module>/` —
   where `bb scaffold generate` writes it. `root` defaults to the project root;
   it is injectable for tests. Returns a map describing the module, or nil if it
   does not exist there."
  ([module-name base-ns] (discover-module module-name base-ns (root-dir)))
  ([module-name base-ns root]
   (let [bnp      (base-ns-path base-ns)
         src-dir  (io/file root "src" bnp module-name)
         test-dir (io/file root "test" bnp module-name)]
     (when (.exists src-dir)
       {:name        module-name
        :base-ns     (or base-ns "wagoe")
        :module-ns   (str (or base-ns "wagoe") "." module-name)
        :src-path    (str "src/" bnp "/" module-name)
        :test-path   (str "test/" bnp "/" module-name)
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
  (let [ns-name (str/replace module-name "_" "-")]
    (str "  ;; " (str/capitalize ns-name) " module (bb scaffold integrate)\n"
         "  :wagoe/" ns-name "\n"
         "  {:enabled? true"
         (when has-routes?
           (str "\n   :base-path \"/api/" ns-name "\""))
         "}")))

;; =============================================================================
;; Orchestration
;; =============================================================================

(defn integrate-module
  "Guide integration of a scaffolded module. `opts` may carry :base-ns and
   :dry-run?."
  [module-name {:keys [base-ns dry-run?]}]
  (let [module (discover-module module-name base-ns)]
    (when-not module
      (println (red (str "Module not found: src/" (base-ns-path base-ns) "/" module-name "/")))
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

    (when-not (:has-wiring? module)
      (println (red "✗") (str "No " (cyan "shell/module_wiring.clj") " in this module."))
      (println (dim "  Regenerate it with `bb scaffold generate` — without it the config"))
      (println (dim "  key resolves to nothing and the boot fails."))
      (System/exit 1))

    (let [snippet (generate-config-snippet module-name (:has-routes? module))
          key-str (str ":wagoe/" (str/replace module-name "_" "-"))]

      (println (bold (if dry-run? "Would write:" "Writing:")))
      (println)
      (println (dim snippet))
      (println)

      (doseq [env ["dev" "test"]]
        (let [path   (str (root-dir) "/resources/conf/" env "/config.edn")
              result (config-edn/inject-key! path key-str (str "\n" snippet "\n")
                                             {:dry-run? dry-run?})]
          (println (str "  " (case result
                               :written           (str (green "✓") " " (if dry-run? "would add to" "added to"))
                               :already-present   (str (green "✓") " already in")
                               :no-active-section (str (red "✗") " no :active section in")
                               :no-file           (str (dim "–") " not found:"))
                        " " (cyan (str "resources/conf/" env "/config.edn"))))))

      (println)
      (if dry-run?
        (println (dim "Nothing written (--dry-run)."))
        (do
          (println (str "Next: " (cyan "clojure -M:test") " then " (cyan "(go)")
                        " — " (dim (str "GET " (or (:base-path module) (str "/api/" module-name))))))
          (println (dim "  The wiring namespace needs no require: ig-config loads it by convention.")))))))

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
  (println "  3. Writes :wagoe/<module> into resources/conf/{dev,test}/config.edn")
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
