#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/check_error_shape.clj
;;
;; The error convention of ADR-036, enforced.
;;
;; ADR-022 decided in April that every exception reaching the HTTP boundary
;; carries a `:type` keyword. Nothing checked it, and by August ~79 `ex-info`
;; throws in `shell/` namespaces had none — the largest single item in the
;; BOU-323 migration, and invisible until someone counted.
;;
;; ADR-036 added the half ADR-022 never covered: what a function *returns* when
;; it does not throw. Five shapes had grown there. The one this gate can check
;; is the failure branch of `{:success? false}` — `:error` must be a map, and
;; the `:type` inside it must be a keyword, so a caller that escalates can
;; rethrow it as a typed `ex-info` without inventing a taxonomy.
;;
;; What this gate deliberately does not do:
;;
;;   - It does not re-check "core must not throw". `check:fcis` already does,
;;     with its own exemption mechanism, and restating the rule here would drop
;;     those exemptions.
;;   - It does not judge whether a given failure *should* have been a throw or
;;     a return. That is the reading of a situation; this reads shapes.
;;   - It cannot see computed values — `(assoc result :success? false)`,
;;     `{:success? (boolean …)}`, `:type (or (:reason check) :forbidden)`.
;;     Those are reviewed, not gated, and pretending otherwise would be the
;;     kind of gate that reports green over what it cannot see.
;;
;; Escape hatch: `.wagoe/check-error-shape.edn`, one justification per entry.
;; It ships non-empty on purpose — it is the burn-down list BOU-323 empties,
;; and an entry that stops exempting anything fails the build.

(ns wagoe.tools.check-error-shape
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [wagoe.tools.ansi :as ansi]
            [wagoe.tools.check-isolation :as iso]))

(def ^:private root-dir (fs/file (System/getProperty "user.dir")))

(def allowlist-path ".wagoe/check-error-shape.edn")

;; =============================================================================
;; Reading forms out of text
;; =============================================================================

(defn- balanced-form
  "The substring of `text` starting at `start` (an opening paren or brace) up to
   its matching close, or nil.

   Reads the code-only projection, so a paren inside a string or a comment
   cannot end a form early."
  [code start]
  (let [open  (nth code start)
        close ({\( \) \{ \} \[ \]} open)]
    (when close
      (loop [i (inc start), depth 1]
        (cond
          (>= i (count code)) nil
          (zero? depth)       (subs code start i)
          :else (let [c (nth code i)]
                  (recur (inc i) (cond (= c open)  (inc depth)
                                       (= c close) (dec depth)
                                       :else       depth))))))))

