#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/check_ports_diff.clj
;;
;; A stable library's ports may grow, but not under the people implementing them.
;;
;; Between 1.0.1 and the 1.1 branch, eight protocols in stable libraries gained
;; twenty methods or arities — `IUserRepository` four, `DBAdapter` seven —
;; and not one of them was under `### Breaking`. Every caller kept working, so
;; nothing in the suite noticed; the only code that breaks is an application's
;; own `defrecord` or `reify`, and it breaks at runtime, on the first call the
;; framework makes to a method the record never had. `check:changelog` counts
;; `### Breaking` entries and could not see these, because nobody wrote any.
;;
;; This gate reads the protocols, not the prose. It compares every `defprotocol`
;; in a `ports` or `protocols` namespace at the last final release tag with the
;; working tree, and fails when a stable library's protocol lost or moved a
;; method (callers break) or gained one (implementers break) without the
;; changelog naming it under `[Unreleased]` `### Breaking`.
;;
;; Deliberately narrow, like its sibling. A new protocol is not a break, and is
;; how a minor release should add a capability. Argument names are not compared,
;; only arities. Incubating and tooling libraries are reported, never failed.
;; Whether a named break may ship in a minor is not this gate's question:
;; `check:changelog` already refuses breaking entries after 1.0.0, so naming one
;; here moves the decision there, where the stability page records exceptions.

(ns wagoe.tools.check-ports-diff
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [babashka.process :as process]
            [edamame.core :as e]
            [wagoe.tools.ansi :as ansi]))

(def changelog-path "CHANGELOG.md")
(def catalogue-path "libs/wagoe-cli/resources/wagoe/cli/modules-catalogue.edn")
(def version-path "libs/core/build.clj")

(def uncatalogued-tiers
  "Libraries `wagoe list modules` does not offer, with the tier the stability
   page gives them. Anything in neither place is treated as stable: a library
   this gate cannot place is one it must not wave through."
  {"test"       :incubating
   "tools"      :tooling
   "devtools"   :tooling
   "wagoe-cli"  :tooling
   "wagoe-mcp"  :tooling
   "scaffolder" :tooling
   "e2e"        :tooling})

;; =============================================================================
;; Reading protocols
;; =============================================================================

