#!/usr/bin/env bb
;; scripts/check_no_boundary.clj
;;
;; Verification gate for the Boundary -> Wagoe rename (BOU-209).
;;
;; Scans tracked files for residual "boundary" identifiers and fails if any
;; remain outside an allowlist. Run after each rename phase to prove that
;; phase's token is gone; run with no args as the final gate (all hard tokens).
;;
;; Token groups (select as args, e.g. `bb check:no-boundary coords keys`):
;;   ns     boundary.<seg>  namespaces / require aliases (code files only)
;;   keys   :boundary/...   Integrant + config keywords
;;   coords org.boundary-app Maven/Clojars group
;;   group  org.wagoe       WRONG Wagoe Clojars group (must be com.wagoe)
;;   env    BND_...         environment-variable prefix
;;   dirs   boundary-cli / boundary-mcp  library directory names
;;   urls   boundary-app.org / thijs-creemers/boundary  external references
;;   prose  the word "boundary" (case-insensitive) in docs — REPORT ONLY,
;;          never fails (it is also a real FC/IS / hexagonal architecture term)
;;
;; No args  -> all HARD groups (ns keys coords group env dirs urls); prose excluded.
;; `all`    -> hard groups + prose (prose still report-only).
;;
;; Allowlist: paths in .wagoe/check-no-boundary.edn `:allow-paths` (prefix
;; match) are exempt — CHANGELOG history, planning docs, the rename tooling
;; itself, and (pre-rename) the boundary-pathed source tree of this checker.

;; Lives under scripts/ (NOT libs/tools) on purpose: the rename phases rewrite
;; the libs/ namespace tree, and this gate's own token literals (:boundary/,
;; org.boundary-app, boundary.) must survive every phase. Keeping it outside the
;; renamed tree — and allowlisted in the driver — makes it self-stable.
(ns check-no-boundary
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [clojure.edn :as edn]
            [babashka.fs :as fs]
            [babashka.process :as process]))

;; ANSI inlined on purpose — the gate must NOT depend on the renamed libs/ tree
;; (boundary.tools.ansi becomes wagoe.tools.ansi mid-rename, which would break
;; this allowlisted, unmodified file). Zero renamed-tree deps = self-stable.
(defn- esc [code s] (str "[" code "m" s "[0m"))
(defn- ansi-bold   [s] (esc "1"  s))
(defn- ansi-green  [s] (esc "32" s))
(defn- ansi-red    [s] (esc "31" s))
(defn- ansi-yellow [s] (esc "33" s))

(def hard-groups [:ns :keys :coords :group :env :dirs :urls])

