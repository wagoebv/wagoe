(ns wagoe.mcp.shell.test-runner
  "Default test-runner for the Tier 1 verify loop. Shells out to the project's
   own Kaocha runner in the current working directory and parses the result.

   Why shell out rather than run in-process: Kaocha exposes no stable
   programmatic API, and the affected tests must run in the *project's* full
   classpath (H2, the module under test, fixtures) — not the MCP server's. The
   runner is injected into the tool deps as `:test-runner`, so this is swappable
   (and stubbed in unit tests).

   The structured parse is best-effort over Kaocha's textual output; the raw
   output tail is always included so the agent has the real failure text even
   when a failure shape isn't recognized."
  (:require [clojure.java.shell :as sh]
            [clojure.string :as str]))

(def ^:private default-command
  "Argv template; `%s` is replaced with the Kaocha suite id (the module name)."
  ["clojure" "-M:test" ":%s"])

(def ^:private summary-re
  ;; e.g. "12 tests, 34 assertions, 1 errors, 2 failures."
  #"(\d+)\s+tests?,\s+(\d+)\s+assertions?(?:,\s+(\d+)\s+errors?)?,\s+(\d+)\s+failures?")

(defn- parse-summary [out]
  (when-let [[_ tests _assertions errors failures] (re-find summary-re out)]
    {:tests    (parse-long tests)
     :errors   (parse-long (or errors "0"))
     :failures (parse-long failures)}))

(defn- parse-failures
  "Best-effort: pull `FAIL`/`ERROR in (var) (file:line)` headers out of the
   output. Kaocha prints these for clojure.test failures."
  [out]
  (->> (str/split-lines out)
       (keep (fn [line]
               (when-let [[_ kind var file ln]
                          (re-find #"(FAIL|ERROR) in \(?([^)\s]+)\)?\s*\(([^:]+):(\d+)\)" line)]
                 {:kind    (if (= "ERROR" kind) :error :failure)
                  :var     var
                  :file    file
                  :line    (parse-long ln)
                  :message (str/trim line)})))
       vec))

(defn- tail [s n]
  (let [lines (str/split-lines (or s ""))]
    (str/join "\n" (take-last n lines))))

(defn default-test-runner
  "Run the project's tests for `module` and return a structured result:
     {:status :passed | :failed | :error
      [:passed n] [:failed n] [:failures [{:var :file :line :message}]]
      [:note str] :raw-tail str}
   `:error` means the runner itself could not run the suite (e.g. the module is
   not yet wired into tests.edn) — distinct from `:failed` (tests ran, some
   failed)."
  ([module] (default-test-runner module {}))
  ([module {:keys [command]}]
   (let [argv (mapv #(str/replace % "%s" (str module)) (or command default-command))
         {:keys [exit out err]} (apply sh/sh argv)
         combined (str out "\n" err)
         summary  (parse-summary combined)]
     (cond
       (nil? summary)
       {:status   :error
        :note     (str "Could not run tests for module " (pr-str module)
                       " (exit " exit "). The module may not be wired into tests.edn yet.")
        :raw-tail (tail combined 40)}

       (zero? exit)
       {:status   :passed
        :passed   (:tests summary)
        :failed   0
        :raw-tail (tail combined 10)}

       :else
       {:status   :failed
        :passed   (max 0 (- (:tests summary) (:failures summary) (:errors summary)))
        :failed   (+ (:failures summary) (:errors summary))
        :failures (parse-failures combined)
        :raw-tail (tail combined 40)}))))
