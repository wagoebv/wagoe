(ns wagoe.scaffolder.core.generators
  "Pure functions for generating file content from templates.

   Each generator function takes a template context and returns
   file content as a string. All functions are pure and deterministic."
  (:require [clojure.string :as str]
            [rewrite-clj.zip :as z]
            [rewrite-clj.node :as n]
            [wagoe.scaffolder.core.template :as template]))

;; BOU-259: the project generators (deps.edn, bb.edn, README, config, main,
;; AGENTS.md, CLAUDE.md) lived here as a second implementation of the `wagoe new`
;; templates in libs/wagoe-cli/resources/wagoe/cli/templates/. They drifted until
;; a project built this way had no com.wagoe deps and no entry point. Removed —
;; libs/wagoe-cli is the only project generator, and the wagoe-tools version pin
;; it needs lives in libs/wagoe-cli/src/wagoe/cli/new.clj. This namespace now
;; generates only what goes *inside* an existing project.

;; =============================================================================
;; Schema File Generator
;; =============================================================================

(defn- repo-fns
  "The entity's repository method names; a hand-built context without them
   gets the first entity's."
  [entity]
  (merge (template/repository-fns nil nil true) (:repo-fns entity)))

(defn- shell-ns
  "The entity's `shell.*` namespace suffix for `k`, the first entity's when
   the context does not say."
  [entity k]
  (get entity k (get {:service-ns "shell.service"
                      :persistence-ns "shell.persistence"
                      :service-test-ns "shell.service-test"} k)))

(defn generate-field-schema
  "Generate Malli schema for a single field.
   
   Args:
     field-ctx - Field context map
   
   Returns:
     String representation of Malli schema
   
   Pure: true"
  ([field-ctx] (generate-field-schema field-ctx false))
  ([field-ctx force-optional?]
   (let [required   (and (:field-required field-ctx) (not force-optional?))
         malli-type (:malli-type field-ctx)
         field-name (keyword (:field-name-kebab field-ctx))]
     (if required
       (format "   [%s %s]" field-name malli-type)
       (format "   [%s {:optional true} %s]" field-name malli-type)))))

(def ^:private banner-rule
  ";; =============================================================================")

(defn- banner [title]
  (str banner-rule "\n;; " title "\n" banner-rule "\n\n"))

