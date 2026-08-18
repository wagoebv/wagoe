#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/check_error_shape.clj
;;
;; The error convention of ADR-036, enforced.
;;
;; ADR-022 decided in April that every exception reaching the HTTP boundary
;; carries a `:type` keyword. Nothing checked it, and by August 59 `ex-info`
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
  "The substring of `code` from `start` (an opening delimiter) through its match,
   or nil.

   Character literals are skipped rather than counted. `code-only` keeps them —
   check:isolation needs them intact — so a map using a character literal as a
   separator would otherwise read one delimiter deeper than it is, and every
   finding after it in the file would be lost."
  [code start]
  (let [open  (nth code start)
        close ({\( \) \{ \} \[ \]} open)
        n     (count code)]
    (when close
      (loop [i (inc start), depth 1]
        (cond
          (zero? depth)  (subs code start i)
          (>= i n)       nil
          :else (let [c (nth code i)]
                  (cond
                    (= c \\) (recur (+ i 2) depth)          ;; a character literal
                    (= c open)  (recur (inc i) (inc depth))
                    (= c close) (recur (inc i) (dec depth))
                    :else       (recur (inc i) depth))))))))

(defn- line-of
  "1-based line number of index `idx` in `text`."
  [text idx]
  (inc (count (re-seq #"\n" (subs text 0 idx)))))

(defn top-level-keys
  "`{key-string -> [start end]}` for the *top level* of a map literal.

   `form` is the map's code-only text; the spans index into it, and because
   code-only preserves length they index the raw text identically.

   Top level is the point. `:type` inside `{:data {:type :x}}` does not give the
   exception a type, and an `:error` inside `{:result {:error …}}` is not the
   failure detail — a substring search says otherwise for both. Keys are read
   whole, so `:errors` and `:error-message` are not `:error`."
  [form]
  (let [n (count form)]
    (loop [i 1, depth 0, acc {}, pending nil]
      (if (>= i n)
        acc
        (let [c (nth form i)]
          (cond
            (= c \\)                       (recur (+ i 2) depth acc pending)
            (#{\{ \[ \(} c)
            (if (and (zero? depth) pending)
              ;; A value that is itself a collection: record its whole span.
              (let [sub (balanced-form form i)
                    end (if sub (+ i (count sub)) (inc i))]
                (recur end depth (assoc acc pending [i end]) nil))
              (recur (inc i) (inc depth) acc pending))

            (#{\} \] \)} c)               (recur (inc i) (dec depth) acc pending)

            (and (zero? depth) (= c \:) (nil? pending))
            (let [m (re-find #"^:[^\s\(\)\[\]\{\},;\"]+" (subs form i))]
              (if m
                (recur (+ i (count m)) depth acc m)
                (recur (inc i) depth acc pending)))

            (and (zero? depth) pending (not (Character/isWhitespace c)))
            ;; A scalar value: to the next whitespace or delimiter.
            (let [m (re-find #"^[^\s\(\)\[\]\{\},;]+" (subs form i))
                  end (+ i (count (or m " ")))]
              (recur end depth (assoc acc pending [i end]) nil))

            :else (recur (inc i) depth acc pending)))))))

(defn- value-at
  "The raw text of the value `k` holds in `form`, or nil when the key is absent.

   Read from `raw` rather than from the code-only projection: a blanked string
   is indistinguishable from a computed expression, and that difference is the
   whole line between `:type \"SmtpError\"` (a finding) and
   `:type (or (:reason c) :forbidden)` (not one)."
  [form raw k]
  (when-let [[a b] (get (top-level-keys form) k)]
    (str/trim (subs raw a (min b (count raw))))))

;; =============================================================================
;; Rule 1 — a thrown ex-info carries :type  (ADR-022, unenforced since April)
;; =============================================================================

(defn- throw-sites
  "[index …] of every `(throw (ex-info` in `code`, metadata hints included."
  [code]
  (loop [from 0, acc []]
    (if-let [i (str/index-of code "(throw" from)]
      (let [head (subs code i (min (count code) (+ i 60)))]
        (recur (inc i)
               (if (re-find #"^\(throw\s+(\^\S+\s+)?\(ex-info\b" head) (conj acc i) acc)))
      acc)))

(defn- ex-info-data-map
  "Span of the data-map literal inside a `(throw (ex-info …))` form, or nil.

   Found by depth, not by skipping the message argument: in the code-only
   projection a string message is blank, so a scan that expected to step over
   `\"boom\"` stepped over nothing and every throw read as having no map. The
   data map is the first `{` that sits directly inside the `ex-info` call."
  [form]
  (when-let [m (re-find #"^\(throw\s+(?:\^\S+\s+)?\(ex-info\b" form)]
    (loop [i (count m), depth 0]
      (when (< i (count form))
        (let [c (nth form i)]
          (cond
            (= c \\)                 (recur (+ i 2) depth)
            (and (zero? depth) (= c \{)) i
            (#{\( \[ \{} c)          (recur (inc i) (inc depth))
            (#{\) \] \}} c)          (if (and (zero? depth) (= c \)))
                                       nil                    ;; call closed, no map
                                       (recur (inc i) (dec depth)))
            :else                    (recur (inc i) depth)))))))

(defn untyped-throw-findings
  "Every `(throw (ex-info … {…}))` whose *literal* data map has no top-level
   `:type`.

   `(throw (ex-info msg err))` — a map built elsewhere — is not judged: that is
   how a shell rethrows what a pure validator produced, and a static check that
   guessed would report it. Neither is `(merge {…} {…})`: an earlier version
   took the first `{` anywhere in the form and called a merged map untyped."
  ([text path] (untyped-throw-findings text path (iso/code-only text)))
  ([text path code]
   (for [i     (throw-sites code)
         :let  [form (balanced-form code i)]
         :when form
         :let  [open (ex-info-data-map form)
                data (when open (balanced-form form open))]
         :when (and data (not (contains? (top-level-keys data) ":type")))]
     {:rule :untyped-throw
      :file path
      :line (line-of text i)})))

;; =============================================================================
;; Rule 2 — a {:success? false} return carries {:error {:type <keyword>}}
;; =============================================================================

(defn- map-literals
  "[index …] of every map literal in `code`."
  [code]
  (loop [i 0, acc []]
    (if-let [j (str/index-of code "{" i)]
      ;; `#{` is a set, and `#_{…}` is discarded code — neither is a map to judge.
      (let [prev (when (pos? j) (nth code (dec j)))]
        (recur (inc j) (if (#{\# \\} prev) acc (conj acc j))))
      acc)))

(defn failure-map-findings
  "Every map literal whose top-level `:success?` is `false` and whose failure
   detail is missing or malformed.

   Keyed on the parsed map, not on the string `\"{:success? false\"`. That
   literal is how the first version scanned, and it missed every map that wrote
   the key second (`{:error … :success? false}`) or aligned its values with
   extra spaces — which is how `libs/push`'s four violations were invisible to
   a gate that reported the tree clean."
  ([text path] (failure-map-findings text path (iso/code-only text)))
  ([text path code]
   (for [i     (map-literals code)
         :let  [form (balanced-form code i)]
         :when form
         :let  [raw   (subs text i (+ i (count form)))
                keys* (top-level-keys form)
                succ  (value-at form raw ":success?")]
         :when (= "false" succ)
         :let  [err  (value-at form raw ":error")
                line (line-of text i)
                finding
                (cond
                  (not (contains? keys* ":error")) {:rule :no-error :file path :line line}
                  (str/starts-with? err "\"")      {:rule :string-error :file path :line line}
                  (str/starts-with? err ":")       {:rule :non-map-error :file path :line line}
                  (str/starts-with? err "{")
                  (let [emap  (balanced-form err 0)
                        eraw  emap
                        tval  (when emap (value-at emap eraw ":type"))]
                    (cond
                      (nil? tval)                   {:rule :no-type :file path :line line}
                      (str/starts-with? tval "\"")  {:rule :string-type :file path :line line}
                      :else nil))
                  ;; Anything else is computed, and this gate does not guess.
                  :else nil)]
         :when finding]
     finding)))

;; =============================================================================
;; Scan
;; =============================================================================

(defn- shell-file?
  "True for a source file under a `shell/` directory.

   Rule 1 is a boundary rule: `core/` is covered by check:fcis, which forbids
   throwing there at all."
  [path]
  (str/includes? (str/replace path "\\" "/") "/shell/"))

(defn scanned-files
  "Every source file this gate reads.

   Exposed so the firing test can assert the scan has inputs without asserting
   that the tree is still broken — the check-isolation test documents why that
   distinction matters: a proof built on \"findings exist\" goes red the day the
   burn-down finishes, and the cheapest fix then is deleting the proof."
  []
  (let [roots (concat (for [lib (iso/libs)] (fs/file root-dir "libs" lib "src"))
                      [(fs/file root-dir "src")])]
    (for [root  roots
          :when (fs/exists? root)
          f     (fs/glob root "**/*.clj{,c}")
          :when (fs/regular-file? f)]
      f)))

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
                 text (slurp (fs/file f))
                 ;; One projection per file, not one per rule: code-only is
                 ;; two thirds of this gate's runtime.
                 code (iso/code-only text)]
          fnd   (concat (when (shell-file? path) (untyped-throw-findings text path code))
                        (failure-map-findings text path code))]
      fnd)))

;; =============================================================================
;; Allowlist
;; =============================================================================

(defn parse-allowlist
  "`[{:file … :rule … :count n :why …}]` → `{[file rule] count}`.

   `:why` is mandatory, and a missing one is a hard error rather than a skipped
   entry: an exemption nobody had to justify is the one that outlives its
   reason.

   `:count` is mandatory too, and it is what keeps this a burn-down list. With
   file+rule alone, one entry covering a file's eight untyped throws exempts a
   ninth added tomorrow — the gate stays green while the tree gets worse, which
   is the failure mode every allowlist in this repository is written to avoid."
  [entries]
  (into {}
        (for [{:keys [file rule count why] :as entry} entries]
          (do
            (when (str/blank? why)
              (throw (ex-info (str "check-error-shape allowlist entry without :why: " (pr-str entry))
                              {:type :invalid-allowlist :entry entry})))
            (when-not (and (integer? count) (pos? count))
              (throw (ex-info (str "check-error-shape allowlist entry without a positive :count: "
                                   (pr-str entry))
                              {:type :invalid-allowlist :entry entry})))
            [[file (or rule :any)] count]))))

(defn read-allowlist []
  (let [f (fs/file root-dir allowlist-path)]
    (if (fs/exists? f)
      (parse-allowlist (edn/read-string (slurp f)))
      ;; Absent is not empty. A gate whose allowlist was never committed reports
      ;; every pre-existing finding as new, which is how this one first went red
      ;; in CI — .wagoe/* is gitignored, and the un-ignore line was missing.
      (throw (ex-info (str "check-error-shape allowlist not found: " allowlist-path)
                      {:type :missing-allowlist :path allowlist-path})))))

(defn unexplained
  "Findings beyond what the allowlist accounts for, per [file rule].

   Counted rather than matched: an entry exempts the violations a file had, not
   the file itself."
  [findings allow]
  (->> (group-by (juxt :file :rule) findings)
       (mapcat (fn [[k fs*]]
                 (let [budget (or (get allow k) (get allow [(first k) :any]) 0)]
                   (drop budget (sort-by :line fs*)))))
       (sort-by (juxt :file :line))))

(defn stale-exemptions
  "Allowlist entries with nothing left to exempt, or more budget than findings —
   what makes this a burn-down list rather than a drawer."
  ([] (stale-exemptions (all-findings) (read-allowlist)))
  ([findings allow]
   (let [counts (frequencies (map (juxt :file :rule) findings))
         files  (set (map :file findings))]
     (->> allow
          (keep (fn [[[file rule] budget]]
                  (let [have (if (= :any rule)
                               (if (contains? files file) budget 0)
                               (get counts [file rule] 0))]
                    (when (< have budget)
                      [file rule budget have]))))
          (sort-by pr-str)))))

(defn unexplained-findings []
  (unexplained (all-findings) (read-allowlist)))

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
        bad   (unexplained all allow)
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
            (doseq [[file rule budget have] stale]
              (println (str "      " file " (" rule "): exempts " budget
                            ", finds " have)))
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
