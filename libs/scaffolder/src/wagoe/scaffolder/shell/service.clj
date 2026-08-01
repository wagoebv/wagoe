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

(defn- existing-migration-ids
  "Numeric ids already used by migration files, as strings.

   Checks both layouts this repo has: generated projects keep migrations in
   `migrations/`, the monorepo in `resources/migrations/`."
  []
  (into #{}
        (for [dir   ["migrations" "resources/migrations"]
              :let  [d (io/file dir)]
              :when (.isDirectory d)
              f     (.listFiles d)
              :let  [m (re-find #"^(\d+)-" (.getName f))]
              :when m]
          (second m))))

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
  []
  (next-migration-id (existing-migration-ids)
                     (.format (java.time.LocalDateTime/now java.time.ZoneOffset/UTC)
                              (java.time.format.DateTimeFormatter/ofPattern "yyyyMMddHHmmss"))))

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

            ;; UTC timestamp id — see get-next-migration-number.
            migration-number (get-next-migration-number)

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
                    :action :create}]]

        ;; Write files (unless dry-run)
        (when-not dry-run?
          (doseq [{:keys [path content]} files]
            (let [file (io/file path)]
              (.mkdirs (.getParentFile file))
              (spit file content))))

        ;; Return result
        {:success true
         :module-name module-name
         :files files
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
            migration-number (get-next-migration-number)

            ;; Generate migration content
            migration-content (generators/generate-add-field-migration
                               module-name entity field migration-number)

            ;; Generate schema instructions
            schema-instructions (generators/generate-add-field-schema-comment
                                 module-name entity field)

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
                    :action :create}
                   {:path (format "src/%s/%s/schema.clj" base-ns-path module-name)
                    :content schema-instructions
                    :action :update}]]

        ;; Write migration files (unless dry-run). Both up and down — writing
        ;; only the first would leave an un-rollbackable migration.
        (when-not dry-run
          (doseq [{:keys [path content action]} files
                  :when (= action :create)]
            (let [file (io/file path)]
              (.mkdirs (.getParentFile file))
              (spit file content))))

        {:success true
         :module-name module-name
         :files files
         :warnings (if dry-run
                     ["Dry run - no files were written"
                      "Manual schema update required - see instructions in output"]
                     ["Manual schema update required - see instructions above"])})

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
         :errors [(str "Add adapter failed: " (.getMessage e))]})))

  (generate-project [_this request]
    (try
      (let [{:keys [name output-dir force dry-run]} request
            project-root (if (= output-dir ".") name (str output-dir "/" name))

            ;; Generate file contents
            deps-content   (generators/generate-project-deps name)
            bb-edn-content (generators/generate-project-bb-edn name)
            readme-content (generators/generate-project-readme name)
            agents-content (generators/generate-project-agents-md name)
            claude-content (generators/generate-project-claude-md name)
            config-content (generators/generate-project-config name)
            main-content   (generators/generate-project-main name)

            files [{:path (str project-root "/deps.edn")
                    :content deps-content
                    :action :create}
                   {:path (str project-root "/bb.edn")
                    :content bb-edn-content
                    :action :create}
                   {:path (str project-root "/README.md")
                    :content readme-content
                    :action :create}
                   {:path (str project-root "/AGENTS.md")
                    :content agents-content
                    :action :create}
                   {:path (str project-root "/CLAUDE.md")
                    :content claude-content
                    :action :create}
                   {:path (str project-root "/resources/conf/dev/config.edn")
                    :content config-content
                    :action :create}
                   {:path (format "%s/src/%s/app.clj"
                                  project-root (str/replace name "-" "/"))
                    :content main-content
                    :action :create}]]

        ;; Check for existing directory if not forcing
        (when (and (not force)
                   (not dry-run)
                   (.exists (io/file project-root)))
          (throw (ex-info (str "Directory already exists: " project-root)
                          {:type :conflict :path project-root})))

        ;; Write files (unless dry-run)
        (when-not dry-run
          (doseq [{:keys [path content]} files]
            (let [file (io/file path)]
              (.mkdirs (.getParentFile file))
              (spit file content))))

        {:success true
         :name name
         :files files
         :warnings (if dry-run
                     ["Dry run - no files were written"]
                     [])})
      (catch Exception e
        {:success false
         :name (:name request)
         :files []
         :errors [(str "Project generation failed: " (.getMessage e))]}))))

(defn create-scaffolder-service
  "Create a new scaffolder service.
   
   Returns:
     ScaffolderService instance"
  []
  (->ScaffolderService))
