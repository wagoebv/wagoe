#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/integrate.clj
;;
;; Module Integration — wire a scaffolded module into the running system.
;;
;; Usage (via bb.edn task):
;;   bb scaffold integrate product                 # Guide integration of "product"
;;   bb scaffold integrate product --base-ns myapp # Module under myapp.product.*
;;
;; This command only reads + prints guidance; it never writes files.
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
  "An Integrant config template snippet for a new module."
  [module-name has-routes?]
  (let [ns-name (str/replace module-name "_" "-")]
    ;; All four keys, not just the aggregate. It used to print :wagoe/<module>
    ;; alone, which the generated wiring destructures for :routes and :service —
    ;; both nil, because nothing initialised the components they come from
    ;; (BOU-309).
    (str "  ;; " (str/capitalize ns-name) " module\n"
         "  :wagoe/" ns-name "-repository\n"
         "  {:ctx (ig/ref :wagoe/db-context)}\n\n"
         "  :wagoe/" ns-name "-service\n"
         "  {:repository (ig/ref :wagoe/" ns-name "-repository)}\n\n"
         (when has-routes?
           (str "  :wagoe/" ns-name "-routes\n"
                "  {:service (ig/ref :wagoe/" ns-name "-service)\n"
                "   :config  config}\n\n"))
         "  :wagoe/" ns-name "\n"
         "  {:enabled? true\n"
         "   :service  (ig/ref :wagoe/" ns-name "-service)"
         (when has-routes?
           (str "\n   :routes   (ig/ref :wagoe/" ns-name "-routes)"
                "\n   :base-path \"/api/" ns-name "\""))
         "}")))

;; =============================================================================
;; Orchestration
;; =============================================================================

(defn integrate-module
  "Guide integration of a scaffolded module. `opts` may carry :base-ns and
   :dry-run?."
  [module-name {:keys [base-ns]}]
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

    (println (bold "Register the module's Integrant components:"))
    (println)
    (println (str "  1. Add config to " (cyan "resources/conf/dev/config.edn")
                  " (and " (cyan "test") "):"))
    (println)
    (println (dim (generate-config-snippet module-name (:has-routes? module))))
    (println)
    (if (:has-wiring? module)
      (do
        (println (str "  2. Load the module's init/halt methods — add to your app's "
                      (cyan "config") " namespace requires:"))
        (println (dim (str "     [" (:module-ns module) ".shell.module-wiring]"))))
      (println (str "  2. This module has no " (cyan "shell/module_wiring.clj")
                    " yet — add one to register its Integrant keys, then require it from your app config.")))
    (println (str "  3. Verify: " (cyan "clojure -M:test")))))

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
  (println (bold "bb scaffold integrate") " — Guide integration of a scaffolded module")
  (println)
  (println "Usage:")
  (println "  bb scaffold integrate <module>              Guide integration (read-only)")
  (println "  bb scaffold integrate <module> --base-ns NS Module under NS.<module>.*")
  (println)
  (println "What it does:")
  (println "  1. Locates the module under src/<base-ns>/<module>/")
  (println "  2. Confirms it is on the classpath + covered by the test suites")
  (println "  3. Prints the Integrant config snippet and the module-wiring require"))

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
