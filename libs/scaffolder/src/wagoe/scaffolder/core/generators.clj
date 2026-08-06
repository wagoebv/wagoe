(ns wagoe.scaffolder.core.generators
  "Pure functions for generating file content from templates.

   Each generator function takes a template context and returns
   file content as a string. All functions are pure and deterministic."
  (:require [clojure.string :as str]
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

(defn generate-field-schema
  "Generate Malli schema for a single field.
   
   Args:
     field-ctx - Field context map
   
   Returns:
     String representation of Malli schema
   
   Pure: true"
  [field-ctx]
  (let [required (:field-required field-ctx)
        malli-type (:malli-type field-ctx)
        field-name (keyword (:field-name-kebab field-ctx))]
    (if required
      (format "   [%s %s]" field-name malli-type)
      (format "   [%s {:optional true} %s]" field-name malli-type))))

(defn generate-schema-file
  "Generate schema.clj file content.
   
   Args:
     ctx - Template context map
   
   Returns:
     String content for schema.clj
   
   Pure: true"
  [ctx]
  (let [base-ns (:base-ns ctx "wagoe")
        module-name (:module-name ctx)
        entity (first (:entities ctx))
        entity-name (:entity-name entity)
        fields (:fields entity)
        field-schemas (str/join "\n" (map generate-field-schema fields))]
    (format "(ns %s.%s.schema
  \"Schema definitions for %s module.\"
  (:require [malli.core :as m]))

;; =============================================================================
;; Domain Entity Schemas
;; =============================================================================

(def %s
  \"Schema for %s entity.\"
  [:map {:title \"%s\"}
   [:id :uuid]
%s
   [:created-at inst?]
   [:updated-at {:optional true} [:maybe inst?]]
   [:deleted-at {:optional true} [:maybe inst?]]])

;; =============================================================================
;; API Request Schemas
;; =============================================================================

(def Create%sRequest
  \"Schema for create %s API requests.\"
  [:map {:title \"Create %s Request\"}
%s])

(def Update%sRequest
  \"Schema for update %s API requests.\"
  [:map {:title \"Update %s Request\"}
%s])

;; =============================================================================
;; Validation Functions
;; =============================================================================

(def ^:private %s-validator (m/validator %s))
(def ^:private %s-explainer (m/explainer %s))

(defn validate-%s
  \"Validates a %s entity against the %s schema.\"
  [%s-data]
  (%s-validator %s-data))

(defn explain-%s
  \"Provides detailed validation errors for %s data.\"
  [%s-data]
  (%s-explainer %s-data))
"
            base-ns
            module-name
            module-name
            entity-name
            entity-name
            entity-name
            field-schemas
            entity-name
            (str/lower-case entity-name)
            entity-name
            field-schemas
            entity-name
            (str/lower-case entity-name)
            entity-name
            field-schemas
            (str/lower-case entity-name)
            entity-name
            (str/lower-case entity-name)
            entity-name
            (str/lower-case entity-name)
            (str/lower-case entity-name)
            entity-name
            (str/lower-case entity-name)
            (str/lower-case entity-name)
            (str/lower-case entity-name)
            (str/lower-case entity-name)
            (str/lower-case entity-name)
            (str/lower-case entity-name)
            (str/lower-case entity-name)
            (str/lower-case entity-name))))

;; =============================================================================
;; Ports File Generator
;; =============================================================================

(defn generate-ports-file
  "Generate ports.clj file content.
   
   Args:
     ctx - Template context map
   
   Returns:
     String content for ports.clj
   
   Pure: true"
  [ctx]
  (let [base-ns (:base-ns ctx "wagoe")
        module-name (:module-name ctx)
        entity (first (:entities ctx))
        entity-name (:entity-name entity)
        entity-lower (:entity-lower entity)]
    (format "(ns %s.%s.ports
  \"%s module port definitions (abstract interfaces).\")

;; =============================================================================
;; Repository Ports
;; =============================================================================

(defprotocol I%sRepository
  \"Repository interface for %s persistence operations.\"

  (find-by-id [this id]
    \"Find %s by ID.\")

  (find-all [this options]
    \"Find all %ss with pagination and filtering.\")

  (create [this entity]
    \"Create new %s.\")

  ;; `update-entity`, not `update-<entity>`: the service protocol below declares
  ;; `update-<entity>` too, and defprotocol interns its methods as vars in this
  ;; namespace — so the second silently overwrote the first, leaving
  ;; ports/update-<entity> with the service arity [this id data]. Nor `update`,
  ;; which would shadow clojure.core/update. The repository's other methods are
  ;; already generic (create, delete), so this matches its own family. (BOU-267)
  (update-entity [this entity]
    \"Update existing %s.\")

  (delete [this id]
    \"Delete %s by ID.\"))

;; =============================================================================
;; Service Ports
;; =============================================================================

(defprotocol I%sService
  \"%s service interface for business operations.\"

  (get-%s [this id]
    \"Get %s by ID.\")

  (list-%ss [this options]
    \"List %ss with pagination.\")

  (create-%s [this data]
    \"Create new %s.\")

  (update-%s [this id data]
    \"Update %s.\")

  (delete-%s [this id]
    \"Delete %s.\"))
"
            base-ns
            module-name
            (str/capitalize module-name)
            entity-name
            entity-lower
            entity-lower
            entity-lower
            entity-lower
            ;; `update-entity` is now a literal, so the method-name arg that
            ;; used to sit here is gone; only its docstring %s remains.
            entity-lower
            entity-lower
            entity-name
            entity-name
            entity-lower
            entity-lower
            entity-lower
            entity-lower
            entity-lower
            entity-lower
            entity-lower
            entity-lower
            entity-lower
            entity-lower)))

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
  [ctx]
  (let [base-ns (:base-ns ctx "wagoe")
        module-name (:module-name ctx)
        entity (first (:entities ctx))
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
            entity-lower)))

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
        null-clause (if required " NOT NULL" "")
        unique-clause (if unique " UNIQUE" "")]
    (format "  %s %s%s%s" field-name sql-type null-clause unique-clause)))

(defn generate-migration-file
  "Generate migration SQL file content.
   
   Args:
     ctx - Template context map
     migration-number - Migration sequence number (e.g., \"005\")
   
   Returns:
     String content for migration SQL
   
   Pure: true"
  [ctx migration-number]
  (let [entity (first (:entities ctx))
        table-name (:entity-table entity)
        fields (:fields entity)
        field-sqls (str/join ",\n" (map generate-migration-field fields))]
    (format "-- Migration %s: Create %s table

CREATE TABLE IF NOT EXISTS %s (
  id UUID PRIMARY KEY,
%s,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP,
  deleted_at TIMESTAMP
);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_%s_created_at ON %s(created_at);
"
            migration-number
            table-name
            table-name
            field-sqls
            table-name
            table-name)))

(defn generate-migration-down-file
  "Generate the rollback SQL matching `generate-migration-file`.

   migratus pairs `<id>-<name>.up.sql` with `<id>-<name>.down.sql`; without the
   down file a migration cannot be rolled back. Dropping the table also removes
   its index, so the index needs no separate statement.

   Pure: true"
  [ctx]
  (let [entity     (first (:entities ctx))
        table-name (:entity-table entity)]
    (format "-- Rollback: drop the %s table

DROP TABLE IF EXISTS %s;
"
            table-name
            table-name)))

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
        entity-lower (str/lower-case entity-name)
        entity-plural (template/pluralize entity-lower)]
    (str "(ns " base-ns "." module-name ".core.ui\n"
         "  \"Pure UI generation for " module-name " module - Hiccup templates.\")\n"
         "\n"
         "(defn " entity-lower "-list-page\n"
         "  \"Generate " entity-lower " listing page.\"\n"
         "  [" entity-plural " _opts]\n"
         "  [:div.page\n"
         "   [:h1 \"" (str/capitalize entity-plural) "\"]\n"
         "   [:div.items\n"
         "    (for [item " entity-plural "]\n"
         "      [:div.item {:key (:id item)}\n"
         "       [:p (str (:id item))]])]])\n")))

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
  [ctx]
  (let [base-ns (:base-ns ctx "wagoe")
        module-name (:module-name ctx)
        entity (first (:entities ctx))
        entity-name (:entity-name entity)
        entity-lower (str/lower-case entity-name)
        entity-kebab (str/replace entity-lower #"\s+" "-")]
    (str "(ns " base-ns "." module-name ".shell.service\n"
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
         "  (create-" entity-lower " [_this data]\n"
         "    (let [prepared (core/prepare-new-" entity-lower " data (generate-" entity-lower "-id) (current-time))]\n"
         "      (.create repository prepared)))\n"
         "  (get-" entity-lower " [_this id]\n"
         "    (.find-by-id repository id))\n"
         ;; find-all, not list-<plural>: the repository port has no
         ;; list-<plural> method, so this called something that does not exist
         ;; and blew up at runtime the first time anyone listed anything.
         "  (list-" (template/pluralize entity-lower) " [_this opts]\n"
         "    (.find-all repository opts))\n"
         "  (update-" entity-lower " [_this id data]\n"
         "    (.update-entity repository (assoc data :id id)))\n"
         "  (delete-" entity-lower " [_this id]\n"
         "    (.delete repository id)))\n"
         "\n"
         "(defn create-service [repository]\n"
         "  (->" entity-name "Service repository))\n")))

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
  [ctx]
  (let [base-ns (:base-ns ctx "wagoe")
        module-name (:module-name ctx)
        entity (first (:entities ctx))
        entity-name (:entity-name entity)
        ;; entity-lower was only used to build the repository's
        ;; `update-<entity>` method name, which is now the literal
        ;; `update-entity` (BOU-267).
        table-name (template/pluralize (template/kebab->snake entity-name))]
    (str "(ns " base-ns "." module-name ".shell.persistence\n"
         "  \"Persistence layer for " module-name " module.\"\n"
         "  (:require [" base-ns "." module-name ".ports :as ports]\n"
         "            [wagoe.platform.shell.adapters.database.common.core :as db]\n"
         "            [honey.sql :as sql]))\n"
         "\n"
         "(defrecord Database" entity-name "Repository [db-ctx]\n"
         "  ports/I" entity-name "Repository\n"
         "  (create [_this entity]\n"
         "    (db/execute-one! db-ctx\n"
         "      (sql/format {:insert-into :" table-name "\n"
         "                   :values [entity]\n"
         "                   :returning [:*]})))\n"
         "  (find-by-id [_this id]\n"
         "    (db/execute-one! db-ctx\n"
         "      (sql/format {:select [:*]\n"
         "                   :from [:" table-name "]\n"
         "                   :where [:= :id id]})))\n"
         "  (find-all [_this opts]\n"
         "    (db/execute-query! db-ctx\n"
         "      (sql/format {:select [:*]\n"
         "                   :from [:" table-name "]\n"
         "                   :limit (:limit opts 20)})))\n"
         "  (update-entity [_this entity]\n"
         "    (db/execute-one! db-ctx\n"
         "      (sql/format {:update :" table-name "\n"
         "                   :set (dissoc entity :id)\n"
         "                   :where [:= :id (:id entity)]\n"
         "                   :returning [:*]})))\n"
         "  (delete [_this id]\n"
         "    (db/execute-one! db-ctx\n"
         "      (sql/format {:delete-from :" table-name "\n"
         "                   :where [:= :id id]}))))\n"
         "\n"
         "(defn create-repository [db-ctx]\n"
         "  (->Database" entity-name "Repository db-ctx))\n")))

;; =============================================================================
;; HTTP File Generator
;; =============================================================================

(defn generate-http-file
  "Generate shell/http.clj file content.
   
   Args:
     ctx - Template context map
     
   Returns:
     String content for http.clj file
     
   Pure: true"
  [ctx]
  (let [base-ns (:base-ns ctx "wagoe")
        module-name (:module-name ctx)
        entity (first (:entities ctx))
        entity-name (:entity-name entity)
        entity-lower (str/lower-case entity-name)
        entity-plural (template/pluralize entity-lower)]
    ;; No :require at all. The handlers below are stubs that call nothing, so
    ;; requiring ports here was an unused require — a clj-kondo warning, and
    ;; `bb check` fails on warnings in the generated project (BOU-267). The
    ;; comment says what to add back when the stubs get bodies.
    (str "(ns " base-ns "." module-name ".shell.http\n"
         "  \"HTTP routes for " module-name " module.\")\n"
         "\n"
         ";; The handlers below are stubs that return canned responses. When you\n"
         ";; wire them to the service, add to the ns form above:\n"
         ";;   (:require [" base-ns "." module-name ".ports :as ports])\n"
         "\n"
         ";; =============================================================================\n"
         ";; Legacy Reitit Routes\n"
         ";; =============================================================================\n"
         "\n"
         "(defn api-routes [_service]\n"
         "  [[\"/api/" entity-plural "\" {:get {:handler (fn [_req] {:status 200 :body []})}\n"
         "                          :post {:handler (fn [_req] {:status 201 :body {}})}}]\n"
         "   [\"/api/" entity-plural "/:id\" {:get {:handler (fn [_req] {:status 200 :body {}})}\n"
         "                                :put {:handler (fn [_req] {:status 200 :body {}})}\n"
         "                                :delete {:handler (fn [_req] {:status 204})}}]])\n"
         "\n"
         "(defn web-routes [_service _config]\n"
         "  [[\"/web/" entity-plural "\" {:get {:handler (fn [_req] {:status 200 :body \"<html><body>Web UI</body></html>\"})}}]])\n"
         "\n"
         "(defn routes [service config]\n"
         "  (vec (concat (api-routes service) (web-routes service config))))\n"
         "\n"
         ";; =============================================================================\n"
         ";; Normalized Routes\n"
         ";; =============================================================================\n"
         "\n"
         "(defn normalized-api-routes\n"
         "  \"Define API routes in normalized format.\n"
         "   \n"
         "   Args:\n"
         "     service: " (str/capitalize module-name) " service instance\n"
         "     \n"
         "   Returns:\n"
         "     Vector of normalized route maps\"\n"
         "  [_service]\n"
         "  [{:path \"/" entity-plural "\"\n"
         "    :methods {:get {:handler (fn [_req] {:status 200 :body []})}\n"
         "              :post {:handler (fn [_req] {:status 201 :body {}})}}}\n"
         "   {:path \"/" entity-plural "/:id\"\n"
         "    :methods {:get {:handler (fn [_req] {:status 200 :body {}})}\n"
         "              :put {:handler (fn [_req] {:status 200 :body {}})}\n"
         "              :delete {:handler (fn [_req] {:status 204})}}}])\n"
         "\n"
         "(defn normalized-web-routes\n"
         "  \"Define web UI routes in normalized format (WITHOUT /web prefix).\n"
         "   \n"
         "   NOTE: These routes will be mounted under /web by the top-level router.\n"
         "   Do NOT include /web prefix in paths here.\n"
         "   \n"
         "   Args:\n"
         "     service: " (str/capitalize module-name) " service instance\n"
         "     config: Application configuration map\n"
         "     \n"
         "   Returns:\n"
         "     Vector of normalized route maps\"\n"
         "  [_service _config]\n"
         "  [{:path \"/" entity-plural "\"\n"
         "    :methods {:get {:handler (fn [_req] {:status 200 :body \"<html><body>Web UI</body></html>\"})}}}])\n"
         "\n"
         "(defn " module-name "-routes-normalized\n"
         "  \"Define " module-name " module routes in normalized format for top-level composition.\n"
         "   \n"
         "   Returns a map with route categories:\n"
         "   - :api - REST API routes (will be mounted under /api)\n"
         "   - :web - Web UI routes (will be mounted under /web)\n"
         "   - :static - Static asset routes (empty)\n"
         "   \n"
         "   Args:\n"
         "     service: " (str/capitalize module-name) " service instance\n"
         "     config: Application configuration map\n"
         "\n"
         "   Returns:\n"
         "     Map with keys :api, :web, :static containing normalized route vectors\"\n"
         "  [service config]\n"
         "  {:api (normalized-api-routes service)\n"
         "   :web (normalized-web-routes service config)\n"
         "   :static []})\n"
         "\n")))

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
        entity-lower (str/lower-case entity-name)
        entity-plural (template/pluralize entity-lower)]
    (str "(ns " base-ns "." module-name ".shell.web-handlers\n"
         "  \"Web UI handlers for " module-name " module.\"\n"
         "  (:require [" base-ns "." module-name ".core.ui :as ui]\n"
         "            [" base-ns "." module-name ".ports :as ports]))\n"
         "\n"
         "(defn " entity-lower "-list-handler [service _config]\n"
         "  (fn [_request]\n"
         "    (let [items (ports/list-" entity-plural " service {})]\n"
         "      {:status 200\n"
         "       :headers {\"Content-Type\" \"text/html\"}\n"
         "       :body (ui/" entity-lower "-list-page items {})})))\n")))

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
  [ctx]
  (let [base-ns (:base-ns ctx "wagoe")
        module-name (:module-name ctx)
        entity (first (:entities ctx))
        entity-name (:entity-name entity)
        entity-lower (str/lower-case entity-name)]
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
         "      (is (= current-time (:updated-at result))))))\n")))

(defn generate-service-test-file
  "Generate test service file content.
   
   Args:
     ctx - Template context map
     
   Returns:
     String content for service test file
     
   Pure: true"
  [ctx]
  (let [base-ns (:base-ns ctx "wagoe")
        module-name (:module-name ctx)
        entity (first (:entities ctx))
        entity-name (:entity-name entity)
        entity-lower (str/lower-case entity-name)]
    (str "(ns " base-ns "." module-name ".shell.service-test\n"
         "  (:require [clojure.test :refer [deftest testing is]]\n"
         "            [" base-ns "." module-name ".shell.service :as service]\n"
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
         "                      (create [_ entity] entity)\n"
         "                      (find-by-id [_ _id] nil)\n"
         "                      (find-all [_ _opts] [])\n"
         "                      (update-entity [_ entity] entity)\n"
         "                      (delete [_ _id] nil))\n"
         "          svc (service/create-service mock-repo)\n"
         "          result (ports/create-" entity-lower " svc {:name \"Test\"})]\n"
         "      (is (some? result)))))\n")))

(defn generate-persistence-test-file
  "Generate test persistence file content.
   
   Args:
     ctx - Template context map
     
   Returns:
     String content for persistence test file
     
   Pure: true"
  [ctx]
  (let [base-ns (:base-ns ctx "wagoe")
        module-name (:module-name ctx)
        entity (first (:entities ctx))
        entity-name (:entity-name entity)
        entity-lower (str/lower-case entity-name)]
    (str "(ns " base-ns "." module-name ".shell." entity-lower "-repository-test\n"
         "  (:require [clojure.test :refer [deftest testing is]]\n"
         "            [" base-ns "." module-name ".shell.persistence :as persistence]\n"
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
         "    ))\n")))

;; =============================================================================
;; Incremental Generators - Add Field
;; =============================================================================

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
  (let [table-name (template/kebab->snake (template/pluralize (str/lower-case entity-name)))
        field-ctx (template/build-field-context field)
        field-name (:field-name-snake field-ctx)
        sql-type (:sql-type field-ctx)
        not-null (if (:field-required field-ctx) " NOT NULL" "")
        unique-clause (if (:field-unique field-ctx) " UNIQUE" "")]
    (format "-- Migration %s: Add %s to %s table

ALTER TABLE %s ADD COLUMN %s %s%s%s;
"
            migration-number
            field-name
            table-name
            table-name
            field-name
            sql-type
            not-null
            unique-clause)))

(defn schema-field-entry
  "The Malli entry line for `field`, without indentation.

   Required fields are plain; optional ones carry `{:optional true}`, matching
   what module generation emits for the same field definition."
  [field]
  (let [field-ctx  (template/build-field-context field)
        field-name (keyword (:field-name-kebab field-ctx))
        malli-type (:malli-type field-ctx)]
    (if (:field-required field-ctx)
      (format "[%s %s]" field-name malli-type)
      (format "[%s {:optional true} %s]" field-name malli-type))))

(defn- def-form-range
  "The [start end] line indices of `(def <schema-name> …)`, or nil.

   `end` is the line closing the form. These defs all end with `])` on the same
   line as their last entry, which is what `insert-schema-entry` relies on."
  [lines schema-name]
  (let [pattern (re-pattern (str "^\\(def " (java.util.regex.Pattern/quote schema-name) "\\b"))
        start   (first (keep-indexed (fn [i line] (when (re-find pattern line) i)) lines))]
    (when start
      (when-let [end (first (keep-indexed
                             (fn [i line] (when (and (>= i start)
                                                     (re-find #"\]\)\s*$" line))
                                            i))
                             lines))]
        [start end]))))

(defn insert-schema-entry
  "Add `entry` to the Malli map in `(def schema-name …)` within `source`.

   Returns the new source, or nil when the shape is not the one this
   understands — a hand-edited file, a renamed schema, a form that does not end
   in `])`. Returning nil rather than guessing is deliberate: the caller reports
   a skip, and a skip the user can see beats a mangled schema file they
   discover later.

   Also returns nil when the field is already there, so re-running is a no-op.

   The closing `])` moves onto the new entry:

     [:total :double]])        ->  [:total :double]
                                   [:status {:optional true} :string]])"
  [source schema-name entry]
  (let [lines (str/split-lines source)]
    (when-let [[start end] (def-form-range lines schema-name)]
      (let [block      (subvec (vec lines) start (inc end))
            entry-key  (second (re-find #"^\[(\S+)" entry))
            ;; Presence is checked per block, not per file: the same field may
            ;; legitimately appear in the entity schema and not yet in the
            ;; request schemas.
            present?   (some #(re-find (re-pattern (str "\\[" (java.util.regex.Pattern/quote entry-key) "[\\s\\]]"))
                                       %)
                             block)
            closing    (nth lines end)
            ;; From the last entry line, not the first: the first is `[:map …`,
            ;; which sits one level out and produced misaligned insertions.
            indent     (or (last (keep #(second (re-find #"^(\s+)\[:\S" %)) block)) "   ")]
        (when (and (not present?) (str/ends-with? (str/trimr closing) "])"))
          ;; Drop exactly the two closing characters — `]` for the map, `)` for
          ;; the def — and re-emit them after the new entry. Anything before
          ;; them belongs to the previous entry and must be left alone; the
          ;; last line here is typically `[:x {:optional true} [:maybe inst?]]])`,
          ;; where three of those four brackets are not ours to touch.
          (let [trimmed (subs (str/trimr closing) 0 (- (count (str/trimr closing)) 2))
                joined  (str/join "\n"
                                  (concat (take end lines)
                                          [trimmed (str indent entry "])")]
                                          (drop (inc end) lines)))]
            ;; `split-lines` discards the final newline, so rebuilding without
            ;; this rewrote the last line of every file it touched — a spurious
            ;; hunk at the bottom of the diff, on a line the change never
            ;; concerned.
            (cond-> joined
              (str/ends-with? source "\n") (str "\n"))))))))

(defn add-field-to-schema
  "Add `field` to the entity and request schemas in `source`.

   Returns {:status :updated :content <new source> :schemas [names]} when at
   least one schema was changed, or {:status :skipped :reason …} when none were.

   All three are edited because that is what the tool has always told users to
   do — the instruction comment it printed said to add the field to the entity
   schema \"and then to Create…Request and Update…Request as well\". Doing two
   of the three would leave the same half-finished state the instruction warns
   about."
  [source entity field]
  (let [entry   (schema-field-entry field)
        targets [entity (str "Create" entity "Request") (str "Update" entity "Request")]
        [content changed]
        (reduce (fn [[src changed] schema-name]
                  (if-let [updated (insert-schema-entry src schema-name entry)]
                    [updated (conj changed schema-name)]
                    [src changed]))
                [source []]
                targets)]
    (if (seq changed)
      {:status :updated :content content :schemas changed}
      {:status :skipped
       :reason (if (str/includes? source (str "[" (str/replace entry #"^\[(\S+).*" "$1")))
                 :already-present
                 :unrecognised-shape)})))

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
     String content for endpoint definition (normalized format)
   
   Pure: true"
  [module-name path method handler-name]
  (let [method-str (name method)]
    (format ";; Add to normalized-api-routes in src/wagoe/%s/shell/http.clj:
;;
;; {:path \"%s\"
;;  :methods {:%s {:handler %s-handler
;;                 :summary \"%s endpoint\"}}}
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
