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
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]))

(defn- suite-ids
  "The suite ids in `root`/tests.edn, or nil when it is absent or unreadable."
  [root]
  (let [f (io/file root "tests.edn")]
    (when (.exists f)
      (try (->> (edn/read-string {:default (fn [_ v] v)} (slurp f)) :tests (map :id) set)
           (catch Exception _ nil)))))

(defn- module-test-namespaces
  "Test namespaces under a `<module>` directory in `root`/test, sorted."
  [root module]
  (let [test-dir (io/file root "test")
        dir-name (str/replace (str module) "-" "_")]
    (when (.isDirectory test-dir)
      (->> (file-seq test-dir)
           (filter #(and (.isFile ^java.io.File %) (str/ends-with? (.getName ^java.io.File %) "_test.clj")))
           (map #(str (.relativize (.toPath test-dir) (.toPath ^java.io.File %))))
           (filter #(some #{dir-name} (butlast (str/split % #"/"))))
           (map #(-> % (str/replace #"\.clj$" "") (str/replace "/" ".") (str/replace "_" "-")))
           sort
           vec))))

(defn test-command
  "How to run `module`'s tests in the project at `root`: `{:argv [...]}`, or
   `{:no-tests true}` when the module has none.

   A suite with the module's id (the framework repo, one per library) runs by
   id. A generated project has only :unit and :integration, and `bb scaffold
   integrate` adds no suite by design, so `-M:test :<module>` answered \"No such
   suite\", exit 254, forever — reported as \"not wired yet\" even for an
   integrated module (BOU-520). There the module's test namespaces are focused
   instead."
  [root module]
  (if (contains? (or (suite-ids root) #{}) (keyword (str module)))
    {:argv ["clojure" "-M:test" (str ":" module)]}
    (let [nss (module-test-namespaces root module)]
      (if (seq nss)
        {:argv (into ["clojure" "-M:test"] (mapcat (fn [n] ["--focus" n]) nss))}
        {:no-tests true}))))

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
     {:status :passed | :failed | :error | :no-tests
      [:passed n] [:failed n] [:failures [{:var :file :line :message}]]
      [:note str] :raw-tail str}
   `:error` means the runner could not run the tests — distinct from `:failed`
   (tests ran, some failed) and from `:no-tests` (the module has none)."
  ([module] (default-test-runner module {}))
  ([module {:keys [command root] :or {root (System/getProperty "user.dir")}}]
   (let [{:keys [argv no-tests]} (if command
                                   {:argv (mapv #(str/replace % "%s" (str module)) command)}
                                   (test-command root module))]
     (if no-tests
       {:status :no-tests
        :note   (str "Module " (pr-str module) " has no tests to run.")}
       (let [{:keys [exit out err]} (apply sh/sh (concat argv [:dir (str root)]))
             combined (str out "\n" err)
             summary  (parse-summary combined)]
     (cond
       (nil? summary)
       {:status   :error
        :note     (str "Could not run tests for module " (pr-str module)
                       " (exit " exit "): " (str/join " " argv))
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
        :raw-tail (tail combined 40)}))))))
