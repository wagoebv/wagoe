#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/check_isolation.clj
;;
;; A library may not reach for a namespace it neither owns nor declares.
;;
;; BOU-304. "30 independently publishable libraries" was documented and never
;; checked: no CI job built a library against its own deps.edn, so the claim
;; rested on nobody having broken it.
;;
;; The obvious gate is to compile each library in isolation, and the CI matrix
;; job added alongside this does exactly that. It is not enough, and the measured
;; numbers say why: 30 of 31 libraries compile clean against their own deps.edn —
;; including `realtime`, the library the assessment names as broken.
;;
;; `libs/realtime/.../jwt_adapter.clj` required `wagoe.user.shell.auth` at the
;; top level, inside a try/catch. The require ran, failed, and was swallowed.
;; The namespace loaded, a compile job went green, and the adapter threw on
;; first use — from Clojars, in a user's application, with realtime's only JWT
;; verifier. (Fixed in BOU-305; the shape is why this gate exists.)
;;
;; A compile answers "does it load". The claim is "does it work without the
;; libraries it does not declare". So this gate reads the loading forms
;; themselves: require, requiring-resolve, the-ns, resolve. Matching only
;; `require` would leave the other three as the documented way round the gate.
;;
;; What it deliberately does not match: every `wagoe.*` string. A first pass that
;; did reported 19 of 31 libraries, and almost all of it was Integrant keys
;; (`:wagoe.observability/logger` names a component, not a namespace to load)
;; and docstrings.
;;
;; Escape hatch: `.wagoe/check-isolation.edn`, which requires a justification per
;; entry. It ships non-empty on purpose — it is the burn-down list that
;; BOU-305, BOU-306 and BOU-307 empty.

(ns wagoe.tools.check-isolation
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [wagoe.tools.ansi :as ansi]))

(def ^:private root-dir (fs/file (System/getProperty "user.dir")))

