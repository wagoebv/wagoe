#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/lint_imports.clj
;;
;; Copies the clj-kondo configs that dependencies export into
;; .clj-kondo/imports/, so a macro defined inside a jar lints correctly.
;;
;; A library ships linter config in its jar under
;; clj-kondo.exports/<group>/<artifact>/config.edn. wagoe-workflow, -search,
;; -reports, -calendar and -push all do, so `defworkflow` and its siblings stop
;; reading as unresolved symbols in every consuming project (BOU-503).
;;
;; clj-kondo does not read those exports during an ordinary lint. They apply
;; only once copied into .clj-kondo/imports/ by a run that passes the whole
;; classpath with --copy-configs. `bb check`'s lint step passes source paths
;; only, so without this task the exports never reach a project at all and the
;; macros keep linting as unresolved — which reads like a broken export rather
;; than a missing step (BOU-503).
;;
;; Run once after `wagoe new`, and again whenever dependencies change.

(ns wagoe.tools.lint-imports
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.process :as process]
            [wagoe.tools.ansi :as ansi]))

(def config-dir
  "clj-kondo's per-project config directory, relative to the project root."
  ".clj-kondo")

(def no-op-marker
  "clj-kondo's own wording when --copy-configs finds no .clj-kondo directory.

   It prints this and exits 0. That pairing is the reason this task creates the
   directory before importing and then asserts the message is absent: an import
   that copies nothing while reporting success is the shape BOU-250 is about,
   and here it would be invisible — the next lint just keeps calling the macro
   unresolved, which looks like a broken export rather than a skipped import.

   A generated project has no .clj-kondo/ until this runs, so the no-op was the
   default outcome, not an edge case."
  "No configs copied")

(defn import-cmd
  "The clj-kondo invocation that copies exported configs, for `classpath`.

   --dependencies suppresses findings for the dependency code itself. Without
   it this reports every warning in every jar on the classpath, none of which
   the project can act on."
  [classpath]
  ["clojure" "-M:clj-kondo" "--lint" classpath "--dependencies" "--copy-configs"])

(defn copied-configs
  "The import paths clj-kondo reports it copied, parsed from `output`, sorted.

   clj-kondo's output shape:

     Configs copied:
     - .clj-kondo/imports/com.wagoe/wagoe-workflow

   Pass stdout and stderr joined. clj-kondo writes this list to **stderr**,
   alongside its `... was already linted, skipping` chatter; reading stdout
   alone reported `No dependency on the classpath exports a clj-kondo config`
   while seven had just been copied — the same silent-success this task exists
   to close, one level up.

   Pure and public so a test can prove this still recognises the format. The
   count is the only evidence the step did anything, so a parser that silently
   matches nothing puts that failure mode straight back."
  [output]
  (->> (str/split-lines (or output ""))
       (map str/trim)
       (filter #(str/starts-with? % (str "- " config-dir "/imports/")))
       (map #(subs % 2))
       sort
       vec))

(defn default-run
  "Run `cmd` (a vector) and return {:exit :out :err}."
  [cmd]
  (apply process/shell {:out :string :err :string :continue true} cmd))

(defn classpath
  "The project's full classpath, via `run`.

   Throws when clojure -Spath fails. Proceeding with an empty classpath would
   copy nothing and still exit 0 — the same silent success this task guards
   against everywhere else."
  [run]
  (let [{:keys [exit out err]} (run ["clojure" "-Spath"])]
    (when-not (zero? exit)
      (throw (ex-info (str "clojure -Spath failed (exit " exit ") — cannot resolve the "
                           "classpath, so no exported config can be found")
                      {:exit exit :err (str/trim (or err ""))})))
    (str/trim out)))

(defn ensure-config-dir!
  "Create `dir` when absent. Returns true when it had to be created."
  ([] (ensure-config-dir! config-dir))
  ([dir]
   (let [f (io/file dir)]
     (if (.isDirectory f)
       false
       (boolean (.mkdirs f))))))

(defn import!
  "Copy every exported clj-kondo config on the classpath, via `run`.

   Returns the sorted import paths. Throws when the invocation fails, and when
   clj-kondo reports the no-op described on `no-op-marker`."
  [run]
  (let [{:keys [exit out err]} (run (import-cmd (classpath run)))
        output (str out "\n" err)]
    (when (str/includes? output no-op-marker)
      (throw (ex-info (str "clj-kondo copied nothing — " (str/trim output))
                      {:output (str/trim output)})))
    (when-not (zero? exit)
      (throw (ex-info (str "clj-kondo --copy-configs failed (exit " exit ")")
                      {:exit exit :err (str/trim (or err ""))})))
    (copied-configs output)))

(defn -main [& _args]
  (try
    (let [created? (ensure-config-dir!)
          copied   (import! default-run)]
      (when created?
        (println (ansi/dim (str "Created " config-dir "/"))))
      (if (seq copied)
        (do
          (println (ansi/green (str "Imported " (count copied)
                                    " exported clj-kondo config(s):")))
          (println)
          (doseq [p copied] (println (str "  " p)))
          (println)
          ;; Not phrased as an instruction: this repository ignores imports/ on
          ;; purpose (its macros are in-tree, so the copies change no lint
          ;; result), while a generated project commits it — there the macro
          ;; only exists in a jar, and CI does not run this task.
          (println (ansi/dim (str config-dir "/imports/ is lint config, not a cache. "
                                  "Commit it unless your .gitignore excludes it "
                                  "deliberately."))))
        (println (str "No dependency on the classpath exports a clj-kondo config."))))
    (catch Exception e
      (println (ansi/red (.getMessage e)))
      (System/exit 1))))
