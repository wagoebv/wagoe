(ns wagoe.scaffolder.core.generators
  "Pure functions for generating file content from templates.

   Each generator function takes a template context and returns
   file content as a string. All functions are pure and deterministic."
  (:require [clojure.string :as str]
            [wagoe.scaffolder.core.template :as template]))

;; When bumping the wagoe-tools release, update this version and redeploy
;; libs/tools to Clojars before cutting a new release.
(def wagoe-tools-version "1.0.0-beta-3")

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

  (update-%s [this entity]
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
            entity-lower  ; update-%s
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
         "  [" entity-plural " opts]\n"
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
         "  (create-" entity-lower " [this data]\n"
         "    (let [prepared (core/prepare-new-" entity-lower " data (generate-" entity-lower "-id) (current-time))]\n"
         "      (.create repository prepared)))\n"
         "  (get-" entity-lower " [this id]\n"
         "    (.find-by-id repository id))\n"
         "  (list-" (template/pluralize entity-lower) " [this opts]\n"
         "    (.list-" (template/pluralize entity-lower) " repository opts))\n"
         "  (update-" entity-lower " [this id data]\n"
         "    (.update-" entity-lower " repository (assoc data :id id)))\n"
         "  (delete-" entity-lower " [this id]\n"
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
        entity-lower (str/lower-case entity-name)
        table-name (template/pluralize (template/kebab->snake entity-name))]
    (str "(ns " base-ns "." module-name ".shell.persistence\n"
         "  \"Persistence layer for " module-name " module.\"\n"
         "  (:require [" base-ns "." module-name ".ports :as ports]\n"
         "            [wagoe.platform.shell.adapters.database.common.core :as db]\n"
         "            [honey.sql :as sql]))\n"
         "\n"
         "(defrecord Database" entity-name "Repository [db-ctx]\n"
         "  ports/I" entity-name "Repository\n"
         "  (create [this entity]\n"
         "    (db/execute-one! db-ctx\n"
         "      (sql/format {:insert-into :" table-name "\n"
         "                   :values [entity]\n"
         "                   :returning [:*]})))\n"
         "  (find-by-id [this id]\n"
         "    (db/execute-one! db-ctx\n"
         "      (sql/format {:select [:*]\n"
         "                   :from [:" table-name "]\n"
         "                   :where [:= :id id]})))\n"
         "  (find-all [this opts]\n"
         "    (db/execute-query! db-ctx\n"
         "      (sql/format {:select [:*]\n"
         "                   :from [:" table-name "]\n"
         "                   :limit (:limit opts 20)})))\n"
         "  (update-" entity-lower " [this entity]\n"
         "    (db/execute-one! db-ctx\n"
         "      (sql/format {:update :" table-name "\n"
         "                   :set (dissoc entity :id)\n"
         "                   :where [:= :id (:id entity)]\n"
         "                   :returning [:*]})))\n"
         "  (delete [this id]\n"
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
    (str "(ns " base-ns "." module-name ".shell.http\n"
         "  \"HTTP routes for " module-name " module.\"\n"
         "  (:require [" base-ns "." module-name ".ports :as ports]))\n"
         "\n"
         ";; =============================================================================\n"
         ";; Legacy Reitit Routes\n"
         ";; =============================================================================\n"
         "\n"
         "(defn api-routes [service]\n"
         "  [[\"/api/" entity-plural "\" {:get {:handler (fn [req] {:status 200 :body []})}\n"
         "                          :post {:handler (fn [req] {:status 201 :body {}})}}]\n"
         "   [\"/api/" entity-plural "/:id\" {:get {:handler (fn [req] {:status 200 :body {}})}\n"
         "                                :put {:handler (fn [req] {:status 200 :body {}})}\n"
         "                                :delete {:handler (fn [req] {:status 204})}}]])\n"
         "\n"
         "(defn web-routes [service config]\n"
         "  [[\"/web/" entity-plural "\" {:get {:handler (fn [req] {:status 200 :body \"<html><body>Web UI</body></html>\"})}}]])\n"
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
         "  [service]\n"
         "  [{:path \"/" entity-plural "\"\n"
         "    :methods {:get {:handler (fn [req] {:status 200 :body []})}\n"
         "              :post {:handler (fn [req] {:status 201 :body {}})}}}\n"
         "   {:path \"/" entity-plural "/:id\"\n"
         "    :methods {:get {:handler (fn [req] {:status 200 :body {}})}\n"
         "              :put {:handler (fn [req] {:status 200 :body {}})}\n"
         "              :delete {:handler (fn [req] {:status 204})}}}])\n"
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
         "  [service config]\n"
         "  [{:path \"/" entity-plural "\"\n"
         "    :methods {:get {:handler (fn [req] {:status 200 :body \"<html><body>Web UI</body></html>\"})}}}])\n"
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
         "(defn " entity-lower "-list-handler [service config]\n"
         "  (fn [request]\n"
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
         "(deftest prepare-new-" entity-lower "-test\n"
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
         "(deftest create-" entity-lower "-test\n"
         "  (testing \"creates " entity-lower " via service\"\n"
         "    (let [mock-repo (reify ports/I" entity-name "Repository\n"
         "                      (create [_ entity] entity))\n"
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
         "(deftest create-" entity-lower "-test\n"
         "  (testing \"creates " entity-lower " in database\"\n"
         "    ;; Test requires database context\n"
         "    (is true)))\n")))

