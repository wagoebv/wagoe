(ns wagoe.mcp.shell.tools
  "Executors for the MCP tools. All real work (running clj-kondo, Malli
   validation, project reflection, AI calls, scaffolding + the verify loop)
   lives here; the core stays a pure catalog. Each executor takes (args deps)
   and returns result data; throwing is fine — the dispatch maps it to an
   isError result.

   Tier 0 (BOU-100): read/analyze. Tier 1 (BOU-101): generate — scaffold/write
   to disk (reversible via git) then run the closed verify loop
   (wagoe.mcp.shell.verify)."
  (:require [wagoe.ai.core.context :as context]
            [wagoe.ai.core.parsing :as parsing]
            [wagoe.ai.core.prompts :as prompts]
            [wagoe.ai.ports :as ai]
            [wagoe.devtools.error-codes :as codes]
            [wagoe.mcp.core.execute :as execute]
            [wagoe.mcp.core.resources :as resources]
            [wagoe.mcp.ports :as ports]
            [wagoe.mcp.shell.verify :as verify]
            [wagoe.scaffolder.ports :as scaffold]
            [clj-kondo.core :as kondo]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me])
  (:import (java.io File)))

;; --- explain-error ----------------------------------------------------------
;; Deterministic: summarise the stacktrace (ai.core.context) and, if the text
;; names a BND code, enrich with the catalog entry (rule / principle / fix).

