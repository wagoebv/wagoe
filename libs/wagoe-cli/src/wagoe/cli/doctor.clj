(ns wagoe.cli.doctor
  "`wagoe doctor` — the one answer to \"something is wrong\".

   Six commands could tell you something was wrong (`bb doctor`, `bb
   doctor:env`, `bb doctor --all`, `bb check`, `bb smoke-check`, `bb guide
   next`) and nothing said which to reach for first. This runs the diagnostic
   ones in the order their answers depend on each other and ends with one next
   action (BOU-324).

   It orchestrates rather than diagnoses. Every check still lives in
   wagoe-tools behind its `bb` task — those remain the machinery, and CI calls
   them directly with `--ci`. This reads their `--edn` output, which is why that
   mode exists: picking one action out of formatted terminal text would mean
   parsing colour codes and column padding.

   Ordering is not cosmetic. A missing Java makes every later answer noise, and
   an unparseable config makes the command smoke checks fail for a reason that
   has nothing to do with the commands. So a stage with errors stops the run and
   says so, rather than burying its own finding under three more screens."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.java.shell :as shell]))

;; ─── Terminal ────────────────────────────────────────────────────────────────

(def ^:private no-colour?
  (or (some? (System/getenv "NO_COLOR"))
      (= "dumb" (System/getenv "TERM"))))

(defn- paint [code s] (if no-colour? s (str "[" code "m" s "[0m")))
(defn- bold   [s] (paint "1" s))
(defn- dim    [s] (paint "2" s))
(defn- green  [s] (paint "32" s))
(defn- yellow [s] (paint "33" s))
(defn- red    [s] (paint "31" s))

(defn- icon [level]
  (case level :pass (green "✓") :warn (yellow "⚠") :error (red "✗")))

;; ─── Running a stage ─────────────────────────────────────────────────────────

(def stages
  "What runs, in order, and what a failure in each means for the rest.

   `:blocking?` says whether an error here makes the later stages unreliable.
   Environment prerequisites and configuration are both foundations: without a
   JVM nothing runs, and without a parseable config every command that boots the
   system fails for the same one reason."
  [{:id :environment :task ["doctor:env" "--edn"]  :label "Environment prerequisites" :blocking? true  :edn? true}
   {:id :config      :task ["doctor" "--edn"]      :label "Configuration"             :blocking? true  :edn? true}
   ;; smoke-check reports by exit code and prints progress as it goes, so there
   ;; is nothing for it to hand over as data.
   {:id :commands    :task ["smoke-check"]         :label "Commands and aliases"      :blocking? false :edn? false}
   {:id :project     :task ["guide" "next" "--edn"] :label "Project setup"            :blocking? false :edn? true}])

(defn- run-bb
  "Run `bb <args...>` in `dir`. Returns {:exit :out :err}.

   Stages that carry --edn print one map on stdout; smoke-check prints progress,
   and for it only the exit code is read.

   clojure.java.shell rather than babashka.process, which the rest of the
   Babashka tooling uses: `wagoe` runs under bb, but wagoe-cli's own tests run
   under JVM Clojure, and a require of babashka.process makes this namespace
   untestable there — as well as a dependency the library would have to declare
   and does not have. `new.clj` shells out to git the same way."
  [dir args]
  (try
    (shell/with-sh-dir dir
      (apply shell/sh "bb" args))
    (catch Exception e
      {:exit 127 :out "" :err (.getMessage e)})))

(defn parse-stage-output
  "Every result a stage reported, from its stdout.

   `bb` writes its own noise to stdout on some systems (`Picked up
   JAVA_TOOL_OPTIONS`, task banners), so this reads the lines that are ours
   rather than assuming the whole of stdout is EDN.

   All of them, not the last: `bb doctor --env all --edn` prints one map per
   profile, and taking the last kept only the alphabetically final one. A
   broken `acc` config reported two errors when asked directly, and `wagoe
   doctor --env all` answered \"Everything checks out\" — a diagnostic saying
   fine over a broken config, which is the failure this command exists to
   remove.

   Returns nil, not [], when there was nothing of ours to read: the caller
   treats \"could not tell\" and \"told me, and it was empty\" differently."
  [out]
  (let [maps (->> (str/split-lines (or out ""))
                  (map str/trim)
                  (filter #(str/starts-with? % "{:section"))
                  (keep (fn [line]
                          (try
                            (let [m (edn/read-string line)]
                              (when (map? m) (:results m)))
                            (catch Exception _ nil)))))]
    (when (seq maps)
      (vec (apply concat maps)))))

(defn- run-stage
  [dir {:keys [id task label edn?] :as stage}]
  (let [{:keys [exit out err]} (run-bb dir task)
        results   (parse-stage-output out)
        plain-cmd (str "bb " (str/join " " (remove #{"--edn"} task)))]
    (assoc stage
           :exit    exit
           :results
           (cond
             results results

             ;; Asked for data and got none. Almost always a project pinned to a
             ;; wagoe-tools older than --edn: it ignores the flag, prints for a
             ;; human, and exits 0 even when it has warnings to report. Counting
             ;; that as a pass would be this command quietly saying "fine" about
             ;; checks it could not read — the thing it exists to stop.
             edn?
             [{:level     (if (zero? exit) :warn :error)
               :id        id
               :unreadable? true
               :msg       (str label ": could not read the results")
               :fix       (str "Run `" plain-cmd "` and read it yourself.")}]

             (zero? exit)
             [{:level :pass :msg (str label " passed")}]

             :else
             [{:level :error
               :id    id
               :msg   (str label " failed")
               :fix   (str "Run `" plain-cmd "` to see why."
                           (when (seq (str/trim (or err "")))
                             (str "\n" (first (str/split-lines (str/trim err))))))}]))))

;; ─── Rendering ───────────────────────────────────────────────────────────────

(defn collapse-unreadable
  "One message instead of one per stage when the project's tools are too old.

   Every stage predating `--edn` reports the same thing for the same reason, and
   three copies of the same paragraph is the noise this command exists to
   remove. The individual results stay in the tally; what collapses is what the
   reader is asked to do about them."
  [results]
  (let [unreadable (filter :unreadable? results)]
    (if (< (count unreadable) 2)
      results
      (conj (vec (remove :unreadable? results))
            {;; The worst of what was collapsed, not a flat :warn. A stage that
             ;; is unreadable because it *crashed* is an error, and flattening it
             ;; into a warning let `wagoe doctor --ci` exit 0 over a check that
             ;; blew up.
             :level (if (some #(= :error (:level %)) unreadable) :error :warn)
             :id    :tools-version
             :msg   (str "This project's wagoe-tools is older than `--edn`, so "
                         (count unreadable) " checks could not be read: "
                         (str/join ", " (map #(name (:id %)) unreadable)))
             :fix   (str "Bump com.wagoe/wagoe-tools in bb.edn to 1.0.0-beta-6 or newer.\n"
                         "Until then, run `bb doctor:env`, `bb doctor` and `bb guide next` "
                         "yourself — they still work, they just cannot hand their results over.")}))))

(defn- tally [results]
  {:passed   (count (filter #(= :pass (:level %)) results))
   :warnings (count (filter #(= :warn (:level %)) results))
   :errors   (count (filter #(= :error (:level %)) results))})

(defn- print-stage
  "One line per stage when it is clean, the detail only when it is not.

   Four tools' full output is four screens, and the reason nobody read them."
  [{:keys [label results]}]
  (let [{:keys [passed warnings errors]} (tally results)
        level (cond (pos? errors) :error (pos? warnings) :warn :else :pass)]
    (println (str "  " (icon level) " " (format "%-26s" label)
                  (dim (str passed " ok"
                            (when (pos? warnings) (str ", " warnings " warning"
                                                       (when (not= warnings 1) "s")))
                            (when (pos? errors) (str ", " errors " error"
                                                     (when (not= errors 1) "s"))))))))
  (doseq [r results
          :when (not= :pass (:level r))]
    (println (str "      " (icon (:level r)) " " (:msg r)))
    (when-let [fix (:fix r)]
      (doseq [line (str/split-lines fix)]
        (println (dim (str "        " line)))))))

(defn next-action
  "The one thing to do, given every result gathered.

   Errors before warnings, and the earliest stage first: the stages run from the
   most fundamental outwards, so an earlier finding is usually what the later
   ones are downstream of. Fixing the first and running again beats a list of
   nine."
  [results]
  (or (first (filter #(= :error (:level %)) results))
      (first (filter #(= :warn (:level %)) results))))

(defn- print-next-action [results stopped-at]
  (println)
  (if-let [action (next-action results)]
    (do
      (println (bold "Next:"))
      (println (str "  " (:msg action)))
      (when-let [fix (:fix action)]
        (doseq [line (str/split-lines fix)]
          (println (dim (str "  " line)))))
      (when stopped-at
        (println)
        (println (dim (str "  Stopped after " (name stopped-at)
                           " — later checks depend on it and would report noise.")))))
    (println (green "Everything checks out. `bb guide` has the topic guides."))))

;; ─── Entry point ─────────────────────────────────────────────────────────────

(defn wagoe-project?
  "True when `dir` is a project whose bb.edn pulls in wagoe-tools."
  [dir]
  (let [f (io/file dir "bb.edn")]
    (and (.exists f)
         (str/includes? (slurp f) "wagoe-tools"))))

(defn- print-help []
  (println (bold "wagoe doctor") " — check a project and say what to do next")
  (println)
  (println "Usage:")
  (println "  wagoe doctor                 Run every diagnostic and print one next action")
  (println "  wagoe doctor --env prod      Check a profile other than dev")
  (println "  wagoe doctor --ci            Exit non-zero when anything errors")
  (println)
  (println "Runs, in order:")
  (doseq [{:keys [label task]} stages]
    (println (str "  " (format "%-26s" label)
                  (dim (str "bb " (str/join " " (remove #{"--edn"} task)))))))
  (println)
  (println (dim "Those tasks are still there, and CI calls them directly."))
  (println (dim "For code quality rather than setup, run `bb check`.")))

(defn run
  "Run the stages against `dir`. Returns {:results :stopped-at :errors}."
  [dir env]
  (reduce
   (fn [acc {:keys [blocking?] :as stage}]
     (let [stage   (run-stage dir (cond-> stage
                                    (and env (= :config (:id stage)))
                                    (update :task #(into ["doctor" "--env" env] (rest %)))))
           results (:results stage)
           errors? (some #(= :error (:level %)) results)]
       (print-stage stage)
       (let [acc (update acc :results into results)]
         (if (and errors? blocking?)
           (reduced (assoc acc :stopped-at (:id stage)))
           acc))))
   {:results [] :stopped-at nil}
   stages))

(defn -main [args]
  (when (some #{"--help" "-h"} args)
    (print-help)
    (System/exit 0))
  (let [dir (System/getProperty "user.dir")
        env (second (drop-while #(not= "--env" %) args))]

    ;; Every stage is a bb task supplied by wagoe-tools, so that is the real
    ;; prerequisite — not merely a bb.edn. A plain Babashka project has one and
    ;; none of the tasks, and reported four stages it "could not read the
    ;; results" of, each suggesting a command that does not exist there.
    (when-not (wagoe-project? dir)
      (println (red "Not a Wagoe project."))
      (println (dim (str "  wagoe doctor runs a project's own diagnostics, and they come from")))
      (println (dim (str "  com.wagoe/wagoe-tools in its bb.edn. Run this from inside a project;")))
      (println (dim (str "  `wagoe new <name>` creates one.")))
      (System/exit 1))

    (println)
    (println (bold "Wagoe Doctor"))
    (println)
    (let [{:keys [results stopped-at]} (run dir env)
          results (collapse-unreadable results)]
      (print-next-action results stopped-at)
      (println)
      (when (and (some #{"--ci"} args)
                 (some #(= :error (:level %)) results))
        (System/exit 1)))))
