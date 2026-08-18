(ns wagoe.scaffolder.shell.service
  "Scaffolder service implementation for module generation.
   
   Orchestrates template rendering and file generation."
  (:require [wagoe.scaffolder.ports :as ports]
            [wagoe.scaffolder.schema :as schema]
            [wagoe.scaffolder.core.template :as template]
            [wagoe.scaffolder.core.generators :as generators]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]))

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

(def ^:private module-generation-request-validator (m/validator schema/ModuleGenerationRequest))
(def ^:private module-generation-request-explainer (m/explainer schema/ModuleGenerationRequest))

(defrecord ScaffolderService []
  ports/IScaffolderService

  (generate-module [_ request]
    (try
      ;; Validate request
      (when-not (module-generation-request-validator request)
        (throw (ex-info "Invalid module generation request"
                        {:type :validation-error
                         :errors (module-generation-request-explainer request)})))

      ;; Build template context
      (let [ctx (template/build-module-context request)
            module-name (:module-name ctx)
            base-ns-path (:base-ns-path ctx)
            entity (first (:entities ctx))
            entity-kebab (:entity-kebab entity)
            dry-run? (:dry-run request false)
            force?   (:force request false)
            output-dir (:output-dir request ".")

;; UTC timestamp id — see get-next-migration-number. Scoped to
            ;; output-dir so ids stay unique in the directory being written to.
            migration-number (get-next-migration-number output-dir)

            ;; Generate source file contents
            schema-content (generators/generate-schema-file ctx)
            ports-content (generators/generate-ports-file ctx)
            core-content (generators/generate-core-file ctx)
            migration-content (generators/generate-migration-file ctx migration-number)
            service-content (generators/generate-service-file ctx)
            persistence-content (generators/generate-persistence-file ctx)
            http-content (generators/generate-http-file ctx)
            web-handlers-content (generators/generate-web-handlers-file ctx)
            module-wiring-content (generators/generate-module-wiring-file ctx)
            ui-content (generators/generate-ui-file ctx)

            ;; Generate test file contents
            core-test-content (generators/generate-core-test-file ctx)
            persistence-test-content (generators/generate-persistence-test-file ctx)
            service-test-content (generators/generate-service-test-file ctx)

            ;; Define file paths
            files [{:path (format "src/%s/%s/schema.clj" base-ns-path module-name)
                    :content schema-content
                    :action :create}
                   {:path (format "src/%s/%s/ports.clj" base-ns-path module-name)
                    :content ports-content
                    :action :create}
                   {:path (format "src/%s/%s/core/%s.clj" base-ns-path module-name entity-kebab)
                    :content core-content
                    :action :create}
                   {:path (format "src/%s/%s/core/ui.clj" base-ns-path module-name)
                    :content ui-content
                    :action :create}
                   {:path (format "src/%s/%s/shell/service.clj" base-ns-path module-name)
                    :content service-content
                    :action :create}
                   {:path (format "src/%s/%s/shell/persistence.clj" base-ns-path module-name)
                    :content persistence-content
                    :action :create}
                   {:path (format "src/%s/%s/shell/http.clj" base-ns-path module-name)
                    :content http-content
                    :action :create}
                   {:path (format "src/%s/%s/shell/web_handlers.clj" base-ns-path module-name)
                    :content web-handlers-content
                    :action :create}
                   ;; Without this, `bb scaffold integrate` reported that the
                   ;; module had no wiring and the user hand-wrote the Integrant
                   ;; keys the framework says never to hand-write (BOU-309).
                   {:path (format "src/%s/%s/shell/module_wiring.clj" base-ns-path module-name)
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
                   {:path (format "test/%s/%s/core/%s_test.clj" base-ns-path module-name entity-kebab)
                    :content core-test-content
                    :action :create}
                   {:path (format "test/%s/%s/shell/%s_repository_test.clj" base-ns-path module-name entity-kebab)
                    :content persistence-test-content
                    :action :create}
                   {:path (format "test/%s/%s/shell/service_test.clj" base-ns-path module-name)
                    :content service-test-content
                    :action :create}]

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
                          files))]
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
         :next-steps ["Review the generated files"
                      (format "Print the config it needs: bb scaffold integrate %s" module-name)
                      (format "Run tests: clojure -M:test --focus %s.%s.core.%s-test"
                              (str/replace base-ns-path "/" ".") module-name module-name)]
         :warnings (if dry-run?
                     ["Dry run - no files were written"]
                     [])})

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
            base-ns-path (str/replace (or (:base-ns request) "wagoe") "." "/")
            output-dir (:output-dir request ".")
            migration-number (get-next-migration-number output-dir)

            ;; Generate migration content
            migration-content (generators/generate-add-field-migration
                               module-name entity field migration-number)

            ;; Define files
            field-name-snake (template/kebab->snake (name (:name field)))
            field-name-kebab (name (:name field))
            table-name (template/kebab->snake (template/pluralize (str/lower-case entity)))
            ;; `<id>-<name>.up.sql` + `.down.sql` — same migratus discovery
            ;; requirement as module generation above (BOU-256).
            files [{:path (format "migrations/%s-add-%s-to-%s.up.sql"
                                  migration-number field-name-kebab
                                  (template/pluralize (str/lower-case entity)))
                    :content migration-content
                    :action :create}
                   {:path (format "migrations/%s-add-%s-to-%s.down.sql"
                                  migration-number field-name-kebab
                                  (template/pluralize (str/lower-case entity)))
                    :content (format "-- Rollback: drop %s from %s\n\nALTER TABLE %s DROP COLUMN %s;\n"
                                     field-name-snake table-name table-name field-name-snake)
                    :action :create}]
            schema-path (format "src/%s/%s/schema.clj" base-ns-path module-name)

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
            edit          (when existing
                            (generators/add-field-to-schema existing entity field))
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
            (cond
              (nil? existing)
              {:path (.getPath schema-file) :action :skip :manual? true
               :note "not found"
               ;; All three targets, each with the form it needs — the file is
               ;; absent, so none of them has the field.
               :manual-note (str (add-clause [entity (str "Create" entity "Request") update-schema])
                                 " — " schema-display)}

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
                                      (by-form (:schemas edit))
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
            all-files (conj (vec written) schema-entry)]

        {:success true
         :module-name module-name
         :command :field
         :files all-files
           ;; Named, not implied. Adding a field needs three changes in step —
           ;; schema, column, persistence transforms — and the third cannot be
           ;; generated, because those transforms are hand-written per module.
           ;; Leaving it unsaid is how a field reads back nil with no error
           ;; anywhere (AGENTS.md pitfall 6).
         ;; Resolved like every other path in this report. The persistence
         ;; file was named relative to the working directory while the
         ;; migrations and the schema edit were written under --output-dir, so
         ;; the one step the user cannot skip pointed at a different project.
         :next-steps (cond-> [(format "Add the field to both transforms in %s (entity->db and db->entity)"
                                      (.getPath (resolve-path
                                                 output-dir
                                                 (format "src/%s/%s/shell/persistence.clj"
                                                         base-ns-path module-name))))
                              ;; And the commands run against whatever project
                              ;; the shell is in, which is not the generated one
                              ;; when --output-dir points elsewhere.
                              (str "Run the migration: clojure -M:migrate up" (in-project))
                                ;; --focus, not --focus-meta: generated tests carry ^:unit, never a
                                ;; per-module tag, so `--focus-meta :order` printed
                                ;; "No tests found with metadata key :order" and ran
                                ;; everything. Advice that does not work is the same
                                ;; defect as a file report that is not true.
                              (str (format "Run the tests: clojure -M:test --focus %s.%s.core.%s-test"
                                           (str/replace base-ns-path "/" ".") module-name module-name)
                                   (in-project))]
                       (:manual? schema-entry)
                       (into [(:manual-note schema-entry)]))
         :warnings (when dry-run ["Dry run - no files were written"])})

      (catch Exception e
        {:success false
         :module-name (:module-name request)
         :files []
         :errors [(str "Add field failed: " (.getMessage e))]})))

  (add-endpoint [_this request]
    (try
      (let [{:keys [module-name path method handler-name dry-run]} request
            base-ns-path (str/replace (or (:base-ns request) "wagoe") "." "/")

            ;; Generate endpoint definition instructions
            endpoint-content (generators/generate-endpoint-definition
                              module-name path method handler-name)

            files [{:path (format "src/%s/%s/shell/http.clj" base-ns-path module-name)
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
            base-ns-path (str/replace base-ns "." "/")

            ;; Generate adapter file content
            adapter-content (generators/generate-adapter-file
                             module-name port adapter-name
                             (or methods [{:name "example-method" :args ["arg1"]}])
                             base-ns)

            adapter-path (format "src/%s/%s/shell/adapters/%s.clj"
                                 base-ns-path module-name adapter-name)
            files [{:path adapter-path
                    :content adapter-content
                    :action :create}]]

        ;; Write adapter file (unless dry-run)
        (when-not dry-run
          (let [file (io/file adapter-path)]
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