(defn libs
  "Every library directory under libs/ that carries a deps.edn."
  []
  (->> (fs/list-dir (fs/file root-dir "libs"))
       (filter fs/directory?)
       (filter #(fs/exists? (fs/file % "deps.edn")))
       (map #(str (fs/file-name %)))
       sort))

(defn namespace-owners
  "{\"wagoe.realtime\" -> \"realtime\"}, read from the tree.

   Derived rather than named, because no string rule maps all 31: `shared-ui`
   owns `wagoe.shared`, `wagoe-cli` owns `wagoe.cli`, `ui-style` owns
   `wagoe.ui-style`. A convention the tree does not follow is one somebody has
   to maintain by hand, which is how every other drift in this epic started."
  []
  (into {}
        (for [lib   (libs)
              :let  [root (fs/file root-dir "libs" lib "src" "wagoe")]
              :when (fs/exists? root)
              entry (fs/list-dir root)
              :let  [seg (-> (str (fs/file-name entry))
                             (str/replace #"\.clj[cs]?$" "")
                             (str/replace "_" "-"))]]
          [(str "wagoe." seg) lib])))

(defn declared-deps
  "The library directory names `lib` declares as dependencies.

   In-repo libraries are declared by `:local/root`, not by coordinate:
   `wagoe/platform {:local/root \"../platform\"}`. `com.wagoe/wagoe-platform`
   is the *published* coordinate, which `build.clj` substitutes at pom time —
   `check_poms.clj` documents that translation.

   An earlier version of this read `com.wagoe/` keys, so it matched nothing in
   any of the 31 libraries, `declared-deps` was always empty, and the \"nor
   declares\" half of this gate never ran. Every cross-library load looked
   smuggled, including the ones properly declared, and four of them reached the
   burn-down list with confident justifications attached. The directory name
   under `:local/root` is the identity `namespace-owners` uses, so it is the one
   to compare against."
  [lib]
  (let [f (fs/file root-dir "libs" lib "deps.edn")]
    (if-not (fs/exists? f)
      #{}
      (let [deps (:deps (edn/read-string (slurp f)))]
        (set (concat
              ;; The in-repo form.
              (for [[_ coord] deps
                    :let  [root (:local/root coord)]
                    :when root]
                (str (fs/file-name (fs/normalize (fs/file root)))))
              ;; The published form, in case a library ever pins a sibling from
              ;; Clojars rather than by path. Both spellings, because the
              ;; artifact-to-directory mapping is not a single rule:
              ;; com.wagoe/wagoe-user is libs/user, com.wagoe/wagoe-cli is
              ;; libs/wagoe-cli. Stripping unconditionally made the latter two
              ;; impossible to declare.
              (mapcat (fn [k] [(name k) (str/replace (name k) #"^wagoe-" "")])
                      (filter #(str/starts-with? (str %) "com.wagoe/") (keys deps)))))))))

;; =============================================================================
;; Namespaces, for the isolated-build matrix
;; =============================================================================

(defn namespaces-of
  "Every namespace `lib` ships under src/, sorted.

   Here rather than in the workflow because the shell version of this was
   `find | sed -e 's|\\.cljc\\?$||' …`, and `\\?` is GNU basic-regex — BSD sed
   leaves the extension on, so the emitted form asked for a namespace with a
   trailing .clj in its name. It would have worked on the Ubuntu runner and
   failed for anyone reproducing the job on a Mac, which is the same portability
   trap `bb bump` removed from the release procedure (BOU-316)."
  [lib]
  (let [src (fs/file root-dir "libs" lib "src")]
    (if-not (fs/exists? src)
      []
      (->> (fs/glob src "**/*.clj{,c}")
           (filter fs/regular-file?)
           (map #(-> (str (fs/relativize src %))
                     (str/replace #"\.cljc?$" "")
                     (str/replace "_" "-")
                     (str/replace fs/file-separator ".")))
           sort
           vec))))

(defn require-form
  "A form that loads every namespace `lib` ships, for `clojure -M -e`.

   Empty string when the library has none — `libs/e2e` carries a deps.edn and
   no src/, and a job that fails on it would be reporting the wrong thing."
  [lib]
  (let [nss (namespaces-of lib)]
    (if (empty? nss)
      ""
      (str "(do " (str/join " " (map #(str "(require '" % ")") nss)) " :ok)"))))

;; =============================================================================
;; Detection
;; =============================================================================

(def ^:private namespace-reference-re
  "Any reference to a wagoe namespace in code, however it is written.

   Three shapes have to match, and earlier versions of this each missed some:

     (require '[wagoe.user.auth :as a])   dynamic, quoted
     (:require [wagoe.user.auth :as a])   static, in an ns form
     (wagoe.user.auth/validate token)     fully qualified, no require in sight

   Anchoring on a verb — `\\((?:require|the-ns|resolve|…)\\s+'…` — missed a
   second namespace in one require and a require whose namespace sits on the
   next line. Requiring the quote missed both remaining shapes, and that gap had
   a hole in it exactly where the gate could least afford one: `libs/tools` is
   the one library the isolated-build matrix cannot cover, because its runtime
   is Babashka rather than the JVM. A static undeclared require there passed
   both jobs.

   So it matches the namespace, not its context. The lookbehind excludes a
   preceding `:`, which is the whole reason this is usable:
   `:wagoe.observability/logger` is an Integrant component key naming a
   component, not a namespace to load. It also excludes a preceding word
   character or dot, so a longer namespace is matched once rather than at every
   internal boundary.

   Strings, comments and `(comment …)` blocks are removed by `code-only` before
   this runs. Without that, every docstring showing a require as an example is a
   finding — matching raw text this way reported 19 of 31 libraries."
  #"(?<![:\w.-])wagoe\.[a-z0-9-]+(?:\.[a-z0-9-]+)*")

(def ^:private comment-form-re
  "The opening of a rich `(comment …)` block."
  #"^\(comment(?=[\s\)])")

(defn code-only
  "`text` with strings, comments and `(comment …)` blocks blanked, lines intact.

   Necessary rather than fastidious. This gate reads quoted namespace symbols,
   and a docstring showing `(require '[wagoe.user.auth :as a])` as an *example*
   contains one — so scanning raw text made this namespace's own documentation a
   finding against `libs/tools`, twice, and turned the `ai` library's prompt
   templates into dependencies on namespaces that do not exist.

   Blanking rather than deleting keeps line numbers and columns true, so a
   finding still points at the right line.

   Character-level rather than regex, because the constructs nest: a `;` inside
   a string is not a comment, a quote inside a comment does not open one, and a
   character literal is neither. A regex for any one of them gets the others
   wrong.

   The character-literal case is not hypothetical — it is how this function's
   own file desynchronised. Clojure writes a literal double-quote as a
   backslash followed by a quote, and a scanner that does not know that reads
   the quote as opening a string, then treats everything up to the next one as
   string content. This namespace contains several, so its own docstrings were
   handed back as code and reported as findings against libs/tools.

   `(comment …)` blocks go too. They are read but never evaluated, so a
   namespace named inside one is not loaded. `libs/platform/.../ports/http.clj`
   carries a worked example in one, referencing four `wagoe.user.*` namespaces
   — which reached the burn-down list as a dependency platform 'has to invert',
   with a justification written for a thing that does not happen."
  [text]
  (let [sb (StringBuilder.)]
    (loop [chars (seq text), state :code, depth 0]
      (when-let [c (first chars)]
        (case state
          :code
          (cond
            ;; A character literal: the next character is data, whatever it is.
            ;; Both are kept — they cannot name a namespace, and dropping them
            ;; would shift columns.
            (= c \\) (do (.append sb c)
                         (when-let [n (second chars)] (.append sb n))
                         (recur (drop 2 chars) :code depth))
            (= c \") (do (.append sb \") (recur (rest chars) :string depth))
            (= c \;) (do (.append sb \space) (recur (rest chars) :comment depth))
            (and (= c \() (re-find comment-form-re (apply str (take 9 chars))))
            (do (.append sb \space) (recur (rest chars) :discard 1))
            :else    (do (.append sb c) (recur (rest chars) :code depth)))

          :string
          (cond
            ;; An escaped character cannot close the string, and both are
            ;; blanked so an escaped quote inside cannot be read as an opener.
            (= c \\) (do (.append sb "  ") (recur (drop 2 chars) :string depth))
            (= c \") (do (.append sb \") (recur (rest chars) :code depth))
            :else    (do (.append sb (if (= c \newline) \newline \space))
                         (recur (rest chars) :string depth)))

          :comment
          (if (= c \newline)
            (do (.append sb \newline) (recur (rest chars) :code depth))
            (do (.append sb \space) (recur (rest chars) :comment depth)))

          ;; Inside a (comment …) block. Everything is blanked, but strings,
          ;; character literals and line comments still have to be recognised —
          ;; otherwise a paren inside one of them ends the block early and the
          ;; rest of the file is scanned as if the example were code.
          :discard
          (cond
            (= c \\) (do (.append sb "  ") (recur (drop 2 chars) :discard depth))
            (= c \") (do (.append sb \space) (recur (rest chars) :discard-string depth))
            (= c \;) (do (.append sb \space) (recur (rest chars) :discard-comment depth))
            (= c \() (do (.append sb \space) (recur (rest chars) :discard (inc depth)))
            (= c \)) (do (.append sb \space)
                         (recur (rest chars) (if (= 1 depth) :code :discard) (dec depth)))
            :else    (do (.append sb (if (= c \newline) \newline \space))
                         (recur (rest chars) :discard depth)))

          :discard-string
          (cond
            (= c \\) (do (.append sb "  ") (recur (drop 2 chars) :discard-string depth))
            (= c \") (do (.append sb \space) (recur (rest chars) :discard depth))
            :else    (do (.append sb (if (= c \newline) \newline \space))
                         (recur (rest chars) :discard-string depth)))

          :discard-comment
          (if (= c \newline)
            (do (.append sb \newline) (recur (rest chars) :discard depth))
            (do (.append sb \space) (recur (rest chars) :discard-comment depth))))))
    (str sb)))

(defn- owning-lib
  "The library owning `ns-name`, or nil when no library does.

   nil is a finding rather than a pass: `wagoe.config` and `wagoe.test-support`
   live in the application. A published library reaching for one of them is
   broken in the same way, and worse — it is the thing a downstream user
   certainly does not have."
  [owners ns-name]
  (get owners (str/join "." (take 2 (str/split ns-name #"\.")))))

(defn smuggle-findings
  "Namespaces `text` loads that `lib` neither owns nor declares.

   `declared` is a set of library names. Pure, so the gate can be proven to fire
   without a repository to break."
  ([lib declared text path] (smuggle-findings lib declared text path (namespace-owners)))
  ([lib declared text path owners]
   (let [own (set (for [[ns-name owner] owners :when (= lib owner)] ns-name))]
     (for [[idx line] (map-indexed vector (str/split-lines (code-only text)))
           ns-name (re-seq namespace-reference-re line)
           :let  [top   (str/join "." (take 2 (str/split ns-name #"\.")))
                  owner (owning-lib owners ns-name)]
           :when (not (contains? own top))
           :when (not (contains? declared owner))]
       {:lib*      lib
        :file      path
        :line      (inc idx)
        :namespace ns-name
        :lib       owner}))))

;; =============================================================================
;; Allowlist
;; =============================================================================

(def allowlist-path ".wagoe/check-isolation.edn")

(defn parse-allowlist
  "`{[lib namespace]}` from an allowlist map.

   Every entry must carry a non-blank `:why`. An exemption with no stated reason
   is indistinguishable from an unfixed bug (BOU-250), so it is rejected loudly
   rather than honoured."
  [m]
  (let [entries (:allow m)]
    (doseq [{:keys [lib namespace why]} entries]
      (when (str/blank? why)
        (throw (ex-info (str "Allowlist entry " lib " -> " namespace " has no :why. "
                             "This list is a burn-down, and an entry without a "
                             "reason cannot be burnt down.")
                        {:lib lib :namespace namespace}))))
    (set (map (juxt :lib :namespace) entries))))

(defn read-allowlist []
  (let [f (fs/file root-dir allowlist-path)]
    (if (fs/exists? f) (parse-allowlist (edn/read-string (slurp f))) #{})))

(defn- allowed?
  "True when `finding` is exempt. A `:namespace` of :any exempts the library's
   whole surface — used for a library whose runtime is not the JVM at all."
  [allow {:keys [lib* namespace]}]
  (boolean (or (contains? allow [lib* namespace])
               (contains? allow [lib* :any]))))

;; =============================================================================
;; Scan
;; =============================================================================

(defn all-findings
  "Every smuggled dependency in the tree, allowlist not applied."
  []
  (let [owners (namespace-owners)]
    (for [lib   (libs)
          :let  [declared (declared-deps lib)
                 src      (fs/file root-dir "libs" lib "src")]
          :when (fs/exists? src)
          f     (fs/glob src "**/*.clj{,c}")
          :when (fs/regular-file? f)
          :let  [path (str (fs/relativize root-dir f))]
          fnd   (smuggle-findings lib declared (slurp (fs/file f)) path owners)]
      fnd)))

(defn unexplained-findings
  "Findings with no allowlist entry — the ones that fail the build."
  []
  (let [allow (read-allowlist)]
    (remove #(allowed? allow %) (all-findings))))

(defn stale-exemptions
  "Allowlist entries with nothing left to exempt.

   This is what makes the list a burn-down rather than a drawer. When BOU-305
   lands and realtime declares its dependency, its entry stops matching
   anything — and unless removing it is forced, it stays, and the next
   regression of the same shape is pre-approved."
  ([] (stale-exemptions (all-findings) (read-allowlist)))
  ([findings allow]
   (let [found (set (map (juxt :lib* :namespace) findings))
         libs* (set (map :lib* findings))]
     (->> allow
          (remove (fn [[lib ns-name]]
                    (if (= :any ns-name)
                      (contains? libs* lib)
                      (contains? found [lib ns-name]))))
          ;; Sorted by printed form, not naturally: `:any` is a keyword and the
          ;; rest are strings, and comparing the two throws — so the first
          ;; person to use the documented :any hatch alongside an ordinary entry
          ;; would get a ClassCastException from the reporting path instead of
          ;; the report.
          (sort-by pr-str)))))

(defn -main [& _]
  (println "Verifying no library reaches for a namespace it does not declare")
  (let [all   (all-findings)
        allow (read-allowlist)
        bad   (remove #(allowed? allow %) all)
        stale (stale-exemptions all allow)
        known (- (count all) (count bad))]

    (when (empty? (libs))
      (binding [*out* *err*]
        (println (ansi/red "  ✗ found no libraries at all — the check is not looking at anything")))
      (System/exit 1))

    (cond
      (seq bad)
      (do (binding [*out* *err*]
            (println (ansi/red (str "  ✗ " (count bad) " undeclared cross-library dependenc"
                                    (if (= 1 (count bad)) "y" "ies"))))
            (println)
            (doseq [{:keys [lib* file line namespace lib]} (sort-by (juxt :lib* :file :line) bad)]
              (println (str "      " (ansi/red lib*) " loads " namespace
                            (if lib (str " (owned by " lib ")") " (owned by no library)")
                            "\n        " file ":" line)))
            (println)
            (println "  A library that loads what it does not declare works in the monorepo")
            (println "  and throws for anyone who takes it off Clojars. Declare the")
            (println "  dependency, invert it behind a port, or add an entry with a :why to")
            (println (str "  " allowlist-path ".")))
          (System/exit 1))

      (seq stale)
      (do (binding [*out* *err*]
            (println (ansi/red (str "  ✗ " (count stale)
                                    " allowlist entr" (if (= 1 (count stale)) "y" "ies")
                                    " no longer exempt anything")))
            (println)
            (doseq [[lib ns-name] stale]
              (println (str "      " lib " -> " ns-name)))
            (println)
            (println "  The dependency is gone — remove the entry. Left in place it is a")
            (println "  pre-approval for the next regression of the same shape, which is")
            (println "  how a burn-down list becomes a drawer."))
          (System/exit 1))

      :else
      (do (println (str "  ✓ " (count (libs)) " libraries declare what they load"
                        (when (pos? known)
                          (str "  (" known " known exception(s) on the burn-down list)"))))
          (System/exit 0)))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
