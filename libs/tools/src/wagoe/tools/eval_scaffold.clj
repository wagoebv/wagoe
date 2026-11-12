(ns wagoe.tools.eval-scaffold
  "`bb eval:scaffold`: how good `bb scaffold ai` output is against a real
   provider, one result file per night (BOU-909).

   Each prompt in eval/scaffold/prompts.edn runs in a fresh project generated
   from this checkout, as scripts/first-run-smoke.sh does it. The output is
   checked; when red, a fixed repair (clj-paren-repair, then cljfmt) runs on
   the generated files and the changed lines are the edits needed. Still red
   after repair: not mergeable, edits nil."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [wagoe.tools.dev :as dev]))

;; =============================================================================
;; Prompts and expectations (pure)
;; =============================================================================

(def default-prompts "eval/scaffold/prompts.edn")

(defn load-prompts [path]
  (edn/read-string (slurp path)))

(defn prompt-expectations
  "The checks for `prompt`: the catalogue's shared ones, then its own."
  [catalogue prompt]
  (into (vec (:expectations catalogue)) (:expectations prompt)))

(defn- strip-comments [src]
  (str/replace src #"(?m);.*$" ""))

(defn- line-of [src idx]
  (inc (count (re-seq #"\n" (subs src 0 idx)))))

(defn- hits [re src]
  (let [m (re-matcher re src)]
    (loop [acc []]
      (if (.find m)
        (recur (conj acc {:match (.group m) :line (line-of src (.start m)) :group (.group m 1)}))
        acc))))

(defn protocol-methods
  "Method names declared by the defprotocol forms in `src`."
  [src]
  (->> (str/split (strip-comments src) #"\n(?=\()")
       (filter #(str/starts-with? (str/triml %) "(defprotocol"))
       (mapcat #(map second (re-seq #"\(\s*([a-z][\w!?*<>-]*)\s+\[" %)))
       set))

(defn- clj? [path] (boolean (re-find #"\.cljc?$" (str path))))

(defn interop-on-protocol-hits
  "(.method ...) calls in `sources` ({path text}) where method is a protocol
   method, or has a dash, which no Java method name does."
  [sources]
  (let [clj     (into {} (filter (comp clj? key)) sources)
        methods (into #{} (mapcat (comp protocol-methods val)) clj)]
    (vec (for [[path src] (sort clj)
               {:keys [group line]} (hits #"\(\.([a-zA-Z][\w!?*<>-]*)[\s)]" (strip-comments src))
               :when (or (methods group) (str/includes? group "-"))]
           {:file path :line line :call (str "(." group)}))))

(defn request-identity-hits
  "Reads of :identity in `sources`. Platform puts the signed-in user under :user."
  [sources]
  (vec (for [[path src] (sort sources)
             :when (clj? path)
             {:keys [line]} (hits #"(:identity)(?![\w-])" (strip-comments src))]
         {:file path :line line})))

(defmulti check-expectation
  "{:id :pass? ...} for one expectation against `sources` ({path text})."
  (fn [exp _sources] (:check exp)))

(defn- no-hits [id h] {:id id :pass? (empty? h) :hits h})

(defmethod check-expectation :no-protocol-interop [{:keys [id]} sources]
  (no-hits id (interop-on-protocol-hits sources)))

(defmethod check-expectation :no-request-identity [{:keys [id]} sources]
  (no-hits id (request-identity-hits sources)))

(defmethod check-expectation :keywords [{:keys [id keywords]} sources]
  (let [all     (str/join "\n" (vals sources))
        missing (vec (remove #(re-find (re-pattern (str "(?<![\\w-])" (java.util.regex.Pattern/quote (str %)) "(?![\\w-])")) all)
                             keywords))]
    {:id id :pass? (empty? missing) :missing missing}))

(defmethod check-expectation :matches [{:keys [id pattern]} sources]
  {:id id :pass? (boolean (re-find (re-pattern pattern) (str/join "\n" (vals sources))))})

(defmethod check-expectation :default [{:keys [id check]} _]
  {:id id :pass? false :error (str "unknown check " check)})

(defn check-expectations [exps sources]
  (mapv #(check-expectation % sources) exps))

;; =============================================================================
;; Edits and summary (pure)
;; =============================================================================

(defn numstat-edits
  "Added plus removed lines in `git diff --numstat` output. A binary file
   (\"-\") counts as none."
  [numstat]
  (->> (str/split-lines (str numstat))
       (keep #(re-find #"^(\d+|-)\s+(\d+|-)\s" %))
       (mapcat (fn [[_ a r]] [a r]))
       (remove #{"-"})
       (map parse-long)
       (reduce + 0)))

(defn median [xs]
  (when (seq xs)
    (let [v (vec (sort xs)) n (count v) h (quot n 2)]
      (if (odd? n) (v h) (/ (+ (v (dec h)) (v h)) 2.0)))))

(defn green?
  "Every step that ran passed, and every expectation."
  [steps expectations]
  (and (every? #(#{:pass :skipped} (:status %)) (vals steps))
       (every? :pass? expectations)))

(defn summarise
  "{:mergeable n :of n :median-edits x}: mergeable means without edits; the
   median is over prompts that were mergeable at all."
  [prompt-results]
  {:mergeable    (count (filter #(= 0 (:edits %)) prompt-results))
   :of           (count prompt-results)
   :median-edits (median (keep :edits prompt-results))})

;; =============================================================================
;; Providers
;; =============================================================================

(def provider-vars
  "Every variable wagoe.ai.shell.cli-entry/provider-env reads, plus AI_MODEL.
   A cell clears them all, or a key in the runner's env would pick the provider."
  ["ANTHROPIC_API_KEY" "OPENAI_BASE_URL" "OPENAI_API_KEY" "REPLICATE_API_TOKEN" "OLLAMA_URL" "AI_MODEL"])

(def providers
  {"ollama" {:label "Ollama" :model "qwen2.5-coder:7b"
             :env   (fn [env] {"OLLAMA_URL" (get env "OLLAMA_URL" "http://localhost:11434")})}
   ;; OPENAI_BASE_URL passes through so a local run can point at a stub.
   "openai" {:label "OpenAI" :model "gpt-4o-mini" :requires "OPENAI_API_KEY"
             :env   (fn [env] (select-keys env ["OPENAI_API_KEY" "OPENAI_BASE_URL"]))}})

(defn cell-env
  "The environment a provider cell runs with: `env` without other providers."
  [env provider-id]
  (let [{:keys [model] f :env} (providers provider-id)]
    (merge (apply dissoc env provider-vars) (f env) {"AI_MODEL" model})))

;; =============================================================================
;; Shell
;; =============================================================================

(def ^:private minute 60000)

(defn- tail
  "The first exception lines (up to 3), then the last `n` lines, capped at
   `max-chars`. A tail alone can be only stack frames (scripts/lib/log-excerpt.sh)."
  [s n max-chars]
  (let [ls   (str/split-lines (str s))
        head (->> (drop-last n ls) (filter #(re-find #"Exception|Error|Caused by" %)) (take 3)
                  (map #(str % "\n")) str/join)
        end  (str/join "\n" (take-last n ls))
        room (max 0 (- max-chars (count head)))]
    ;; The cap cuts the tail's oldest lines, so the exception lines survive it.
    (str head (if (> (count end) room) (subs end (- (count end) room)) end))))

(defn- run
  "Run `cmd` in `dir`. {:exit :out :ms}; :exit is :timeout past `timeout-ms`."
  [{:keys [dir env timeout-ms] :or {timeout-ms (* 10 minute)}} & cmd]
  (let [start (System/currentTimeMillis)
        proc  (apply p/process {:dir (str dir) :env env :out :string :err :out :in ""} cmd)
        res   (deref proc timeout-ms ::timeout)
        ms    (- (System/currentTimeMillis) start)]
    (if (= ::timeout res)
      (do (p/destroy-tree proc) {:exit :timeout :out "" :ms ms})
      {:exit (:exit res) :out (:out res) :ms ms})))

(defn- step [r]
  (cond-> {:status (if (= 0 (:exit r)) :pass :fail) :ms (:ms r)}
    (not= 0 (:exit r)) (assoc :exit (:exit r) :log (tail (:out r) 60 4000))))

;; Commits pass --no-verify: a generated project's pre-commit hook lints, and
;; a red module must still be committed to be measured.
(defn- git [ctx & args]
  (apply run ctx "git" "-c" "user.name=eval" "-c" "user.email=eval@localhost" args))

(defn- wagoe-cli [{:keys [repo] :as ctx} & args]
  (apply run ctx "bb" "--config" (str repo "/bb.edn") "-m" "wagoe.cli.main" args))

(defn- pin! [{:keys [repo dir] :as ctx}]
  (run ctx "bash" "-c" ". \"$1/scripts/lib/smoke-worktree.sh\" && pin_to_worktree \"$2\" \"$1\""
       "_" (str repo) (str dir)))

(defn- must! [what r]
  (when-not (= 0 (:exit r))
    (throw (ex-info (str what " failed") {:type :internal-error :log (tail (:out r) 40 4000)})))
  r)

(defn- new-base-project!
  "A project from this checkout's `wagoe new`, pinned to it and committed."
  [{:keys [work] :as ctx}]
  (let [dir (fs/path work "base")]
    (fs/delete-tree dir)
    (must! "wagoe new" (wagoe-cli (assoc ctx :dir work) "new" "eval-app"))
    (fs/move (fs/path work "eval-app") dir)
    (must! "pin to worktree" (pin! (assoc ctx :dir dir)))
    (must! "commit base" (git (assoc ctx :dir dir) "commit" "--no-verify" "-qam" "pinned to worktree"))
    dir))

(defn- source-files [dir rev]
  (let [r (git {:dir dir} "diff" "--name-only" rev "HEAD")]
    (vec (sort (remove str/blank? (str/split-lines (:out r)))))))

(defn- read-sources [dir files]
  (into {} (for [f files
                 :when (re-find #"\.(cljc?|edn|sql)$" f)
                 :let [file (fs/file dir f)]
                 :when (fs/exists? file)]
             [f (slurp file)])))

(defn- module-of
  "The module scaffold ai wrote: the first directory under src/<ns>/."
  [files]
  (some-> (some #(second (re-find #"^src/[^/]+/([^/]+)/" %)) files)
          (str/replace "_" "-")))

(defn- src-ns [path]
  (when-let [[_ p] (re-find #"^src/(.+)\.cljc?$" path)]
    (-> p (str/replace "/" ".") (str/replace "_" "-"))))

(def ^:private boot-deps
  "dev/ on the path, for user/go, without :repl's nREPL main-opts."
  "{:aliases {:eval-boot {:extra-paths [\"dev\"] :jvm-opts [\"-Duser.timezone=UTC\"]}}}")

(defn run-checks
  "Every check a scaffolded module must pass, in order. Fail soft: each runs."
  [ctx files]
  (let [nss (keep src-ns files)]
    (array-map
     :compiles   (if (seq nss)
                   (step (run ctx "clojure" "-M" "-e"
                              (str "(require " (str/join " " (map #(str "'" %) nss)) ") (System/exit 0)")))
                   {:status :fail :log "no source namespace was generated"})
     :tests      (step (run ctx "bb" "test"))
     :check-fcis (step (run ctx "bb" "check:fcis"))
     ;; wagoe.tools.check/all-checks scopes check:deps to the monorepo: it walks
     ;; libs/*, and a generated project has none.
     :check-deps {:status :skipped :reason "check:deps is monorepo-only; a generated project has no libs/"}
     :migrate    (step (run ctx "bb" "migrate" "up"))
     :boot       (step (run ctx "clojure" "-Sdeps" boot-deps "-M:eval-boot" "-e"
                            "(require 'user) (user/go) (user/halt) (shutdown-agents) (System/exit 0)")))))

(defn- repair!
  "clj-paren-repair, then cljfmt, on the generated Clojure files. Returns the
   changed lines and the diff."
  [ctx files]
  (let [clj (filterv clj? files)]
    (when (seq clj)
      (apply run ctx "clj-paren-repair" clj)
      ;; The cljfmt the repo's :cljfmt alias pins.
      (apply run ctx "clojure" "-Sdeps" "{:aliases {:fmt {:replace-deps {cljfmt/cljfmt {:mvn/version \"0.9.2\"}}}}}"
             "-M:fmt" "-m" "cljfmt.main" "fix" clj))
    {:edits (numstat-edits (:out (apply git ctx "diff" "--numstat" "--" files)))
     :diff  (tail (:out (apply git ctx "diff" "--" files)) 200 4000)}))

(defn- first-failure [steps expectations]
  (or (some (fn [[k v]] (when (= :fail (:status v)) {:step k :log (:log v)})) steps)
      (some #(when-not (:pass? %) {:expectation (:id %)}) expectations)))

(defn run-prompt
  "One prompt in a fresh copy of `base`. Never throws."
  [{:keys [work catalogue] :as ctx} base {:keys [id prompt setup] :as p}]
  (let [start (System/currentTimeMillis)
        dir   (fs/path work (name id))
        ctx   (assoc ctx :dir dir)
        done  #(assoc % :id id :prompt prompt :tokens nil
                      :duration-ms (- (System/currentTimeMillis) start))]
    (try
      (fs/delete-tree dir)
      (fs/copy-tree base dir)
      (doseq [args setup]
        (must! (str "wagoe " (str/join " " args)) (apply wagoe-cli ctx args)))
      (must! "pin to worktree" (pin! ctx))
      (git ctx "add" "-A")
      (git ctx "commit" "--no-verify" "-qm" "setup" "--allow-empty")
      (git ctx "tag" "-f" "eval-setup")
      (let [ctx  (update ctx :env merge (dev/dotenv (str (fs/path dir ".env"))))
            gen  (step (run (assoc ctx :timeout-ms (* 30 minute))
                            "bb" "scaffold" "ai" prompt "--yes"))
            _    (git ctx "add" "-A")
            _    (git ctx "commit" "--no-verify" "-qm" "scaffold ai" "--allow-empty")
            mod  (module-of (source-files dir "eval-setup"))
            intg (if mod
                   (step (run ctx "bb" "scaffold" "integrate" mod))
                   {:status :fail :log "scaffold ai wrote no module under src/"})
            _    (git ctx "add" "-A")
            _    (git ctx "commit" "--no-verify" "-qm" "integrate" "--allow-empty")
            files (source-files dir "eval-setup")
            setup {:generate gen :integrate intg}
            expected (prompt-expectations catalogue p)]
        (if-not (and (= :pass (:status gen)) mod)
          (done {:generated? false :module mod
                 :steps setup
                 :mergeable? false :edits nil
                 :failure {:step :generate :log (or (:log gen) (:log intg))}})
          (let [checks (run-checks ctx files)
                exps   (check-expectations expected (read-sources dir files))
                steps  (merge setup checks)]
            (if (green? steps exps)
              (done {:generated? true :module mod :files files :steps steps :expectations exps
                     :mergeable? true :edits 0})
              (let [{:keys [edits diff]} (repair! ctx files)
                    checks2 (if (pos? edits) (run-checks ctx files) checks)
                    exps2   (check-expectations expected (read-sources dir files))
                    steps2  (merge setup checks2)
                    ok?     (green? steps2 exps2)]
                (done {:generated? true :module mod :files files
                       :steps steps2 :expectations exps2
                       :before-repair {:steps checks :expectations exps}
                       :mergeable? ok? :edits (when ok? edits)
                       :failure (when-not ok? (assoc (first-failure steps2 exps2) :repair-diff diff))}))))))
      (catch Exception e
        (done {:generated? false :mergeable? false :edits nil
               :failure {:error (ex-message e) :log (:log (ex-data e))}})))))

(defn- git-sha [repo]
  (str/trim (:out (run {:dir repo} "git" "rev-parse" "HEAD"))))

(defn result-file [out {:keys [date provider]}]
  (fs/path out (str date "-" provider ".edn")))

(defn evaluate
  "Run every prompt for `provider-id`; the result map."
  [{:keys [repo env catalogue only] :as ctx} provider-id]
  (let [{:keys [label model requires]} (providers provider-id)
        ;; A run against a stub or a compatible endpoint is not a run against OpenAI.
        base (cond-> {:date     (str (java.time.LocalDate/now java.time.ZoneOffset/UTC))
                      :git-sha  (git-sha repo)
                      :provider provider-id
                      :model    model
                      :label    label}
               (and (= "openai" provider-id) (get env "OPENAI_BASE_URL"))
               (assoc :endpoint (get env "OPENAI_BASE_URL")))
        prompts (cond->> (:prompts catalogue)
                  (seq only) (filter #((set only) (name (:id %)))))]
    (if (and requires (str/blank? (get env requires)))
      (assoc base :status :skipped :reason (str requires " is not set") :prompts []
             :summary {:mergeable nil :of (count prompts) :median-edits nil})
      (let [ctx     (assoc ctx :env (merge (cell-env env provider-id)
                                           {"WAGOE_SCAFFOLDER_ROOT" (str repo "/libs/scaffolder")
                                            "WAGOE_AI_ROOT"         (str repo "/libs/ai")}))
            project (new-base-project! ctx)
            results (mapv (fn [p]
                            (println (str "── " (name (:id p)) ": " (:prompt p)))
                            (let [r (run-prompt ctx project p)]
                              (println (str "   " (if (:mergeable? r) "mergeable" "not mergeable")
                                            ", edits " (pr-str (:edits r))
                                            ", " (quot (:duration-ms r) 1000) "s"))
                              r))
                          prompts)]
        (assoc base :status :ran :prompts results :summary (summarise results))))))

(def ^:private usage
  "Usage: bb eval:scaffold --provider ollama|openai [--only id,id] [--prompts FILE] [--out DIR] [--work DIR]")

(defn- parse-args [args]
  (let [m (when (even? (count args)) (apply hash-map args))]
    (when-not (and m
                   (every? #{"--provider" "--only" "--prompts" "--out" "--work"} (keys m))
                   (providers (get m "--provider")))
      (binding [*out* *err*] (println usage))
      (System/exit 1))
    m))

(defn -main [& args]
  (let [opts   (parse-args args)
        repo   (str (fs/canonicalize "."))
        out    (fs/absolutize (get opts "--out" "target/eval-scaffold"))
        work   (fs/create-dirs (fs/absolutize (or (get opts "--work") (fs/create-temp-dir {:prefix "eval-scaffold"}))))
        result (try
                 (evaluate {:repo      repo
                            :work      work
                            :env       (into {} (System/getenv))
                            :catalogue (load-prompts (get opts "--prompts" default-prompts))
                            :only      (some-> (get opts "--only") (str/split #","))}
                           (get opts "--provider"))
                 ;; The base project could not be made: no prompt can run, and
                 ;; that is a broken harness, not a score.
                 (catch clojure.lang.ExceptionInfo e
                   (binding [*out* *err*]
                     (println (ex-message e))
                     (some-> (:log (ex-data e)) println))
                   (System/exit 1)))
        file   (result-file out result)]
    (fs/create-dirs out)
    (spit (str file) (with-out-str (pprint/pprint result)))
    (let [{:keys [mergeable of median-edits]} (:summary result)]
      (println (str "Wrote " file ": " (if (= :skipped (:status result))
                                         (str "skipped (" (:reason result) ")")
                                         (str mergeable " of " of " mergeable without edits, median edits "
                                              (pr-str median-edits))))))))
