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
;; `libs/realtime/.../jwt_adapter.clj` requires `wagoe.user.shell.auth` at the
;; top level, inside a try/catch. The require runs, fails, and is swallowed. The
;; namespace loads, the compile job goes green, and the adapter throws on first
;; use — from Clojars, in a user's application, with realtime's only JWT
;; verifier.
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
  "The library names `lib` declares as com.wagoe dependencies."
  [lib]
  (let [f (fs/file root-dir "libs" lib "deps.edn")]
    (if-not (fs/exists? f)
      #{}
      (->> (:deps (edn/read-string (slurp f)))
           keys
           (filter #(str/starts-with? (str %) "com.wagoe/"))
           (map #(str/replace (name %) #"^wagoe-" ""))
           set))))

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

(def loading-forms
  "The ways a namespace gets loaded or reached at runtime.

   Each of these appears in the tree. `resolve` and `the-ns` do not load
   anything themselves, but a library only names another library's namespace in
   them because something else loaded it — realtime uses all three in one file
   to work around the dependency it does not declare."
  #{"require" "requiring-resolve" "the-ns" "resolve" "ns-resolve" "find-ns"})

(def ^:private loading-form-re
  (re-pattern (str "\\((?:" (str/join "|" loading-forms) ")\\s+'?\\[?'?"
                   "(wagoe\\.[a-z0-9.-]+)")))

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
     (for [[idx line] (map-indexed vector (str/split-lines text))
           ;; A whole-line comment is not code. Commented-out requires and
           ;; examples in prose would otherwise be findings — this gate found
           ;; its own docstring that way. Only the fully-commented line is
           ;; skipped, never a trailing `; note` after real code, because
           ;; dropping the code before it would be a false negative and this
           ;; gate is only worth having if it has none.
           :when (not (str/starts-with? (str/triml line) ";"))
           [_ ns-name] (re-seq loading-form-re line)
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
          sort))))

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
