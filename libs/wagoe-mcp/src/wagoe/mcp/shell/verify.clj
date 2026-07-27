(ns wagoe.mcp.shell.verify
  "Shell side of the Tier 1 closed verify loop (BOU-101). Runs the steps that
   touch the filesystem / subprocesses against the files a generate tool just
   wrote, and hands the raw results to wagoe.mcp.core.verify for the pure
   report assembly.

   Steps:
     * kondo  — clj-kondo over the written .clj files (static; in-process).
     * fcis   — wagoe.tools.check-fcis/check-file over written core/ files,
                so FC/IS (BND-806) violations are caught per-file regardless of
                the monorepo's `core-source-paths` discovery.
     * tests  — the project's affected tests, via an injected `:test-runner`
                (a fn of the module name). The runner is injected, not hardcoded,
                because there is no stable programmatic Kaocha API and the tests
                must run in the *project's* classpath, not the MCP server's; the
                default shell-out runner lives in wagoe.mcp.shell.test-runner.
                Absent runner → an honest :unavailable step (never a silent pass).

   Malli validation of the generate *request* happens in the scaffolder service
   before any file is written; its failures arrive as `:errors` on the generate
   result and are surfaced by the core report, so there is no separate Malli
   step here."
  (:require [wagoe.mcp.core.verify :as verify]
            [wagoe.tools.check-fcis :as fcis]
            [clj-kondo.core :as kondo]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- written-files
  "Paths the generate step actually wrote, that still exist on disk. Only
   `:create` entries are written by the scaffolder; `:update` entries are
   manual-edit instructions (their content is never spit), so linting them would
   re-check an unchanged file the tool did not touch."
  [files]
  (->> files
       (filter #(= :create (:action %)))
       (map :path)
       (filter #(.exists (io/file %)))))

(defn- clj-files [paths]
  (filter #(str/ends-with? % ".clj") paths))

(defn- core-files [paths]
  (filter #(and (str/ends-with? % ".clj")
                (str/includes? % "/core/"))
          paths))

(defn- run-kondo [paths]
  (when (seq paths)
    {:findings (:findings (kondo/run! {:lint (vec paths)}))}))

(defn- run-fcis [paths]
  (when (seq paths)
    ;; Read the allowlist once and pass it, instead of the 1-arity re-reading
    ;; .boundary/check-fcis.edn for every file.
    (let [config (fcis/read-config)]
      {:violations (vec (mapcat #(fcis/check-file % config) paths))})))

(defn- run-tests [test-runner module]
  (if test-runner
    (test-runner module)
    {:status :unavailable
     :note   "No test-runner configured; affected tests were not run."}))

(defn verify-generated
  "Run the verify loop over a generate result and return the structured report
   (wagoe.mcp.core.verify/build-report shape).

   `deps`   — needs :test-runner (optional fn of module name).
   `result` — {:module str :files [{:path :action}] [:errors] :success bool}
   `opts`   — {:overridden? bool} (an audited soft-guardrail override).

   kondo runs over every written .clj file; FC/IS over the written core/ files;
   tests over the module (via the injected runner)."
  ([deps result] (verify-generated deps result {}))
  ([deps {:keys [module files] :as result} opts]
   (let [written (written-files files)
         clj     (clj-files written)]
     (verify/build-report
      {:generate {:success (boolean (:success result))
                  :files   files
                  :errors  (:errors result)}
       :kondo    (run-kondo clj)
       :fcis     (run-fcis (core-files clj))
       :tests    (run-tests (:test-runner deps) module)}
      opts))))
