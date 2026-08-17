#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/check_ports.clj
;;
;; Hexagonal boundary enforcement: the FC/IS counterpart to check_fcis.
;; Where check_fcis verifies core/ stays pure, this verifies that the
;; *ports* side of FC/IS + Hexagonal stays intact:
;;
;;   1. Module completeness — every module (a directory with both core/ and
;;      shell/) must define a ports.clj containing at least one defprotocol.
;;   2. No cross-module shell coupling — a wagoe.X.shell.* namespace must
;;      not require wagoe.Y.shell.persistence / wagoe.Y.shell.service of
;;      another module Y. Cross-module access goes through wagoe.Y.ports.
;;   3. Web/HTTP layers never require *.shell.persistence directly — they must
;;      go through service ports.
;;
;; Escape hatches (for legitimate exceptions and gradual adoption downstream):
;;   - `^:wagoe/allow-direct` metadata on a namespace exempts it from
;;     rules 2 and 3.
;;   - An optional .wagoe/check-ports.edn at the repo root supplies
;;     :allow-missing-ports (module ns prefixes) and :allow-direct (namespace
;;     names) allowlists.
;;
;; See BOU-79/BOU-81 for rationale.

(ns wagoe.tools.check-ports
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [wagoe.tools.ansi :as ansi]
            [wagoe.tools.parsing :as parsing]))

;; ---------------------------------------------------------------------------
;; Layer / namespace classification
;; ---------------------------------------------------------------------------

(def ^:private web-layer-segments
  "Namespace path segments that identify a web/HTTP delivery layer.
   A namespace containing any of these is subject to rule 3 (no direct
   persistence requires)."
  #{"web" "http" "api" "handler" "handlers"
    "routes" "router" "controller" "controllers"
    "endpoint" "endpoints"})