(defn- schema-parts
  "The entity, request and validation defs for one entity, as three strings."
  [entity]
  (let [entity-name (:entity-name entity)
        ;; Names the `validate-<x>` / `explain-<x>` vars the core file calls,
        ;; so it has to be the same derivation the core file uses — kebab, not
        ;; a lowercased run of words (BOU-480).
        e (template/pascal->kebab entity-name)
        fields (:fields entity)
        field-schemas (str/join "\n" (map generate-field-schema fields))
        ;; Every field optional, whatever it is on the entity. An update
        ;; request is a partial: one required field in this schema means every
        ;; caller doing a partial update has to send it. The same
        ;; `field-schemas` string used to be interpolated into all three
        ;; schemas, so `--field name:string:required` made `name` mandatory on
        ;; update — against the convention in libs/scaffolder/AGENTS.md.
        update-field-schemas (str/join "\n" (map #(generate-field-schema % true) fields))]
    {:entity (str "(def " entity-name "\n"
                  "  \"Schema for " entity-name " entity.\"\n"
                  "  [:map {:title \"" entity-name "\"}\n"
                  "   [:id :uuid]\n"
                  field-schemas "\n"
                  "   [:created-at inst?]\n"
                  "   [:updated-at {:optional true} [:maybe inst?]]\n"
                  "   [:deleted-at {:optional true} [:maybe inst?]]])\n")
     :requests (str "(def Create" entity-name "Request\n"
                    "  \"Schema for create " e " API requests.\"\n"
                    "  [:map {:title \"Create " entity-name " Request\"}\n"
                    field-schemas "])\n"
                    "\n"
                    "(def Update" entity-name "Request\n"
                    "  \"Schema for update " e " API requests.\"\n"
                    "  [:map {:title \"Update " entity-name " Request\"}\n"
                    update-field-schemas "])\n")
     :validation (str "(def ^:private " e "-validator (m/validator " entity-name "))\n"
                      "(def ^:private " e "-explainer (m/explainer " entity-name "))\n"
                      "\n"
                      "(defn validate-" e "\n"
                      "  \"Validates a " e " entity against the " entity-name " schema.\"\n"
                      "  [" e "-data]\n"
                      "  (" e "-validator " e "-data))\n"
                      "\n"
                      "(defn explain-" e "\n"
                      "  \"Provides detailed validation errors for " e " data.\"\n"
                      "  [" e "-data]\n"
                      "  (" e "-explainer " e "-data))\n")}))

(defn generate-schema-file
  "Generate schema.clj file content for the module's first entity.

   Pure: true"
  [ctx]
  (let [base-ns (:base-ns ctx "wagoe")
        module-name (:module-name ctx)
        {:keys [entity requests validation]} (schema-parts (first (:entities ctx)))]
    (str "(ns " base-ns "." module-name ".schema\n"
         "  \"Schema definitions for " module-name " module.\"\n"
         "  (:require [malli.core :as m]))\n"
         "\n"
         (banner "Domain Entity Schemas")
         entity
         "\n"
         (banner "API Request Schemas")
         requests
         "\n"
         (banner "Validation Functions")
         validation)))

(defn entity-schema-section
  "The schema.clj text a further entity appends: its entity, request and
   validation defs under one banner. Every name in it carries the entity's
   name, so it cannot collide with the defs already in the file.

   Pure: true"
  [entity-ctx]
  (let [{:keys [entity requests validation]} (schema-parts entity-ctx)]
    (str (banner (:entity-name entity-ctx))
         entity "\n" requests "\n" validation)))

;; =============================================================================
;; Ports File Generator
;; =============================================================================

(defn- ports-parts
  "The repository and service protocols for one entity, as two strings."
  [entity]
  (let [entity-name (:entity-name entity)
        e (:entity-lower entity)
        {:keys [find-by-id find-all create update delete]} (repo-fns entity)]
    {:repository
     (str "(defprotocol I" entity-name "Repository\n"
          "  \"Repository interface for " e " persistence operations.\"\n"
          "\n"
          "  (" find-by-id " [this id]\n"
          "    \"Find " e " by ID.\")\n"
          "\n"
          "  (" find-all " [this options]\n"
          "    \"Find all " e "s with pagination and filtering.\")\n"
          "\n"
          "  (" create " [this entity]\n"
          "    \"Create new " e ".\")\n"
          "\n"
          (when (get entity :primary? true)
            (str "  ;; `update-entity`, not `update-<entity>`: the service protocol below declares\n"
                 "  ;; `update-<entity>` too, and defprotocol interns its methods as vars in this\n"
                 "  ;; namespace — so the second silently overwrote the first, leaving\n"
                 "  ;; ports/update-<entity> with the service arity [this id data]. Nor `update`,\n"
                 "  ;; which would shadow clojure.core/update. The repository's other methods are\n"
                 "  ;; already generic (create, delete), so this matches its own family. (BOU-267)\n"))
          "  (" update " [this entity]\n"
          "    \"Update existing " e ".\")\n"
          "\n"
          "  (" delete " [this id]\n"
          "    \"Delete " e " by ID.\"))\n")
     :service
     (str "(defprotocol I" entity-name "Service\n"
          "  \"" entity-name " service interface for business operations.\"\n"
          "\n"
          "  (get-" e " [this id]\n"
          "    \"Get " e " by ID.\")\n"
          "\n"
          "  (list-" e "s [this options]\n"
          "    \"List " e "s with pagination.\")\n"
          "\n"
          "  (create-" e " [this data]\n"
          "    \"Create new " e ".\")\n"
          "\n"
          "  (update-" e " [this id data]\n"
          "    \"Update " e ".\")\n"
          "\n"
          "  (delete-" e " [this id]\n"
          "    \"Delete " e ".\"))\n")}))

(defn generate-ports-file
  "Generate ports.clj file content for the module's first entity.

   Pure: true"
  [ctx]
  (let [base-ns (:base-ns ctx "wagoe")
        module-name (:module-name ctx)
        {:keys [repository service]} (ports-parts (first (:entities ctx)))]
    (str "(ns " base-ns "." module-name ".ports\n"
         "  \"" (str/capitalize module-name) " module port definitions (abstract interfaces).\")\n"
         "\n"
         (banner "Repository Ports")
         repository
         "\n"
         (banner "Service Ports")
         service)))

(defn entity-ports-section
  "The ports.clj text a further entity appends: its repository and service
   protocols under one banner.

   Pure: true"
  [entity]
  (let [{:keys [repository service]} (ports-parts entity)]
    (str (banner (:entity-name entity)) repository "\n" service)))

;; =============================================================================
;; Core Logic File Generator
;; =============================================================================

(defn generate-core-file
  "Generate core/{entity}.clj file content.
   
   Args:
     ctx - Template context map
   
   Returns:
     String content for core/{entity}.clj
   
   Pure: true"
  ([ctx] (generate-core-file ctx (first (:entities ctx))))
  ([ctx entity]
   (let [base-ns (:base-ns ctx "wagoe")
         module-name (:module-name ctx)
         _entity-name (:entity-name entity)
         entity-lower (:entity-lower entity)
         entity-kebab (:entity-kebab entity)]
     (format "(ns %s.%s.core.%s
  \"Pure business logic for %s domain.
   
   All functions in this namespace are pure - they have no side effects,
   don't perform I/O, and always return the same output for the same input.\"
  (:require [%s.%s.schema :as schema]))

;; =============================================================================
;; Entity Creation
;; =============================================================================

(defn prepare-new-%s
  \"Prepare data for creating a new %s.
   
   Args:
     data - Input data map
     entity-id - UUID supplied by the shell
     current-time - java.time.Instant for timestamps
   
   Returns:
     Prepared %s entity map
   
   Pure: true\"
  [data entity-id current-time]
  (merge data
         {:id entity-id
          :created-at current-time
          :updated-at current-time}))

;; =============================================================================
;; Entity Updates
;; =============================================================================

(defn apply-%s-update
  \"Apply updates to existing %s entity.
   
   Args:
     existing - Current %s entity
     updates - Map of fields to update
     current-time - java.time.Instant for updated-at
   
   Returns:
     Updated %s entity map
   
   Pure: true\"
  [existing updates current-time]
  (merge existing
         updates
         {:updated-at current-time}))

;; =============================================================================
;; Validation
;; =============================================================================

(defn validate-%s
  \"Validate %s entity data.
   
   Args:
     data - %s data to validate
   
   Returns:
     Vector of [valid? errors data]
   
   Pure: true\"
  [data]
  (if (schema/validate-%s data)
    [true nil data]
    [false (schema/explain-%s data) nil]))
"
             base-ns
             module-name
             entity-kebab
             entity-lower
             base-ns
             module-name
             entity-kebab
             entity-lower
             entity-lower
             entity-kebab
             entity-lower
             entity-lower
             entity-lower
             entity-kebab
             entity-lower
             entity-lower
             entity-lower
             entity-lower))))

;; =============================================================================
;; Migration File Generator
;; =============================================================================

(defn generate-migration-field
  "Generate SQL for a single field.
   
   Args:
     field-ctx - Field context map
   
   Returns:
     SQL field definition string
   
   Pure: true"
  [field-ctx]
  (let [field-name (:field-name-snake field-ctx)
        sql-type (:sql-type field-ctx)
        required (:field-required field-ctx)
        unique (:field-unique field-ctx)
        default-clause (if-let [d (:sql-default field-ctx)] (str " DEFAULT " d) "")
        null-clause (if required " NOT NULL" "")
        unique-clause (if unique " UNIQUE" "")
        ;; A relation carries its REFERENCES inline. Written by hand before,
        ;; every time, because there was no field type that meant it (BOU-480).
        references-clause (if-let [table (:relation-table field-ctx)]
                            (format " REFERENCES %s(id) ON DELETE %s"
                                    table
                                    (get template/on-delete-clauses
                                         (:on-delete field-ctx)
                                         "CASCADE"))
                            "")]
    (format "  %s %s%s%s%s%s" field-name sql-type default-clause null-clause unique-clause references-clause)))

(defn generate-migration-file
  "Generate migration SQL file content.
   
   Args:
     ctx - Template context map
     migration-number - Migration sequence number (e.g., \"005\")
   
   Returns:
     String content for migration SQL
   
   Pure: true"
  ([ctx migration-number] (generate-migration-file ctx (first (:entities ctx)) migration-number))
  ([_ctx entity migration-number]
   (let [table-name (:entity-table entity)
         fields (:fields entity)
         field-sqls (str/join ",\n" (map generate-migration-field fields))
        ;; Every foreign key gets one: it is what a join reads, and what the
        ;; database scans on each cascading delete of the parent. An `indexed`
        ;; field gets one too (BOU-535).
         relation-indexes (->> fields
                               (filter #(or (:relation-table %) (:field-indexed %)))
                               (map (fn [f]
                                      (format "CREATE INDEX IF NOT EXISTS idx_%s_%s ON %s(%s);"
                                              table-name (:field-name-snake f)
                                              table-name (:field-name-snake f))))
                               (str/join "\n"))]
     (format "-- Migration %s: Create %s table

CREATE TABLE IF NOT EXISTS %s (
  id UUID PRIMARY KEY,
%s,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE,
  deleted_at TIMESTAMP WITH TIME ZONE
);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_%s_created_at ON %s(created_at);
%s"
             migration-number
             table-name
             table-name
             field-sqls
             table-name
             table-name
             (if (str/blank? relation-indexes) "" (str relation-indexes "\n"))))))

(defn generate-migration-down-file
  "Generate the rollback SQL matching `generate-migration-file`.

   migratus pairs `<id>-<name>.up.sql` with `<id>-<name>.down.sql`; without the
   down file a migration cannot be rolled back. Dropping the table also removes
   its index, so the index needs no separate statement.

   Pure: true"
  ([ctx] (generate-migration-down-file ctx (first (:entities ctx))))
  ([_ctx entity]
   (let [table-name (:entity-table entity)]
     (format "-- Rollback: drop the %s table

DROP TABLE IF EXISTS %s;
"
             table-name
             table-name))))

;; =============================================================================
;; UI File Generator
;; =============================================================================

(defn generate-ui-file
  "Generate core/ui.clj file content.
   
   Args:
     ctx - Template context map
     
   Returns:
     String content for ui.clj file
     
   Pure: true"
  [ctx]
  (let [base-ns (:base-ns ctx "wagoe")
        module-name (:module-name ctx)
        entity (first (:entities ctx))
        entity-name (:entity-name entity)
        entity-lower (template/pascal->kebab entity-name)
        entity-plural (template/pluralize entity-lower)
        field-names (map :field-name-kebab (:fields entity))
        ;; Plain strings, not [:t ...] markers: the page renders through
        ;; hiccup2, and wagoe-i18n is a library a project may drop (BOU-484).
        headers (->> field-names
                     (map #(str "[:th \"" (str/capitalize (str/replace % "-" " ")) "\"]"))
                     (str/join " "))
        cells (->> field-names
                   (map #(str "[:td (str (:" % " item))]"))
                   (str/join "\n        "))]
    (str "(ns " base-ns "." module-name ".core.ui\n"
         "  \"Pure UI generation for " module-name " module - Hiccup templates.\")\n"
         "\n"
         "(defn " entity-lower "-list-page\n"
         "  \"Generate " entity-lower " listing page.\"\n"
         "  [" entity-plural " _opts]\n"
         "  [:div.page\n"
         "   [:h1 \"" (str/capitalize entity-plural) "\"]\n"
         "   [:table.items\n"
         "    [:thead [:tr " headers "]]\n"
         "    [:tbody\n"
         "     (for [item " entity-plural "]\n"
         "       [:tr\n"
         "        " cells "])]]])\n")))

;; =============================================================================
;; Service File Generator
;; =============================================================================

(defn generate-service-file
  "Generate shell/service.clj file content.
   
   Args:
     ctx - Template context map
     
   Returns:
     String content for service.clj file
     
   Pure: true"
  ([ctx] (generate-service-file ctx (first (:entities ctx))))
  ([ctx entity]
   (let [base-ns (:base-ns ctx "wagoe")
         module-name (:module-name ctx)
         entity-name (:entity-name entity)
         entity-lower (template/pascal->kebab entity-name)
         entity-kebab (str/replace entity-lower #"\s+" "-")
         {:keys [find-by-id find-all create update delete]} (repo-fns entity)]
     (str "(ns " base-ns "." module-name "." (shell-ns entity :service-ns) "\n"
          "  \"Service layer for " module-name " module.\"\n"
          "  (:require [" base-ns "." module-name ".ports :as ports]\n"
          "            [" base-ns "." module-name ".core." entity-kebab " :as core])\n"
          "  (:import [java.time Instant]\n"
          "           [java.util UUID]))\n"
          "\n"
          "(defn- current-time []\n"
          "  (Instant/now))\n"
          "\n"
          "(defn- generate-" entity-lower "-id []\n"
          "  (UUID/randomUUID))\n"
          "\n"
          "(defrecord " entity-name "Service [repository]\n"
          "  ports/I" entity-name "Service\n"
         ;; _this everywhere: none of these bodies use it, and an unused binding
         ;; is a clj-kondo warning — which fails `bb check` in the generated
         ;; project, since kondo exits non-zero on warnings (BOU-267).
         ;; `ports/create`, not `(.create repository …)`. Interop asks the
         ;; reflector for a method on whatever the repository happens to be:
         ;; every call is reflective, and nothing ties the service to the port
         ;; it is written against. The protocol function is the way through a
         ;; port (BOU-478).
          "  (create-" entity-lower " [_this data]\n"
          "    (let [prepared (core/prepare-new-" entity-lower " data (generate-" entity-lower "-id) (current-time))]\n"
          "      (ports/" create " repository prepared)))\n"
          "  (get-" entity-lower " [_this id]\n"
          "    (ports/" find-by-id " repository id))\n"
         ;; find-all, not list-<plural>: the repository port has no
         ;; list-<plural> method, so this called something that does not exist
         ;; and blew up at runtime the first time anyone listed anything.
          "  (list-" (template/pluralize entity-lower) " [_this opts]\n"
          "    (ports/" find-all " repository opts))\n"
          "  (update-" entity-lower " [_this id data]\n"
          "    (ports/" update " repository (assoc data :id id)))\n"
          "  (delete-" entity-lower " [_this id]\n"
          "    (ports/" delete " repository id)))\n"
          "\n"
          "(defn create-service [repository]\n"
          "  (->" entity-name "Service repository))\n"))))

;; =============================================================================
;; Persistence File Generator
;; =============================================================================

(defn generate-persistence-file
  "Generate shell/persistence.clj file content.
   
   Args:
     ctx - Template context map
     
   Returns:
     String content for persistence.clj file
     
   Pure: true"
  ([ctx] (generate-persistence-file ctx (first (:entities ctx))))
  ([ctx entity]
   (let [base-ns (:base-ns ctx "wagoe")
         module-name (:module-name ctx)
         entity-name (:entity-name entity)
        ;; entity-lower was only used to build the repository's
        ;; `update-<entity>` method name, which is now the literal
        ;; `update-entity` (BOU-267).
        ;;
        ;; `:entity-table` rather than a derivation of its own: this built the
        ;; table from the PascalCase name, so it queried `Products` while the
        ;; migration created `products` — which worked only because neither H2
        ;; nor PostgreSQL distinguishes unquoted identifiers by case (BOU-486).
         table-name (:entity-table entity)
         {:keys [find-by-id find-all create update delete]} (repo-fns entity)]
     ;; HoneySQL maps, not `sql/format` output: the platform formats a map for
     ;; the adapter's dialect and converts Instants, and a pre-formatted vector
     ;; skips both. No RETURNING either — H2, the test profile's database,
     ;; rejects it, so every create answered 500 (BOU-497).
     (str "(ns " base-ns "." module-name "." (shell-ns entity :persistence-ns) "\n"
          "  \"Persistence layer for " module-name " module.\"\n"
          "  (:require [" base-ns "." module-name ".ports :as ports]\n"
          "            [wagoe.platform.database :as db])\n"
          "  (:import [java.sql SQLException]\n"
          "           [java.time Instant LocalDate]))\n"
          "\n"
          "(defn- date->iso\n"
          "  \"A DATE column as the schema's YYYY-MM-DD. Drivers return java.sql.Date,\n"
          "   whose JSON form depends on the JVM's time zone.\"\n"
          "  [v]\n"
          "  (cond (instance? java.sql.Date v) (str (.toLocalDate ^java.sql.Date v))\n"
          "        (instance? LocalDate v) (str v)\n"
          "        :else v))\n"
          "\n"
          "(defn- ->entity [row]\n"
          "  (some-> row (update-vals date->iso)))\n"
          "\n"
          ;; A 400, not the 500 a :database-error is answered with (BOU-540).
          "(defn- missing-reference?\n"
          "  \"Whether the database refused a reference to a row that does not exist:\n"
          "   SQLState 23503 (PostgreSQL) or 23506 (H2), error 1452 (MySQL), and SQLite's\n"
          "   result code, which it reports only in the message.\"\n"
          "  [e]\n"
          "  (some #(and (instance? SQLException %)\n"
          "              (or (#{\"23503\" \"23506\"} (.getSQLState ^SQLException %))\n"
          "                  (= 1452 (.getErrorCode ^SQLException %))\n"
          "                  (re-find #\"SQLITE_CONSTRAINT_FOREIGNKEY\" (str (ex-message %)))))\n"
          "        (take-while some? (iterate ex-cause e))))\n"
          "\n"
          "(defn- write! [db-ctx query]\n"
          "  (try\n"
          "    (db/execute-update! db-ctx query)\n"
          "    (catch clojure.lang.ExceptionInfo e\n"
          "      (throw (if (missing-reference? e)\n"
          "               (ex-info \"A referenced record does not exist\" {:type :validation-error} e)\n"
          "               e)))))\n"
          "\n"
          "(defn- select-by-id [db-ctx id]\n"
          "  (->entity (db/execute-one! db-ctx {:select [:*] :from [:" table-name "] :where [:= :id id]})))\n"
          "\n"
          "(defrecord Database" entity-name "Repository [db-ctx]\n"
          "  ports/I" entity-name "Repository\n"
          "  (" create " [_this entity]\n"
          "    (write! db-ctx {:insert-into :" table-name " :values [entity]})\n"
          "    (select-by-id db-ctx (:id entity)))\n"
          "  (" find-by-id " [_this id]\n"
          "    (select-by-id db-ctx id))\n"
          "  (" find-all " [_this opts]\n"
          "    (mapv ->entity (db/execute-query! db-ctx {:select [:*]\n"
          "                                              :from [:" table-name "]\n"
          "                                              :limit (or (:limit opts) 20)})))\n"
          "  (" update " [_this entity]\n"
          ;; Refused here rather than sent: an empty :set is `UPDATE t SET  WHERE`,
          ;; a database syntax error (BOU-547).
          "    (let [changes (dissoc entity :id :created-at :updated-at)]\n"
          "      (when (empty? changes)\n"
          "        (throw (ex-info \"Nothing to update\" {:type :validation-error :id (:id entity)})))\n"
          "      (write! db-ctx {:update :" table-name "\n"
          "                      :set (assoc changes :updated-at (Instant/now))\n"
          "                      :where [:= :id (:id entity)]})\n"
          "      (select-by-id db-ctx (:id entity))))\n"
          "  (" delete " [_this id]\n"
          "    (db/execute-update! db-ctx {:delete-from :" table-name " :where [:= :id id]})))\n"
          "\n"
          "(defn create-repository [db-ctx]\n"
          "  (->Database" entity-name "Repository db-ctx))\n"))))

;; =============================================================================
;; Web Handlers File Generator
;; =============================================================================

(defn generate-web-handlers-file
  "Generate shell/web_handlers.clj file content.
   
   Args:
     ctx - Template context map
     
   Returns:
     String content for web_handlers.clj file
     
   Pure: true"
  [ctx]
  (let [base-ns (:base-ns ctx "wagoe")
        module-name (:module-name ctx)
        entity (first (:entities ctx))
        entity-name (:entity-name entity)
        entity-lower (template/pascal->kebab entity-name)
        entity-plural (template/pluralize entity-lower)]
    (str "(ns " base-ns "." module-name ".shell.web-handlers\n"
         "  \"Web UI handlers for " module-name " module.\"\n"
         "  (:require [" base-ns "." module-name ".core.ui :as ui]\n"
         "            [" base-ns "." module-name ".ports :as ports]\n"
         "            [hiccup2.core :as h]))\n"
         "\n"
         "(defn " entity-lower "-list-handler [service _config]\n"
         "  (fn [_request]\n"
         "    (let [items (ports/list-" entity-plural " service {})]\n"
         "      {:status 200\n"
         "       :headers {\"Content-Type\" \"text/html; charset=utf-8\"}\n"
         ;; A string, not the Hiccup tree: Ring cannot write a vector, so
         ;; returning the page directly produced a response no adapter could
         ;; serve (BOU-484).
         ;;
         ;; hiccup2 rather than wagoe.i18n's renderer, which resolves [:t ...]
         ;; markers as well: i18n is a module a project may drop, and a
         ;; generated module that cannot load without it is a hard dependency
         ;; bought for a page that has no markers in it. Adding markers means
         ;; adding wagoe-i18n and rendering through
         ;; `wagoe.i18n.shell.render/render` instead.
         "       :body (str (h/html (ui/" entity-lower "-list-page items {})))})))\n")))

;; =============================================================================
;; Test File Generators
;; =============================================================================

(defn generate-core-test-file
  "Generate test core file content.
   
   Args:
     ctx - Template context map
     
   Returns:
     String content for core test file
     
   Pure: true"
  ([ctx] (generate-core-test-file ctx (first (:entities ctx))))
  ([ctx entity]
   (let [base-ns (:base-ns ctx "wagoe")
         module-name (:module-name ctx)
         entity-name (:entity-name entity)
         entity-lower (template/pascal->kebab entity-name)]
     (str "(ns " base-ns "." module-name ".core." entity-lower "-test\n"
          "  (:require [clojure.test :refer [deftest testing is]]\n"
          "            [" base-ns "." module-name ".core." entity-lower " :as core])\n"
          "  (:import [java.time Instant]\n"
          "           [java.util UUID]))\n"
          "\n"
         ;; Pyramid tag is required, not decorative: `bb check:test-tags`
         ;; enforces exactly one per deftest, and it runs in generated projects.
         ;; Untagged output made `bb check` fail the moment a user scaffolded a
         ;; module, while AGENTS.md claimed the scaffolder emits correct test
         ;; metadata (BOU-264 review).
          "(deftest ^:unit prepare-new-" entity-lower "-test\n"
          "  (testing \"prepares " entity-lower " for creation\"\n"
          "    (let [data {:name \"Test\"}\n"
          "          " entity-lower "-id (UUID/fromString \"11111111-1111-1111-1111-111111111111\")\n"
          "          current-time (Instant/parse \"2026-01-01T00:00:00Z\")\n"
          "          result (core/prepare-new-" entity-lower " data " entity-lower "-id current-time)]\n"
          "      (is (= " entity-lower "-id (:id result)))\n"
          "      (is (= current-time (:created-at result)))\n"
          "      (is (= current-time (:updated-at result))))))\n"))))

(defn generate-service-test-file
  "Generate test service file content.
   
   Args:
     ctx - Template context map
     
   Returns:
     String content for service test file
     
   Pure: true"
  ([ctx] (generate-service-test-file ctx (first (:entities ctx))))
  ([ctx entity]
   (let [base-ns (:base-ns ctx "wagoe")
         module-name (:module-name ctx)
         entity-name (:entity-name entity)
         entity-lower (template/pascal->kebab entity-name)
         {:keys [find-by-id find-all create update delete]} (repo-fns entity)]
     (str "(ns " base-ns "." module-name "." (shell-ns entity :service-test-ns) "\n"
          "  (:require [clojure.test :refer [deftest testing is]]\n"
          "            [" base-ns "." module-name "." (shell-ns entity :service-ns) " :as service]\n"
          "            [" base-ns "." module-name ".ports :as ports]))\n"
          "\n"
         ;; ^:unit — the repository is a reify'd stub, so no database is touched.
          "(deftest ^:unit create-" entity-lower "-test\n"
          "  (testing \"creates " entity-lower " via service\"\n"
         ;; Every method, not just create: reify'ing a protocol partially is a
         ;; clj-kondo warning, and `bb check` fails on warnings in the generated
         ;; project. It also makes the stub usable as you add tests, instead of
         ;; blowing up the first time one calls find-all (BOU-267).
          "    (let [mock-repo (reify ports/I" entity-name "Repository\n"
          "                      (" create " [_ entity] entity)\n"
          "                      (" find-by-id " [_ _id] nil)\n"
          "                      (" find-all " [_ _opts] [])\n"
          "                      (" update " [_ entity] entity)\n"
          "                      (" delete " [_ _id] nil))\n"
          "          svc (service/create-service mock-repo)\n"
          "          result (ports/create-" entity-lower " svc {:name \"Test\"})]\n"
          "      (is (some? result)))))\n"))))

(defn generate-persistence-test-file
  "Generate test persistence file content.
   
   Args:
     ctx - Template context map
     
   Returns:
     String content for persistence test file
     
   Pure: true"
  ([ctx] (generate-persistence-test-file ctx (first (:entities ctx))))
  ([ctx entity]
   (let [base-ns (:base-ns ctx "wagoe")
         module-name (:module-name ctx)
         entity-name (:entity-name entity)
         entity-lower (template/pascal->kebab entity-name)]
     (str "(ns " base-ns "." module-name ".shell." entity-lower "-repository-test\n"
          "  (:require [clojure.test :refer [deftest testing is]]\n"
          "            [" base-ns "." module-name "." (shell-ns entity :persistence-ns) " :as persistence]\n"
          "            [" base-ns "." module-name ".ports :as ports]))\n"
          "\n"
         ;; Was `(is true)` with a \"requires database context\" comment, which
         ;; `bb check:placeholder-tests` rejects — and that check runs in
         ;; generated projects, so scaffolding a module broke `bb check`.
         ;;
         ;; Asserting the wiring instead is both real and database-free: it
         ;; fails if the repository stops implementing its port, which is the
         ;; mistake this file can actually catch before a database exists.
         ;; ^:integration because the exercises you add next need one.
          "(deftest ^:integration create-" entity-lower "-test\n"
          "  (testing \"the repository implements its persistence port\"\n"
          "    (is (satisfies? ports/I" entity-name "Repository\n"
          "                    (persistence/create-repository nil))))\n"
          "  (testing \"creating a " entity-lower " round-trips through the database\"\n"
          "    ;; Add a database context and assert on a real create here.\n"
          "    ;; See the module README for wiring a test db-ctx.\n"
          "    ))\n"))))

;; =============================================================================
;; Further entities (BOU-497)
;; =============================================================================
;;
;; A module's first entity owns schema.clj, ports.clj, shell/service.clj,
;; shell/persistence.clj and the web files. A further entity gets files of its
;; own and appends a section to schema.clj and ports.clj. Appending keeps what
;; is already there byte for byte: the file is not re-parsed and re-printed, so
;; hand edits survive, and every name the section defines carries the entity's
;; name, so the file still compiles.

(defn defined-symbols
  "Every top-level name `source` defines, protocol methods included, or nil
   when it does not parse.

   Pure: true"
  [source]
  (try
    (loop [loc (z/of-string source) acc #{}]
      (if (nil? loc)
        acc
        (let [head (when (= :list (z/tag loc)) (some-> loc z/down))
              op   (some-> head z/sexpr)
              nm   (some-> head z/right z/sexpr)
              methods (when (= 'defprotocol op)
                        (->> (iterate z/right (z/right head))
                             (take-while some?)
                             (filter #(= :list (z/tag %)))
                             (keep #(some-> % z/down z/sexpr))))]
          (recur (z/right loc)
                 (cond-> (into acc methods)
                   (and (symbol? op) (str/starts-with? (name op) "def") (symbol? nm))
                   (conj nm))))))
    (catch Exception _ nil)))

(defn append-section
  "`source` with `section` after it, one blank line between.

   Pure: true"
  [source section]
  (str (str/trimr source) "\n\n" section))

(defn- api-requires
  "The ns requires the API section below needs."
  [base-ns module-name]
  [(str "[" base-ns "." module-name ".ports :as ports]")
   (str "[" base-ns "." module-name ".schema :as schema]")
   "[malli.core :as m]"
   "[malli.transform :as mt]"])

(defn- ns-form
  "An ns form with a docstring and `requires`, one per line."
  [ns-name doc requires]
  (str "(ns " ns-name "\n"
       "  \"" doc "\""
       (if (seq requires)
         (str "\n  (:require " (str/join "\n            " requires) "))\n")
         ")\n")))

(defn- api-section
  "An entity's CRUD API: request decoding and `api-routes`. Every entity's
   http namespace gets this one, the first entity's included (BOU-539).

   Bodies are decoded with the Create/Update request schemas, which drop
   unknown keys — they would otherwise become column names in the insert — and
   turn JSON strings into UUIDs."
  [entity]
  (let [entity-name (:entity-name entity)
        e (or (:entity-kebab entity) (template/pascal->kebab entity-name))
        plural (or (:entity-plural entity) (template/pluralize e))]
    (str ";; JSON has no decimal type: Muuntaja reads 9.99 as a Double, and malli\n"
         ";; has no decoder for decimal?, so without this a decimal field refused both\n"
         ";; 9.99 and \"9.99\".\n"
         "(defn- ->decimal [x]\n"
         "  (cond (number? x) (bigdec x)\n"
         "        (string? x) (try (bigdec x) (catch NumberFormatException _ x))\n"
         "        :else x))\n"
         "\n"
         "(def ^:private json->data\n"
         "  (mt/transformer mt/strip-extra-keys-transformer mt/json-transformer\n"
         "                  {:decoders {'decimal? ->decimal}}))\n"
         "\n"
         "(def ^:private decode-create (m/decoder schema/Create" entity-name "Request json->data))\n"
         "(def ^:private valid-create? (m/validator schema/Create" entity-name "Request))\n"
         "(def ^:private decode-update (m/decoder schema/Update" entity-name "Request json->data))\n"
         "(def ^:private valid-update? (m/validator schema/Update" entity-name "Request))\n"
         "\n"
         "(defn- invalid []\n"
         "  {:status 400 :body {:error {:type :validation-error :message \"Invalid " e "\"}}})\n"
         "\n"
         "(defn- not-found []\n"
         "  {:status 404 :body {:error {:type :not-found :message \"No such " e "\"}}})\n"
         "\n"
         "(defn- id-of [request]\n"
         "  (some-> (get-in request [:path-params :id]) parse-uuid))\n"
         "\n"
         "(defn api-routes\n"
         "  \"Reitit route data. Paths are relative — the platform mounts them under /api/v1.\"\n"
         "  [service]\n"
         "  [[\"/" plural "\"\n"
         "    {:get  {:summary \"List " plural "\"\n"
         "            :handler (fn [_request]\n"
         "                       {:status 200 :body (ports/list-" e "s service {})})}\n"
         "     :post {:summary \"Create a " e "\"\n"
         "            :handler (fn [request]\n"
         "                       (let [data (decode-create (:body-params request))]\n"
         "                         (if (valid-create? data)\n"
         "                           {:status 201 :body (ports/create-" e " service data)}\n"
         "                           (invalid))))}}]\n"
         "   [\"/" plural "/:id\"\n"
         "    {:swagger {:parameters [{:name \"id\" :in \"path\" :required true :type \"string\"}]}\n"
         "     :get    {:summary \"Get a " e "\"\n"
         "              :handler (fn [request]\n"
         "                         (if-let [found (some->> (id-of request) (ports/get-" e " service))]\n"
         "                           {:status 200 :body found}\n"
         "                           (not-found)))}\n"
         "     :put    {:summary \"Update a " e "\"\n"
         "              :handler (fn [request]\n"
         "                         (let [id   (id-of request)\n"
         "                               data (decode-update (:body-params request))]\n"
         "                           (cond\n"
         "                             (nil? id)                 (not-found)\n"
         "                             (empty? data)             (invalid)\n"
         "                             (not (valid-update? data)) (invalid)\n"
         "                             :else (if-let [updated (ports/update-" e " service id data)]\n"
         "                                     {:status 200 :body updated}\n"
         "                                     (not-found)))))}\n"
         "     :delete {:summary \"Delete a " e "\"\n"
         "              :handler (fn [request]\n"
         "                         (if-let [id (id-of request)]\n"
         "                           (do (ports/delete-" e " service id) {:status 204})\n"
         "                           (not-found)))}}]])\n")))

(defn generate-entity-http-file
  "shell/<entity>_http.clj for a further entity: its CRUD API routes.

   Pure: true"
  [ctx entity]
  (let [base-ns (:base-ns ctx "wagoe")
        module-name (:module-name ctx)]
    (str (ns-form (str base-ns "." module-name ".shell." (:entity-kebab entity) "-http")
                  (str "HTTP API for " (:entity-name entity) ", mounted by the "
                       module-name " module's routes.")
                  (api-requires base-ns module-name))
         "\n"
         (api-section entity))))

(defn generate-http-file
  "Generate shell/http.clj: the first entity's API and web routes, and the
   module's route contribution.

   Pure: true"
  [ctx]
  (let [base-ns (:base-ns ctx "wagoe")
        module-name (:module-name ctx)
        entity (first (:entities ctx))
        entity-lower (template/pascal->kebab (:entity-name entity))
        entity-plural (template/pluralize entity-lower)
        ;; `:interfaces` decides what this file defines and what the
        ;; contribution carries. A module generated with --no-web has no web
        ;; UI files on disk, so it must not mount web routes either (BOU-479).
        {:keys [http web]} (:interfaces ctx {:http true :web true})]
    (str (ns-form (str base-ns "." module-name ".shell.http")
                  (str "HTTP routes for " module-name " module.")
                  ;; Only what is used: an unused require is a clj-kondo
                  ;; warning, and `bb check` fails on those (BOU-267).
                  (concat (when http (api-requires base-ns module-name))
                          (when web [(str "[" base-ns "." module-name ".shell.web-handlers :as web-handlers]")])))
         "\n"
         (when http (str (api-section entity) "\n"))
         (when web
           (str "(defn web-routes\n"
                "  \"Mounted under /web — do not repeat the prefix here.\"\n"
                "  [service config]\n"
                "  [[\"/" entity-plural "\"\n"
                "    {:get {:handler (web-handlers/" entity-lower "-list-handler service config)}}]])\n"
                "\n"))
         "(defn " module-name "-routes\n"
         "  \"This module's contribution to the application's route table.\n"
         "\n"
         "   :api    versioned, mounted under /api/v1\n"
         "   :web    mounted under /web\n"
         "   :static mounted as written\"\n"
         ;; All three keys, always: the platform folds a contribution by
         ;; looking each part up, and a missing one is not the same as an
         ;; empty one to a reader trying to see what the module serves.
         "  [" (if (or http web) "service" "_service") " "
         (if web "config" "_config") "]\n"
         "  {:api    " (if http "(api-routes service)" "[]") "\n"
         "   :web    " (if web "(web-routes service config)" "[]") "\n"
         "   :static []})\n"
         "\n")))

(defn- http?
  [ctx]
  (get-in ctx [:interfaces :http] true))

(defn entity-files
  "The files a further entity adds, as [{:path :content}]: core, service,
   persistence and http namespaces of its own, its migration pair and its tests.
   No http namespace when the module has no HTTP interface.

   Pure: true"
  [ctx entity migration-number]
  (let [base   (str (:base-ns-path ctx) "/" (:module-path ctx) "/")
        src    #(str "src/" base (template/ns->path %) ".clj")
        tst    #(str "test/" base (template/ns->path %) ".clj")
        e      (:entity-kebab entity)
        plural (:entity-plural entity)]
    (cond->
     [{:path (src (str "core." e)) :content (generate-core-file ctx entity)}
      {:path (src (:service-ns entity)) :content (generate-service-file ctx entity)}
      {:path (src (:persistence-ns entity)) :content (generate-persistence-file ctx entity)}
      {:path    (format "migrations/%s-create-%s.up.sql" migration-number plural)
       :content (generate-migration-file ctx entity migration-number)}
      {:path    (format "migrations/%s-create-%s.down.sql" migration-number plural)
       :content (generate-migration-down-file ctx entity)}
      {:path (tst (str "core." e "-test")) :content (generate-core-test-file ctx entity)}
      {:path (tst (str "shell." e "-repository-test")) :content (generate-persistence-test-file ctx entity)}
      {:path (tst (:service-test-ns entity)) :content (generate-service-test-file ctx entity)}]
      (http? ctx)
      (conj {:path (src (str "shell." e "-http")) :content (generate-entity-http-file ctx entity)}))))

;; =============================================================================
;; Incremental Generators - Add Field
;; =============================================================================

(defn generate-module-wiring-file
  "Generate shell/module_wiring.clj file content.

   The scaffolder emitted every other file a module needs and not this one, so
   `bb scaffold integrate` always reported that the module had no wiring yet and
   the user hand-wrote the Integrant keys the framework says never to hand-write
   (BOU-309).

   Pure: true"
  [ctx]
  (let [base-ns     (:base-ns ctx "wagoe")
        module-name (:module-name ctx)]
    (format "(ns %s.%s.shell.module-wiring
  \"Integrant wiring for the %s module.

   Generated by `bb scaffold`. `bb scaffold integrate` writes the config key
   that activates this module; these methods are what that key resolves to.

   Components:
     :wagoe/%s-repository  persistence, on the shared :wagoe/db-context
     :wagoe/%s-service     business logic, on the repository
     :wagoe/%s-routes      HTTP routes, on the service
     :wagoe/%s            the module itself — what discovery looks for\"
  (:require [%s.%s.shell.http :as http]
            [%s.%s.shell.persistence :as persistence]
            [%s.%s.shell.service :as service]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

;; =============================================================================
;; Repository
;; =============================================================================

(defmethod ig/init-key :wagoe/%s-repository
  [_ {:keys [ctx]}]
  (log/info \"Initializing %s repository\")
  (persistence/create-repository ctx))

(defmethod ig/halt-key! :wagoe/%s-repository
  [_ _repository]
  (log/info \"%s repository halted\"))

;; =============================================================================
;; Service
;; =============================================================================

(defmethod ig/init-key :wagoe/%s-service
  [_ {:keys [repository]}]
  (log/info \"Initializing %s service\")
  (service/create-service repository))

(defmethod ig/halt-key! :wagoe/%s-service
  [_ _service]
  (log/info \"%s service halted\"))

;; =============================================================================
;; Routes
;; =============================================================================

(defmethod ig/init-key :wagoe/%s-routes
  [_ {:keys [service config]}]
  (log/info \"Initializing %s routes\")
  ;; A contribution — {:api [...] :web [...] :static [...]} — not a route
  ;; table. :wagoe/http-handler prefixes and versions each part before
  ;; mounting it.
  (http/%s-routes service (or config {})))

(defmethod ig/halt-key! :wagoe/%s-routes
  [_ _routes]
  (log/info \"%s routes halted\"))

;; =============================================================================
;; The module
;; =============================================================================
;;
;; The key module discovery looks for. `bb scaffold integrate` writes it to the
;; :active section of config.edn, ig-config turns it into the components below,
;; and the app hands :wagoe/%s-routes to the HTTP handler as one of
;; :module-routes. Setting :enabled? false in config.edn switches the module off
;; without deleting anything.

(defmethod ig/init-key :wagoe/%s
  [_ {:keys [routes service enabled?]}]
  (if (false? enabled?)
    (do (log/info \"%s module disabled\") nil)
    (do (log/info \"%s module enabled\")
        {:routes routes :service service})))

(defmethod ig/halt-key! :wagoe/%s
  [_ _module]
  (log/info \"%s module halted\"))
"
            ;; Positional, and every slot but the three ns prefixes is the
            ;; module name. An earlier version passed entity-name into nine of
            ;; them and emitted "Integrant wiring for the Widget module" for
            ;; module `inventory`; the fixture used module `product` with entity
            ;; `Product`, so every substring assertion passed either way.
            base-ns module-name                              ; 1-2   ns
            module-name                                      ; 3     docstring
            module-name module-name module-name module-name  ; 4-7   component list
            base-ns module-name                              ; 8-9   require http
            base-ns module-name                              ; 10-11 require persistence
            base-ns module-name                              ; 12-13 require service
            module-name module-name                          ; 14-15 repository init
            module-name module-name                          ; 16-17 repository halt
            module-name module-name                          ; 18-19 service init
            module-name module-name                          ; 20-21 service halt
            module-name module-name module-name              ; 22-24 routes init + fn
            module-name module-name                          ; 25-26 routes halt
            module-name                                      ; 27    discovery note
            module-name module-name module-name              ; 28-30 module init
            module-name module-name)))

;; =============================================================================
;; Wiring a further entity (BOU-497)
;; =============================================================================
;;
;; Platform discovery builds a scaffolded module from four keys, one service
;; and one routes contribution, unless the wiring namespace defines `ig-config`.
;; So the first further entity installs, once: an `ig-config` that adds
;; :wagoe/<m>-entities, and a routes init-key that adds those entities' API
;; routes to the module's. The routes init-key is the one form replaced rather
;; than appended to — a second defmethod further down would silently win and
;; leave the first as dead code. Every entity after that is an append.

(defn- top-level-forms
  "Zippers at each top-level form of `source`, in order."
  [source]
  (take-while some? (iterate z/right (z/of-string source {:track-position? true}))))

(defn- form-head
  "The first three elements of a list form, as data, or nil."
  [loc]
  (when (= :list (z/tag loc))
    (try (vec (take 3 (map z/sexpr (take-while some? (iterate z/right (z/down loc))))))
         (catch Exception _ nil))))

(defn- routes-init-form?
  [module-name loc]
  (= ['defmethod 'ig/init-key (keyword "wagoe" (str module-name "-routes"))]
     (form-head loc)))

(defn- wiring-routes-form
  "The routes init-key that adds the further entities' API routes."
  [module-name]
  (str "(defmethod ig/init-key :wagoe/" module-name "-routes\n"
       "  [_ {:keys [service config entities]}]\n"
       "  (log/info \"Initializing " module-name " routes\")\n"
       "  ;; A contribution — {:api [...] :web [...] :static [...]} — not a route\n"
       "  ;; table. :wagoe/http-handler prefixes and versions each part before\n"
       "  ;; mounting it. The API routes of every entity `bb scaffold entity` added\n"
       "  ;; (see entity-wiring below) join the first entity's.\n"
       "  (reduce (fn [contribution {entity-service :service routes :routes}]\n"
       "            (cond-> contribution\n"
       "              routes (update :api into (routes entity-service))))\n"
       "          (http/" module-name "-routes service (or config {}))\n"
       "          (vals entities)))"))

(defn- wiring-seam-section
  [module-name]
  (let [k #(str ":wagoe/" module-name %)]
    (str (banner "Further entities (bb scaffold entity)")
         ";; One entity-wiring method per entity. ig-config builds each one's\n"
         ";; repository and service under " (k "-entities") ", and the routes\n"
         ";; init-key above mounts their API routes with the module's.\n"
         "\n"
         "(defmulti entity-wiring\n"
         "  \"{:repository f :service f :routes f} for one further entity.\"\n"
         "  identity)\n"
         "\n"
         "(defmethod ig/init-key " (k "-entities") "\n"
         "  [_ {:keys [ctx]}]\n"
         "  (into {}\n"
         "        (for [entity (keys (methods entity-wiring))\n"
         "              :let [{:keys [repository service routes]} (entity-wiring entity)]]\n"
         "          [entity {:service (service (repository ctx)) :routes routes}])))\n"
         "\n"
         "(defn ig-config\n"
         "  \"This module's Integrant graph: the four keys platform discovery would\n"
         "   build, plus " (k "-entities") ".\"\n"
         "  [settings _opts]\n"
         "  {:components\n"
         "   {" (k "-repository") "\n"
         "    {:ctx (ig/ref :wagoe/db-context)}\n"
         "    " (k "-service") "\n"
         "    {:repository (ig/ref " (k "-repository") ")}\n"
         "    " (k "-entities") "\n"
         "    {:ctx (ig/ref :wagoe/db-context)}\n"
         "    " (k "-routes") "\n"
         "    {:service  (ig/ref " (k "-service") ")\n"
         "     :entities (ig/ref " (k "-entities") ")\n"
         "     :config   settings}\n"
         "    " (k "") "\n"
         "    {:enabled? true\n"
         "     :service  (ig/ref " (k "-service") ")\n"
         "     :routes   (ig/ref " (k "-routes") ")}}})\n")))

(defn- entity-wiring-section
  [ctx entity]
  (let [e (:entity-kebab entity)]
    (str (banner (:entity-name entity))
         "(defmethod entity-wiring :" e "\n"
         "  [_]\n"
         "  {:repository " e "-persistence/create-repository\n"
         "   :service    " e "-service/create-service"
         (if (http? ctx)
           (str "\n   :routes     " e "-http/api-routes})\n")
           "})\n"))))

(defn- entity-requires
  "[alias namespace] pairs the entity's wiring section needs."
  [ctx entity]
  (let [prefix (str (:base-ns ctx "wagoe") "." (:module-name ctx) ".")
        e      (:entity-kebab entity)]
    (for [part (cond-> ["persistence" "service"] (http? ctx) (conj "http"))]
      [(symbol (str e "-" part))
       (symbol (str prefix (if (= "http" part)
                             (str "shell." e "-http")
                             (shell-ns entity (keyword (str part "-ns"))))))])))

(defn- require-aliases
  "alias -> namespace for the vector entries of the ns form's :require."
  [ns-loc]
  (let [req (some->> (iterate z/right (z/down ns-loc))
                     (take-while some?)
                     (filter #(and (= :list (z/tag %)) (= :require (some-> % z/down z/sexpr))))
                     first)]
    {:loc req
     :aliases (into {}
                    (for [v (some->> req z/down (iterate z/right) (take-while some?))
                          :when (= :vector (z/tag v))
                          :let [[n & opts] (z/sexpr v)
                                a (get (apply hash-map (if (odd? (count opts)) (butlast opts) opts)) :as)]
                          :when a]
                      [a n]))}))

(defn- add-requires
  "`source` with `pairs` added to its ns :require, or an :error."
  [source pairs]
  (let [ns-loc (some #(when (= 'ns (first (form-head %))) %) (top-level-forms source))
        {:keys [loc aliases]} (when ns-loc (require-aliases ns-loc))
        clash (some (fn [[a n]] (when (and (contains? aliases a) (not= n (aliases a))) a)) pairs)
        todo  (remove (fn [[a _]] (contains? aliases a)) pairs)]
    (cond
      (nil? loc) {:error "its ns form has no :require"}
      clash      {:error (str "it already uses the alias " clash " for " (aliases clash))}
      :else
      (let [indent (or (some-> loc z/down z/right z/position second dec) 12)]
        {:content (z/root-string
                   (reduce (fn [l [a n]]
                             (-> l
                                 (z/append-child* (n/newlines 1))
                                 (z/append-child* (n/spaces indent))
                                 (z/append-child* (z/node (z/of-string (str "[" n " :as " a "]"))))))
                           loc
                           todo))}))))

(defn- defmethod-dispatches
  "Dispatch values of the top-level `(defmethod multi …)` forms in `source`."
  [source multi]
  (set (keep #(let [[op m d] (form-head %)] (when (and (= 'defmethod op) (= multi m)) d))
             (top-level-forms source))))

(defn add-entity-to-wiring
  "`source` — a module_wiring.clj — with `entity` wired in.

   Returns {:content s} or {:error reason}. The first further entity also
   installs the seam described above, and refuses when the routes init-key is
   not the one `generate` wrote: replacing it would drop the edit.

   Pure: true"
  [source ctx entity]
  (try
    (let [module-name (:module-name ctx)
          defined     (defined-symbols source)
          seam?       (contains? defined 'entity-wiring)
          k           (keyword (:entity-kebab entity))
          installed
          (if seam?
            {:content source}
            (let [loc      (some #(when (routes-init-form? module-name %) %) (top-level-forms source))
                  expected (some #(when (routes-init-form? module-name %) (z/sexpr %))
                                 (top-level-forms (generate-module-wiring-file ctx)))]
              (cond
                (contains? defined 'ig-config)
                {:error "it already defines ig-config, so the module builds its own graph"}

                (or (nil? loc) (not= expected (z/sexpr loc)))
                {:error (str "its :wagoe/" module-name "-routes init-key is not the one bb scaffold"
                             " generate wrote, and wiring an entity replaces it")}

                :else
                {:content (append-section
                           (z/root-string (z/replace loc (z/node (z/of-string (wiring-routes-form module-name)))))
                           (wiring-seam-section module-name))})))]
      (cond
        (:error installed) installed

        (contains? (defmethod-dispatches (:content installed) 'entity-wiring) k)
        {:error (str "it already wires " k)}

        :else
        (let [with-requires (add-requires (:content installed) (entity-requires ctx entity))]
          (if (:error with-requires)
            with-requires
            {:content (append-section (:content with-requires) (entity-wiring-section ctx entity))}))))
    (catch Exception e
      {:error (str "it could not be read: " (.getMessage e))})))

(defn generate-add-field-migration
  "Generate ALTER TABLE migration for adding a field.
   
   Args:
     module-name - Module name
     entity-name - Entity name (PascalCase)
     field - Field definition map {:name :type :required :unique}
     migration-number - Migration sequence number (e.g., \"006\")
   
   Returns:
     String content for migration SQL
   
   Pure: true"
  [_module-name entity-name field migration-number]
  (let [table-name (template/kebab->snake (template/pluralize (template/pascal->kebab entity-name)))
        field-ctx (template/build-field-context field)
        field-name (:field-name-snake field-ctx)
        sql-type (:sql-type field-ctx)
        default-clause (if-let [d (:sql-default field-ctx)] (str " DEFAULT " d) "")
        not-null (if (:field-required field-ctx) " NOT NULL" "")
        unique-clause (if (:field-unique field-ctx) " UNIQUE" "")
        ;; The same clause and index `generate-migration-file` gives a
        ;; relation. Without them a relation added to an existing entity got a
        ;; bare UUID column: no referential integrity, and no index for the
        ;; joins and cascades that read it (BOU-480 review).
        relation-table (:relation-table field-ctx)
        references-clause (if relation-table
                            (format " REFERENCES %s(id) ON DELETE %s"
                                    relation-table
                                    (get template/on-delete-clauses
                                         (:on-delete field-ctx)
                                         "CASCADE"))
                            "")
        index-sql (if (or relation-table (:field-indexed field-ctx))
                    (format "\nCREATE INDEX IF NOT EXISTS idx_%s_%s ON %s(%s);\n"
                            table-name field-name table-name field-name)
                    "")]
    (format "-- Migration %s: Add %s to %s table

ALTER TABLE %s ADD COLUMN %s %s%s%s%s%s;
%s"
            migration-number
            field-name
            table-name
            table-name
            field-name
            sql-type
            default-clause
            not-null
            unique-clause
            references-clause
            index-sql)))

(defn schema-field-entry
  "The Malli entry line for `field`, without indentation.

   Required fields are plain; optional ones carry `{:optional true}`, matching
   what module generation emits for the same field definition.

   `force-optional?` produces the optional form regardless. Update request
   schemas take that one: an update is a partial, so a required entry there
   means every existing caller doing a partial update has to start sending the
   new field or fail validation. See `libs/scaffolder/AGENTS.md`, and
   `generate-field-schema`, which applies the same rule at module generation."
  ([field] (schema-field-entry field false))
  ([field force-optional?]
   (let [field-ctx  (template/build-field-context field)
         field-name (keyword (:field-name-kebab field-ctx))
         malli-type (:malli-type field-ctx)]
     (if (and (:field-required field-ctx) (not force-optional?))
       (format "[%s %s]" field-name malli-type)
       (format "[%s {:optional true} %s]" field-name malli-type)))))

(defn- schema-map-zloc
  "Zipper at the `[:map …]` vector inside `(def schema-name …)`, or nil.

   Parsed, not scanned. The line-based predecessor looked for the first line
   ending `])` at or after the `def`, with no upper bound: a def it could not
   recognise — one ending `]))` — handed back the *next* def's closing line, and
   the field was written into the following schema and reported as inserted.
   A zipper cannot leave the form it is standing in."
  [source schema-name]
  (try
    (let [wanted (symbol schema-name)]
      (loop [loc (z/of-string source {:track-position? true})]
        (cond
          (or (nil? loc) (z/end? loc)) nil

          (and (= :list (z/tag loc))
               (= (quote def) (some-> loc z/down z/sexpr))
               (= wanted (some-> loc z/down z/right z/sexpr)))
          ;; The Malli map, which is not necessarily the third child — a
          ;; docstring may sit between. Anything that is not a plain [:map …]
          ;; vector (say `(m/schema [:map …])`) is left alone deliberately.
          (loop [child (some-> loc z/down z/right z/right)]
            (cond
              (nil? child) nil
              (and (= :vector (z/tag child))
                   (= :map (first (z/sexpr child)))) child
              :else (recur (z/right child))))

          :else (recur (z/right loc)))))
    (catch Exception _
      ;; Unparseable source. Refusing beats guessing at it with regexes.
      nil)))

(defn- entry-node
  "Parse `entry` into a node, preserving its text exactly.

   rewrite-clj's reader, not clojure.edn: EDN has no dispatch macro for `#\"`,
   so an entry for a regex-backed type — :email and :date both render
   `[:re {…} #\"…\"]` — threw `No dispatch macro for: \"`. That happened after
   the migration pair had been written, leaving the column added and the schema
   untouched."
  [entry]
  (z/node (z/of-string entry)))

(defn- entry-key-of
  "The keyword a Malli entry is for, e.g. `[:sku …]` -> :sku.

   Reads only the first child, so nothing else in the entry has to be
   interpretable as a value."
  [entry]
  (try (-> (z/of-string entry) z/down z/sexpr) (catch Exception _ nil)))

(defn- existing-entry
  "The zipper of the entry for `k` inside `map-zloc`, or nil."
  [map-zloc k]
  (loop [child (z/down map-zloc)]
    (cond
      (nil? child) nil
      (and (= :vector (z/tag child))
           (= k (first (z/sexpr child)))) child
      :else (recur (z/right child)))))

(defn- entry-indent
  "Column the existing entries sit at, as a count. Defaults to 3."
  [map-zloc]
  (or (loop [child (z/down map-zloc)]
        (cond
          (nil? child) nil
          (and (= :vector (z/tag child))
               (keyword? (first (z/sexpr child)))) (dec (second (z/position child)))
          :else (recur (z/right child))))
      3))

(defn insert-schema-entry
  "Add `entry` to the Malli map in `(def schema-name …)` within `source`.

   Returns one of:

     {:status :inserted :content <new source>}
     {:status :present :entry <the entry already there>}
     {:status :unrecognised}  a shape this cannot place the field in safely

   The last two are kept apart rather than collapsed into one falsey value: the
   caller has to distinguish nothing-to-do from could-not-do-it. Reporting the
   second as the first is how the request schemas ended up without the field
   while the output said the work was finished.

   `:present` carries the entry that is already there, because its shape
   matters — a required entry in an update request is not nothing-to-do.

   :unrecognised covers a hand-edited file, a renamed schema, or a `def` whose
   value is not a literal `[:map …]`. Refusing rather than guessing is
   deliberate: a skip the user can see beats a mangled schema file they discover
   later.

   Everything outside the inserted entry round-trips byte for byte, including
   the trailing newline — rewrite-clj preserves whitespace and comments, so the
   diff is the one line added."
  [source schema-name entry]
  (if-let [map-zloc (schema-map-zloc source schema-name)]
    (let [k (entry-key-of entry)]
      (if-let [found (and k (existing-entry map-zloc k))]
        {:status :present :entry (z/string found)}
        (let [indent (entry-indent map-zloc)]
          {:status  :inserted
           :content (-> map-zloc
                        (z/append-child* (n/newlines 1))
                        (z/append-child* (n/spaces indent))
                        (z/append-child* (entry-node entry))
                        z/root-string)})))
    {:status :unrecognised}))

(defn add-field-to-schema
  "Add `field` to the entity and request schemas in `source`.

   Returns {:status :updated :content <new source> :schemas [changed]
            :unreachable [names]} when at least one schema changed, or
   {:status :skipped :reason :already-present|:unrecognised-shape
            :unreachable [names]} when none did.

   `:unreachable` names the target schemas the field could not be placed in. It
   is what tells the caller that manual work remains, and it is reported on a
   successful edit too — two of three schemas updated is still a schema set that
   does not agree with itself.

   All three targets are edited because that is what the tool has always told
   users to do: the instruction comment it used to print said to add the field
   to the entity schema and then to the Create and Update request schemas as
   well.

   Each target is tracked separately. Deciding `:already-present` by searching
   the whole file let one schema answer for the others — with the field in the
   entity schema and a hand-restructured `CreateXRequest`, this reported that
   nothing remained to be done while both request schemas still lacked it. That
   is the unsynchronised Malli set of AGENTS.md pitfall 6, reported as success."
  [source entity field]
  (let [update-schema (str "Update" entity "Request")
        ;; The update request gets the optional form even for a required field:
        ;; an update is a partial, so a required entry there breaks every
        ;; caller that was sending a subset. Applying one entry to all three
        ;; schemas made `--required` mandatory on update.
        entry-for     #(schema-field-entry field (= % update-schema))
        targets       [entity (str "Create" entity "Request") update-schema]
        ;; An entry that is present but not optional, in the update request
        ;; only. Scoped that narrowly on purpose: a project generated before
        ;; update requests were made partial carries the required form there,
        ;; and a rerun reported "already in every schema" while partial updates
        ;; stayed broken. Differences anywhere else — a tightened type on the
        ;; entity, say — are the user's business and are left alone.
        needs-optional? (fn [schema-name found]
                          (and (= schema-name update-schema)
                               found
                               (not (str/includes? found "{:optional true}"))))
        ;; One outcome per target, in target order. This used to be four
        ;; parallel vectors, and every caller re-derived what it needed from
        ;; whichever of them it happened to consult — which is how a mixed
        ;; state got half-reported. There is one thing to read now, and the
        ;; vectors below are views of it.
        {:keys [content outcomes]}
        (reduce (fn [acc schema-name]
                  (let [r      (insert-schema-entry (:content acc) schema-name
                                                    (entry-for schema-name))
                        status (case (:status r)
                                 :inserted :inserted
                                 :present  (if (needs-optional? schema-name (:entry r))
                                             :needs-optional
                                             :present)
                                 :unreachable)]
                    (-> acc
                        (cond-> (= :inserted status) (assoc :content (:content r)))
                        (update :outcomes conj (cond-> {:schema schema-name :status status}
                                                 (:entry r) (assoc :entry (:entry r)))))))
                {:content source :outcomes []}
                targets)
        of-status     (fn [st] (mapv :schema (filter #(= st (:status %)) outcomes)))
        changed       (of-status :inserted)
        present       (of-status :present)
        unreachable   (of-status :unreachable)
        wrong-shape   (of-status :needs-optional)]
    (cond
      (seq changed)
      {:status :updated :content content :outcomes outcomes :schemas changed
       :unreachable unreachable :wrong-shape wrong-shape}

      ;; Only when every target already carries it, in a shape that works.
      ;; Anything short of that leaves the caller something to do, and has to
      ;; say so.
      (= (count present) (count targets))
      {:status :skipped :reason :already-present :outcomes outcomes
       :unreachable [] :wrong-shape []}

      (seq wrong-shape)
      {:status :skipped :reason :requires-optional :outcomes outcomes
       :unreachable unreachable :wrong-shape wrong-shape}

      :else
      {:status :skipped :reason :unrecognised-shape :outcomes outcomes
       :unreachable unreachable :wrong-shape wrong-shape})))

(defn generate-add-field-schema-comment
  "Generate schema addition comment/instructions for adding a field.
   
   Args:
     module-name - Module name
     entity-name - Entity name
     field - Field definition map
   
   Returns:
     String with instructions for manual schema update
   
   Pure: true"
  [module-name entity-name field]
  (let [field-ctx (template/build-field-context field)
        field-name (keyword (:field-name-kebab field-ctx))
        malli-type (:malli-type field-ctx)
        required (:field-required field-ctx)]
    (format ";; Add to src/wagoe/%s/schema.clj in the %s schema:
;;
;; %s
;;
;; Then add to Create%sRequest and Update%sRequest schemas as well.
"
            module-name
            entity-name
            (if required
              (format "[%s %s]" field-name malli-type)
              (format "[%s {:optional true} %s]" field-name malli-type))
            entity-name
            entity-name)))

;; =============================================================================
;; Incremental Generators - Add Endpoint
;; =============================================================================

(defn generate-endpoint-definition
  "Generate a single endpoint definition for adding to http.clj.
   
   Args:
     module-name - Module name
     path - Route path (e.g., \"/invoices/:id/send\")
     method - HTTP method keyword (e.g., :post)
     handler-name - Handler function name (e.g., \"send-invoice\")
   
   Returns:
     String content for endpoint definition (Reitit route data)

   Pure: true"
  [module-name path method handler-name]
  (let [method-str (name method)]
    (format ";; Add to api-routes in src/wagoe/%s/shell/http.clj:
;;
;; [\"%s\"
;;  {:%s {:handler %s-handler
;;        :summary \"%s endpoint\"}}]
;;
;; Then create the handler function:
;;
;; (defn %s-handler [service]
;;   (fn [request]
;;     {:status 200
;;      :body {:message \"Success\"}}))
"
            module-name
            path
            method-str
            handler-name
            (str/replace handler-name "-" " ")
            handler-name)))

;; =============================================================================
;; Incremental Generators - Add Adapter
;; =============================================================================

(defn generate-adapter-file
  "Generate a complete adapter implementation file.
   
   Args:
     module-name - Module name (e.g., \"cache\")
     port-name - Port protocol name (e.g., \"ICache\")
     adapter-name - Adapter name (e.g., \"redis\")
     methods - Vector of method specs [{:name \"get-value\" :args [\"key\"]}]
     base-ns - Optional base namespace (default \"wagoe\")

   Returns:
     String content for adapter.clj file

   Pure: true"
  [module-name port-name adapter-name methods & [base-ns]]
  (let [base-ns (or base-ns "wagoe")
        adapter-pascal (template/kebab->pascal adapter-name)
        record-name (str adapter-pascal (template/kebab->pascal module-name))
        methods-str (str/join "\n\n"
                              (map (fn [{:keys [name args _returns]}]
                                     (let [args-str (str/join " " (cons "_this" args))]
                                       (format "  (%s [%s]\n    ;; TODO: Implement %s\n    (throw (ex-info \"Not implemented\" {:method :%s})))"
                                               name args-str name name)))
                                   methods))]
    (format "(ns %s.%s.shell.adapters.%s
  \"%s adapter implementation for %s.
   
   TODO: Implement all methods of the %s protocol.\"
  (:require [%s.%s.ports :as ports]))

;; =============================================================================
;; %s Adapter Implementation
;; =============================================================================

(defrecord %s [config]
  ports/%s

%s)

;; =============================================================================
;; Factory Functions
;; =============================================================================

(defn create-%s-%s
  \"Create %s %s adapter instance.
   
   Args:
     config - Configuration map
   
   Returns:
     %s instance implementing %s\"
  [config]
  (->%s config))
"
            base-ns
            module-name
            adapter-name
            (str/capitalize adapter-name)
            module-name
            port-name
            base-ns
            module-name
            (str/capitalize adapter-name)
            record-name
            port-name
            methods-str
            adapter-name
            module-name
            (str/capitalize adapter-name)
            module-name
            record-name
            port-name
            record-name)))