(def token-defs
  "Each group: :desc human label, :grep git-grep args (before the pathspec),
   :paths optional pathspec limiting the search, :hard? counts toward failure."
  {:ns     {:desc  "boundary.<ns> namespaces"
            ;; Two alternatives, because `boundary.` is ambiguous:
            ;;   boundary\.        — inside a REGEX LITERAL (#"boundary\.…").
            ;;                       The Phase-1 sub couldn't rewrite these; the
            ;;                       backslash shields the match (BOU-210).
            ;;   boundary\.[a-z]   — a real namespace segment (boundary.core).
            ;; Requiring the lowercase letter is what keeps the ARCHITECTURAL
            ;; term at a sentence end ("at the HTTP boundary.") from matching —
            ;; Phase 1 had no such guard and corrupted 19 docstrings into
            ;; "at the HTTP wagoe.".
            ;; *.tmpl / *.template are IN SCOPE on purpose: they become real
            ;; source in generated projects. They were in no phase's glob, so
            ;; every `wagoe new` project shipped pre-rename namespaces against
            ;; renamed artifacts and could not boot (BOU-215).
            :grep  ["-nIE" "boundary\\\\\\.|boundary\\.[a-z]"]
            ;; NO :paths — scan every tracked file. Restricting by extension is
            ;; what let this class through repeatedly: a namespace reference is
            ;; just a string, and it turned up in .tmpl (generated projects),
            ;; .sh (the installer wrapper ran boundary.cli.main), .yml and
            ;; .service. The allowlist, not the file type, decides exemptions.
            :hard? true}
   :keys   {:desc  ":boundary/ config + Integrant keys"
            :grep  ["-nIF" ":boundary/"]
            :hard? true}
   :coords {:desc  "org.boundary-app Maven coords (dot + path form)"
            ;; `org[./]boundary-app` catches BOTH the coord form
            ;; (org.boundary-app/x) and the repo PATH form used in Clojars/m2
            ;; URLs (clojars.org/repo/org/boundary-app/...). Matching only the
            ;; dot form left the deploy verification URL silently pointing at
            ;; the old group after the coord rename (BOU-213).
            :grep  ["-nIE" "org[./]boundary-app"]
            :hard? true}
   :env    {:desc  "BND_ / BOUNDARY_ env prefixes"
            ;; Both prefixes were in use. Matching only BND_ let three live
            ;; runtime knobs through — BOUNDARY_ENFORCE_TYPED_ERRORS,
            ;; BOUNDARY_TENANT_ID, BOUNDARY_USER_ID — while the gate reported
            ;; env clean. Assume a token family has more than one spelling.
            :grep  ["-nIE" "(BND|BOUNDARY)_[A-Z0-9_]+"]
            :hard? true}
   :group  {:desc  "org.wagoe — wrong Clojars group (dot + path form)"
            ;; NOT a Boundary residual: org.wagoe was the rename's own mistake.
            ;; Clojars verifies a reverse-domain group against the matching
            ;; domain, so org.wagoe requires wagoe.org — which we do not own and
            ;; will not buy. We operate from wagoe.com, so the group is
            ;; com.wagoe. Phase 3 produced org.wagoe by swapping the second
            ;; segment of org.boundary-app and keeping the org. prefix, instead
            ;; of re-deriving the prefix from the new domain (BOU-213).
            ;;
            ;; Same dot-OR-path alternation as :coords, for the same reason: the
            ;; Clojars verification URL carries the group in path form
            ;; (clojars.org/repo/com/wagoe/...), and a sweep that only rewrote
            ;; the coord form would leave it pointing at a group that does not
            ;; exist — every artifact then reports as unpublished.
            :grep  ["-nIE" "org[./]wagoe"]
            :hard? true}
   :dirs   {:desc  "boundary-cli / boundary-mcp dir names"
            :grep  ["-nIE" "boundary-(cli|mcp)"]
            :hard? true}
   :urls   {:desc  "boundary-app.org / <owner>/boundary repo refs"
            ;; Match ANY owner, not just thijs-creemers: the repo URL also
            ;; appears under the old org (tcbv/boundary, in the systemd unit).
            ;; Pinning one owner would let Phase 5a miss the other variants.
            :grep  ["-nIE" "boundary-app\\.org|github\\.com/[A-Za-z0-9_-]+/boundary"]
            :hard? true}
   :prose  {:desc  "\"boundary\" word in prose (REPORT ONLY — also an arch term)"
            :grep  ["-nIiE" "boundary"]
            :paths ["*.md" "*.adoc"]
            :hard? false}})

(def ^:private default-allow-paths
  "Baked-in exemptions (prefix match on the repo-relative path). The rename
   tooling and its config legitimately contain the tokens; CHANGELOG + planning
   docs preserve history; the checker's own boundary-pathed source is exempt
   until Phase 1 moves it to wagoe/."
  ["CHANGELOG.md"
   "docs/superpowers/"
   ".wagoe/"
   "scripts/rename_wagoe.clj"
   "scripts/check_no_boundary.clj"])

(defn allow-paths
  "Every exempt path prefix: the baked-in defaults plus the config.

   Public so the report-only canary can exclude exactly what the gate excludes,
   instead of carrying its own copy of the list — a second registry to drift
   is what BOU-250 mechanism 3 is about, and the canary had one."
  []
  (let [f ".wagoe/check-no-boundary.edn"]
    (into default-allow-paths
          (when (fs/exists? f)
            (:allow-paths (edn/read-string (slurp f)))))))

(defn- allowed? [allow path]
  (some #(str/starts-with? path %) allow))

(def ^:dynamic *repo-dir*
  "Work tree to scan. nil means the process's own directory, which is what
   every real run wants. A test binds it to a throwaway git repo so the gate
   can be pointed at a planted violation — without it, `git grep` only ever
   sees this repository and the gate cannot be proven to fire (BOU-250)."
  nil)

(defn grep-group
  "Returns [path ...] of git-grep hit lines for a group, allowlist-filtered.
   Each element is the raw `path:line:content` string."
  [{:keys [grep paths]} allow]
  (let [args (concat ["git" "grep" "--no-color"] grep
                     (when (seq paths) (cons "--" paths)))
        opts (cond-> {:out :string :err :string :continue true}
               *repo-dir* (assoc :dir (str *repo-dir*)))
        {:keys [exit out]} (apply process/shell opts args)]
    ;; git grep exits 1 when there are no matches — that is success here.
    (if (#{0 1} exit)
      (->> (str/split-lines out)
           (remove str/blank?)
           (remove (fn [line]
                     (let [path (first (str/split line #":" 2))]
                       (allowed? allow path)))))
      (throw (ex-info "git grep failed" {:exit exit :args args})))))

(defn- files-of [hits]
  (->> hits (map #(first (str/split % #":" 2))) distinct sort))

;; =============================================================================
;; Allowlist audit — is every exemption still load-bearing?
;; =============================================================================

(defn all-hit-paths
  "Every path the hard groups match with NO allowlist applied.

   One unfiltered pass, so each entry can be judged against what the gate would
   actually flag without it."
  []
  (->> hard-groups
       (mapcat (fn [g] (grep-group (token-defs g) #{})))
       (map #(first (str/split % #":" 2)))
       distinct
       set))

(defn audit-allowlist
  "Classify every allowlist entry as load-bearing, redundant, inert or missing.

   The question is not `does this path contain a residual token` — that is
   answered by any overlapping entry and says nothing about this one. It is
   `would removing THIS entry expose a hit that nothing else covers`. Entries
   are walked in order (baked-in defaults first, then config), and one is
   load-bearing only when it is the first to cover some hit path.

   That distinction is not academic: four config entries duplicate a default
   verbatim. Under the weaker question all four looked load-bearing, because
   the paths do contain tokens — while deleting any of them would change
   nothing. The audit would have been reporting a property it never tested,
   which is the failure this whole ticket is about.

   Four outcomes:
   - :missing      the path is gone, or never existed. The Wagoe rename swept
                   this very file and turned scripts/check_no_boundary.clj into
                   scripts/check_no_wagoe.clj, a path that has never existed.
   - :inert        exists, but the gate finds nothing there to exempt.
   - :redundant    covers hits, but every one is already covered by an earlier
                   entry — a duplicate or a nested prefix.
   - :load-bearing removing it would expose at least one hit.

   Returns a map of those four keys to vectors of entries; :redundant holds
   [entry covering-entry] pairs."
  [entries]
  (let [hits     (all-hit-paths)
        covers   (fn [path] (set (filter #(str/starts-with? % path) hits)))
        ;; Exact duplicates are handled first and by position: under the
        ;; order-independent test below each copy is covered by the other, so
        ;; both would be flagged and neither could be removed without the
        ;; report changing its mind. Keep the first, report the rest.
        seen     (volatile! #{})
        tagged   (for [path entries
                       :let [dup? (contains? @seen path)]]
                   (do (vswap! seen conj path)
                       [path dup?]))
        unique-e (distinct entries)]
    (reduce
     (fn [acc [path dup?]]
       (let [mine (covers path)
             ;; Everything the OTHER entries cover. Order-independent on
             ;; purpose: allowlist matching is a prefix test over the whole
             ;; list, so "would removing this one expose a hit" cannot depend
             ;; on where it sits. An earlier-only comparison called
             ;; foo/bar.clj load-bearing when a later foo/ already covered it.
             others (->> unique-e
                         (remove #(= % path))
                         (mapcat covers)
                         set)]
         (cond
           dup?                     (update acc :redundant conj [path path])
           (not (fs/exists? path))  (update acc :missing conj path)
           (empty? mine)            (update acc :inert conj path)
           (empty? (set/difference mine others))
           (let [by (->> unique-e
                         (remove #(= % path))
                         (filter (fn [e] (seq (set/intersection mine (covers e)))))
                         first)]
             (update acc :redundant conj [path by]))
           :else                    (update acc :load-bearing conj path))))
     {:load-bearing [] :redundant [] :inert [] :missing []}
     tagged)))

(defn -main [& args]
  (let [selected (cond
                   (some #{"all"} args) (conj hard-groups :prose)
                   (seq args)           (map keyword args)
                   :else                hard-groups)
        allow    (allow-paths)
        results  (for [g selected
                       :let [def (token-defs g)]
                       :when def]
                   (let [hits (grep-group def allow)]
                     {:group g :hard? (:hard? def) :desc (:desc def)
                      :hits hits :files (files-of hits)}))
        hard-fail (->> results (filter :hard?) (mapcat :hits) count)]
    (println (ansi-bold "Wagoe rename — residual boundary scan"))
    (println)
    (doseq [{:keys [group hard? desc hits files]} results]
      (let [n (count hits)
            tag (cond (not hard?)     (ansi-yellow "report")
                      (zero? n)       (ansi-green "clean ")
                      :else           (ansi-red   "RESIDUAL"))]
        (println (format "  %-8s %s  %4d hits / %d files  — %s"
                         (name group) tag n (count files) desc))
        (when (and hard? (pos? n))
          (doseq [f (take 8 files)] (println (str "      " f)))
          (when (> (count files) 8)
            (println (str "      … +" (- (count files) 8) " more files"))))))
    (println)

    ;; The allowlist is audited on every run rather than on request. An
    ;; exemption that stopped matching is invisible by construction — nothing
    ;; fails, the gate just quietly covers less than its config claims.
    (let [{:keys [inert missing redundant]} (audit-allowlist allow)
          rot (+ (count inert) (count missing) (count redundant))]
      (when (pos? rot)
        (println (ansi-yellow "Allowlist entries that suppress nothing of their own:"))
        (doseq [p missing]
          (println (str "  " p "  — path does not exist")))
        (doseq [p inert]
          (println (str "  " p "  — exists, but the gate finds nothing there to exempt")))
        (doseq [[p by] redundant]
          (println (str "  " p "  — "
                        (cond
                          (= p by) "duplicates an earlier entry with the same path"
                          by       (str "nested under " by ", which already covers it")
                          :else    "every path it covers is already covered earlier"))))
        (println)
        (println (str "Remove them from the allowlist config (or default-allow-paths). "
                      "A stale entry switches the gate off for a path that does not need it."))
        (println))

      (cond
        (pos? hard-fail)
        (do (println (ansi-red (format "%d residual hard-token hit(s) remain." hard-fail)))
            (System/exit 1))

        (pos? rot)
        (do (println (ansi-red (format "%d stale allowlist entr%s."
                                       rot (if (= 1 rot) "y" "ies"))))
            (System/exit 1))

        :else
        (do (println (ansi-green "No residual hard boundary tokens; allowlist is fully load-bearing."))
            (System/exit 0))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
