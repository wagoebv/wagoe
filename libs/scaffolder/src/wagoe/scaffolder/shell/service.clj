(ns wagoe.scaffolder.shell.service
  "Scaffolder service implementation for module generation.
   
   Orchestrates template rendering and file generation."
  (:require [wagoe.scaffolder.ports :as ports]
            [wagoe.scaffolder.schema :as schema]
            [wagoe.scaffolder.core.template :as template]
            [wagoe.scaffolder.core.generators :as generators]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]))

(defn- resolve-path
  "Where a reported path actually lands, given `output-dir`.

   The CLI has always accepted `--output-dir DIR` and passed it in the request,
   and the service ignored it: every write went to `(io/file path)`, relative to
   the working directory. `--output-dir /tmp/x` wrote nothing to /tmp/x and a
   full module into the current project (BOU-275, same root cause — an
   affordance decoupled from what the code does).

   \".\" and nil resolve to the plain relative path, so default behaviour and
   every existing report are unchanged."
  [output-dir path]
  (if (or (str/blank? output-dir) (= "." output-dir))
    (io/file path)
    (io/file output-dir path)))

(defn- require-existing-file!
  "Refuse to continue when `file` is not there.

   The commands that edit an existing module — `field`, `endpoint`, `adapter` —
   all used to carry on regardless: `field` wrote its migration and reported the
   schema as \":skip … not found\", `endpoint` printed instructions for a file
   nobody could open, and `adapter` called `.mkdirs` and built a tree for a
   module that does not exist. Each exited 0 (BOU-364).

   Thrown rather than returned so it lands in the `catch` each command already
   has, which turns it into `{:success false :errors [...]}` — and the CLI turns
   that into exit 1."
  [^java.io.File file why]
  (when-not (.isFile file)
    (throw (ex-info (str why " Looked for " (.getPath file) ".")
                    {:type ::missing-file :path (.getPath file)})))
  file)

(defn- require-existing-dir!
  "Refuse to continue when `dir` is not there.

   For `adapter`, which creates a file rather than editing one: the thing that
   has to already exist is the module. `.mkdirs` meant a typo in
   `--module-name` built a tree for a module nobody has (BOU-364)."
  [^java.io.File dir why]
  (when-not (.isDirectory dir)
    (throw (ex-info (str why " Looked for " (.getPath dir) ".")
                    {:type ::missing-dir :path (.getPath dir)})))
  dir)