(defn- line-of
  "1-based line number of index `idx` in `text`."
  [text idx]
  (inc (count (re-seq #"\n" (subs text 0 idx)))))

;; =============================================================================
;; Rule 1 — a thrown ex-info carries :type  (ADR-022, unenforced since April)
;; =============================================================================

(defn- throw-sites
  "[index …] of every `(throw (ex-info` in `code`."
  [code]
  (loop [from 0, acc []]
    (if-let [i (str/index-of code "(throw" from)]
      (let [head (subs code i (min (count code) (+ i 40)))]
        (recur (inc i)
               (if (re-find #"^\(throw\s+\(ex-info\b" head) (conj acc i) acc)))
      acc)))

(defn untyped-throw-findings
  "Every `(throw (ex-info … {…}))` in `text` whose *literal* data map has no
   `:type` key."
  [text path]
  (let [code (iso/code-only text)]
    (for [i     (throw-sites code)
          :let  [form (balanced-form code i)]
          :when form
          ;; The data map, when it is a literal: the first `{` inside the form.
          :let  [brace (str/index-of form "{")
                 data  (when brace (balanced-form form brace))]
          ;; No literal map at all — `(ex-info msg err)` — is not judged here.
          :when (and data (not (re-find #":type\b" data)))]
      {:rule :untyped-throw
       :file path
       :line (line-of text i)})))

;; =============================================================================
;; Rule 2 — a {:success? false} return carries {:error {:type <keyword>}}
;; =============================================================================

(defn failure-map-findings
  "Every `{:success? false …}` literal in `text` whose failure detail is missing
   or malformed.

   Three ways it goes wrong, all present in the tree: no `:error` at all, an
   `:error` that is a bare string, and an `:error` map whose `:type` is a string
   literal (`\"SmtpError\"`) where every other `:type` in the framework is a
   keyword.

   Structure is read from the code-only projection so a brace inside a string
   cannot end a form early; the *values* are read from the raw text at the same
   indices, because code-only blanks strings and a blanked string is
   indistinguishable from a computed expression. That distinction is the
   difference between flagging `:type \"SmtpError\"` (a finding) and
   `:type (or (:reason check) :forbidden)` (not one — this gate does not guess
   at computed values)."
  [text path]
  (let [code (iso/code-only text)]
    (loop [from 0, acc []]
      (if-let [i (str/index-of code "{:success? false" from)]
        (let [form (balanced-form code i)
              line (line-of text i)
              ;; Same span, raw: strings are visible again.
              raw  (when form (subs text i (+ i (count form))))
              ei   (when form (str/index-of form ":error"))
              val  (when ei (str/triml (subs raw (+ ei 6))))
              finding
              (cond
                (nil? form) nil
                (nil? ei)   {:rule :no-error :file path :line line}

                (str/starts-with? val "\"")
                {:rule :string-error :file path :line line}

                (str/starts-with? val "{")
                (let [emap (balanced-form val 0)
                      ti   (when emap (str/index-of emap ":type"))
                      tval (when ti (str/triml (subs emap (+ ti 5))))]
                  (when (and tval (str/starts-with? tval "\""))
                    {:rule :string-type :file path :line line}))

                :else nil)]
          (recur (inc i) (cond-> acc finding (conj finding))))
        acc))))

;; =============================================================================
;; Scan
;; =============================================================================

(defn- shell-file?
  "True for a source file under a `shell/` directory.

   Rule 1 is a boundary rule: `core/` is covered by check:fcis, which forbids
   throwing there at all."
  [path]
  (str/includes? (str/replace path "\\" "/") "/shell/"))

(defn all-findings
  "Every violation in the tree, allowlist not applied."
  []
  (let [roots (concat (for [lib (iso/libs)] (fs/file root-dir "libs" lib "src"))
                      [(fs/file root-dir "src")])]
    (for [root  roots
          :when (fs/exists? root)
          f     (fs/glob root "**/*.clj{,c}")
          :when (fs/regular-file? f)
          :let  [path (str (fs/relativize root-dir f))
                 text (slurp (fs/file f))]
          fnd   (concat (when (shell-file? path) (untyped-throw-findings text path))
                        (failure-map-findings text path))]
      fnd)))

;; =============================================================================
;; Allowlist
;; =============================================================================

(defn parse-allowlist
  "`[{:file … :rule … :why …}]` → #{[file rule]}.

   `:why` is mandatory, and a missing one is a hard error rather than a skipped
   entry: an exemption nobody had to justify is the one that outlives its
   reason."
  [entries]
  (into #{}
        (for [{:keys [file rule why] :as entry} entries]
          (do
            (when (str/blank? why)
              (throw (ex-info (str "check-error-shape allowlist entry without :why: " (pr-str entry))
                              {:type :invalid-allowlist :entry entry})))
            [file (or rule :any)]))))

(defn read-allowlist []
  (let [f (fs/file root-dir allowlist-path)]
    (if (fs/exists? f) (parse-allowlist (edn/read-string (slurp f))) #{})))

(defn allowed?
  [allow {:keys [file rule]}]
  (boolean (or (contains? allow [file rule])
               (contains? allow [file :any]))))

(defn stale-exemptions
  "Allowlist entries with nothing left to exempt — what makes this a burn-down
   list rather than a drawer."
  ([] (stale-exemptions (all-findings) (read-allowlist)))
  ([findings allow]
   (let [found (set (map (juxt :file :rule) findings))
         files (set (map :file findings))]
     (->> allow
          (remove (fn [[file rule]]
                    (if (= :any rule)
                      (contains? files file)
                      (contains? found [file rule]))))
          (sort-by pr-str)))))

(defn unexplained-findings []
  (let [allow (read-allowlist)]
    (remove #(allowed? allow %) (all-findings))))

;; =============================================================================
;; Report
;; =============================================================================

(def ^:private rule-text
  {:untyped-throw "thrown ex-info with no :type in its data map (ADR-022)"
   :no-error      "{:success? false} with no :error (ADR-036 §3)"
   :string-error  "{:success? false} whose :error is a string, not a map"
   :string-type   "{:success? false} whose :error :type is a string, not a keyword"})

(defn -main [& _]
  (println "Verifying errors carry the shape ADR-022 and ADR-036 decided")
  (let [all   (all-findings)
        allow (read-allowlist)
        bad   (remove #(allowed? allow %) all)
        stale (stale-exemptions all allow)
        known (- (count all) (count bad))]

    (when (empty? (iso/libs))
      (binding [*out* *err*]
        (println (ansi/red "  ✗ found no libraries at all — the check is not looking at anything")))
      (System/exit 1))

    (cond
      (seq bad)
      (do (binding [*out* *err*]
            (println (ansi/red (str "  ✗ " (count bad) " error"
                                    (when (not= 1 (count bad)) "s")
                                    " with the wrong shape")))
            (println)
            (doseq [{:keys [file line rule]} (sort-by (juxt :file :line) bad)]
              (println (str "      " (ansi/red (str file ":" line)) " — " (rule-text rule))))
            (println)
            (println "  ADR-036 decides the shapes; dev-docs/adr/ADR-036-error-convention.adoc")
            (println (str "  If one is deliberate, add it to " allowlist-path " with a :why.")))
          (System/exit 1))

      (seq stale)
      (do (binding [*out* *err*]
            (println (ansi/red (str "  ✗ " (count stale) " allowlist entr"
                                    (if (= 1 (count stale)) "y" "ies")
                                    " no longer exempt anything")))
            (println)
            (doseq [[file rule] stale]
              (println (str "      " file " (" rule ")")))
            (println)
            (println "  The violation is gone — remove the entry. Left in place it is a")
            (println "  pre-approval for the next regression of the same shape."))
          (System/exit 1))

      :else
      (do (println (ansi/green (str "  ✓ every error carries its shape"
                                    (when (pos? known)
                                      (str " (" known " allowed by "
                                           allowlist-path ")")))))
          (System/exit 0)))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