(defn- explain-error [{:keys [error]} _deps]
  (let [code  (some-> error (->> (re-find #"BND-\d{3}")))
        entry (when code (codes/lookup code))]
    (cond-> {:summary (context/summarise-stacktrace (or error "") 40)}
      code  (assoc :code code)
      entry (assoc :rule      (:title entry)
                   :principle (:description entry)
                   :fix       (:fix entry)))))

;; --- lint -------------------------------------------------------------------
;; Paths are agent-supplied, so they are confined to the project root (the
;; current working directory the in-process adapter reflects). Relative paths
;; resolve against the root; anything that escapes it — via `..` or an absolute
;; path elsewhere — is rejected before clj-kondo touches the filesystem.

(defn- project-root ^File []
  (.getCanonicalFile (io/file (System/getProperty "user.dir"))))

(defn- confine-path
  "Resolve `p` against `root` (absolute paths are honored as-is) and return its
   canonical path, or throw if it escapes the root."
  [^File root p]
  (let [in (io/file (str p))
        f  (.getCanonicalFile (if (.isAbsolute in) in (io/file root (str p))))
        rp (.getPath root)]
    (if (or (= (.getPath f) rp)
            (str/starts-with? (.getPath f) (str rp File/separator)))
      (.getPath f)
      (throw (ex-info (str "Path escapes the project root: " (pr-str p))
                      {:type :validation-error :path p})))))

(defn- lint [{:keys [paths]} _deps]
  (let [root  (project-root)
        safe  (mapv #(confine-path root %) paths)
        {:keys [findings summary]} (kondo/run! {:lint safe})]
    {:summary  summary
     :findings (mapv #(select-keys % [:filename :row :col :level :type :message])
                     findings)}))

;; --- validate-schema --------------------------------------------------------

(defn- validate-schema [{:keys [schema value]} _deps]
  (let [schema (m/schema (edn/read-string schema))]
    (if (m/validate schema value)
      {:valid? true}
      {:valid? false
       :errors (me/humanize (m/explain schema value))})))

;; --- describe-module --------------------------------------------------------

(defn- describe-module [{:keys [module]} deps]
  (let [mg    (resources/force-val (:module-graph (ports/snapshot (:system-source deps))))
        entry (first (filter #(= module (:name %)) (:modules mg)))]
    (or entry
        {:status    :not-found
         :module    module
         :available (mapv :name (:modules mg))})))

;; --- sql-preview ------------------------------------------------------------

(def ^:private sql-system-prompt
  (str "You translate a natural-language request into a database query for a "
       "Clojure project that uses HoneySQL. Respond ONLY with JSON of the shape "
       "{\"honeysql\": <edn string>, \"raw-sql\": <string>, \"explanation\": <string>}. "
       "Generate the query only; never execute it."))

(defn- sql-preview [{:keys [query]} deps]
  (if-let [provider (:ai-provider deps)]
    (let [resp (ai/complete provider
                            [{:role :system :content sql-system-prompt}
                             {:role :user :content query}]
                            {})]
      (if (:error resp)
        {:status :error :error (:error resp)}
        (parsing/parse-sql-response (:text resp))))
    {:status :unavailable
     :note   "sql-preview requires an AI provider; none configured for this server."}))

;; --- Tier 1 (:generate) — scaffold + closed verify loop ---------------------
;; Each tool builds a scaffolder request, the scaffolder writes the files
;; (reversible via git), and the verify loop runs kondo → FC/IS → tests over
;; what was written. The structured report is the agent's feedback to
;; self-correct and re-invoke. `:allow true` requests an audited override of the
;; soft guardrails (FC/IS / convention); it is honored only when *every*
;; blocking issue is soft (see core/verify).

;; Names become file paths in the scaffolder (`src/wagoe/<module>/…`), which
;; does not sanitize them. Guard agent-supplied names here so a value like
;; "../../../etc/x" cannot write outside the project tree.
(def ^:private module-name-re #"[a-z][a-z0-9-]*")
(def ^:private entity-name-re #"[A-Za-z][A-Za-z0-9]*")

(defn- valid-name!
  [kind ^java.util.regex.Pattern re v]
  (when-not (and (string? v) (re-matches re v))
    (throw (ex-info (format "Invalid %s name: %s (must match %s)"
                            (name kind) (pr-str v) (str re))
                    {:type :validation-error :field kind :value v})))
  v)

(defn- field->scaffolder
  "MCP field map → scaffolder FieldDefinition: :name and :type become keywords."
  [{:keys [name type] :as f}]
  (-> f
      (assoc :name (keyword name) :type (keyword type))
      (select-keys [:name :type :required :unique :default :enum-values :min :max :description])))

(defn- entity->scaffolder
  [{:keys [name plural fields]}]
  (cond-> {:name name :fields (mapv field->scaffolder fields)}
    plural (assoc :plural plural)))

(defn- file-summary [files]
  (mapv #(select-keys % [:path :action]) files))

(defn- record-override!
  "Audit a soft-guardrail override when the verify report was overridden."
  [deps tool-name module report]
  (when (= :overridden (:status report))
    (when-let [audit (:audit deps)]
      (ports/record! audit {:event  :guardrail-override
                            :tool   tool-name
                            :module module
                            :codes  (vec (distinct (keep :code (:issues report))))}))))

(defn- source->test-path
  "Map a source path to its conventional test path:
   .../src/.../foo.clj -> .../test/.../foo_test.clj."
  [src]
  (-> src
      (str/replace #"(^|/)src/" "$1test/")
      (str/replace #"\.clj$" "_test.clj")))

(defn- scaffold-module [{:keys [module entities interfaces preview force allow]} deps]
  (valid-name! :module module-name-re module)
  (run! #(valid-name! :entity entity-name-re (:name %)) entities)
  (let [svc (:scaffolder deps)
        req {:module-name module
             :entities    (mapv entity->scaffolder entities)
             :interfaces  (select-keys (or interfaces {}) [:http :cli :web])
             :dry-run     (boolean preview)
             ;; Without this the verify-and-re-invoke loop this tool documents
             ;; is a dead end: the first call writes the files, so every later
             ;; one is refused and the agent has no flag to get past it
             ;; (BOU-308).
             :force       (boolean force)}]
    (if preview
      (let [r (scaffold/generate-module svc (assoc req :dry-run true))]
        {:status  :preview
         :module  module
         :success (boolean (:success r))
         :plan    (file-summary (:files r))
         :errors  (:errors r)})
      (let [r      (scaffold/generate-module svc req)
            report (verify/verify-generated deps (assoc r :module module)
                                            {:overridden? (boolean allow)})]
        (record-override! deps "scaffold-module" module report)
        (assoc report :module module :files (file-summary (:files r)))))))

(defn- add-field [{:keys [module entity field allow]} deps]
  (valid-name! :module module-name-re module)
  (valid-name! :entity entity-name-re entity)
  (let [svc (:scaffolder deps)
        r   (scaffold/add-field svc {:module-name module
                                     :entity      entity
                                     :field       (field->scaffolder field)
                                     :dry-run     false})
        report (verify/verify-generated deps (assoc r :module module)
                                        {:overridden? (boolean allow)})]
    (record-override! deps "add-field" module report)
    (assoc report :module module
           :files    (file-summary (:files r))
           :warnings (:warnings r))))

(defn- gen-migration [{:keys [module entity fields allow]} deps]
  (valid-name! :module module-name-re module)
  (valid-name! :entity entity-name-re entity)
  (let [svc (:scaffolder deps)
        ;; Reuse generate-module's migration generator via dry-run, then write
        ;; only the .sql file — no source/test files for a migration-only tool.
        r   (scaffold/generate-module svc {:module-name module
                                           :entities    [{:name entity :fields (mapv field->scaffolder fields)}]
                                           :interfaces  {}
                                           :dry-run     true})
        migration (first (filter #(re-find #"migrations/.*\.sql$" (:path %)) (:files r)))]
    (if-not migration
      {:status :error :module module :note "No migration was generated."}
      (do
        (let [f (io/file (:path migration))]
          (.mkdirs (.getParentFile f))
          (spit f (:content migration)))
        (let [written {:success true :module module :files [(assoc migration :action :create)]}
              report  (verify/verify-generated deps written {:overridden? (boolean allow)})]
          (record-override! deps "gen-migration" module report)
          (assoc report :module module :files (file-summary [migration])))))))

(defn- gen-tests [{:keys [source-path allow]} deps]
  (if-let [provider (:ai-provider deps)]
    (let [root        (project-root)
          src         (confine-path root source-path)
          source-code (slurp src)
          messages    (prompts/test-generator-messages
                       src source-code (context/determine-test-type src))
          result      (ai/complete provider messages {})]
      (if (:error result)
        {:status :error :error (:error result)}
        (let [test-path (confine-path root (source->test-path src))
              test-src  (parsing/parse-generated-tests (:text result))]
          (let [f (io/file test-path)]
            (.mkdirs (.getParentFile f))
            (spit f test-src))
          (let [written {:success true :files [{:path test-path :action :create}]}
                report  (verify/verify-generated deps written {:overridden? (boolean allow)})]
            (assoc report :test-file test-path)))))
    {:status :unavailable
     :note   "gen-tests requires an AI provider; none configured for this server."}))

;; --- Tier 2 (:execute) — RCE surface, off by default ------------------------
;; The dispatch gate denies :execute outside the :full (local-dev) context, so
;; these never run in prod/CI. The real work is injected (so it targets the
;; project, not the server, and is stubbable); a missing dep yields an honest
;; :unavailable rather than a silent no-op. Beyond the generic :tool-call event
;; the dispatch records, each executor audits its own payload (the code run, the
;; SQL, the migration direction) so the audit trail names exactly what executed.

(def ^:private audit-payload-limit
  "Cap on the length of a string value carried in an :execute audit event, so a
   large eval blob or query can't bloat the audit stream."
  2000)

(defn- truncate-audit-val [v]
  (if (and (string? v) (> (count v) audit-payload-limit))
    (str (subs v 0 audit-payload-limit) " …[truncated]")
    v))

(defn- record-execute!
  "Audit the *attempt* of a Tier 2 execution (in addition to the generic
   :tool-call event the dispatch records). Called before validation so a refused
   call still names what was attempted; string payloads are length-capped."
  [deps tool detail]
  (when-let [audit (:audit deps)]
    (ports/record! audit (merge {:event :execute :tool tool}
                                (update-vals detail truncate-audit-val)))))

(defn- run-tests [{:keys [module]} deps]
  (record-execute! deps "run-tests" {:module module})
  (if-let [runner (:test-runner deps)]
    (-> (or (runner module) {:status :error :note "test-runner returned no result"})
        (assoc :module module))
    {:status :unavailable
     :note   "run-tests requires a test-runner; none configured for this server."}))

(defn- eval-code [{:keys [code]} deps]
  (record-execute! deps "eval" {:code code})
  (if-let [evaluator (:evaluator deps)]
    (evaluator code)
    {:status :unavailable
     :note   "eval requires an evaluator; none configured for this server."}))

(defn- run-migration [{:keys [direction]} deps]
  (let [dir (or direction "up")]
    ;; Audit the attempt first (consistent with query-db), then validate.
    (record-execute! deps "run-migration" {:direction dir})
    (when-not (execute/valid-direction? dir)
      (throw (ex-info (str "Unsupported migration direction: " (pr-str dir))
                      {:type    :validation-error
                       :field   :direction
                       :value   dir
                       :allowed (vec (sort execute/migration-directions))})))
    (if-let [migrator (:migrator deps)]
      (assoc (migrator dir) :direction dir)
      {:status :unavailable
       :note   "run-migration requires a migrator; none configured for this server."})))

(defn- query-db [{:keys [sql limit]} deps]
  ;; Audit the attempt first, then validate, so a refused query is still named.
  (record-execute! deps "query-db" {:sql sql :limit limit})
  (when-let [violation (execute/sql-violation sql)]
    (throw (ex-info (str "Refused query (" (name violation) "): query-db only runs a single read-only statement")
                    {:type :validation-error :field :sql :violation violation})))
  (let [n (execute/clamp-limit limit)]
    (if-let [q (:db-query deps)]
      (let [rows (vec (take n (q sql n)))]
        {:status :ok :limit n :row-count (count rows) :rows rows})
      {:status :unavailable
       :limit  n
       :note   "query-db requires a read-only datasource; none configured for this server."})))

;; --- registry ---------------------------------------------------------------

(def ^:private executors
  {"explain-error"   explain-error
   "lint"            lint
   "validate-schema" validate-schema
   "describe-module" describe-module
   "sql-preview"     sql-preview
   "scaffold-module" scaffold-module
   "add-field"       add-field
   "gen-tests"       gen-tests
   "gen-migration"   gen-migration
   "run-tests"       run-tests
   "eval"            eval-code
   "run-migration"   run-migration
   "query-db"        query-db})

(defn run
  "Execute tool `name` with `args` (a map) and `deps` ({:system-source
   :ai-provider :scaffolder :test-runner :audit :evaluator :migrator
   :db-query}). Returns result data, or nil if `name` is not a known tool."
  [deps name args]
  (when-let [f (get executors name)]
    (f args deps)))
