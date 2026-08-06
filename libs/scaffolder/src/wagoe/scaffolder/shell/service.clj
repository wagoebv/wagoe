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
                            (let [file (resolve-path output-dir path)]
                              (.mkdirs (.getParentFile file))
                              (spit file content)
                              (assoc entry :action :create :path (.getPath file))))
                          files))]
        {:success true
         :module-name module-name
         :files files
         ;; Supplied here rather than in the CLI so the namespace follows
         ;; --base-ns. The CLI cannot know it, and a hardcoded "wagoe." would
         ;; be one more instruction that does not run.
         :next-steps ["Review the generated files"
                      "Add module to config: [:active :wagoe/settings :modules]"
                      "Wire module into Integrant system configuration"
                      (format "Run tests: clojure -M:test --focus %s.%s.core.%s-test"
                              (str/replace base-ns-path "/" ".") module-name module-name)]
         :warnings (if dry-run?
                     ["Dry run - no files were written"]
                     [])})

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
            existing      (when (.isFile schema-file) (slurp schema-file))
            edit          (when existing
                            (generators/add-field-to-schema existing entity field))
            schema-entry
            (cond
              (nil? existing)
              {:path (.getPath schema-file) :action :skip :manual? true
               :note "not found"
               :manual-note (str "add " (generators/schema-field-entry field)
                                 " to " schema-path " by hand")}

              dry-run
              {:path (.getPath schema-file) :action :skip
               :note "dry run — would add the field to the entity and request schemas"}

              ;; A partial success is still a partial success. Editing two of
              ;; the three schemas and reporting only the two is how the Malli
              ;; set ends up unsynchronised while the output reads as done.
              (= :updated (:status edit))
              (do (spit schema-file (:content edit))
                  (cond-> {:path (.getPath schema-file) :action :update
                           :note (str "added " (generators/schema-field-entry field) " to "
                                      (str/join ", " (:schemas edit)))}
                    (seq (:unreachable edit))
                    (assoc :manual? true
                           :note (str "added " (generators/schema-field-entry field) " to "
                                      (str/join ", " (:schemas edit))
                                      "; could not place it in "
                                      (str/join ", " (:unreachable edit)))
                           ;; Only the part that is left. Repeating what
                           ;; succeeded in an instruction makes the user re-read
                           ;; it to work out what to do.
                           :manual-note (str "add " (generators/schema-field-entry field)
                                             " to " (str/join " and " (:unreachable edit))
                                             " in " schema-path))))

                ;; Not :manual? — nothing is left for the user to do, so
                ;; telling them to "finish the schema edit" would send them to
                ;; a file that is already correct. Re-running must be a no-op
                ;; in the output as well as on disk. This arm requires *every*
                ;; target to already carry the field; one of them answering for
                ;; the others is the defect above.
              (= :already-present (:reason edit))
              {:path (.getPath schema-file) :action :skip
               :note "field is already in every schema"}

              :else
              {:path (.getPath schema-file) :action :skip :manual? true
               :note (str "could not place the field in "
                          (str/join ", " (:unreachable edit)))
               :manual-note (str "add " (generators/schema-field-entry field)
                                 " to " (str/join " and " (:unreachable edit))
                                 " in " schema-path)})
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
         :next-steps (cond-> [(format "Add the field to both transforms in src/%s/%s/shell/persistence.clj (entity->db and db->entity)"
                                      base-ns-path module-name)
                              "Run the migration: clojure -M:migrate up"
                                ;; --focus, not --focus-meta: generated tests carry ^:unit, never a
                                ;; per-module tag, so `--focus-meta :order` printed
                                ;; "No tests found with metadata key :order" and ran
                                ;; everything. Advice that does not work is the same
                                ;; defect as a file report that is not true.
                              (format "Run the tests: clojure -M:test --focus %s.%s.core.%s-test"
                                      (str/replace base-ns-path "/" ".") module-name module-name)]
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
