#!/usr/bin/env bb
;; scripts/rename_wagoe.clj
;;
;; Per-phase, scoped, inspectable driver for the Boundary -> Wagoe rename
;; (BOU-209). Each phase applies a narrow set of substitutions and/or git
;; directory moves so that phase PRs stay atomic and reviewable. NEVER
;; one-shots the whole rename.
;;
;; Usage:
;;   bb scripts/rename_wagoe.clj <phase> [--apply]
;;   bb scripts/rename_wagoe.clj list
;;
;; Default is DRY-RUN: prints planned content edits (per file) and dir moves,
;; changes nothing. Add --apply to write files and run `git mv`.
;;
;; Phases (run in this order, each behind its own PR):
;;   ns      boundary.<seg> -> wagoe.<seg>  (code + edn) + git mv of the
;;           `boundary` namespace-root dirs to `wagoe`
;;   keys    :boundary/ -> :wagoe/
;;   coords  org.boundary-app/boundary-<lib> -> org.wagoe/wagoe-<lib>,
;;           then org.boundary-app -> org.wagoe
;;   env     BND_ -> WAG_
;;   dirs    libs/boundary-cli|mcp -> libs/wagoe-cli|mcp (+ content refs)
;;   urls    external refs -> Wagoe homes
;;   prose   docs/prose Boundary -> Wagoe — ASSIST ONLY: auto-replaces the
;;           unambiguous "Boundary framework"/"Boundary" brand uses and FLAGS
;;           lines with the architectural word "boundary" for manual review.
;;
;; The verification gate is `bb check:no-boundary` (separate); run it after a
;; phase to confirm that phase's tokens are gone.

(ns rename-wagoe
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [babashka.process :as process]))

;; --- tracked-file discovery ------------------------------------------------

(defn- tracked
  "Tracked files, optionally limited to git pathspecs (e.g. \"*.clj\")."
  [& pathspecs]
  (let [{:keys [exit out]} (apply process/shell {:out :string :err :string :continue true}
                                  (concat ["git" "ls-files"]
                                          (when (seq pathspecs) (cons "--" pathspecs))))]
    (if (zero? exit)
      (->> (str/split-lines out) (remove str/blank?))
      (throw (ex-info "git ls-files failed" {:exit exit})))))

(def code-globs ["*.clj" "*.cljc" "*.cljs" "*.edn"])

;; --- phase definitions -----------------------------------------------------
;; :subs  — ordered [regex replacement] pairs; :globs limits the file set.
;; :moves — fn returning [[from to] ...] git mv pairs (computed lazily).
;; :flag  — optional regex; lines matching are reported for manual review
;;          instead of/alongside auto-substitution (the prose ambiguity guard).