(defn web-layer?
  "True when `ns-str` belongs to a web/HTTP delivery layer."
  [ns-str]
  (let [segs (set (str/split ns-str #"\."))]
    (boolean (some web-layer-segments segs))))

(defn shell-ns?
  "True when `ns-str` lives under a module's shell/ layer."
  [ns-str]
  (boolean (or (str/includes? ns-str ".shell.")
               (str/ends-with? ns-str ".shell"))))

(defn core-ns?
  "True when `ns-str` lives under a module's core/ layer. Core namespaces are
   never a delivery layer — they are exempt from the web/HTTP rule even when a
   path segment happens to match (e.g. a `…core.api` namespace)."
  [ns-str]
  (boolean (or (str/includes? ns-str ".core.")
               (str/ends-with? ns-str ".core"))))

(defn ns->module
  "Given a namespace string, return the owning module prefix — everything
   before the first `.core`/`.shell`/`.ports` segment. Returns nil when the
   namespace is not part of a module (no core/shell/ports segment)."
  [ns-str]
  (let [m (re-find #"^(.*?)\.(?:core|shell|ports)(?:\.|$)" ns-str)]
    (second m)))

(defn foreign-shell-require?
  "True when `req` names some module's shell namespace.

   Any shell namespace, not two named suffixes. The rule used to match exactly
   `.shell.persistence` and `.shell.service`, and every real coupling in the
   tree went around it — database adapters, i18n render helpers, auth
   middleware. A list of suffixes cannot keep up with names (BOU-307)."
  [req]
  (str/includes? req ".shell."))

(defn composition-root?
  "True when `ns-str` exists to assemble components.

   Wiring is the one job that has to name concrete implementations, so flagging
   it would report every adapter the system assembles — 39 of the 62
   cross-module requires in this tree — and an allowlist that size stops being
   read. Matched on the namespace's last segments rather than a substring
   anywhere, so `tenant.shell.wiring-helpers` is not exempt for the word."
  [ns-str]
  (some #(or (str/ends-with? ns-str %) (str/includes? ns-str (str % ".")))
        [".shell.module-wiring" ".shell.system.wiring" ".shell.cli-entry" ".shell.boot"]))

(defn parse-shell-allowlist
  "`:allow-cross-module-shell` entries as a set of target prefixes.

   By prefix, not by site: 43 findings in this tree reach into 8 target groups,
   and listing them one by one would hide that they are eight decisions rather
   than forty-three. Every entry must carry a `:why` — an exemption with no
   stated reason cannot be burnt down (BOU-250)."
  [m]
  (let [entries (:allow-cross-module-shell m)]
    (doseq [{:keys [target-prefix why]} entries]
      (when (str/blank? why)
        (throw (ex-info (str "Allowlist entry for " target-prefix " has no :why.")
                        {:target-prefix target-prefix}))))
    (set (map :target-prefix entries))))

(defn shell-target-allowed?
  "True when `req` falls under an allowed target prefix."
  [allow req]
  (boolean (some #(str/starts-with? req %) allow)))

(defn persistence-require?
  "True when a required namespace is a module's shell persistence namespace."
  [req]
  (str/ends-with? req ".shell.persistence"))

(defn service-require?
  "True when a required namespace is a module's shell service namespace."
  [req]
  (str/ends-with? req ".shell.service"))

;; ---------------------------------------------------------------------------
;; Source discovery
;; ---------------------------------------------------------------------------

(defn source-roots
  "Source directories subject to enforcement. Mirrors check_fcis: scans
   libs/*/src in the monorepo and the project-level src/ in downstream
   scaffolded projects."
  []
  (let [root      (io/file (System/getProperty "user.dir"))
        libs      (io/file root "libs")
        lib-srcs  (when (.exists libs)
                    (->> (.listFiles libs)
                         (filter #(.isDirectory %))
                         (map #(io/file % "src"))
                         (filter #(.exists %))))
        root-src  (io/file root "src")]
    (cond-> (vec lib-srcs)
      (.exists root-src) (conj root-src))))

(defn- clj-files
  "All .clj files under a directory."
  [dir]
  (->> (file-seq dir)
       (filter #(and (.isFile %) (str/ends-with? (.getName %) ".clj")))))

(defn- has-subdir?
  [dir name]
  (let [d (io/file dir name)]
    (and (.exists d) (.isDirectory d))))

(defn module-dirs
  "Find all module directories under the given source roots. A module is a
   directory that contains both a core/ and a shell/ subdirectory."
  [roots]
  (->> roots
       (mapcat (fn [root]
                 (->> (file-seq root)
                      (filter #(and (.isDirectory %)
                                    (has-subdir? % "core")
                                    (has-subdir? % "shell"))))))
       (distinct)))

(defn dir->ns-prefix
  "Convert a module directory File to its namespace prefix, e.g.
   .../src/wagoe/license/billing -> wagoe.license.billing.
   Uses the path segment after the nearest `src/` boundary."
  [^java.io.File dir]
  (let [path  (.getPath dir)
        after (or (last (str/split path #"/src/")) path)]
    (-> after
        (str/replace "/" ".")
        (str/replace "_" "-"))))

;; ---------------------------------------------------------------------------
;; ns-form helpers
;; ---------------------------------------------------------------------------

(defn- extract-requires
  "Extract required namespace strings from a (ns ...) form."
  [ns-form]
  (when ns-form
    (let [require-clause (->> ns-form
                              (filter #(and (sequential? %) (= :require (first %))))
                              first)]
      (when require-clause
        (->> (rest require-clause)
             (map #(cond (symbol? %) (str %)
                         (vector? %) (str (first %))
                         :else nil))
             (remove nil?))))))

(defn- ns-allow-direct?
  "True when the namespace symbol carries ^:wagoe/allow-direct metadata."
  [ns-form]
  (boolean (:wagoe/allow-direct (meta (second ns-form)))))

;; ---------------------------------------------------------------------------
;; Per-file rules (2 + 3)
;; ---------------------------------------------------------------------------

(defn cross-module-violations
  "Rule 2: a shell namespace reaching into another module's shell.

   Reaching into an implementation is the violation; assembling components is
   not, so composition roots are exempt. A foreign `ports` or `core` namespace
   is fine — ports are the sanctioned way across a boundary, and core is pure.

   Returns a seq of violation maps."
  [ns-str requires]
  (when (and (shell-ns? ns-str) (not (composition-root? ns-str)))
    (let [own (ns->module ns-str)]
      (->> requires
           (filter foreign-shell-require?)
           (keep (fn [req]
                   (let [target (ns->module req)]
                     (when (and target own (not= target own))
                       {:kind :cross-module
                        :ns   ns-str
                        :req  req}))))))))

(defn web-persistence-violations
  "Rule 3: a web/HTTP namespace requiring a shell persistence namespace.
   Returns a seq of violation maps.

   Scope: applies to any delivery-layer namespace — `web/*` as well as
   `shell.http`/`shell.api` handlers — including a handler reaching into its
   own module's persistence (HTTP handlers must go through a service port, not
   the repository). Core namespaces are exempt even if a path segment matches."
  [ns-str requires]
  (when (and (web-layer? ns-str) (not (core-ns? ns-str)))
    (->> requires
         (filter persistence-require?)
         (map (fn [req]
                {:kind :web-persistence
                 :ns   ns-str
                 :req  req})))))

(defn- check-file
  "Apply rules 2 and 3 to a single file. Honours ^:wagoe/allow-direct, the
   :allow-direct config allowlist, and :allow-cross-module-shell target
   prefixes."
  [file allow-direct-set allow-shell]
  (let [ns-form (parsing/read-ns-form file)
        ns-str  (str (second ns-form))]
    (when (and ns-form
               (not (ns-allow-direct? ns-form))
               (not (contains? allow-direct-set ns-str)))
      (let [requires (extract-requires ns-form)]
        (->> (concat (remove #(shell-target-allowed? allow-shell (:req %))
                             (cross-module-violations ns-str requires))
                     (web-persistence-violations ns-str requires))
             (map #(assoc % :file (str file))))))))

;; ---------------------------------------------------------------------------
;; Module completeness (rule 1)
;; ---------------------------------------------------------------------------

(defn- defprotocol-count
  "Number of defprotocol forms in a ports.clj file (0 if missing)."
  [ports-file]
  (if (.exists ports-file)
    (count (re-seq #"\(defprotocol\b" (slurp ports-file)))
    0))

(defn module-completeness-violation
  "Rule 1: a module must have a ports.clj with at least one defprotocol.
   Returns a violation map or nil."
  [module-dir]
  (let [ports (io/file module-dir "ports.clj")
        prefix (dir->ns-prefix module-dir)]
    (cond
      (not (.exists ports))
      {:kind :missing-ports :module prefix :dir (str module-dir)}

      (zero? (defprotocol-count ports))
      {:kind :empty-ports :module prefix :dir (str module-dir)})))

;; ---------------------------------------------------------------------------
;; Config allowlist
;; ---------------------------------------------------------------------------

(def ^:private builtin-allow-missing-ports
  "Module ns prefixes acknowledged as not yet having a ports.clj in this
   monorepo. Remove entries as modules are retrofitted; new entries need an ADR."
  #{"wagoe.platform"})

(defn read-config
  "Read the optional .wagoe/check-ports.edn allowlist.

   Returns :allow-missing-ports, :allow-direct and :allow-cross-module-shell.

   A malformed file used to be swallowed and read as an empty allowlist, which
   turns every exemption off and the gate red — or, for a rule whose findings
   are all exempt, would have turned it green while nobody could tell the file
   was broken. It throws now (BOU-250)."
  []
  (let [f (io/file (System/getProperty "user.dir") ".wagoe" "check-ports.edn")]
    (if-not (.exists f)
      {:allow-missing-ports #{} :allow-direct #{} :allow-cross-module-shell #{}}
      (let [m (try
                (edn/read-string (slurp f))
                (catch Exception e
                  (throw (ex-info (str "Cannot read " (.getPath f) " — the allowlist is "
                                       "unreadable, so this gate cannot report a verdict")
                                  {:file (.getPath f)} e))))]
        {:allow-missing-ports      (set (map str (:allow-missing-ports m)))
         :allow-direct             (set (map str (:allow-direct m)))
         :allow-cross-module-shell (parse-shell-allowlist m)}))))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn collect-violations
  "Collect all ports violations across the given source roots (defaults to
   `source-roots`). Used by both -main and tests; takes the config allowlists
   explicitly."
  ([config] (collect-violations config (source-roots)))
  ([{:keys [allow-missing-ports allow-direct allow-cross-module-shell]} roots]
   (let [modules  (module-dirs roots)
         missing  (->> modules
                       (keep module-completeness-violation)
                       (remove #(contains? allow-missing-ports (:module %))))
         coupling (->> roots
                       (mapcat clj-files)
                       (mapcat #(check-file % allow-direct allow-cross-module-shell)))]
     {:modules    (count modules)
      :violations (concat missing coupling)})))

(defn -main [& _args]
  (let [config     (-> (read-config)
                       (update :allow-missing-ports into builtin-allow-missing-ports))
        {:keys [modules violations]} (collect-violations config)]
    (if (seq violations)
      (do
        (println (ansi/red "Ports / hexagonal boundary violations found:"))
        (println)
        (doseq [{:keys [kind file ns req module dir]} violations]
          (case kind
            :missing-ports
            (do (println (str "  VIOLATION: " dir))
                (println (str "    module " (ansi/red module) " has core/ and shell/ but no ports.clj with a defprotocol")))
            :empty-ports
            (do (println (str "  VIOLATION: " dir "/ports.clj"))
                (println (str "    module " (ansi/red module) " has a ports.clj but it defines no defprotocol")))
            :cross-module
            (do (println (str "  VIOLATION: " file))
                (println (str "    shell namespace " ns " requires " (ansi/red req)
                              " of another module (use that module's ports.clj)")))
            :web-persistence
            (do (println (str "  VIOLATION: " file))
                (println (str "    web/HTTP namespace " ns " requires " (ansi/red req)
                              " directly (go through a service port)")))))
        (println)
        (println (str (count violations) " violation(s) found across " modules " module(s)."))
        (println (ansi/dim "Escape hatch: ^:wagoe/allow-direct ns metadata, or .wagoe/check-ports.edn allowlist."))
        (System/exit 1))
      (do
        (println (ansi/green "Ports check passed.")
                 (str modules " module(s) scanned, 0 violations."))
        (System/exit 0)))))

;; Run when executed directly (not via bb.edn task)
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