(defn port-file?
  "Whether `path` is shipped source that can hold a supported protocol.

   The stability page draws the line at `wagoe.<lib>.ports` and its documented
   equivalents: `ports/` directories, per-concern `ports.clj` files, and the
   `protocols` namespaces `DBAdapter` and `IFileSystemAdapter` lived in."
  [path]
  (boolean (and (re-matches #"libs/[^/]+/src/.*\.clj[cs]?" path)
                (some #{"ports" "protocols"}
                      (map #(str/replace % #"\.clj[cs]?$" "") (str/split path #"/"))))))

(defn lib-of [path] (second (re-find #"^libs/([^/]+)/" path)))

(defn- parse-forms
  [raw]
  (e/parse-string-all raw
                      {:all          true
                       :auto-resolve (fn [a] (if (= a :current) (symbol "this.ns") (symbol (str a))))
                       :readers      (fn [_tag] identity)
                       :features     #{:clj :bb}
                       :read-cond    :allow}))

(defn- defprotocol? [form]
  (and (seq? form) (symbol? (first form)) (= "defprotocol" (name (first form)))))

(defn method-arities
  "`{method-name #{arity …}}` for a `defprotocol` form.

   A declaration is `(name [args]+ docstring?)`; its arity is the length of each
   argument vector, `this` included, so `[this] [this opts]` is `#{1 2}`.
   Options (`:extend-via-metadata true`) and the docstring are not lists."
  [form]
  (into {}
        (for [decl (drop 2 form)
              :when (and (seq? decl) (symbol? (first decl)))]
          [(name (first decl)) (into (sorted-set) (map count (filter vector? (rest decl))))])))

(defn protocols-in
  "Every top-level protocol in `content`, as `{\"ns/Name\" {…}}`.

   Top-level only: a `defprotocol` inside `(comment …)` is a REPL note, not
   surface. Throws when the file does not parse; the caller reports it, because
   a gate that skipped what it could not read would pass by not looking."
  [path content]
  (let [forms (parse-forms content)
        nsn   (some #(when (and (seq? %) (= 'ns (first %))) (str (second %))) forms)]
    (into {}
          (for [form forms :when (defprotocol? form)]
            [(str nsn "/" (second form))
             {:lib (lib-of path) :path path :name (str (second form))
              :methods (method-arities form)}]))))

;; =============================================================================
;; Comparing
;; =============================================================================

(defn- method-changes
  [base-key {:keys [lib methods]} {new-methods :methods}]
  (concat
   (for [[m arities] (sort methods)
         :let [now (get new-methods m)]
         :when (not= arities now)
         c (if (nil? now)
             [{:kind :removed-method :arities arities}]
             (concat (when-let [gone (seq (remove now arities))]
                       [{:kind :removed-arity :arities (vec gone)}])
                     (when-let [added (seq (remove arities now))]
                       [{:kind :added-arity :arities (vec added)}])))]
     (assoc c :lib lib :protocol base-key :method m))
   (for [[m arities] (sort new-methods)
         :when (not (contains? methods m))]
     {:kind :added-method :lib lib :protocol base-key :method m :arities (vec arities)})))

(defn changes
  "What happened to each protocol in `base` by `head`, both `{\"ns/Name\" {…}}`.

   A protocol whose namespace changed is matched by its simple name within the
   library and reported as `:moved` — the var callers name is gone — and its
   methods are still compared against where it went. Pure, so a test can prove
   each kind still fires."
  [base head]
  (mapcat (fn [[k {:keys [lib name] :as p}]]
            (if-let [now (get head k)]
              (method-changes k p now)
              (if-let [[k2 now] (first (filter (fn [[_ q]] (and (= lib (:lib q)) (= name (:name q))))
                                               head))]
                (cons {:kind :moved :lib lib :protocol k :to k2}
                      (method-changes k p now))
                [{:kind :removed-protocol :lib lib :protocol k}])))
          (sort-by key base)))

(def breaks-callers #{:removed-protocol :moved :removed-method :removed-arity})

;; =============================================================================
;; What the changelog and the catalogue say
;; =============================================================================

(defn tiers
  "`{lib-name tier}` from the module catalogue, over `uncatalogued-tiers`."
  [catalogue]
  (merge uncatalogued-tiers
         (into {} (map (juxt :name :tier)) (:modules catalogue))))

(defn tier-of [tiers lib] (get tiers lib :stable))

(defn unreleased-breaking
  "The body of `### Breaking` under `## [Unreleased]`, or \"\"."
  [changelog]
  (let [section (second (re-find #"(?s)## \[Unreleased\][^\n]*(.*?)(?=\n## \[|\z)" changelog))]
    (or (second (re-find #"(?s)### Breaking\s*\n(.*?)(?=\n### |\z)" (str section))) "")))

(defn named?
  "Whether `breaking` names `ident` in backticks — `like`, `DBAdapter/like` or
   `wagoe.platform.ports.database/DBAdapter`. Backticks are required: method
   names such as `like` and `engine` are ordinary words in prose."
  [breaking ident]
  (boolean (re-find (re-pattern (str "`(?:[^`\\s]*/)?" (java.util.regex.Pattern/quote ident) "`"))
                    breaking)))

(defn acknowledged?
  "Whether the changelog names what changed: the method, or for a removed or
   moved protocol, the protocol."
  [breaking {:keys [method protocol]}]
  (if method
    (named? breaking method)
    (or (named? breaking (last (str/split protocol #"/")))
        (named? breaking protocol))))

(defn major [version] (some-> (re-find #"^(\d+)\." (str version)) second parse-long))

(defn verdict
  "`{:failing [...] :reported [...]}` for `all-changes`.

   Failing: changes to stable libraries the changelog does not name. Reported:
   everything in incubating and tooling libraries. Nothing fails when the
   working tree's major version is past the base's — that is what a major is for."
  [all-changes tiers breaking base-version head-version]
  (let [major-bump? (and (major base-version) (major head-version)
                         (> (major head-version) (major base-version)))
        {stable true other false} (group-by #(= :stable (tier-of tiers (:lib %))) all-changes)]
    {:failing  (if major-bump? [] (vec (remove #(acknowledged? breaking %) stable)))
     :reported (vec other)
     :major-bump? major-bump?}))

;; =============================================================================
;; Shell
;; =============================================================================

(defn- git [& args]
  (let [{:keys [exit out err]} (apply process/shell {:out :string :err :string :continue true} "git" args)]
    (when-not (zero? exit)
      (throw (ex-info (str "git " (str/join " " args) " failed (exit " exit
                           ") — this gate cannot report a verdict without it")
                      {:exit exit :err (str/trim (or err ""))})))
    out))

(defn- lines [s] (remove str/blank? (str/split-lines s)))

(defn base-ref
  "The ref to compare against: the argument, then `PORTS_BASE`, then the newest
   final release tag reachable from HEAD (`1.0.1`, not `1.1.0-rc-1`)."
  [arg]
  (or arg
      (System/getenv "PORTS_BASE")
      (let [{:keys [exit out]} (process/shell {:out :string :err :string :continue true}
                                              "git" "describe" "--tags" "--abbrev=0"
                                              "--match" "[0-9]*.[0-9]*.[0-9]*" "--exclude" "*-*" "HEAD")]
        (when (zero? exit) (str/trim out)))))

(defn- version-in [content]
  (second (re-find #"\(def version\s+\"([^\"]+)\"" (str content))))

(defn- collect
  "Protocols in every port file `paths`, read with `read-file`; unreadable files
   come back under `:unreadable` instead of being skipped."
  [paths read-file]
  (reduce (fn [acc path]
            (try (update acc :protocols merge (protocols-in path (read-file path)))
                 (catch Exception ex
                   (update acc :unreadable conj (str path " — " (.getMessage ex))))))
          {:protocols {} :unreadable []}
          (filter port-file? paths)))

(defn- describe [{:keys [kind method arities to]}]
  (case kind
    :removed-protocol "protocol removed"
    :moved            (str "moved to " to)
    :removed-method   (str "method `" method "` removed")
    :removed-arity    (str "`" method "` lost arity " (str/join ", " arities))
    :added-method     (str "method `" method "` added")
    :added-arity      (str "`" method "` gained arity " (str/join ", " arities))))

(defn- print-changes [cs]
  (doseq [[protocol pcs] (group-by :protocol cs)]
    (println (str "  " (ansi/bold protocol) (ansi/dim (str "  (" (:lib (first pcs)) ")"))))
    (doseq [c pcs]
      (println (str "    " (if (breaks-callers (:kind c)) (ansi/red "callers") (ansi/yellow "implementers"))
                    "  " (describe c))))))

(defn -main [& args]
  (if-let [base (base-ref (first args))]
    (let [old      (collect (lines (git "ls-tree" "-r" "--name-only" base "--" "libs"))
                            #(git "show" (str base ":" %)))
          new      (collect (lines (git "ls-files" "libs")) slurp)
          all      (changes (:protocols old) (:protocols new))
          {:keys [failing reported major-bump?]}
          (verdict all
                   (tiers (edn/read-string (slurp catalogue-path)))
                   (unreleased-breaking (slurp changelog-path))
                   (version-in (try (git "show" (str base ":" version-path)) (catch Exception _ nil)))
                   (version-in (slurp version-path)))
          unreadable (concat (:unreadable old) (:unreadable new))]
      (when (seq reported)
        (println (ansi/dim (str "Incubating and tooling ports changed since " base " (not gated):")))
        (print-changes reported)
        (println))
      (when (seq unreadable)
        (println (ansi/red "Port files this gate could not read:"))
        (doseq [u unreadable] (println (str "  " u)))
        (println))
      (when (seq failing)
        (println (ansi/red (str "Stable ports changed since " base " with no `[Unreleased]` `### Breaking` entry naming them:")))
        (println)
        (print-changes failing)
        (println)
        (println "An application's own defrecord or reify of these protocols breaks at runtime,")
        (println "on the first call to what it does not implement. Either")
        (println "  - add the capability as a new protocol and check it with satisfies?, or")
        (println "  - name each method in backticks under ### Breaking in [Unreleased];")
        (println "    check:changelog then holds you to a major version or a documented exception."))
      (if (or (seq failing) (seq unreadable))
        (System/exit 1)
        (do (println (ansi/green (str "Stable ports match " base
                                      (cond major-bump? " — skipped: this is a major version"
                                            (seq all)   ", or every change is named under ### Breaking"
                                            :else       ""))))
            (System/exit 0))))
    (do
      (println (ansi/red "No final release tag reachable from HEAD — nothing to compare the ports with."))
      (println "Pass a ref, or set PORTS_BASE.")
      (System/exit 1))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