(defn- boundary-name-dirs
  "Directories named exactly `boundary` (namespace roots) — NOT `.boundary`,
   `boundary-cli`, etc. Moved to a sibling `wagoe`."
  []
  (->> (fs/glob "." "**/boundary" {:hidden false})
       (filter fs/directory?)
       (map str)
       (remove #(str/includes? % "/target/"))
       sort
       (map (fn [d] [d (str (subs d 0 (- (count d) (count "boundary"))) "wagoe")]))))

(def phases
  {"ns"     {:desc  "boundary.<ns> -> wagoe.<ns> (code+edn) + dir moves"
             :globs code-globs
             :subs  [[#"boundary\." "wagoe."]]
             :moves boundary-name-dirs}
   "keys"   {:desc "keys :boundary/ -> :wagoe/"
             :subs [[#":boundary/" ":wagoe/"]]}
   "coords" {:desc "org.boundary-app/boundary-<lib> -> org.wagoe/wagoe-<lib>"
             :subs [[#"org\.boundary-app/boundary-" "org.wagoe/wagoe-"]
                    [#"org\.boundary-app" "org.wagoe"]]}
   "env"    {:desc "BND_ -> WAG_"
             :subs [[#"BND_" "WAG_"]]}
   "dirs"   {:desc  "boundary-cli|mcp -> wagoe-cli|mcp (content + git mv)"
             :subs  [[#"boundary-cli" "wagoe-cli"]
                     [#"boundary-mcp" "wagoe-mcp"]]
             :moves (fn [] (->> [["libs/boundary-cli" "libs/wagoe-cli"]
                                 ["libs/boundary-mcp" "libs/wagoe-mcp"]]
                                (filter #(fs/exists? (first %)))))}
   "urls"   {:desc "external refs -> Wagoe homes"
             :subs [[#"boundary-app\.org" "framework.wagoe.com"]
                    [#"thijs-creemers/boundary-examples" "wagoebv/examples"]
                    [#"thijs-creemers/boundary" "wagoebv/wagoe"]]}
   "prose"  {:desc  "docs Boundary -> Wagoe (assist: flags arch 'boundary')"
             :globs ["*.md" "*.adoc"]
             :subs  [[#"Boundary framework" "Wagoe framework"]
                     [#"Boundary Framework" "Wagoe Framework"]]
             :flag  #"(?i)boundar(y|ies)"}})

;; --- engine ----------------------------------------------------------------

(defn- apply-subs [content subs]
  (reduce (fn [c [re rep]] (str/replace c re rep)) content subs))

(defn- run-content [{:keys [globs subs flag]} apply?]
  (let [files (apply tracked (or globs ["*"]))]
    (reduce
     (fn [acc f]
       (if-not (fs/regular-file? f)
         acc
         (let [orig (slurp f)
               new  (if subs (apply-subs orig subs) orig)
               changed? (not= orig new)
               flagged (when flag
                         (->> (str/split-lines orig)
                              (map-indexed (fn [i l] [(inc i) l]))
                              (filter (fn [[_ l]] (re-find flag l)))))]
           (when (and apply? changed?) (spit f new))
           (cond-> acc
             changed?      (update :changed conj f)
             changed?      (update :nchanged inc)
             (seq flagged) (update :flagged conj [f (count flagged)])))))
     {:changed [] :nchanged 0 :flagged []}
     files)))

(defn- run-moves [move-fn apply?]
  (let [moves (when move-fn (move-fn))]
    (doseq [[from to] moves]
      (when apply?
        (process/shell {:continue true} "git" "mv" from to)))
    moves))

(defn- do-phase [name apply?]
  (let [{:keys [desc moves] :as ph} (phases name)]
    (when-not ph
      (println "Unknown phase:" name "— run `list`.") (System/exit 2))
    (println (str (if apply? "APPLY " "DRY-RUN ") name " — " desc))
    (println)
    (let [{:keys [changed nchanged flagged]} (run-content ph apply?)
          mvs (run-moves moves apply?)]
      (println (format "  content: %d file(s) %s" nchanged (if apply? "rewritten" "would change")))
      (doseq [f (take 12 changed)] (println (str "    ~ " f)))
      (when (> (count changed) 12) (println (str "    … +" (- (count changed) 12) " more")))
      (when (seq mvs)
        (println (format "  moves: %d dir(s) %s" (count mvs) (if apply? "moved" "to move")))
        (doseq [[from to] (take 20 mvs)] (println (str "    " from " -> " to))))
      (when (seq flagged)
        (println (format "  ⚑ %d file(s) contain the architectural word \"boundary\" — MANUAL REVIEW:" (count flagged)))
        (doseq [[f n] (take 20 flagged)] (println (str "    " f " (" n " line(s))"))))
      (println)
      (println (if apply?
                 "Applied. Run `bb check:no-boundary` + tests, then commit."
                 "Dry run only. Re-run with --apply to write.")))))

(defn -main [& args]
  (let [apply? (boolean (some #{"--apply"} args))
        pos    (remove #{"--apply"} args)
        cmd    (first pos)]
    (cond
      (or (nil? cmd) (= cmd "list"))
      (do (println "Phases (run in order):")
          (doseq [k ["ns" "keys" "coords" "env" "dirs" "urls" "prose"]]
            (println (format "  %-7s %s" k (:desc (phases k))))))
      :else (do-phase cmd apply?))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