(defn- existing-migration-ids
  "Numeric ids already used by migration files, as strings.

   Checks both layouts this repo has: generated projects keep migrations in
   `migrations/`, the monorepo in `resources/migrations/`.

   And under `output-dir`, not only the working directory. Scanning the cwd
   alone made the collision guard below useless exactly where collisions are
   likeliest: two scaffold runs into the same `--output-dir` inside one second
   saw no existing ids, took the same timestamp, and produced two different
   migrations sharing one 14-digit id. Measured — migratus then refuses the
   entire run:

     Multiple migrations with id 20260806060706 (\"create-betas\" \"create-alphas\")"
  ([] (existing-migration-ids "."))
  ([output-dir]
   (into #{}
         (for [root  (distinct [(or output-dir ".") "."])
               dir   ["migrations" "resources/migrations"]
               :let  [d (resolve-path root dir)]
               :when (.isDirectory d)
               f     (.listFiles d)
               :let  [m (re-find #"^(\d+)-" (.getName f))]
               :when m]
           (second m)))))

(defn- next-migration-id
  "First id at or after `now` that is not in `used`, as a string.

   Second precision alone is not unique: two scaffold operations within the
   same second produce different filenames sharing one id, and migratus throws
   \"Multiple migrations with id N\" — which fails the entire migration run, not
   just the offending pair. So step forward until the id is free.

   Stepping into the next second rather than adding sub-second precision is
   deliberate. Every id already in this repo, and every id `migratus create`
   generates, is 14 digits; a 17-digit id is ~1000x larger numerically, so it
   would sort after all 14-digit ones forever and any later hand-made migration
   would sort before it. That trades a rare collision for a permanent ordering
   hazard.

   Pure: takes the used set and the current timestamp rather than reading either."
  [used now]
  (loop [id (Long/parseLong now)]
    (if (contains? used (str id))
      (recur (inc id))
      (str id))))

(defn- get-next-migration-number
  "Timestamp id for a new migration, e.g. 20260801120000.

   Replaces a sequential \"%03d\" counter that produced ids migratus could not
   use and could not have made collision-free anyway (BOU-256): it scanned
   `resources/migrations` while writing to `migrations/`, so it counted nothing
   and always returned 001, and it parsed ids with `Integer/parseInt`, which
   overflows on 14-digit timestamps and sent the function into its catch
   branch."
  ([] (get-next-migration-number "."))
  ([output-dir]
   (next-migration-id (existing-migration-ids output-dir)
                      (.format (java.time.LocalDateTime/now java.time.ZoneOffset/UTC)
                               (java.time.format.DateTimeFormatter/ofPattern "yyyyMMddHHmmss")))))

(defn- existing-migration-for
  "The id of this module's create-<table> migration, if one is already there.

   Returns the id only — the caller rebuilds both filenames from it, so an
   overwrite replaces the pair it found instead of adding another."
  [output-dir table]
  (let [dir (io/file output-dir "migrations")]
    (when (.isDirectory dir)
      (some (fn [^java.io.File f]
              (second (re-matches (re-pattern (str "(\\d+)-create-" table "\\.up\\.sql"))
                                  (.getName f))))
            (sort-by #(.getName ^java.io.File %) (.listFiles dir))))))

(defn- migration-ids
  "One fresh migration id per table, in order and all distinct. With `force?` a
   table's existing create migration keeps its id, as for a single entity."
  [output-dir force? tables]
  (let [now (.format (java.time.LocalDateTime/now java.time.ZoneOffset/UTC)
                     (java.time.format.DateTimeFormatter/ofPattern "yyyyMMddHHmmss"))
        used (existing-migration-ids output-dir)]
    (reduce (fn [ids table]
              (conj ids (or (when force? (existing-migration-for output-dir table))
                            (next-migration-id (into used ids) now))))
            []
            tables)))

(defn- entities-problem
  "Why a module's entity list cannot be generated, or nil. A `:belongs-to`
   names an entity before it in the list: the parent's table has to exist when
   the child's foreign key is created."
  [entities]
  (let [kebab (fn [n] (when (string? n) (template/pascal->kebab n)))
        names (map (comp kebab :name) entities)]
    (or (when-let [dup (some (fn [[n c]] (when (> c 1) n)) (frequencies names))]
          (str "Two entities are called " (template/kebab->pascal dup) "."))
        (some (fn [[i e]]
                (when-let [parent (kebab (:belongs-to e))]
                  (when-not (some #{parent} (take i names))
                    (str (:name e) " belongs to " (template/kebab->pascal parent)
                         ", which is not an entity listed before it."))))
              (map-indexed vector entities)))))

;; =============================================================================
;; Admin config (BOU-562)
;; =============================================================================

(defn- profile-dirs
  "The profile directories under `output-dir`'s resources/conf, sorted."
  [output-dir]
  (->> (.listFiles ^java.io.File (resolve-path output-dir "resources/conf"))
       (filter #(.isDirectory ^java.io.File %))
       (sort-by #(.getName ^java.io.File %))))

(defn- profile-config ^java.io.File [dir] (io/file dir "config.edn"))

(defn- admin-on?
  "Whether any profile switches the admin UI on."
  [output-dir]
  (boolean (some #(let [f (profile-config %)]
                    (and (.isFile f) (generators/admin-active? (slurp f))))
                 (profile-dirs output-dir))))

(defn- admin-file-path [dir plural]
  (str "resources/conf/" (.getName ^java.io.File dir) "/admin/" plural ".edn"))

(defn- admin-files
  "One admin entity file per profile for each [entity relations] pair, split
   into {:new [...] :kept [...]}. One already there is kept, --force or not:
   it is where the user's admin edits live, and it may be the framework's own
   (users.edn, tenants.edn)."
  [output-dir entity+relations]
  (let [all (for [dir (profile-dirs output-dir)
                  [entity relations] entity+relations]
              {:path    (admin-file-path dir (:entity-plural entity))
               :content (generators/admin-entity-file entity relations)
               :action  :create})
        exists? #(.exists ^java.io.File (resolve-path output-dir (:path %)))]
    {:new  (vec (remove exists? all))
     :kept (mapv #(assoc % :action :skip
                         :path (.getPath (resolve-path output-dir (:path %)))
                         :note "already exists — left as it is")
                 (filter exists? all))}))

(defn- admin-config-edits
  "{:edits [{:file :content :note}] :warnings [...]}: each profile's
   config.edn with `plurals` listed in its admin."
  [output-dir plurals]
  (reduce (fn [acc dir]
            (let [f (profile-config dir)]
              (if-not (.isFile f)
                acc
                (let [before (slurp f)
                      {:keys [content reasons]}
                      (reduce (fn [st plural]
                                (let [r (generators/add-admin-entity (:content st) plural)]
                                  (case (:status r)
                                    :updated      (assoc st :content (:content r))
                                    :unrecognised (update st :reasons conj (:reason r))
                                    st)))
                              {:content before :reasons []}
                              plurals)]
                  (cond-> acc
                    (not= before content) (update :edits conj {:file f :content content
                                                               :note "listed the entities in the admin"})
                    (seq reasons) (update :warnings into (map #(str (.getPath f) ": " %) (distinct reasons))))))))
          {:edits [] :warnings []}
          (profile-dirs output-dir)))

(defn- write-edits!
  "Report entries for `edits`, written unless `dry-run?`."
  [edits dry-run?]
  (mapv (fn [{:keys [^java.io.File file content note]}]
          (when-not dry-run? (spit file content))
          {:path (.getPath file) :content content
           :action (if dry-run? :skip :update)
           :note (if dry-run? (str "dry run — would have " note) note)})
        edits))

(defn- workflow-steps
  "What an entity with a workflow still needs from the project: the modules no
   profile under `output-dir` switches on."
  [output-dir entities]
  (when-let [wf (seq (filter :workflow entities))]
    (let [configs (keep #(let [f (profile-config %)] (when (.isFile f) (slurp f)))
                        (profile-dirs output-dir))
          off?    (fn [k] (not-any? #(generators/module-active? % k) configs))]
      (cond-> []
        (off? :wagoe/workflow)
        (conj (str "Switch the workflow module on: wagoe add workflow. "
                   (str/join ", " (map :entity-name wf)) " will not boot without it"))

        (and (admin-on? output-dir) (off? :wagoe/events))
        (conj (str "For the admin's " (str/join ", " (map :entity-plural wf))
                   " to get a workflow too, switch on the event bus: wagoe add events"))))))

(defn- children-of-first
  "`ctx` with every entity that belongs to the first one marked :child-of:
   the first entity's create takes them (BOU-578)."
  [ctx]
  (let [first-kebab (:entity-kebab (first (:entities ctx)))]
    (update ctx :entities
            (fn [es] (mapv #(cond-> % (= first-kebab (:belongs-to %)) (assoc :child-of true)) es)))))

(defn- minimum-problem
  "Why a minimum cannot be kept, or nil: only the first entity's create takes
   children, so a child of any other has no create that could."
  [entities]
  (let [first-name (template/pascal->kebab (:name (first entities)))]
    (some #(when (and (:min %) (not= first-name (some-> (:belongs-to %) template/pascal->kebab)))
             (str (:name %) " has --min, but " (:belongs-to %) " is not the module's first entity ("
                  (:name (first entities)) "): only the first entity's create takes children."))
          entities)))

(def ^:private module-generation-request-validator (m/validator schema/ModuleGenerationRequest))
(def ^:private module-generation-request-explainer (m/explainer schema/ModuleGenerationRequest))

;; `add-field` validated nothing. `generate-module` checks every field against
;; ModuleGenerationRequest, so the CLI and the MCP tool were both covered
;; there — and both bypassed it when adding a field to a module that already
;; existed. A relation arriving here could name no target (a bare UUID column
;; with no foreign key), an unknown `:on-delete` (silently CASCADE), or
;; `:required` with `:set-null` (a migration the database accepts and then
;; refuses every parent delete against) (BOU-480 review).
(def ^:private field-definition-validator (m/validator schema/FieldDefinition))
(def ^:private field-definition-explainer (m/explainer schema/FieldDefinition))

(def ^:private add-subscriber-request-validator (m/validator schema/AddSubscriberRequest))
(def ^:private add-subscriber-request-explainer (m/explainer schema/AddSubscriberRequest))

(def ^:private add-entity-request-validator (m/validator schema/AddEntityRequest))
(def ^:private add-entity-request-explainer (m/explainer schema/AddEntityRequest))

(defrecord ScaffolderService []
  ports/IScaffolderService

  (generate-module [_ request]
    (try
      ;; Validate request. The humanized explanation goes into the message,
      ;; not only into ex-data: the catch below reports `(.getMessage e)` and
      ;; nothing else, so "Invalid module generation request" was the whole of
      ;; what a user saw for, say, an enum field with no values.
      (when-not (module-generation-request-validator request)
        (let [explanation (me/humanize (module-generation-request-explainer request))]
          (throw (ex-info (str "Invalid module generation request: " (pr-str explanation))
                          {:type :validation-error
                           :errors explanation}))))
      (when-let [problem (or (entities-problem (:entities request))
                             (minimum-problem (:entities request)))]
        (throw (ex-info (str "Invalid module generation request: " problem)
                        {:type :validation-error})))

      ;; Build template context
      (let [ctx (children-of-first (template/build-module-context request))
            module-name (:module-name ctx)
            ;; Directory name, not namespace segment — see template/ns->path.
            module-path (:module-path ctx)
            base-ns-path (:base-ns-path ctx)
            entity (first (:entities ctx))
            entity-path (:entity-snake entity)
            dry-run? (:dry-run request false)
            force?   (:force request false)
            output-dir (:output-dir request ".")

;; UTC timestamp id — see get-next-migration-number. Scoped to
            ;; output-dir so ids stay unique in the directory being written to.
            ;; Reuse the module's existing migration id when overwriting.
            ;; A fresh UTC timestamp means the migration filename is never in
            ;; `existing`, so --force added a *second* create-<table> pair per
            ;; run rather than replacing the first. `up` is CREATE TABLE IF NOT
            ;; EXISTS so `migrate up` stays quiet, but `migrate down` then drops
            ;; the table with an older create still recorded as applied.
            ;; One per entity, and distinct: two entities generated in the
            ;; same second would otherwise share an id (BOU-514).
            ids (migration-ids output-dir force? (map :entity-plural (:entities ctx)))
            migration-number (first ids)
            ;; Every entity after the first appends to schema.clj and ports.clj
            ;; and gets files of its own, exactly as `bb scaffold entity` would
            ;; add it later (BOU-514).
            more (rest (:entities ctx))

            ;; `--no-web` means no web UI files. They were written either way,
            ;; and nothing mounted them: `http.clj`'s web route served an
            ;; inline stub, so `core/ui.clj` and `shell/web_handlers.clj`
            ;; arrived dead in every project (BOU-479).
            web? (get-in ctx [:interfaces :web] true)

            ;; Generate source file contents
            schema-content (reduce (fn [src child]
                                     (let [r (generators/add-children-to-schema src (:entity-name entity) child)]
                                       (or (:content r)
                                           (throw (ex-info (:error r) {:type :validation-error})))))
                                   (reduce #(generators/append-section %1 (generators/entity-schema-section %2))
                                           (generators/generate-schema-file ctx)
                                           more)
                                   (filter :child-of more))
            ports-content (reduce #(generators/append-section %1 (generators/entity-ports-section %2))
                                  (generators/generate-ports-file ctx)
                                  more)
            core-content (generators/generate-core-file ctx)
            migration-content (generators/generate-migration-file ctx migration-number)
            service-content (generators/generate-service-file ctx)
            persistence-content (generators/generate-persistence-file ctx)
            http-content (generators/generate-http-file ctx)
            module-wiring-content (reduce (fn [src e]
                                            (let [r (generators/add-entity-to-wiring src ctx e)]
                                              (or (:content r)
                                                  (throw (ex-info (str "Cannot wire " (:entity-name e) ": " (:error r))
                                                                  {:type :validation-error})))))
                                          (generators/generate-module-wiring-file ctx)
                                          more)

            ;; Generate test file contents
            core-test-content (generators/generate-core-test-file ctx)
            persistence-test-content (generators/generate-persistence-test-file ctx)
            service-test-content (generators/generate-service-test-file ctx)

            ;; Define file paths
            files [{:path (format "src/%s/%s/schema.clj" base-ns-path module-path)
                    :content schema-content
                    :action :create}
                   {:path (format "src/%s/%s/ports.clj" base-ns-path module-path)
                    :content ports-content
                    :action :create}
                   {:path (format "src/%s/%s/core/%s.clj" base-ns-path module-path entity-path)
                    :content core-content
                    :action :create}
                   {:path (format "src/%s/%s/shell/service.clj" base-ns-path module-path)
                    :content service-content
                    :action :create}
                   {:path (format "src/%s/%s/shell/persistence.clj" base-ns-path module-path)
                    :content persistence-content
                    :action :create}
                   {:path (format "src/%s/%s/shell/http.clj" base-ns-path module-path)
                    :content http-content
                    :action :create}
                   ;; Without this, `bb scaffold integrate` reported that the
                   ;; module had no wiring and the user hand-wrote the Integrant
                   ;; keys the framework says never to hand-write (BOU-309).
                   {:path (format "src/%s/%s/shell/module_wiring.clj" base-ns-path module-path)
                    :content module-wiring-content
                    :action :create}
                   ;; migratus discovers `<id>-<name>.up.sql` / `.down.sql`. The old
                   ;; `%s_create_%s.sql` shape was invisible to it, so scaffolded
                   ;; tables were never created and `bb migrate status` reported
                   ;; 0 pending while the file sat on disk (BOU-256).
                   {:path (format "migrations/%s-create-%s.up.sql"
                                  migration-number (:entity-plural entity))
                    :content migration-content
                    :action :create}
                   {:path (format "migrations/%s-create-%s.down.sql"
                                  migration-number (:entity-plural entity))
                    :content (generators/generate-migration-down-file ctx)
                    :action :create}
                   {:path (format "test/%s/%s/core/%s_test.clj" base-ns-path module-path entity-path)
                    :content core-test-content
                    :action :create}
                   {:path (format "test/%s/%s/shell/%s_repository_test.clj" base-ns-path module-path entity-path)
                    :content persistence-test-content
                    :action :create}
                   {:path (format "test/%s/%s/shell/service_test.clj" base-ns-path module-path)
                    :content service-test-content
                    :action :create}]

            files (cond-> files
                    (:workflow entity)
                    (conj {:path (format "src/%s/%s/shell/%s_workflow.clj" base-ns-path module-path entity-path)
                           :content (generators/generate-workflow-file ctx entity)
                           :action :create}
                          {:path (format "test/%s/%s/shell/%s_workflow_test.clj" base-ns-path module-path entity-path)
                           :content (generators/generate-workflow-test-file ctx entity)
                           :action :create}))

            files (into files (mapcat #(generators/entity-files ctx %1 %2) more (rest ids)))

            files (cond-> files
                    web?
                    (conj {:path (format "src/%s/%s/core/ui.clj" base-ns-path module-path)
                           :content (generators/generate-ui-file ctx)
                           :action :create}
                          {:path (format "src/%s/%s/shell/web_handlers.clj" base-ns-path module-path)
                           :content (generators/generate-web-handlers-file ctx)
                           :action :create}))

            admin?  (admin-on? output-dir)
            by-name (into {} (map (juxt :entity-kebab identity)) (:entities ctx))
            admin-out (when admin?
                        (admin-files
                         output-dir
                         (for [e (:entities ctx)]
                           [e {:children (filter #(= (:entity-kebab e) (:belongs-to %)) (:entities ctx))
                               :parent   (when-let [p (by-name (:belongs-to e))]
                                           {:name   (:entity-name p)
                                            :fields (generators/admin-display-fields
                                                     (map (comp keyword :field-name-kebab) (:fields p)))})}])))
            files   (into files (:new admin-out))
            admin-edits (when admin? (admin-config-edits output-dir (map :entity-plural (:entities ctx))))

            ;; Which of them are already on disk. Checked before anything is
            ;; written: a re-run of the framework's most-recommended command
            ;; replaced all fourteen files with no prompt, no backup and exit 0,
            ;; so a day's edits went with them. --force was declared in the CLI
            ;; and read by nothing (BOU-308).
            existing (when-not dry-run?
                       (->> files
                            (map #(resolve-path output-dir (:path %)))
                            (filter #(.exists ^java.io.File %))
                            (mapv #(.getPath ^java.io.File %))))

            ;; Before anything is written, not after — the rebinding below is
            ;; the write.
            _ (when (and (seq existing) (not force?))
                (throw (ex-info "refuse-overwrite"
                                {:type ::refuse-overwrite :existing existing})))

            ;; Rebound to what was actually done, so the report cannot drift
            ;; from the filesystem.
            files (if dry-run?
                    ;; Resolved, like the write branch below. A preview that
                    ;; printed cwd-relative paths while --output-dir pointed
                    ;; elsewhere described a run that would not happen.
                    (mapv #(assoc % :action :skip
                                  :path (.getPath (resolve-path output-dir (:path %)))
                                  :note "dry run — would be created")
                          files)
                    (mapv (fn [{:keys [path content] :as entry}]
                            (let [file    (resolve-path output-dir path)
                                  existed (.exists file)]
                              (.mkdirs (.getParentFile file))
                              (spit file content)
                              ;; :overwrite, not :create — a report that calls a
                              ;; replaced file "created" hides what --force did.
                              (assoc entry
                                     :action (if existed :overwrite :create)
                                     :path   (.getPath file))))
                          files))
            files (-> files
                      (into (:kept admin-out))
                      (into (write-edits! (:edits admin-edits) dry-run?)))]
        {:success true
         :module-name module-name
         :files files
         ;; Supplied here rather than in the CLI so the namespace follows
         ;; --base-ns. The CLI cannot know it, and a hardcoded "wagoe." would
         ;; be one more instruction that does not run.
         ;; "Add module to config: [:active :wagoe/settings :modules]" used to be
         ;; here. Nothing reads that path, and it contradicted `integrate`, which
         ;; writes the :wagoe/<module> key the generated module_wiring.clj
         ;; defines (BOU-309).
         :next-steps (into ["Review the generated files"
                            (format "Write its config key: bb scaffold integrate %s" module-name)
                            ;; The first entity's, not <module>-test (BOU-562).
                            (format "Run tests: clojure -M:test --focus %s.%s.core.%s-test"
                                    (:base-ns ctx) module-name (:entity-kebab entity))]
                           (workflow-steps output-dir (:entities ctx)))
         :warnings (cond-> (vec (:warnings admin-edits))
                     dry-run? (conj "Dry run - no files were written"))})

      (catch clojure.lang.ExceptionInfo e
        (if (= ::refuse-overwrite (:type (ex-data e)))
          (let [existing (:existing (ex-data e))]
            {:success        false
             :module-name    (:module-name request)
             :files          []
             :existing-files existing
             ;; Named, not counted: "14 files already exist" tells you nothing
             ;; about which of your edits are at stake.
             :errors         (into [(str (count existing) " file(s) already exist. "
                                         "Re-run with --force to overwrite them, "
                                         "or move them aside first:")]
                                   (map #(str "  " %) existing))})
          {:success false
           :module-name (:module-name request)
           :files []
           :errors [(str "Generation failed: " (.getMessage e))]}))
      (catch Exception e
        {:success false
         :module-name (:module-name request)
         :files []
         :errors [(str "Generation failed: " (.getMessage e))]})))

  (add-field [_this request]
    (try
      (let [{:keys [module-name entity field dry-run]} request
            _ (when-not (field-definition-validator field)
                (let [explanation (me/humanize (field-definition-explainer field))]
                  (throw (ex-info (str "Invalid field definition: " (pr-str explanation))
                                  {:type :validation-error
                                   :errors explanation}))))
            base-ns-path (template/ns->path (or (:base-ns request) "wagoe"))
            module-path (template/kebab->snake module-name)
            output-dir (:output-dir request ".")
            migration-number (get-next-migration-number output-dir)

            ;; Generate migration content
            migration-content (generators/generate-add-field-migration
                               module-name entity field migration-number)

            ;; Define files
            ;; Through build-field-context, which knows a relation's column is
            ;; the field name plus `-id`. Built from the raw field name, the
            ;; down migration dropped `invoice` while the up added `invoice_id`
            ;; (BOU-480).
            field-name-snake (:field-name-snake (template/build-field-context field))
            ;; The filename keeps the relationship's own name — `add-invoice-to-…`
            ;; reads as what it does, and it is not an identifier.
            field-name-kebab (name (:name field))
            ;; Through pascal->kebab, the same derivation
            ;; `generate-add-field-migration` uses for the up migration. It was
            ;; `str/lower-case` here, so for a multi-word entity the up
            ;; migration altered `invoice_line_items` and the down migration
            ;; dropped the column from `invoicelineitems` — the migration
            ;; applied and only failed on the way back (BOU-480).
            entity-plural (template/pluralize (template/pascal->kebab entity))
            table-name (template/kebab->snake entity-plural)
            ;; `<id>-<name>.up.sql` + `.down.sql` — same migratus discovery
            ;; requirement as module generation above (BOU-256).
            files [{:path (format "migrations/%s-add-%s-to-%s.up.sql"
                                  migration-number field-name-kebab entity-plural)
                    :content migration-content
                    :action :create}
                   {:path (format "migrations/%s-add-%s-to-%s.down.sql"
                                  migration-number field-name-kebab entity-plural)
                    ;; The index first: SQLite refuses to drop an indexed column.
                    :content (str (format "-- Rollback: drop %s from %s\n\n" field-name-snake table-name)
                                  (when (or (= :relation (:type field)) (:indexed field))
                                    (str (format "DROP INDEX IF EXISTS idx_%s_%s;" table-name field-name-snake)
                                         generators/statement-separator))
                                  (format "ALTER TABLE %s DROP COLUMN %s;\n" table-name field-name-snake))
                    :action :create}]
            schema-path (format "src/%s/%s/schema.clj" base-ns-path module-path)
            ;; A module generated before DATE columns reads them back as
            ;; java.sql.Date, which JSON writes as the day before east of UTC.
            persistence (let [own (resolve-path output-dir
                                                (format "src/%s/%s/shell/%s_persistence.clj" base-ns-path module-path
                                                        (template/kebab->snake (template/pascal->kebab entity))))]
                          (if (.isFile own)
                            own
                            (resolve-path output-dir (format "src/%s/%s/shell/persistence.clj" base-ns-path module-path))))
            date-warning (when (and (= :date (:type field))
                                    (.isFile persistence)
                                    (not (str/includes? (slurp persistence) "date->iso")))
                           (str (.getPath persistence) " has no date->iso, so this field will read back a day"
                                " early east of UTC. Regenerate that file, or copy date->iso and ->entity"
                                " from a newly generated module."))
            ;; Written as a keyword, an enum value is a column name to HoneySQL.
            enum-step (when (= :enum (:type field))
                        (str "Add :" (name (:name field)) " to enum-fields in " (.getPath persistence)
                             (when-not (and (.isFile persistence)
                                            (str/includes? (slurp persistence) "enum-fields"))
                               (str ", copied with ->row and ->entity from a newly generated module"))
                             ", or storing it fails"))

            ;; Before anything is written. The migration and the schema entry
            ;; are the two halves this command exists to keep in step, and the
            ;; migration used to go to disk first — so a missing schema left a
            ;; column behind that validation would reject on every write, under
            ;; a ":skip … not found" and exit 0. Nobody asked for that file to
            ;; be left alone; the command could not do half its job (BOU-364).
            _ (require-existing-file!
               (resolve-path output-dir schema-path)
               (str "Cannot add a field to " module-name ": its schema.clj is not there."))

            ;; Write migration files (unless dry-run). Both up and down — writing
        ;; only the first would leave an un-rollbackable migration.
        ;;
        ;; `files` is rebound to what was actually done. Reporting the planned
        ;; action regardless is the defect this ticket is about, and a dry run
        ;; hit it too: it printed ":create: migrations/…up.sql" for two files it
        ;; had deliberately not written.
        ;;
        ;; The schema edit, and its report, are one operation. That entry used
        ;; to be appended to `files` as `:action :update` regardless — the write
        ;; loop skipped anything that was not `:create`, so the command reported
        ;; a file it had never opened (BOU-275). A user who reads
        ;; ":update: …/schema.clj" reasonably stops looking, and then ships a
        ;; field that fails validation with the migration already applied.
            written       (if dry-run
                            (mapv #(assoc % :action :skip
                                          :path (.getPath (resolve-path output-dir (:path %)))
                                          :note "dry run — would be created")
                                  files)
                            (mapv (fn [{:keys [path content] :as entry}]
                                    (let [file (resolve-path output-dir path)]
                                      (.mkdirs (.getParentFile file))
                                      (spit file content)
                                      (assoc entry :action :create :path (.getPath file))))
                                  files))
            schema-file   (resolve-path output-dir schema-path)
            ;; The path the user has to open, which is not `schema-path` once
            ;; --output-dir is in play: the file list named
            ;; /tmp/app/src/…/schema.clj while the instruction below said
            ;; src/…/schema.clj, sending the reader to the current project.
            schema-display (.getPath schema-file)
            ;; Suffix for steps that are commands rather than paths: they act on
            ;; whichever project the shell is in.
            in-project    (fn [] (if (or (str/blank? output-dir) (= "." output-dir))
                                   ""
                                   (str " (from " output-dir ")")))
            existing      (when (.isFile schema-file) (slurp schema-file))
            ;; A child created with its parent: the parent's create request
            ;; takes the field too, and so does the admin's panel (BOU-578).
            nested        (when existing
                            (generators/add-field-to-children-entry existing entity-plural field))
            existing      (if (= :inserted (:status nested)) (:content nested) existing)
            edit          (when existing
                            (generators/add-field-to-schema existing entity field))
            edit          (if (and (= :inserted (:status nested)) (not= :updated (:status edit)))
                            (assoc edit :status :updated :content existing :schemas [])
                            edit)
            panel-edits   (when-let [parent (:parent nested)]
                            (let [parent-plural (template/pluralize (template/pascal->kebab parent))]
                              (for [dir   (when (.isDirectory (resolve-path output-dir "resources/conf"))
                                            (profile-dirs output-dir))
                                    :let  [f (resolve-path output-dir (admin-file-path dir parent-plural))]
                                    :when (.isFile f)
                                    :let  [r (generators/add-admin-has-many-field (slurp f) parent-plural entity-plural field)]
                                    :when (= :updated (:status r))]
                                {:file f :content (:content r)
                                 :note (str "added " (name (:name field)) " to the " entity-plural " panel")})))
            ;; The tests generate wrote insert rows without the new column, so
            ;; a required one failed them on their next run (BOU-581).
            test-edits    (for [suffix ["repository" "workflow"]
                                :let  [f (resolve-path output-dir
                                                       (format "test/%s/%s/shell/%s_%s_test.clj" base-ns-path module-path
                                                               (template/kebab->snake (template/pascal->kebab entity)) suffix))]
                                :when (.isFile f)
                                :let  [r (generators/add-field-to-generated-test (slurp f) field)]
                                :when r]
                            {:file f :content (:content r)
                             :note (str "gave the " suffix " test's row a " (name (:name field)))})
            ;; Every arm consults `edit`, which is pure and is computed for a
            ;; dry run too. A dedicated dry-run arm short-circuited ahead of it
            ;; and promised "would add the field to the entity and request
            ;; schemas" for a file the real run then refused to touch — a
            ;; preview contradicting the run it previews, which is the
            ;; false-success report this ticket is about.
            update-schema (str "Update" entity "Request")
            ;; Per target, not one string reused for all of them. The update
            ;; request takes the optional form: telling the user to add
            ;; `[:sku :string]` to Update<Entity>Request makes the field
            ;; mandatory on every partial update, and this note is the only
            ;; instruction they get — following it is the expected outcome, not
            ;; a mistake on their part.
            entry-for     #(generators/schema-field-entry field (= % update-schema))
            ;; "<entry> to A and B; <other entry> to C" — grouped by the form
            ;; each schema needs, so both the description of what happened and
            ;; the instruction for what is left name the right entry per target.
            by-form       (fn [schemas]
                            (str/join "; "
                                      (for [entry (distinct (map entry-for schemas))]
                                        ;; Commas, not repeated "and": a field
                                        ;; that takes the same form everywhere
                                        ;; grouped all three targets into
                                        ;; "A and B and C".
                                        (str entry " to "
                                             (str/join ", "
                                                       (filter #(= entry (entry-for %)) schemas))))))
            ;; The two ways a target can still need attention. Kept as
            ;; clause builders without the path so several can be joined and
            ;; the path named once.
            add-clause    (fn [schemas] (str "add " (by-form schemas)))
            ;; A different instruction: the entry exists, it is the optionality
            ;; that is wrong, so "add …" would read as though it were missing.
            optional-clause (fn [schemas]
                              (str "in " (str/join " and " schemas)
                                   ", change the entry to " (entry-for update-schema)))
            ;; Both categories, always. Two `assoc` clauses used to write
            ;; :manual-note in turn, so a run that could not edit one schema and
            ;; found another still required told the user about only one of
            ;; them — and the skipped arm dropped the unreachable list entirely.
            ;; Following those instructions left the schema set unsynchronised,
            ;; which is the state this whole path exists to prevent.
            remaining-note (fn [{:keys [unreachable wrong-shape]}]
                             (let [clauses (cond-> []
                                             (seq unreachable) (conj (add-clause unreachable))
                                             (seq wrong-shape) (conj (optional-clause wrong-shape)))]
                               (when (seq clauses)
                                 (str (str/join "; " clauses) " — " schema-display))))
            problem-desc   (fn [{:keys [unreachable wrong-shape]}]
                             (str/join "; "
                                       (cond-> []
                                         (seq unreachable)
                                         (conj (str "could not place it in "
                                                    (str/join ", " unreachable)))
                                         (seq wrong-shape)
                                         (conj (str (str/join ", " wrong-shape)
                                                    " already has it, but not as optional")))))
            schema-entry
            ;; No arm for a missing file: `require-existing-file!` above refuses
            ;; the run before the migration is written, so by here the schema is
            ;; on disk. The arm that used to be here is what produced the
            ;; ":skip … not found" this ticket is about (BOU-364).
            (cond
              ;; A partial success is still a partial success. Editing two of
              ;; the three schemas and reporting only the two is how the Malli
              ;; set ends up unsynchronised while the output reads as done.
              (= :updated (:status edit))
              (do
                (when-not dry-run (spit schema-file (:content edit)))
                (let [remaining (remaining-note edit)]
                  (cond-> {:path (.getPath schema-file)
                           :action (if dry-run :skip :update)
                           :note (str (when dry-run "dry run — ")
                                      (if dry-run "would add " "added ")
                                      (str/join "; " (cond-> []
                                                       (seq (:schemas edit)) (conj (by-form (:schemas edit)))
                                                       (:parent nested)
                                                       (conj (str (name (:name field)) " to Create" (:parent nested)
                                                                  "Request's " entity-plural))))
                                      (when remaining (str " — " (problem-desc edit))))}
                    remaining (assoc :manual? true :manual-note remaining))))

              ;; Not :manual? — nothing is left for the user to do, so telling
              ;; them to finish the schema edit would send them to a file that
              ;; is already correct. Re-running must be a no-op in the output as
              ;; well as on disk. This arm requires *every* target to already
              ;; carry the field, in a shape that works.
              (= :already-present (:reason edit))
              {:path (.getPath schema-file) :action :skip
               :note "field is already in every schema"}

              ;; Nothing was written and something is left: unplaceable
              ;; schemas, an update request still carrying the required form,
              ;; or both. One arm, because the mix is what went unreported.
              :else
              {:path (.getPath schema-file) :action :skip :manual? true
               :note (str (when dry-run "dry run — ") (problem-desc edit))
               :manual-note (remaining-note edit)})
            all-files (into (conj (vec written) schema-entry) (write-edits! (concat panel-edits test-edits) dry-run))]

        {:success true
         :module-name module-name
         :command :field
         :files all-files
         ;; The generated persistence reads and writes every column as it is,
         ;; so a field needs nothing there — except an enum, which has to be in
         ;; `enum-fields` to be stored as a string (BOU-562). The path is
         ;; resolved under --output-dir like every other in this report.
         :next-steps (cond-> (filterv some?
                                      [enum-step
                                       ;; The commands run against whatever project
                                       ;; the shell is in, which is not the generated
                                       ;; one when --output-dir points elsewhere.
                                       (str "Run the migration: clojure -M:migrate up" (in-project))
                                       ;; --focus, not --focus-meta: generated tests carry
                                       ;; ^:unit, never a per-module tag.
                                       (str (format "Run the tests: clojure -M:test --focus %s.%s.core.%s-test"
                                                    (or (:base-ns request) "wagoe") module-name
                                                    (template/pascal->kebab entity))
                                            (in-project))])
                       (:manual? schema-entry)
                       (into [(:manual-note schema-entry)]))
         :warnings (not-empty (cond-> []
                                date-warning (conj date-warning)
                                dry-run      (conj "Dry run - no files were written")))})

      (catch Exception e
        {:success false
         :module-name (:module-name request)
         :files []
         :errors [(str "Add field failed: " (.getMessage e))]})))

  (add-entity [_this request]
    (try
      (when-not (add-entity-request-validator request)
        (let [explanation (me/humanize (add-entity-request-explainer request))]
          (throw (ex-info (str "Invalid entity: " (pr-str explanation))
                          {:type :validation-error :errors explanation}))))
      (let [{:keys [module-name dry-run]} request
            output-dir  (or (:output-dir request) ".")
            ctx         (template/build-module-context
                         {:module-name module-name
                          :base-ns     (or (:base-ns request) "wagoe")
                          :interfaces  (:interfaces request {})
                          :entities    []})
            entity      (template/build-entity-context (:entity request) module-name {:primary? false})
            ctx         (assoc ctx :entities [entity])
            module-root (format "src/%s/%s/" (:base-ns-path ctx) (:module-path ctx))
            ;; The first entity is the one whose service is shell/service.clj;
            ;; a further one has a service of its own. Only the first entity's
            ;; create takes children (BOU-578).
            parent-first? (and (:belongs-to entity)
                               (not (.exists (resolve-path output-dir (str module-root "shell/"
                                                                           (template/kebab->snake (:belongs-to entity))
                                                                           "_service.clj")))))
            _ (when (and (:min entity) (not parent-first?))
                (throw (ex-info (str (:entity-name entity) " has --min, but "
                                     (template/kebab->pascal (:belongs-to entity))
                                     " is not the module's first entity: only the first entity's create takes children.")
                                {:type :validation-error})))
            schema-file (require-existing-file!
                         (resolve-path output-dir (str module-root "schema.clj"))
                         (str "Cannot add an entity to " module-name ": its schema.clj is not there."))
            ports-file  (require-existing-file!
                         (resolve-path output-dir (str module-root "ports.clj"))
                         (str "Cannot add an entity to " module-name ": its ports.clj is not there."))
            wiring-file (require-existing-file!
                         (resolve-path output-dir (str module-root "shell/module_wiring.clj"))
                         (str "Cannot add an entity to " module-name ": its shell/module_wiring.clj is not there."))
            schema-src  (slurp schema-file)
            ports-src   (slurp ports-file)
            wiring-src  (slurp wiring-file)
            ;; A service from before BOU-578 takes no children, and its
            ;; wiring must go on building it as it did.
            service-file (resolve-path output-dir (str module-root "shell/service.clj"))
            children?   (and (.isFile service-file)
                             (generators/service-takes-children? (slurp service-file)))
            ctx         (assoc ctx :service-children? children?)
            nested?     (and parent-first? children?)
            _ (when (and (:min entity) (not nested?))
                (throw (ex-info (str "Cannot keep a minimum: " (.getPath service-file) " creates the "
                                     module-name " without the entities that belong to it. Replace it, and the :wagoe/"
                                     module-name "-service and :wagoe/" module-name "-entities init-keys and ig-config"
                                     " in its module_wiring.clj, with what a new `bb scaffold generate` writes,"
                                     " then run this again.")
                                {:type :validation-error})))
            entity      (cond-> entity nested? (assoc :child-of true))
            wiring      (generators/add-entity-to-wiring wiring-src ctx entity)
            _ (when (:error wiring)
                (throw (ex-info (str "Cannot wire " (:entity-name entity) " into "
                                     (.getPath wiring-file) ": " (:error wiring) ".")
                                {:type :validation-error})))
            schema-add  (generators/entity-schema-section entity)
            ports-add   (generators/entity-ports-section entity)
            parent      (some-> (get-in request [:entity :belongs-to])
                                template/pascal->kebab template/kebab->pascal)
            ;; Names, not text: a section is refused when the file already
            ;; defines one of its vars, which is what would stop it compiling
            ;; or silently redefine a protocol method.
            clashes     (sort (concat
                               (some-> (generators/defined-symbols schema-src)
                                       (filter (generators/defined-symbols schema-add)))
                               (some-> (generators/defined-symbols ports-src)
                                       (filter (generators/defined-symbols ports-add)))))
            _ (when (and parent
                         (not (contains? (generators/defined-symbols schema-src) (symbol parent))))
                (throw (ex-info (str (:entity-name entity) " belongs to " parent
                                     ", but " (.getPath schema-file) " defines no " parent ".")
                                {:type :validation-error})))
            _ (when (seq clashes)
                (throw (ex-info (str module-name " already defines "
                                     (str/join ", " (map str clashes))
                                     " — is " (:entity-name entity) " already in it?")
                                {:type :validation-error})))
            admin?      (admin-on? output-dir)
            parent-plural (some-> parent template/pascal->kebab template/pluralize)
            admin-out   (when admin?
                          (admin-files
                           output-dir
                           [[entity {:parent (when parent
                                               {:name   parent
                                                :fields (generators/admin-display-fields
                                                         (generators/schema-field-names schema-src parent))})}]]))
            new-files   (into (generators/entity-files ctx entity (get-next-migration-number output-dir))
                              (:new admin-out))
            admin-edits (when admin? (admin-config-edits output-dir [(:entity-plural entity)]))
            ;; The parent's own admin file gets the editable panel; one
            ;; generated before admin config existed is left to the admin's
            ;; read-only detection.
            parent-edits (when (and admin? parent)
                           (for [dir   (profile-dirs output-dir)
                                 :let  [f (resolve-path output-dir (admin-file-path dir parent-plural))]
                                 :when (.isFile f)
                                 :let  [r (generators/add-admin-has-many (slurp f) parent-plural entity)]]
                             (if (= :updated (:status r))
                               {:file f :content (:content r) :note (str "added the " (:entity-plural entity) " panel")}
                               (when (= :unrecognised (:status r))
                                 {:warning (str (.getPath f) ": " (:reason r))}))))
            existing    (->> new-files
                             (map #(resolve-path output-dir (:path %)))
                             (filter #(.exists ^java.io.File %))
                             (mapv #(.getPath ^java.io.File %)))
            _ (when (seq existing)
                (throw (ex-info "refuse-overwrite"
                                {:type ::refuse-overwrite :existing existing})))
            schema-src  (if nested?
                          (let [r (generators/add-children-to-schema schema-src parent entity)]
                            (or (:content r)
                                (throw (ex-info (str "Cannot let " parent " be created with its "
                                                     (:entity-plural entity) ": " (:error r) ".")
                                                {:type :validation-error}))))
                          schema-src)
            edits       [{:file schema-file :content (generators/append-section schema-src schema-add)}
                         {:file ports-file :content (generators/append-section ports-src ports-add)}
                         {:file wiring-file :content (:content wiring)}]
            files       (if dry-run
                          (into (mapv #(assoc % :action :skip
                                              :path (.getPath (resolve-path output-dir (:path %)))
                                              :note "dry run — would be created")
                                      new-files)
                                (map (fn [{:keys [file content]}]
                                       {:path (.getPath ^java.io.File file) :content content
                                        :action :skip :note "dry run — would append the entity"}))
                                edits)
                          (into (mapv (fn [{:keys [path content] :as entry}]
                                        (let [file (resolve-path output-dir path)]
                                          (.mkdirs (.getParentFile file))
                                          (spit file content)
                                          (assoc entry :action :create :path (.getPath file))))
                                      new-files)
                                (map (fn [{:keys [file content]}]
                                       (spit file content)
                                       {:path (.getPath ^java.io.File file) :content content
                                        :action :update :note "appended the entity"}))
                                edits))
            files       (into (into files (:kept admin-out))
                              (write-edits! (concat (:edits admin-edits) (filter :file parent-edits))
                                            dry-run))
            warnings    (cond-> (vec (concat (:warnings admin-edits) (keep :warning parent-edits)))
                          (and parent-first? (not nested?))
                          (conj (str parent "'s create does not take " (:entity-plural entity)
                                     ": its service.clj predates that. Create them through /api/v1/"
                                     (:entity-plural entity) ".")))
            service-ns  (str (:base-ns ctx) "." module-name "." (:service-ns entity))
            http?       (get-in ctx [:interfaces :http])
            uri         (str "/api/v1/" (:entity-plural entity))]
        {:success     true
         :module-name module-name
         :command     :entity
         :entity      (:entity-name entity)
         :files       files
         :next-steps  (into (filterv some?
                                     ["Run the migration: clojure -M:migrate up"
                                      (when http? (str "Restart the system; the API is at " uri " and " uri "/:id"))
                                      (str "Run the tests: clojure -M:test --focus " service-ns "-test")])
                            (workflow-steps output-dir [entity]))
         :warnings    (not-empty (cond-> (vec warnings)
                                   dry-run (conj "Dry run - no files were written")))})
      (catch clojure.lang.ExceptionInfo e
        (if (= ::refuse-overwrite (:type (ex-data e)))
          (let [existing (:existing (ex-data e))]
            {:success false :module-name (:module-name request) :files []
             :existing-files existing
             :errors (into [(str (count existing) " file(s) the entity needs already exist. "
                                 "Move them aside first:")]
                           (map #(str "  " %) existing))})
          {:success false :module-name (:module-name request) :files []
           :errors [(str "Add entity failed: " (.getMessage e))]}))
      (catch Exception e
        {:success false :module-name (:module-name request) :files []
         :errors [(str "Add entity failed: " (.getMessage e))]})))

  (add-subscriber [_this request]
    (try
      (when-not (add-subscriber-request-validator request)
        (throw (ex-info (str "Invalid subscriber: "
                             (pr-str (me/humanize (add-subscriber-request-explainer request))))
                        {:type :validation-error})))
      (let [{:keys [module-name dry-run event entity]} request
            output-dir  (or (:output-dir request) ".")
            ctx         (template/build-module-context
                         {:module-name module-name
                          :base-ns     (or (:base-ns request) "wagoe")
                          :entities    []})
            sub         (generators/subscriber-context ctx event entity (:name request))
            module-root (format "src/%s/%s/" (:base-ns-path ctx) (:module-path ctx))
            wiring-file (require-existing-file!
                         (resolve-path output-dir (str module-root "shell/module_wiring.clj"))
                         (str "Cannot add a subscriber to " module-name ": its shell/module_wiring.clj is not there."))
            service-file (resolve-path output-dir (str module-root "shell/service.clj"))
            ctx         (assoc ctx :service-children? (and (.isFile service-file)
                                                           (generators/service-takes-children? (slurp service-file))))
            wiring      (generators/add-subscriber-to-wiring (slurp wiring-file) ctx sub)
            _ (when (:error wiring)
                (throw (ex-info (str "Cannot wire the subscriber into " (.getPath wiring-file) ": "
                                     (:error wiring) ".")
                                {:type :validation-error})))
            new-files   [{:path (str "src/" (:path sub) ".clj") :content (generators/generate-subscriber-file sub)}
                         {:path (str "test/" (:path sub) "_test.clj") :content (generators/generate-subscriber-test-file sub)}]
            existing    (->> new-files
                             (map #(resolve-path output-dir (:path %)))
                             (filter #(.exists ^java.io.File %))
                             (mapv #(.getPath ^java.io.File %)))
            _ (when (seq existing)
                (throw (ex-info "refuse-overwrite" {:type ::refuse-overwrite :existing existing})))
            files       (conj (mapv (fn [{:keys [path content]}]
                                      (let [file (resolve-path output-dir path)]
                                        (when-not dry-run
                                          (.mkdirs (.getParentFile file))
                                          (spit file content))
                                        {:path (.getPath file) :content content
                                         :action (if dry-run :skip :create)}))
                                    new-files)
                              (do (when-not dry-run (spit wiring-file (:content wiring)))
                                  {:path (.getPath ^java.io.File wiring-file) :content (:content wiring)
                                   :action (if dry-run :skip :update) :note "requires the subscriber"}))
            configs     (keep #(let [f (profile-config %)] (when (.isFile f) (slurp f)))
                              (when (.isDirectory (resolve-path output-dir "resources/conf"))
                                (profile-dirs output-dir)))]
        {:success     true
         :module-name module-name
         :command     :subscriber
         :files       files
         :next-steps  (cond-> []
                        (not-any? #(generators/module-active? % :wagoe/events) configs)
                        (conj "Switch the event bus on: wagoe add events. The subscriber starts only with it")
                        :always
                        (conj (str "Fill in handle in " (:ns sub))
                              (str "Run its test: clojure -M:test --focus " (:ns sub) "-test")))
         :warnings    (when dry-run ["Dry run - no files were written"])})
      (catch clojure.lang.ExceptionInfo e
        (if (= ::refuse-overwrite (:type (ex-data e)))
          {:success false :module-name (:module-name request) :files []
           :errors (into ["The subscriber already exists:"] (map #(str "  " %) (:existing (ex-data e))))}
          {:success false :module-name (:module-name request) :files []
           :errors [(str "Add subscriber failed: " (.getMessage e))]}))
      (catch Exception e
        {:success false :module-name (:module-name request) :files []
         :errors [(str "Add subscriber failed: " (.getMessage e))]})))

  (add-endpoint [_this request]
    (try
      (let [{:keys [module-name path method handler-name dry-run]} request
            base-ns-path (template/ns->path (or (:base-ns request) "wagoe"))
            module-path  (template/kebab->snake module-name)
            output-dir   (:output-dir request ".")

            http-path (format "src/%s/%s/shell/http.clj" base-ns-path module-path)

            ;; This command writes nothing — it returns instructions. Pointing
            ;; them at a file nobody can open is the same false success in a
            ;; quieter form, so the file has to be there before we describe an
            ;; edit to it (BOU-364).
            http-file (require-existing-file!
                       (resolve-path output-dir http-path)
                       (str "Cannot add an endpoint to " module-name
                            ": its shell/http.clj is not there."))

            ;; Generate endpoint definition instructions
            endpoint-content (generators/generate-endpoint-definition
                              module-name path method handler-name)

            ;; Resolved, like every other reported path: the instruction named
            ;; src/… while --output-dir put the module somewhere else, sending
            ;; the reader to the current project.
            files [{:path (.getPath http-file)
                    :content endpoint-content
                    :action :update}]]

        {:success true
         :module-name module-name
         :files files
         :warnings ["Manual code update required - see instructions in output"
                    (when dry-run "Dry run - showing what to add")]})

      (catch Exception e
        {:success false
         :module-name (:module-name request)
         :files []
         :errors [(str "Add endpoint failed: " (.getMessage e))]})))

  (add-adapter [_this request]
    (try
      (let [{:keys [module-name port adapter-name methods dry-run]} request
            base-ns      (or (:base-ns request) "wagoe")
            base-ns-path (template/ns->path base-ns)
            module-path  (template/kebab->snake module-name)
            output-dir   (:output-dir request ".")

            ;; The module, not the adapter: the adapter file is the one being
            ;; created. Checked before `.mkdirs` below, which otherwise builds
            ;; the tree for whatever name it is given (BOU-364).
            _ (require-existing-dir!
               (resolve-path output-dir (format "src/%s/%s" base-ns-path module-path))
               (str "Cannot add an adapter to " module-name ": there is no such module."))

            ;; Generate adapter file content
            adapter-content (generators/generate-adapter-file
                             module-name port adapter-name
                             (or methods [{:name "example-method" :args ["arg1"]}])
                             base-ns)

            adapter-path (format "src/%s/%s/shell/adapters/%s.clj"
                                 base-ns-path module-path
                                 (template/kebab->snake adapter-name))
            files [{:path adapter-path
                    :content adapter-content
                    ;; :skip on a dry run — it reported :create for a file it
                    ;; never wrote.
                    :action (if dry-run :skip :create)}]]

        ;; Write adapter file (unless dry-run)
        (when-not dry-run
          ;; resolve-path, not io/file: this path ignored --output-dir, so
          ;; `bb scaffold adapter --output-dir /tmp` wrote into the cwd. And it
          ;; spit unconditionally, like generate did before BOU-308 — a re-run
          ;; replaced a hand-edited adapter with no warning.
          (let [file (resolve-path output-dir adapter-path)]
            (when (and (.exists file) (not (:force request false)))
              (throw (ex-info "refuse-overwrite"
                              {:type ::refuse-overwrite :existing [(.getPath file)]})))
            (.mkdirs (.getParentFile file))
            (spit file adapter-content)))

        {:success true
         :module-name module-name
         :files files
         :warnings (if dry-run
                     ["Dry run - no files were written"]
                     ["Implement TODO methods in the generated adapter"])})

      (catch Exception e
        {:success false
         :module-name (:module-name request)
         :files []
         :errors [(str "Add adapter failed: " (.getMessage e))]}))))

(defn create-scaffolder-service
  "Create a new scaffolder service.
   
   Returns:
     ScaffolderService instance"
  []
  (->ScaffolderService))