;; =============================================================================
;; Project Generator
;; =============================================================================

(defn generate-project-deps
  "DUPLICATE OF libs/wagoe-cli/resources/wagoe/cli/templates/deps.edn.tmpl.
   Both produce a generated project's deps.edn, by different routes:
   this one via `scaffolder generate-project`, the template via `wagoe new`.
   They have drifted before — the :seed alias landed in the template and not
   here, so `bb db:seed` failed in projects made this way. Change both.

   Generate deps.edn for a new project.
   
   Args:
     name - Project name
     
   Returns:
     String content for deps.edn"
  [name]
  (format ";; deps.edn - %s project dependencies
{:paths [\"src\" \"resources\"]

 :deps {org.clojure/clojure               {:mvn/version \"1.12.4\"}
        
        ;; Environment / Lifecycle
        integrant/integrant               {:mvn/version \"1.0.1\"}
        integrant/repl                    {:mvn/version \"0.5.0\"}
        aero/aero                         {:mvn/version \"1.1.6\"}
        
        ;; HTTP Server
        ring/ring-core                    {:mvn/version \"1.15.3\"}
        ring/ring-jetty-adapter           {:mvn/version \"1.15.3\"}
        metosin/reitit-ring               {:mvn/version \"0.9.2\"}
        
        ;; Database
        com.github.seancorfield/next.jdbc {:mvn/version \"1.3.1086\"}
        com.github.seancorfield/honeysql  {:mvn/version \"2.7.1364\"}
        com.zaxxer/HikariCP               {:mvn/version \"7.0.2\"}
        org.xerial/sqlite-jdbc            {:mvn/version \"3.48.0.0\"}
        
        ;; Validation
        metosin/malli                     {:mvn/version \"0.20.0\"}
        
        ;; UI (Server-Side Rendering)
        hiccup/hiccup                     {:mvn/version \"2.0.0-RC3\"}
        
        ;; Logging
        org.clojure/tools.logging         {:mvn/version \"1.3.1\"}
        ch.qos.logback/logback-classic    {:mvn/version \"1.5.23\"}}

 :aliases
 {:test
  {:extra-paths [\"test\"]
   :extra-deps  {lambdaisland/kaocha {:mvn/version \"1.95.1463\"}
                 org.clojure/test.check {:mvn/version \"1.1.1\"}}
   :main-opts   [\"-m\" \"kaocha.runner\"]}
  
  :repl
  {:extra-deps {nrepl/nrepl {:mvn/version \"1.3.0\"}
                cider/cider-nrepl {:mvn/version \"0.52.1\"}}
   :main-opts  [\"-m\" \"nrepl.cmdline\"
                \"--middleware\" \"[cider.nrepl/cider-middleware]\"
                \"--interactive\"]}
  
  :build
  {:deps {io.github.clojure/tools.build {:mvn/version \"0.10.9\"}}
   :ns-default build}

  :clj-kondo
  {:replace-deps {clj-kondo/clj-kondo {:mvn/version \"2026.04.15\"}}
   :main-opts    [\"-m\" \"clj-kondo.main\"]}

  :migrate
  {:main-opts  [\"-m\" \"wagoe.platform.shell.database.cli-migrations\"]
   :extra-deps {com.wagoe/wagoe-platform {:mvn/version \"1.0.0-beta-3\"}
                org.xerial/sqlite-jdbc             {:mvn/version \"3.51.0.0\"}
                org.postgresql/postgresql          {:mvn/version \"42.7.12\"}
                com.h2database/h2                  {:mvn/version \"2.4.240\"}
                com.mysql/mysql-connector-j        {:mvn/version \"9.6.0\"}}}

  ;; Backs `bb db:seed`, which shells out to `clojure -M:seed`. Same driver set
  ;; as :migrate — seeding opens the same database.
  :seed
  {:main-opts  [\"-m\" \"wagoe.platform.shell.database.cli-seed\"]
   :extra-deps {com.wagoe/wagoe-platform {:mvn/version \"1.0.0-beta-3\"}
                org.xerial/sqlite-jdbc             {:mvn/version \"3.51.0.0\"}
                org.postgresql/postgresql          {:mvn/version \"42.7.12\"}
                com.h2database/h2                  {:mvn/version \"2.4.240\"}
                com.mysql/mysql-connector-j        {:mvn/version \"9.6.0\"}}}}}
"
          name))

(defn generate-project-bb-edn
  "Generate bb.edn for a new project.

   Wires wagoe-tools so all bb tasks (scaffold, i18n, deploy, etc.)
   are available out of the box.

   Args:
     _name - Project name (unused, kept for API consistency)

   Returns:
     String content for bb.edn

   Pure: true"
  [_name]
  (str ";; bb.edn — Babashka task runner for this Wagoe project\n"
       ";; All tasks are provided by wagoe-tools; no local scripts needed.\n"
       "{:deps {com.wagoe/wagoe-tools {:mvn/version \"" wagoe-tools-version "\"}}\n"
       "\n"
       " :tasks\n"
       " {:requires ([wagoe.tools.scaffold    :as scaffold]\n
             [wagoe.tools.ai          :as ai]
             [wagoe.tools.deps        :as deps]
             [wagoe.tools.i18n        :as i18n]
             [wagoe.tools.admin       :as admin]
             [wagoe.tools.deploy      :as deploy]
             [wagoe.tools.dev         :as dev]
             [wagoe.tools.setup       :as setup]
             [wagoe.tools.doctor      :as doctor]
             [wagoe.tools.doctor-env  :as doctor-env]
             [wagoe.tools.check       :as check]
             [wagoe.tools.check-fcis  :as check-fcis]
             [wagoe.tools.check-tests :as check-tests]
             [wagoe.tools.check-deps  :as check-deps]
             [wagoe.tools.check-ports :as check-ports]
             [wagoe.tools.db          :as db]
             [wagoe.tools.quickstart  :as quickstart]
             [wagoe.tools.help        :as help]
             [wagoe.tools.integrate   :as integrate])

  ;; Generate a new FC/IS module interactively
  scaffold          {:doc \"Interactive module scaffolding wizard\"
                     :task (apply scaffold/-main *command-line-args*)}
  scaffold:ai       {:doc \"NL scaffolding via AI (--yes for non-interactive)\"
                     :task (apply scaffold/-main \"ai\" *command-line-args*)}
  scaffold:integrate {:doc \"Wire a scaffolded module into deps.edn, tests.edn, wiring.clj\"
                      :task (apply integrate/-main *command-line-args*)}

  ;; Config setup wizard
  setup             {:doc \"Interactive config setup wizard (bb setup [ai <description>])\"
                     :task (apply setup/-main *command-line-args*)}

  ;; Config validation
  doctor            {:doc \"Validate config for common mistakes\"
                     :task (apply doctor/-main *command-line-args*)}
  doctor:env        {:doc \"Check development environment prerequisites\"
                     :task (apply doctor-env/-main *command-line-args*)}

  ;; Quality checks
  check             {:doc \"Run all quality checks (FC/IS, deps, placeholder-tests, kondo, doctor)\"
                     :task (apply check/-main *command-line-args*)}
  check:fcis        {:doc \"FC/IS boundary enforcement\"
                     :task (check-fcis/-main)}
  check:placeholder-tests {:doc \"Detect placeholder (is true) assertions in test files\"
                            :task (check-tests/-main)}
  check:deps        {:doc \"Verify library dependency direction and detect cycles\"
                     :task (check-deps/-main)}
  check:ports       {:doc \"Hexagonal enforcement: modules must define ports.clj; shell/web must not bypass protocols\"
                     :task (check-ports/-main)}

  ;; Database management
  migrate           {:doc \"Run database migrations (bb migrate [up|status|rollback|create ...])\"
                     :task (apply dev/migrate *command-line-args*)}
  db:status         {:doc \"Show database configuration and migration status\"
                     :task (db/-main \"status\")}
  db:reset          {:doc \"Drop and recreate the database with all migrations\"
                     :task (db/-main \"reset\")}
  db:seed           {:doc \"Seed database from resources/seeds/dev.edn (dev-like envs only; --force to override)\"
                     :task (apply db/-main \"seed\" *command-line-args*)}

  ;; Onboarding
  quickstart        {:doc \"Zero-to-running-app setup: check env, configure, scaffold, migrate, start\"
                     :task (apply quickstart/-main *command-line-args*)}
  guide             {:doc \"Contextual help and guidance\"
                     :task (apply help/-main *command-line-args*)}

  ;; AI-assisted tooling (NL scaffolding, error explainer, SQL copilot, docs wizard)
  ai                {:doc \"Framework-aware AI tooling (explain|gen-tests|sql|docs|admin-entity)\"
                     :task (apply ai/-main *command-line-args*)}

  ;; Bootstrap the first admin user (prompts for password securely)
  create-admin      {:doc \"Create the first admin user (interactive wizard)\"
                     :task (apply admin/-main *command-line-args*)}

  ;; Check and optionally update outdated Maven dependencies
  upgrade-outdated  {:doc \"Check and optionally upgrade outdated Maven deps\"
                     :task (apply deps/-main *command-line-args*)}

  ;; Publish libraries to Clojars
  deploy            {:doc \"Deploy libraries to Clojars\"
                     :task (apply deploy/-main *command-line-args*)}

  ;; Developer utilities
  check-links       {:doc \"Validate local markdown links in AGENTS documentation\"
                     :task (dev/check-links)}
  smoke-check       {:doc \"Verify deps.edn aliases and key tool entrypoints\"
                     :task (dev/smoke-check)}
  install-hooks     {:doc \"Configure git hooks path to .githooks\"
                     :task (dev/install-hooks)}

  ;; i18n tooling
  i18n:find         {:doc \"Find a translation key by substring or exact keyword\"
                     :task (apply i18n/-main \"find\" *command-line-args*)}
  i18n:scan         {:doc \"Scan core/ui.clj files for unexternalised string literals\"
                     :task (i18n/-main \"scan\")}
  i18n:missing      {:doc \"Report translation keys missing from locale files\"
                     :task (i18n/-main \"missing\")}
  i18n:unused       {:doc \"Report catalogue keys not referenced in source\"
                     :task (i18n/-main \"unused\")}}}"))

(defn generate-project-readme
  "Generate README.md for a new project."
  [name]
  (let [name-snake (str/replace name "-" "_")]
    (format "# %s

A Clojure web application built with the Wagoe Framework.

## Quick Start

### Prerequisites

- JDK 21 or later
- Clojure CLI tools

### Running Locally

```bash
# Start REPL
clojure -M:repl

# In REPL:
(require '[integrant.repl :as ig-repl])
(ig-repl/go)

# Visit http://localhost:3000
```

### Running Tests

```bash
clojure -M:test
```

## Project Structure

```
%s/
├── src/%s/
│   └── app.clj              # Application entrypoint and wiring
├── resources/
│   └── conf/dev/config.edn  # Configuration (SQLite by default)
├── test/
├── deps.edn                 # Dependencies
└── README.md                # This file
```

## Development Workflow

### REPL

The recommended development workflow uses Integrant REPL:

```clojure
(require '[integrant.repl :as ig-repl])

;; Start system
(ig-repl/go)

;; Reload and restart after code changes
(ig-repl/reset)

;; Stop system
(ig-repl/halt)
```

### Adding Features

Use the Wagoe scaffolder to generate new modules:

```bash
clojure -M -m wagoe.scaffolder.shell.cli-entry generate \\
  --module-name product \\
  --entity Product \\
  --field name:string:required \\
  --field price:decimal:required
```

## Configuration

Configuration is in `resources/conf/dev/config.edn` using Aero and Integrant.

By default, the project uses SQLite with no setup required. The database file is created automatically at `dev-database.db`.

## Testing

```bash
# Run all tests
clojure -M:test

# Watch mode
clojure -M:test --watch

# Run specific test namespace
clojure -M:test --focus %s.app-test
```

## Architecture

This project follows the **Functional Core / Imperative Shell** (FC/IS) architectural pattern:

- **Core** (`core/`): Pure business logic, no side effects
- **Shell** (`shell/`): I/O, HTTP, database, side effects
- **Ports** (`ports.clj`): Protocol definitions for dependency injection

## License

Copyright © 2024-2026

Distributed under the Eclipse Public License version 2.0.
"
            name name name name-snake)))

(defn generate-project-config
  "Generate initial config.edn for a new project."
  [name]
  (format ";; config.edn - Development configuration for %s
{:wagoe/app
 {:name \"%s\"
  :version \"0.1.0\"
  :env :development}

 :wagoe/db-context
 {:datasource
  {:jdbcUrl \"jdbc:sqlite:dev-database.db\"
   :driverClassName \"org.sqlite.JDBC\"
   :maximumPoolSize 5
   :connectionTimeout 30000}}

 :wagoe/http-server
 {:port 3000
  :host \"0.0.0.0\"
  :handler #ig/ref :wagoe/handler}

 :wagoe/handler
 {:routes [[\"/health\" {:get {:handler (fn [_] {:status 200 :body {:status \"ok\"}})}}]
           [\"/\" {:get {:handler (fn [_] {:status 200 :body \"%s is running\"})}}]]}}
"
          name name name))

(defn generate-project-main
  "Generate app.clj for a new project."
  [name]
  (let [ns-name (str/replace name "-" "_")]
    (format "(ns %s.app
  \"Application entrypoint and Integrant system wiring.\"
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [integrant.core :as ig]
            [integrant.repl :as ig-repl]
            [ring.adapter.jetty :as jetty]
            [reitit.ring :as ring]
            [next.jdbc :as jdbc]
            [next.jdbc.connection :as connection]
            [com.zaxxer.hikari.HikariDataSource])
  (:import [com.zaxxer.hikari HikariDataSource])
  (:gen-class))

;; =============================================================================
;; Configuration Loading
;; =============================================================================

(defn load-config
  \"Load configuration using Aero.\"
  []
  (aero/read-config (io/resource \"conf/dev/config.edn\")))

;; =============================================================================
;; Integrant Component: Database Context
;; =============================================================================

(defmethod ig/init-key :wagoe/db-context
  [_ {:keys [datasource]}]
  (println \"Starting database connection pool...\")
  (let [ds (connection/->pool HikariDataSource datasource)]
    {:datasource ds}))

(defmethod ig/halt-key! :wagoe/db-context
  [_ {:keys [datasource]}]
  (println \"Stopping database connection pool...\")
  (.close ^HikariDataSource datasource))

;; =============================================================================
;; Integrant Component: HTTP Handler
;; =============================================================================

(defmethod ig/init-key :wagoe/handler
  [_ {:keys [routes]}]
  (println \"Creating HTTP handler...\")
  (ring/ring-handler
    (ring/router routes)))

;; =============================================================================
;; Integrant Component: HTTP Server
;; =============================================================================

(defmethod ig/init-key :wagoe/http-server
  [_ {:keys [port host handler]}]
  (println (str \"Starting HTTP server on \" host \":\" port \"...\"))
  (jetty/run-jetty handler
                   {:port port
                    :host host
                    :join? false}))

(defmethod ig/halt-key! :wagoe/http-server
  [_ server]
  (println \"Stopping HTTP server...\")
  (.stop server))

;; =============================================================================
;; System Initialization
;; =============================================================================

(defn start-system!
  \"Start the Integrant system.\"
  []
  (let [config (load-config)]
    (ig-repl/set-prep! (constantly config))
    (ig-repl/go)))

(defn -main
  \"Application entrypoint.\"
  [& args]
  (start-system!)
  (println \"%s started successfully.\"))

;; =============================================================================
;; REPL Development Helpers
;; =============================================================================

(comment
  ;; Start system
  (start-system!)
  
  ;; Reload and restart
  (ig-repl/reset)
  
  ;; Stop system
  (ig-repl/halt)
  
  ;; Get database connection
  (def db (get-in integrant.repl.state/system [:wagoe/db-context :datasource]))
  
  ;; Test query
  (jdbc/execute! db [\"SELECT 1\"])
  )
"
            ns-name name)))

;; =============================================================================
;; Agent Guidance Generators (AGENTS.md / CLAUDE.md)
;; =============================================================================

(defn generate-project-agents-md
  "Generate AGENTS.md for a new project.

   This is the canonical agent guidance file delivered at project
   scaffold time. It documents the Functional Core / Imperative Shell +
   Hexagonal (ports) architecture and marks ports.clj as a REQUIRED layer,
   so the convention does not silently erode in hand-written modules.

   Args:
     name - Project name

   Returns:
     String content for AGENTS.md

   Pure: true"
  [name]
  (format "# AGENTS.md — %s

Guidance for coding agents (Claude Code, etc.) working in this Wagoe project.

## Architecture: Functional Core / Imperative Shell + Ports

This project follows the **Functional Core / Imperative Shell** (FC/IS) pattern
with a **Hexagonal (ports & adapters)** boundary. Dependencies flow downward:
the shell depends on protocols defined in `ports.clj`; the core implements the
behaviour those protocols describe. The core never depends on the shell.

```
┌─────────────────────────────────────────────┐
│         IMPERATIVE SHELL (shell/*)          │
│  I/O, persistence, HTTP, side effects        │
└─────────────────────────────────────────────┘
                    ↓ depends on
┌─────────────────────────────────────────────┐
│              PORTS (ports.clj)              │
│  Protocol definitions (interfaces)          │
└─────────────────────────────────────────────┘
                    ↑ implemented by
┌─────────────────────────────────────────────┐
│         FUNCTIONAL CORE (core/*)            │
│  Pure functions, business logic only         │
└─────────────────────────────────────────────┘
```

## Module Layout

Every module under `src/wagoe/{module}/` has the same shape:

```
src/wagoe/{module}/
├── core/        # Pure business logic — no I/O, no logging, no exceptions
├── shell/       # All side effects: persistence, services, HTTP handlers
├── ports.clj    # Protocol definitions (interfaces)  ← REQUIRED, not optional
└── schema.clj   # Malli validation schemas
```

**`ports.clj` is required for every module.** A module with `core/` and
`shell/` but no `ports.clj` is incomplete — the shell would couple directly to
concrete implementations instead of protocols, defeating the architecture.

## Dependency & Boundary Rules

- ✅ Shell → Core (shell calls core)
- ✅ Core → Ports (core depends on protocols)
- ✅ Shell → Adapters (shell implements protocols from `ports.clj`)
- ❌ Core → Shell (NEVER — this violates FC/IS)

Concrete rules every module MUST follow:

1. **Shell services depend on the protocols declared in `ports.clj`**, never on
   another module's concrete records.
2. **Cross-module calls go through service ports**, not by reaching into another
   module's `shell.*` namespaces.
3. **Web / HTTP layers never require `*.shell.persistence` directly** — they go
   through the service port so the persistence adapter stays swappable.

## Creating Modules — use the scaffolder

`bb scaffold` is the canonical way to create modules. It generates the full
FC/IS structure **including a correct `ports.clj`** and passes the quality gates
(`bb check:fcis`, `bb check:ports`) by design.

```bash
bb scaffold                                          # interactive wizard
bb scaffold ai \"product module with name, price\" --yes  # from a description
```

Do **not** hand-write a new module skeleton — you will almost certainly forget
`ports.clj` and drift from the architecture. Let the scaffolder do it.

## Quality Gates

```bash
bb check:fcis     # core/ must not import shell/IO/logging/DB
bb check:ports    # every module must define ports.clj; shell/web must not bypass protocols
bb check          # run all gates
```
"
          name))

(defn generate-project-claude-md
  "Generate CLAUDE.md for a new project.

   CLAUDE.md is the file Claude Code reads first. To avoid drift it is a thin
   pointer to AGENTS.md, repeating only the single non-negotiable rule: every
   module needs a ports.clj.

   Args:
     name - Project name

   Returns:
     String content for CLAUDE.md

   Pure: true"
  [name]
  (format "# CLAUDE.md — %s

This file guides Claude Code (claude.ai/code) in this repository.

**Read [AGENTS.md](AGENTS.md) first** — it is the source of truth for this
project's Functional Core / Imperative Shell + ports architecture and
conventions. Keep this file thin so the two cannot drift apart.

## The one rule that is easy to forget

Every module under `src/wagoe/{module}/` MUST have a **`ports.clj`** defining
its protocols. This layer is REQUIRED: `core/` + `shell/` without `ports.clj` is
incomplete, because the shell would couple to concrete implementations instead
of protocols.

- Shell services depend on the protocols in `ports.clj`, not on concrete records.
- Web / HTTP layers never require `*.shell.persistence` directly — go through the service port.
- Use `bb scaffold` to create modules; it generates `ports.clj` correctly.
- Run `bb check:ports` to verify the boundary.
"
          name))

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
