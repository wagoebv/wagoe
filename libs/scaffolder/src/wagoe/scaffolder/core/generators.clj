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

(defn- count-fn
  "The repository method that counts a child's rows under one parent, for a
   child with a minimum (BOU-578), else nil."
  [entity]
  (when (and (:min entity) (:belongs-to entity))
    (str "count-" (:entity-plural entity) "-by-" (:belongs-to entity))))

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
        ;; A workflow's status is set by its transitions, never by a request.
        requested (remove :workflow-state? fields)
        request-schemas (str/join "\n" (map generate-field-schema requested))
        ;; Every field optional, whatever it is on the entity. An update
        ;; request is a partial: one required field in this schema means every
        ;; caller doing a partial update has to send it. The same
        ;; `field-schemas` string used to be interpolated into all three
        ;; schemas, so `--field name:string:required` made `name` mandatory on
        ;; update — against the convention in libs/scaffolder/AGENTS.md.
        update-field-schemas (str/join "\n" (map #(generate-field-schema % true) requested))]
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
                    request-schemas "])\n"
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
          "    \"Delete " e " by ID.\")"
          (when (get entity :primary? true)
            (str "\n\n  (transact [this f]\n"
                 "    \"Call `f` in one database transaction, and return what it returns.\")"))
          (when-let [c (count-fn entity)]
            (str "\n\n  (" c " [this " (:belongs-to entity) "-id]\n"
                 "    \"How many " (:entity-plural entity) " the " (:belongs-to entity) " has.\")"))
          ")\n")
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
          "    \"Delete " e ".\")"
          (if (:workflow entity)
            (str "\n\n"
                 "  (transition-" e " [this id transition actor]\n"
                 "    \"Move the " e "'s " (get-in entity [:workflow :field]) " along its workflow. The result, with\n"
                 "     the " e " as it is now on success; nil when there is no such " e ".\"))\n"
                 "\n"
                 "(defprotocol I" entity-name "Workflow\n"
                 "  \"The " e "'s " (get-in entity [:workflow :field]) " workflow, as the service drives it.\"\n"
                 "\n"
                 "  (start-" e "-workflow! [this id]\n"
                 "    \"Start the workflow of a new " e ", unless it has one.\")\n"
                 "\n"
                 "  (" e "-workflow-state [this id]\n"
                 "    \"The state of the " e "'s workflow, started if it has none.\")\n"
                 "\n"
                 "  (remove-" e "-workflow! [this id]\n"
                 "    \"Remove the workflow of a deleted " e ".\")\n"
                 "\n"
                 "  (transition-" e "-workflow! [this id transition actor]\n"
                 "    \"Run `transition` as `actor`, starting the workflow if it has none.\"))\n")
            ")\n"))}))

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

(def statement-separator
  "What migratus splits a migration on. Without it the file is one statement:
   SQLite runs the first and drops the rest, and PostgreSQL's driver refuses
   it (BOU-569)."
  "\n--;;\n")

(defn- index-sql [table column]
  (format "CREATE INDEX IF NOT EXISTS idx_%s_%s ON %s(%s);" table column table column))

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
         indexes (cons (index-sql table-name "created_at")
                       (for [f fields :when (or (:relation-table f) (:field-indexed f))]
                         (index-sql table-name (:field-name-snake f))))]
     (str (format "-- Migration %s: Create %s table

CREATE TABLE IF NOT EXISTS %s (
  id UUID PRIMARY KEY,
%s,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE,
  deleted_at TIMESTAMP WITH TIME ZONE
);"
                  migration-number
                  table-name
                  table-name
                  field-sqls)
          statement-separator
          (str/join statement-separator indexes)
          "\n"))))

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

(defn- keep-minimum-fn
  "The service's guard for a child with a minimum: it refuses to leave the
   parent with fewer (BOU-578)."
  [entity]
  (let [parent (:belongs-to entity)
        plural (:entity-plural entity)
        n      (:min entity)]
    (str "(defn- keep-minimum!\n"
         "  \"Refuse to leave the " parent " with fewer than " n " " plural ".\"\n"
         "  [repository " parent "-id]\n"
         "  (when (<= (ports/" (count-fn entity) " repository " parent "-id) " n ")\n"
         "    (throw (ex-info \"Every " parent " keeps at least " n " of its " plural "\"\n"
         "                    {:type :validation-error\n"
         "                     :errors {:" plural " [\"every " parent " keeps at least " n "\"]}}))))\n"
         "\n")))

(defn- create-with-children-fn
  "The first entity's create, with the children its request may carry."
  [entity-lower]
  (str "(defn- create-with-children\n"
       "  \"Create `prepared` and each child `data` carries under a key of `children`,\n"
       "   in one transaction: all of them or none. The " entity-lower " comes back with them.\"\n"
       "  [repository children prepared data]\n"
       "  (ports/transact repository\n"
       "                  (fn []\n"
       "                    (reduce-kv (fn [created k {:keys [foreign-key create]}]\n"
       "                                 (cond-> created\n"
       "                                   (contains? data k)\n"
       "                                   (assoc k (mapv #(create (assoc % foreign-key (:id created))) (get data k)))))\n"
       "                               (ports/create repository prepared)\n"
       "                               children))))\n"
       "\n"))

(defn generate-service-file
  "Generate shell/service.clj file content.

   The first entity's service creates the entities that belong to it with it,
   in one transaction: `children`, which the module wiring hands it, says
   which (BOU-578).

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
         wf (:workflow entity)
         primary? (:primary? entity true)
         minimum? (some? (count-fn entity))
         parent-id (str ":" (:belongs-to entity) "-id")
         fields (str "[repository" (when wf " workflow") (when primary? " children") "]")
         {:keys [find-by-id find-all create update delete]} (repo-fns entity)
         prepare (str "(core/prepare-new-" entity-lower " "
                      (if primary? "(apply dissoc data (keys children))" "data")
                      " (generate-" entity-lower "-id) (current-time))")
         insert  (if primary?
                   "(create-with-children repository children prepared data)"
                   (str "(ports/" create " repository prepared)"))
         ;; The guard a child with a minimum puts before an update that moves
         ;; it to another parent, and before a delete.
         guard-update (when minimum?
                        (str "    (when-let [row (and (contains? data " parent-id ") (ports/" find-by-id " repository id))]\n"
                             "      (when (not= (" parent-id " row) (" parent-id " data))\n"
                             "        (keep-minimum! repository (" parent-id " row))))\n"))
         guard-delete (when minimum?
                        (str "    (when-let [row (ports/" find-by-id " repository id)]\n"
                             "      (keep-minimum! repository (" parent-id " row)))\n"))]
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
          (when primary? (create-with-children-fn entity-lower))
          (when minimum? (keep-minimum-fn entity))
          (when wf
            (str "(defn- mirror-" (:field wf) "!\n"
                 "  \"Write the workflow's `state` to the " (:field wf) " column, and fail unless it\n"
                 "   reads back: a transition whose column did not follow is an error, not\n"
                 "   something to find later.\"\n"
                 "  [repository id state]\n"
                 "  (let [row (ports/" update " repository {:id id :" (:field wf) " state})]\n"
                 "    (when (not= state (:" (:field wf) " row))\n"
                 "      (throw (ex-info \"The " (:field wf) " column does not match the workflow\"\n"
                 "                      {:type :internal-error :id id :workflow state :column (:" (:field wf) " row)})))\n"
                 "    row))\n"
                 "\n"))
          "(defrecord " entity-name "Service " fields "\n"
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
          (if wf
            (str "    (let [prepared " prepare "\n"
                 "          created  " insert "]\n"
                 ;; After the transaction: the workflow store writes on a
                 ;; connection of its own, which SQLite would make wait on it.
                 "      (try\n"
                 "        (ports/start-" entity-lower "-workflow! workflow (:id created))\n"
                 "        (catch Exception e\n"
                 "          ;; No row without its workflow.\n"
                 "          (ports/" delete " repository (:id created))\n"
                 "          (throw e)))\n"
                 "      created))\n")
            (str "    (let [prepared " prepare "]\n"
                 "      " insert "))\n"))
          "  (get-" entity-lower " [_this id]\n"
          "    (ports/" find-by-id " repository id))\n"
         ;; find-all, not list-<plural>: the repository port has no
         ;; list-<plural> method, so this called something that does not exist
         ;; and blew up at runtime the first time anyone listed anything.
          "  (list-" (template/pluralize entity-lower) " [_this opts]\n"
          "    (ports/" find-all " repository opts))\n"
          "  (update-" entity-lower " [_this id data]\n"
          guard-update
          (if wf
            (str "    ;; Only a transition moves " (:field wf) ".\n"
                 "    (ports/" update " repository (assoc (dissoc data :" (:field wf) ") :id id)))\n")
            (str "    (ports/" update " repository (assoc data :id id)))\n"))
          "  (delete-" entity-lower " [_this id]\n"
          guard-delete
          (if wf
            (str "    ;; The row first: a delete the database refuses keeps its workflow.\n"
                 "    (let [deleted (ports/" delete " repository id)]\n"
                 "      (ports/remove-" entity-lower "-workflow! workflow id)\n"
                 "      deleted))\n"
                 "  (transition-" entity-lower " [_this id transition actor]\n"
                 "    (when-let [row (ports/" find-by-id " repository id)]\n"
                 "      ;; The workflow and the column are written apart, so a column an\n"
                 "      ;; earlier write left behind is brought level before moving on.\n"
                 "      (let [state (ports/" entity-lower "-workflow-state workflow id)]\n"
                 "        (when (not= state (:" (:field wf) " row))\n"
                 "          (mirror-" (:field wf) "! repository id state)))\n"
                 "      (let [result (ports/transition-" entity-lower "-workflow! workflow id transition actor)]\n"
                 "        (if (:success? result)\n"
                 "          (assoc result :" entity-lower " (mirror-" (:field wf) "! repository id (get-in result [:instance :current-state])))\n"
                 "          result)))))\n")
            (str "    (ports/" delete " repository id)))\n"))
          "\n"
          (cond
            (and primary? wf)
            (str "(defn create-service\n"
                 "  \"`children` is what a create may carry: {key {:foreign-key k :create f}},\n"
                 "   one per entity that belongs to this one. The module wiring builds it.\"\n"
                 "  ([repository workflow] (create-service repository workflow {}))\n"
                 "  ([repository workflow children]\n"
                 "   (->" entity-name "Service repository workflow children)))\n")

            primary?
            (str "(defn create-service\n"
                 "  \"`children` is what a create may carry: {key {:foreign-key k :create f}},\n"
                 "   one per entity that belongs to this one. The module wiring builds it.\"\n"
                 "  ([repository] (create-service repository {}))\n"
                 "  ([repository children]\n"
                 "   (->" entity-name "Service repository children)))\n")

            wf
            (str "(defn create-service [repository workflow]\n"
                 "  (->" entity-name "Service repository workflow))\n")

            :else
            (str "(defn create-service [repository]\n"
                 "  (->" entity-name "Service repository))\n"))))))

;; =============================================================================
;; Persistence File Generator
;; =============================================================================

(defn- enum-field-set
  "The entity's enum fields as a set literal, e.g. `#{:status}`."
  [entity]
  (str "#{" (str/join " " (for [f (:fields entity) :when (= :enum (:field-type f))]
                            (str ":" (:field-name-kebab f))))
       "}"))

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
          ;; A keyword value is a column name to HoneySQL, so an enum insert
          ;; failed with `no such column: entered` (BOU-562).
          "(def ^:private enum-fields\n"
          "  \"Keywords in Clojure, strings in the database.\"\n"
          "  " (enum-field-set entity) ")\n"
          "\n"
          "(defn- ->row [entity]\n"
          "  (reduce (fn [m k] (cond-> m (keyword? (get m k)) (update k name))) entity enum-fields))\n"
          "\n"
          "(defn- ->entity [row]\n"
          "  (some-> row\n"
          "          (update-vals date->iso)\n"
          "          (as-> r (reduce (fn [m k] (cond-> m (string? (get m k)) (update k keyword))) r enum-fields))))\n"
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
          "    (write! db-ctx {:insert-into :" table-name " :values [(->row entity)]})\n"
          "    (select-by-id db-ctx (:id entity)))\n"
          "  (" find-by-id " [_this id]\n"
          "    (select-by-id db-ctx id))\n"
          "  (" find-all " [_this opts]\n"
          "    (mapv ->entity (db/execute-query! db-ctx {:select [:*]\n"
          "                                              :from [:" table-name "]\n"
          "                                              :order-by [[:created-at :asc] [:id :asc]]\n"
          "                                              :limit (or (:limit opts) 20)\n"
          "                                              :offset (or (:offset opts) 0)})))\n"
          "  (" update " [_this entity]\n"
          ;; Refused here rather than sent: an empty :set is `UPDATE t SET  WHERE`,
          ;; a database syntax error (BOU-547).
          "    (let [changes (dissoc entity :id :created-at :updated-at)]\n"
          "      (when (empty? changes)\n"
          "        (throw (ex-info \"Nothing to update\" {:type :validation-error :id (:id entity)})))\n"
          "      (write! db-ctx {:update :" table-name "\n"
          "                      :set (->row (assoc changes :updated-at (Instant/now)))\n"
          "                      :where [:= :id (:id entity)]})\n"
          "      (select-by-id db-ctx (:id entity))))\n"
          "  (" delete " [_this id]\n"
          "    (db/execute-update! db-ctx {:delete-from :" table-name " :where [:= :id id]}))"
          (when (:primary? entity true)
            (str "\n  (transact [_this f]\n"
                 "    (db/with-transaction [_ db-ctx] (f)))"))
          (when-let [c (count-fn entity)]
            (str "\n  (" c " [_this " (:belongs-to entity) "-id]\n"
                 "    (:n (db/execute-one! db-ctx {:select [[:%count.* :n]] :from [:" table-name "]\n"
                 "                                 :where [:= :" (:belongs-to entity) "-id " (:belongs-to entity) "-id]})))"))
          ")\n"
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
          "                      (" delete " [_ _id] nil)"
          (when (:primary? entity true) "\n                      (transact [_ f] (f))")
          (when-let [c (count-fn entity)] (str "\n                      (" c " [_ _id] 0)"))
          ")\n"
          (if (:workflow entity)
            (str "          started (atom [])\n"
                 "          workflow (reify ports/I" entity-name "Workflow\n"
                 "                     (start-" entity-lower "-workflow! [_ id] (swap! started conj id))\n"
                 "                     (" entity-lower "-workflow-state [_ _id] nil)\n"
                 "                     (remove-" entity-lower "-workflow! [_ _id] nil)\n"
                 "                     (transition-" entity-lower "-workflow! [_ _id _transition _actor] nil))\n"
                 "          svc (service/create-service mock-repo workflow)\n"
                 "          result (ports/create-" entity-lower " svc {:name \"Test\"})]\n"
                 "      (is (some? result))\n"
                 "      (is (= [(:id result)] @started) \"its workflow is started\"))))\n")
            (str "          svc (service/create-service mock-repo)\n"
                 "          result (ports/create-" entity-lower " svc {:name \"Test\"})]\n"
                 "      (is (some? result)))))\n"))))))

(defn- sample-value
  "A literal the field's column accepts and reads back unchanged, as source
   text, or nil for a type that does not read back as it was written."
  [field]
  (case (:field-type field)
    (:string :text) "\"Test\""
    :email          "\"test@example.com\""
    :int            "1"
    :decimal        "9.99M"
    :boolean        "true"
    (:uuid :relation) "(UUID/randomUUID)"
    :enum           (str (second (:malli-type field)))
    :date           "\"2026-01-01\""
    nil))

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
         entity-lower (template/pascal->kebab entity-name)
         {:keys [create find-by-id]} (repo-fns entity)
         fields    (:fields entity)
         relation? (some :relation-table fields)
         ;; Compared after the round trip. A timestamp comes back in the
         ;; driver's type and JSON has no portable column value, so those two
         ;; are left out of the comparison.
         compared  (keep #(when-let [v (sample-value %)] (str ":" (:field-name-kebab %) " " v)) fields)
         opaque    (str/join " " (for [f fields :when (= :json (:field-type f))]
                                   (str ":" (:field-name-kebab f))))
         ;; Written but not compared: a NOT NULL column still needs a value.
         instants  (concat
                    (for [f fields :when (= :inst (:field-type f))]
                      (str " :" (:field-name-kebab f) " (Instant/now)"))
                    (for [f fields :when (and (nil? (sample-value f)) (not= :inst (:field-type f))
                                              (:field-required f))]
                      (str " :" (:field-name-kebab f) " \"{}\"")))]
     (str "(ns " base-ns "." module-name ".shell." entity-lower "-repository-test\n"
          "  (:require [clojure.test :refer [deftest testing is]]\n"
          "            [" base-ns "." module-name "." (shell-ns entity :persistence-ns) " :as persistence]\n"
          "            [" base-ns "." module-name ".ports :as ports]\n"
          (when relation? "            [wagoe.platform.database :as db]\n")
          "            [wagoe.platform.shell.adapters.database.factory :as db-factory]\n"
          "            [wagoe.platform.shell.database.migrations :as migrations])\n"
          "  (:import [java.time Instant]\n"
          "           [java.util UUID]))\n"
          "\n"
          "(defn- with-database\n"
          "  \"Call `f` with a context on a fresh in-memory H2 database, migrated.\"\n"
          "  [f]\n"
          "  (let [ctx (db-factory/db-context {:adapter :h2\n"
          "                                    :database-path (str \"mem:\" (UUID/randomUUID))\n"
          "                                    :pool {:minimum-idle 1 :maximum-pool-size 2}})]\n"
          "    (try\n"
          "      (migrations/migrate-datasource! (:datasource ctx))\n"
          "      (f ctx)\n"
          "      (finally (db-factory/close-db-context! ctx)))))\n"
          "\n"
          "(deftest ^:integration create-" entity-lower "-test\n"
          "  (testing \"the repository implements its persistence port\"\n"
          "    (is (satisfies? ports/I" entity-name "Repository\n"
          "                    (persistence/create-repository nil))))\n"
          "  (testing \"a " entity-lower " round-trips through the database\"\n"
          "    (with-database\n"
          "      (fn [ctx]\n"
          (when relation?
            (str "        ;; The rows it refers to are not what this tests.\n"
                 "        (db/execute-ddl! ctx \"SET REFERENTIAL_INTEGRITY FALSE\")\n"))
          "        (let [repo    (persistence/create-repository ctx)\n"
          "              fields  {" (str/join "\n                       " compared) "}\n"
          "              created (ports/" create " repo (assoc fields :id (UUID/randomUUID) :created-at (Instant/now)"
          (apply str instants) "))]\n"
          "          (is (= fields (select-keys created (keys fields))))\n"
          (if (seq opaque)
            ;; A JSON column reads back as the driver's own type, which `=`
            ;; compares by identity.
            (str "          (is (= (dissoc created " opaque ")\n"
                 "                 (dissoc (ports/" find-by-id " repo (:id created)) " opaque "))))))))\n")
            (str "          (is (= created (ports/" find-by-id " repo (:id created)))))))))\n"))))))

;; =============================================================================
;; Workflow (BOU-569)
;; =============================================================================
;;
;; `--workflow status:entered>delivered>paid` makes the status a workflow: a
;; definition that moves one state forward at a time, registered through the
;; workflow module's registry port by the module's wiring. The service starts an
;; instance on every create; the admin creates rows without the service, so its
;; `:admin/entity-created` events start theirs. A hook writes each new state to
;; the status column, which no request may set.

(defn- state-label [s]
  (str/capitalize (str/replace s "-" " ")))

(defn generate-workflow-file
  "shell/<entity>_workflow.clj: the definition, its registration and the
   adapter the service drives it through.

   Pure: true"
  [ctx entity]
  (let [base-ns (:base-ns ctx "wagoe")
        module-name (:module-name ctx)
        e (:entity-kebab entity)
        entity-name (:entity-name entity)
        {:keys [field states]} (:workflow entity)
        {:keys [update find-by-id]} (repo-fns entity)
        kw #(str ":" %)]
    (str "(ns " base-ns "." module-name ".shell." e "-workflow\n"
         "  \"" entity-name " " field " as a workflow: " (str/join " -> " states) ", forward only.\n"
         "   The " field " column mirrors the workflow's state, and a transition is the\n"
         "   only thing that moves it.\"\n"
         "  (:require [" base-ns "." module-name ".ports :as ports]\n"
         "            [wagoe.events.ports :as events]\n"
         "            [wagoe.workflow.ports :as workflow]))\n"
         "\n"
         "(def definition\n"
         "  {:id            :" e "-workflow\n"
         "   :description   \"" entity-name " " field ", forward only\"\n"
         "   :initial-state " (kw (first states)) "\n"
         "   :states        #{" (str/join " " (map kw states)) "}\n"
         "   :state-config  {" (str/join "\n                   "
                                         (for [st states] (str (kw st) " {:label \"" (state-label st) "\"}"))) "}\n"
         "   :transitions   [" (str/join "\n                   "
                                         (for [[from to] (partition 2 1 states)]
                                           (str "{:from " (kw from) " :to " (kw to) " :label \"" (state-label to) "\"}"))) "]})\n"
         "\n"
         "(def ^:private entity-type :" e ")\n"
         "\n"
         "(defn- ->uuid [id] (cond-> id (string? id) parse-uuid))\n"
         "\n"
         "(defn- instance-of [store id]\n"
         "  (workflow/find-instance-by-entity store entity-type (->uuid id)))\n"
         "\n"
         "(defn- state-of\n"
         "  \"The state the row's " field " holds: the first for a new row, any for a\n"
         "   seeded one (bb db:seed).\"\n"
         "  [repository id]\n"
         "  (let [state (or (:" field " (ports/" find-by-id " repository (->uuid id))) (:initial-state definition))]\n"
         "    (when-not (contains? (:states definition) state)\n"
         "      (throw (ex-info (str \"" entity-name " \" id \" has " field " \" (name state) \", which is not a state of its workflow\")\n"
         "                      {:type :validation-error :id id :" field " state})))\n"
         "    state))\n"
         "\n"
         "(defrecord " entity-name "Workflow [engine store repository bus subscription]\n"
         "  ports/I" entity-name "Workflow\n"
         "  (start-" e "-workflow! [_ id]\n"
         "    ;; Once: an admin event may be delivered twice. In the state the row\n"
         "    ;; holds, so a row seeded as paid is not reset to the first.\n"
         "    (or (instance-of store id)\n"
         "        (let [state    (state-of repository id)\n"
         "              instance (workflow/start-workflow! engine {:workflow-id (:id definition)\n"
         "                                                         :entity-type entity-type\n"
         "                                                         :entity-id   (->uuid id)})]\n"
         "          (if (= state (:current-state instance))\n"
         "            instance\n"
         "            (do (workflow/update-instance-state! store (:id instance) state)\n"
         "                (assoc instance :current-state state))))))\n"
         "  (remove-" e "-workflow! [_ id]\n"
         "    (when-let [instance (instance-of store id)]\n"
         "      (workflow/delete-instance! store (:id instance))))\n"
         "  (" e "-workflow-state [this id]\n"
         "    (:current-state (ports/start-" e "-workflow! this id)))\n"
         "  (transition-" e "-workflow! [this id transition actor]\n"
         "    ;; Started here too: a row the admin wrote while the event bus was down\n"
         "    ;; has none yet.\n"
         "    (let [instance (ports/start-" e "-workflow! this id)]\n"
         "      (workflow/transition! engine {:instance-id (:id instance)\n"
         "                                    :transition  transition\n"
         "                                    :actor-id    (:id actor)\n"
         "                                    :actor-roles (filterv some? [(some-> (:role actor) keyword)])})))\n"
         "\n"
         "  java.lang.AutoCloseable\n"
         "  (close [_]\n"
         "    (when subscription (events/unsubscribe! bus subscription))))\n"
         "\n"
         "(defn- mirror-" field "\n"
         "  \"The hook that writes each new state to the " field " column when a\n"
         "   transition comes from outside the service: the workflow API or the admin.\n"
         "   The service writes it itself, and repairs what a failed hook left.\"\n"
         "  [repository]\n"
         "  (fn [instance _audit-entry _context]\n"
         "    (ports/" update " repository {:id (:entity-id instance) :" field " (:current-state instance)})))\n"
         "\n"
         "(defn install!\n"
         "  \"Register the workflow through the workflow module's registry port, with\n"
         "   the hook that mirrors its state, and return what the service drives it\n"
         "   through. With `bus`, every " e " the admin creates gets one too: the\n"
         "   admin writes rows without the service. `component` is :wagoe/workflow.\"\n"
         "  [{:keys [registry engine store] :as component} repository bus]\n"
         "  (when-not component\n"
         "    (throw (ex-info \"" entity-name " has a workflow and the workflow module is off: run `wagoe add workflow`\"\n"
         "                    {:type :configuration-error})))\n"
         "  (workflow/register-workflow! registry (assoc definition :hooks {:on-any-transition [(mirror-" field " repository)]}))\n"
         "  (let [adapter (->" entity-name "Workflow engine store repository bus nil)\n"
         "        ;; What bb db:seed calls with the rows it inserted, by table.\n"
         "        adapter (assoc adapter :seed (fn [inserted]\n"
         "                                       (doseq [{:keys [id]} (get inserted \"" (:entity-table entity) "\")]\n"
         "                                         (ports/start-" e "-workflow! adapter id))))]\n"
         "    (cond-> adapter\n"
         "      bus (assoc :subscription\n"
         "                 (events/subscribe! bus :admin\n"
         "                                    (fn [{:keys [type payload]}]\n"
         "                                      (when (and (= :admin/entity-created type)\n"
         "                                                 (= :" (:entity-plural entity) " (:entity payload)))\n"
         "                                        (ports/start-" e "-workflow! adapter (:id payload)))))))))\n")))

(defn generate-workflow-test-file
  "test/.../shell/<entity>_workflow_test.clj: the transitions, and the status
   following them through the service on H2.

   Pure: true"
  [ctx entity]
  (let [base-ns (:base-ns ctx "wagoe")
        module-name (:module-name ctx)
        e (:entity-kebab entity)
        {:keys [field states]} (:workflow entity)
        kw #(str ":" %)
        fields (remove :workflow-state? (:fields entity))
        relation? (some :relation-table fields)
        data (concat
              (keep #(when-let [v (sample-value %)] (str ":" (:field-name-kebab %) " " v)) fields)
              (for [f fields :when (= :inst (:field-type f))]
                (str ":" (:field-name-kebab f) " (Instant/now)"))
              (for [f fields :when (and (nil? (sample-value f)) (not= :inst (:field-type f)) (:field-required f))]
                (str ":" (:field-name-kebab f) " \"{}\"")))
        [first-state second-state third-state] states]
    (str "(ns " base-ns "." module-name ".shell." e "-workflow-test\n"
         "  (:require [clojure.test :refer [deftest testing is]]\n"
         "            [integrant.core :as ig]\n"
         "            [" base-ns "." module-name "." (shell-ns entity :persistence-ns) " :as persistence]\n"
         "            [" base-ns "." module-name "." (shell-ns entity :service-ns) " :as service]\n"
         "            [" base-ns "." module-name ".ports :as ports]\n"
         "            [" base-ns "." module-name ".shell." e "-workflow :as " e "-workflow]\n"
         (when relation? "            [wagoe.platform.database :as db]\n")
         "            [wagoe.platform.shell.adapters.database.factory :as db-factory]\n"
         "            [wagoe.platform.shell.database.migrations :as migrations]\n"
         "            [wagoe.workflow.core.transitions :as transitions]\n"
         "            [wagoe.workflow.shell.module-wiring])\n"
         (if (some #(str/includes? % "Instant/") data)
           "  (:import [java.time Instant]\n           [java.util UUID]))\n"
           "  (:import [java.util UUID]))\n")
         "\n"
         "(defn- allowed? [from to]\n"
         "  (:allowed? (transitions/can-transition? " e "-workflow/definition from to nil nil nil)))\n"
         "\n"
         "(deftest ^:unit the-" field "-only-moves-forward\n"
         (str/join (for [[from to] (partition 2 1 states)]
                     (str "  (is (allowed? " (kw from) " " (kw to) "))\n")))
         (when third-state
           (str "  (is (not (allowed? " (kw first-state) " " (kw third-state) ")) \"no skipping\")\n"))
         "  (is (not (allowed? " (kw second-state) " " (kw first-state) ")) \"no going back\"))\n"
         "\n"
         "(defn- with-service\n"
         "  \"Call `f` with the " e " service on a fresh H2 database, its workflow installed.\"\n"
         "  [f]\n"
         "  (let [ctx (db-factory/db-context {:adapter :h2\n"
         "                                    :database-path (str \"mem:\" (UUID/randomUUID))\n"
         "                                    :pool {:minimum-idle 1 :maximum-pool-size 2}})]\n"
         "    (try\n"
         "      (migrations/migrate-datasource! (:datasource ctx))\n"
         (when relation?
           (str "      ;; The rows it refers to are not what this tests.\n"
                "      (db/execute-ddl! ctx \"SET REFERENTIAL_INTEGRITY FALSE\")\n"))
         "      (ig/init-key :wagoe/workflow-db-schema {:ctx ctx})\n"
         "      (let [component  (ig/init-key :wagoe/workflow {:db-ctx ctx :db-schema {} :guard-registry {}})\n"
         "            repository (persistence/create-repository ctx)]\n"
         "        (f (service/create-service repository (" e "-workflow/install! component repository nil))))\n"
         "      (finally (db-factory/close-db-context! ctx)))))\n"
         "\n"
         "(deftest ^:integration the-" field "-follows-the-workflow\n"
         "  (with-service\n"
         "    (fn [svc]\n"
         "      (let [created (ports/create-" e " svc {" (str/join "\n                                    " data) "})\n"
         "            move    #(ports/transition-" e " svc (:id created) % {:role :user})]\n"
         "        (is (= " (kw first-state) " (:" field " created)))\n"
         "        (testing \"a move the workflow does not make is refused, and the " field " stays\"\n"
         "          (is (false? (:success? (move " (kw first-state) "))))\n"
         "          (is (= " (kw first-state) " (:" field " (ports/get-" e " svc (:id created))))))\n"
         "        (testing \"each step forward moves the " field " with it\"\n"
         (str/join "\n" (for [st (rest states)]
                          (str "          (is (= " (kw st) " (:" field " (:" e " (move " (kw st) ")))))")))
         ;; Closes the testing, let, fn, with-service and deftest.
         ")))))\n")))

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
   "[malli.error :as me]"
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
   turn JSON strings into UUIDs. Errors are thrown with a :type, so the
   platform answers them in the one shape it uses for every other error.

   Unless `public?`, every route requires a signed-in user. The platform
   enforces that for any route not marked `:public true` (BOU-568); the guard
   named here says so in the module, by symbol, so it requires nothing from
   another module's shell."
  [entity public?]
  (let [entity-name (:entity-name entity)
        e (or (:entity-kebab entity) (template/pascal->kebab entity-name))
        plural (or (:entity-plural entity) (template/pluralize e))
        guard (if public? "\n            :public true" "\n            :interceptors signed-in")
        guard-id (if public? "\n              :public true" "\n              :interceptors signed-in")]
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
         ";; Thrown, not returned: the platform maps :type to the status and answers\n"
         ";; in the shape it uses for every error, a missing reference included.\n"
         ";; `errors` lands in the body's details. Keys are kebab-case: an unknown\n"
         ";; one, camelCase included, is dropped, and its field reported missing.\n"
         "(defn- invalid [errors]\n"
         "  (throw (ex-info \"Invalid " e "\" {:type :validation-error :errors errors})))\n"
         "\n"
         "(defn- explain [schema data]\n"
         "  (me/humanize (m/explain schema data)))\n"
         "\n"
         "(defn- not-found []\n"
         "  (throw (ex-info \"No such " e "\" {:type :not-found})))\n"
         "\n"
         "(defn- id-of [request]\n"
         "  (some-> (get-in request [:path-params :id]) parse-uuid))\n"
         "\n"
         "(def ^:private max-page 100)\n"
         "\n"
         "(defn- page-of\n"
         "  \"limit and offset from the query string. A missing or malformed value is\n"
         "   the default, and limit is capped so one request cannot read the table.\"\n"
         "  [request]\n"
         "  (let [n (fn [k default]\n"
         "            (let [v (get-in request [:query-params k])]\n"
         "              (or (when (string? v) (parse-long v)) default)))]\n"
         "    {:limit  (-> (n \"limit\" 20) (max 1) (min max-page))\n"
         "     :offset (max 0 (n \"offset\" 0))}))\n"
         "\n"
         (if public?
           ";; Public: these routes answer anyone, signed in or not (--public-api).\n"
           (str ";; Every route requires a signed-in user and answers 401 without one.\n"
                ";; Generate with --public-api for routes open to anyone.\n"
                "(def ^:private signed-in ['wagoe.user.shell.http-interceptors/require-authenticated])\n"))
         "\n"
         "(defn api-routes\n"
         "  \"Reitit route data. Paths are relative — the platform mounts them under /api/v1.\"\n"
         "  [service]\n"
         "  [[\"/" plural "\"\n"
         "    {:get  {:summary \"List " plural ", oldest first\"" guard "\n"
         "            :swagger {:parameters [{:name \"limit\" :in \"query\" :required false :type \"integer\"\n"
         "                                    :description \"Default 20, at most 100\"}\n"
         "                                   {:name \"offset\" :in \"query\" :required false :type \"integer\"}]}\n"
         "            :handler (fn [request]\n"
         "                       {:status 200 :body (ports/list-" e "s service (page-of request))})}\n"
         "     :post {:summary \"Create a " e "\"" guard "\n"
         "            :handler (fn [request]\n"
         "                       (let [data (decode-create (:body-params request))]\n"
         "                         (if (valid-create? data)\n"
         "                           {:status 201 :body (ports/create-" e " service data)}\n"
         "                           (invalid (explain schema/Create" entity-name "Request data)))))}}]\n"
         "   [\"/" plural "/:id\"\n"
         "    {:swagger {:parameters [{:name \"id\" :in \"path\" :required true :type \"string\"}]}\n"
         "     :get    {:summary \"Get a " e "\"" guard-id "\n"
         "              :handler (fn [request]\n"
         "                         (if-let [found (some->> (id-of request) (ports/get-" e " service))]\n"
         "                           {:status 200 :body found}\n"
         "                           (not-found)))}\n"
         "     :put    {:summary \"Update a " e "\"" guard-id "\n"
         "              :handler (fn [request]\n"
         "                         (let [id   (id-of request)\n"
         "                               data (decode-update (:body-params request))]\n"
         "                           (cond\n"
         "                             (nil? id)                 (not-found)\n"
         "                             (empty? data)             (invalid {:body [\"no field it knows\"]})\n"
         "                             (not (valid-update? data)) (invalid (explain schema/Update" entity-name "Request data))\n"
         "                             :else (if-let [updated (ports/update-" e " service id data)]\n"
         "                                     {:status 200 :body updated}\n"
         "                                     (not-found)))))}\n"
         "     :delete {:summary \"Delete a " e "\"" guard-id "\n"
         "              :handler (fn [request]\n"
         "                         (if-let [id (id-of request)]\n"
         "                           (do (ports/delete-" e " service id) {:status 204})\n"
         "                           (not-found)))}}]"
         (when-let [wf (:workflow entity)]
           (str "\n"
                "   ;; The one way to change " (:field wf) ": a body of {\"transition\": \"<state>\"}.\n"
                "   [\"/" plural "/:id/transition\"\n"
                "    {:swagger {:parameters [{:name \"id\" :in \"path\" :required true :type \"string\"}]}\n"
                "     :post {:summary \"Move a " e "'s " (:field wf) " along its workflow\"" guard "\n"
                "            :handler (fn [request]\n"
                "                       (let [id         (id-of request)\n"
                "                             transition (some-> (get-in request [:body-params :transition]) keyword)]\n"
                "                         (cond\n"
                "                           (nil? id)         (not-found)\n"
                "                           (nil? transition) (invalid {:transition [\"missing\"]})\n"
                "                           :else\n"
                "                           (let [result (ports/transition-" e " service id transition (:user request))]\n"
                "                             (cond\n"
                "                               (nil? result)      (not-found)\n"
                "                               (:success? result) {:status 200 :body (:" e " result)}\n"
                "                               ;; Not a move the workflow makes from where it is.\n"
                "                               :else              {:status 422 :body {:error (:error result)}})))))}}]"))
         "])\n")))

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
         (api-section entity (get-in ctx [:interfaces :public-api] false)))))

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
        {:keys [http web]} (:interfaces ctx {:http true :web true})
        ;; One switch for the module's routes, API and page alike.
        public? (get-in ctx [:interfaces :public-api] false)]
    (str (ns-form (str base-ns "." module-name ".shell.http")
                  (str "HTTP routes for " module-name " module.")
                  ;; Only what is used: an unused require is a clj-kondo
                  ;; warning, and `bb check` fails on those (BOU-267).
                  (concat (when http (api-requires base-ns module-name))
                          (when web [(str "[" base-ns "." module-name ".shell.web-handlers :as web-handlers]")])))
         "\n"
         (when http (str (api-section entity public?) "\n"))
         (when web
           (str (if public?
                  ";; Public: this page answers anyone, signed in or not (--public-api).\n"
                  (str ";; Signed-in users only; anyone else is sent to /web/login.\n"
                       "(def ^:private signed-in-page ['wagoe.user.shell.http-interceptors/require-web-authenticated])\n"))
                "\n"
                "(defn web-routes\n"
                "  \"Mounted under /web — do not repeat the prefix here.\"\n"
                "  [service config]\n"
                "  [[\"/" entity-plural "\"\n"
                "    {:get {" (if public? ":public true\n           " ":interceptors signed-in-page\n           ")
                ":handler (web-handlers/" entity-lower "-list-handler service config)}}]])\n"
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
      (conj {:path (src (str "shell." e "-http")) :content (generate-entity-http-file ctx entity)})

      (:workflow entity)
      (conj {:path (src (str "shell." e "-workflow")) :content (generate-workflow-file ctx entity)}
            {:path (tst (str "shell." e "-workflow-test")) :content (generate-workflow-test-file ctx entity)}))))

;; =============================================================================
;; Incremental Generators - Add Field
;; =============================================================================

(defn- workflow-service-wiring
  "The service's init and halt for a first entity with a workflow: it is
   installed with the service, and its admin subscription closed with it."
  [module-name entity]
  (let [e (:entity-kebab entity)]
    (str "(defmethod ig/init-key :wagoe/" module-name "-service\n"
         "  [_ {:keys [repository workflow events entities]}]\n"
         "  (log/info \"Initializing " module-name " service\")\n"
         "  ;; " (:entity-name entity) "'s " (get-in entity [:workflow :field])
         " is a workflow, registered here and started on every create.\n"
         "  ;; Its create takes the entities that belong to it (entity-wiring below).\n"
         "  (service/create-service repository (" e "-workflow/install! workflow repository events)\n"
         "                          (into {} (keep :child) (vals entities))))\n"
         "\n"
         "(defmethod ig/halt-key! :wagoe/" module-name "-service\n"
         "  [_ service]\n"
         "  (.close ^java.lang.AutoCloseable (:workflow service))\n"
         "  (log/info \"" module-name " service halted\"))")))

(defn- module-wiring-base
  "shell/module_wiring.clj for the module's first entity, before any further
   entity is wired in.

   Pure: true"
  [ctx]
  (let [base-ns     (:base-ns ctx "wagoe")
        module-name (:module-name ctx)
        entity      (first (:entities ctx))
        workflow?   (and (:primary? entity) (:workflow entity))]
    (cond-> (format "(ns %s.%s.shell.module-wiring
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
                    module-name module-name)
      workflow?
      (-> (str/replace (str "            [" base-ns "." module-name ".shell.service :as service]\n")
                       (str "            [" base-ns "." module-name ".shell.service :as service]\n"
                            "            [" base-ns "." module-name ".shell." (:entity-kebab entity)
                            "-workflow :as " (:entity-kebab entity) "-workflow]\n"))
          (str/replace (str "(defmethod ig/init-key :wagoe/" module-name "-service\n"
                            "  [_ {:keys [repository]}]\n"
                            "  (log/info \"Initializing " module-name " service\")\n"
                            "  (service/create-service repository))\n"
                            "\n"
                            "(defmethod ig/halt-key! :wagoe/" module-name "-service\n"
                            "  [_ _service]\n"
                            "  (log/info \"" module-name " service halted\"))")
                       (workflow-service-wiring module-name entity))))))

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

(defn- service-init-form?
  [module-name loc]
  (= ['defmethod 'ig/init-key (keyword "wagoe" (str module-name "-service"))]
     (form-head loc)))

(defn- wiring-service-form
  "The service init-key that hands the service the entities belonging to it."
  [module-name]
  (str "(defmethod ig/init-key :wagoe/" module-name "-service\n"
       "  [_ {:keys [repository entities]}]\n"
       "  (log/info \"Initializing " module-name " service\")\n"
       "  ;; Its create takes the entities that belong to it (entity-wiring below).\n"
       "  (service/create-service repository (into {} (keep :child) (vals entities))))"))

(defn- wiring-seam-section
  [module-name]
  (let [k #(str ":wagoe/" module-name %)]
    (str (banner "Further entities (bb scaffold entity)")
         ";; One entity-wiring method per entity. ig-config builds each one's\n"
         ";; repository and service under " (k "-entities") ", and the routes\n"
         ";; init-key above mounts their API routes with the module's.\n"
         "\n"
         "(defmulti entity-wiring\n"
         "  \"{:repository f :service f :routes f :workflow f :child-of m} for one further entity.\n"
         "   :workflow, for an entity with one, installs it: (f workflow repository events).\n"
         "   :child-of, for one that belongs to the first entity, says how its create makes it.\"\n"
         "  identity)\n"
         "\n"
         "(defmethod ig/init-key " (k "-entities") "\n"
         "  [_ {:keys [ctx workflow events]}]\n"
         "  (into {}\n"
         "        (for [entity (keys (methods entity-wiring))\n"
         "              :let [{:keys [repository service routes child-of] install :workflow} (entity-wiring entity)\n"
         "                    repo (repository ctx)\n"
         "                    svc  (if install (service repo (install workflow repo events)) (service repo))]]\n"
         "          ;; :child is what the first entity's create needs to make this one.\n"
         "          [entity (cond-> {:service svc :routes routes}\n"
         "                    child-of (assoc :child [(:key child-of)\n"
         "                                            {:foreign-key (:foreign-key child-of)\n"
         "                                             :create      #((:create child-of) svc %)}]))])))\n"
         "\n"
         "(defmethod ig/halt-key! " (k "-entities") "\n"
         "  [_ entities]\n"
         "  (doseq [{:keys [service]} (vals entities)\n"
         "          :let [workflow (:workflow service)]\n"
         "          :when (instance? java.lang.AutoCloseable workflow)]\n"
         "    (.close ^java.lang.AutoCloseable workflow)))\n"
         "\n"
         ";; bb db:seed inserts rows without the services, then initialises what\n"
         ";; derives from :wagoe/seed-hook and calls it with those rows. Each entity\n"
         ";; with a workflow starts one for its rows, in the state they hold.\n"
         "(derive " (k "-seed") " :wagoe/seed-hook)\n"
         "\n"
         "(defmethod ig/init-key " (k "-seed") "\n"
         "  [_ {:keys [service entities]}]\n"
         "  (let [hooks (keep #(get-in % [:workflow :seed]) (cons service (map :service (vals entities))))]\n"
         "    (fn [inserted] (doseq [hook hooks] (hook inserted)))))\n"
         "\n"
         "(defn- switched-on?\n"
         "  \"Whether the loaded config's :active switches module `k` on.\"\n"
         "  [config k]\n"
         "  (let [v (get-in config [:active k])]\n"
         "    (and (some? v) (not (false? (:enabled? v))))))\n"
         "\n"
         "(defn ig-config\n"
         "  \"This module's Integrant graph: the four keys platform discovery would\n"
         "   build, plus " (k "-entities") ". With the workflow module on, the services\n"
         "   get it, and the event bus the admin publishes to when both are on.\"\n"
         "  [settings {:keys [config]}]\n"
         "  (let [workflow (when (switched-on? config :wagoe/workflow)\n"
         "                   {:workflow (ig/ref :wagoe/workflow)\n"
         "                    :events   (when (and (switched-on? config :wagoe/events)\n"
         "                                         (switched-on? config :wagoe/admin))\n"
         "                                (ig/ref :wagoe/events))})]\n"
         "    {:components\n"
         "     (cond->\n"
         "      {" (k "-repository") "\n"
         "      {:ctx (ig/ref :wagoe/db-context)}\n"
         "      " (k "-service") "\n"
         "      (merge {:repository (ig/ref " (k "-repository") ")\n"
         "              :entities   (ig/ref " (k "-entities") ")}\n"
         "             workflow)\n"
         "      " (k "-entities") "\n"
         "      (merge {:ctx (ig/ref :wagoe/db-context)} workflow)\n"
         "      " (k "-routes") "\n"
         "      {:service  (ig/ref " (k "-service") ")\n"
         "       :entities (ig/ref " (k "-entities") ")\n"
         "       :config   settings}\n"
         "      " (k "") "\n"
         "      {:enabled? true\n"
         "       :service  (ig/ref " (k "-service") ")\n"
         "       :routes   (ig/ref " (k "-routes") ")}}\n"
         "      workflow\n"
         "      (assoc " (k "-seed") " {:service  (ig/ref " (k "-service") ")\n"
         "                               :entities (ig/ref " (k "-entities") ")}))}))\n")))

(defn- entity-wiring-section
  [ctx entity]
  (let [e (:entity-kebab entity)]
    (str (banner (:entity-name entity))
         "(defmethod entity-wiring :" e "\n"
         "  [_]\n"
         "  {:repository " e "-persistence/create-repository\n"
         "   :service    " e "-service/create-service"
         (when (http? ctx)
           (str "\n   :routes     " e "-http/api-routes"))
         (when (:workflow entity)
           (str "\n   :workflow   " e "-workflow/install!"))
         (when (:child-of entity)
           (str "\n   ;; Created with its " (:belongs-to entity) " too: its create takes :" (:entity-plural entity) ".\n"
                "   :child-of   {:key         :" (:entity-plural entity) "\n"
                "                :foreign-key :" (:belongs-to entity) "-id\n"
                "                :create      ports/create-" e "}"))
         "})\n")))

(defn- entity-requires
  "[alias namespace] pairs the entity's wiring section needs."
  [ctx entity]
  (let [prefix (str (:base-ns ctx "wagoe") "." (:module-name ctx) ".")
        e      (:entity-kebab entity)]
    (cond-> (vec (for [part (cond-> ["persistence" "service"]
                              (http? ctx)        (conj "http")
                              (:workflow entity) (conj "workflow"))]
                   [(symbol (str e "-" part))
                    (symbol (str prefix (if (#{"http" "workflow"} part)
                                          (str "shell." e "-" part)
                                          (shell-ns entity (keyword (str part "-ns"))))))]))
      (:child-of entity) (conj ['ports (symbol (str prefix "ports"))]))))

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

(defn- install-seam
  "`source` with the seam described above, or an :error."
  [source ctx]
  (let [module-name (:module-name ctx)
        base        (top-level-forms (module-wiring-base ctx))
        form-of     (fn [pred forms] (some #(when (pred module-name %) %) forms))
        loc         (form-of routes-init-form? (top-level-forms source))
        expected    (z/sexpr (form-of routes-init-form? base))
        svc         (form-of service-init-form? (top-level-forms source))
        svc-wanted  (z/sexpr (form-of service-init-form? base))]
    (cond
      (contains? (defined-symbols source) 'ig-config)
      {:error "it already defines ig-config, so the module builds its own graph"}

      (or (nil? loc) (not= expected (z/sexpr loc)))
      {:error (str "its :wagoe/" module-name "-routes init-key is not the one bb scaffold"
                   " generate wrote, and wiring an entity replaces it")}

      (or (nil? svc) (not= svc-wanted (z/sexpr svc)))
      {:error (str "its :wagoe/" module-name "-service init-key is not the one bb scaffold"
                   " generate wrote, and wiring an entity replaces it")}

      :else
      (let [routes-done (z/root-string (z/replace loc (z/node (z/of-string (wiring-routes-form module-name)))))
            ;; A workflow's service init already takes the entities.
            svc-done    (if (str/includes? (z/string svc) "(keep :child)")
                          routes-done
                          (z/root-string (z/replace (form-of service-init-form? (top-level-forms routes-done))
                                                    (z/node (z/of-string (wiring-service-form module-name))))))]
        {:content (append-section svc-done (wiring-seam-section module-name))}))))

(defn generate-module-wiring-file
  "Generate shell/module_wiring.clj file content.

   The scaffolder emitted every other file a module needs and not this one, so
   `bb scaffold integrate` always reported that the module had no wiring yet and
   the user hand-wrote the Integrant keys the framework says never to hand-write
   (BOU-309). A first entity with a workflow gets the seam's `ig-config` at
   once: it is what hands the service the workflow module.

   Pure: true"
  [ctx]
  (let [base   (module-wiring-base ctx)
        entity (first (:entities ctx))]
    (if (and (:primary? entity) (:workflow entity))
      (:content (install-seam base ctx))
      base)))

(defn add-entity-to-wiring
  "`source` — a module_wiring.clj — with `entity` wired in.

   Returns {:content s} or {:error reason}. The first further entity also
   installs the seam described above, and refuses when the routes init-key is
   not the one `generate` wrote: replacing it would drop the edit.

   Pure: true"
  [source ctx entity]
  (try
    (let [module-name (:module-name ctx)
          seam?       (contains? (defined-symbols source) 'entity-wiring)
          k           (keyword (:entity-kebab entity))
          installed   (if seam? {:content source} (install-seam source ctx))]
      (cond
        (:error installed) installed

        ;; A seam from before BOU-569 builds each entity's service with one
        ;; argument, so a workflow wired into it would fail at boot.
        (and (:workflow entity)
             (not (contains? (defmethod-dispatches (:content installed) 'ig/halt-key!)
                             (keyword "wagoe" (str module-name "-entities")))))
        {:error (str "it wires entities as it did before workflows. Replace its :wagoe/"
                     module-name "-entities init-key and ig-config with what a new `bb scaffold"
                     " generate` writes, then run this again")}

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
        index (if (or relation-table (:field-indexed field-ctx))
                (str statement-separator (index-sql table-name field-name) "\n")
                "\n")]
    (format "-- Migration %s: Add %s to %s table

ALTER TABLE %s ADD COLUMN %s %s%s%s%s%s;%s"
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
            index)))

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

(defn children-entry
  "The entry the first entity's Create request gets for `child`, an entity
   that belongs to it: its children, each with the child's create fields but
   the parent's id, which the create fills in. Required, with at least :min of
   them, when the child has a minimum (BOU-578).

   Pure: true"
  [child]
  (let [fk     (str (:belongs-to child) "-id")
        fields (remove #(or (:workflow-state? %) (= fk (:field-name-kebab %))) (:fields child))
        item   (str "[:map " (str/join " " (map #(str/trim (generate-field-schema %)) fields)) "]")]
    (if-let [n (:min child)]
      (str "[:" (:entity-plural child) " [:vector {:min " n "} " item "]]")
      (str "[:" (:entity-plural child) " {:optional true} [:vector " item "]]"))))

(defn add-children-to-schema
  "`source` — a schema.clj — with `child`'s entry in `parent`'s Create
   request. {:content s} or {:error reason}.

   Pure: true"
  [source parent child]
  (let [r (insert-schema-entry source (str "Create" parent "Request") (children-entry child))]
    (case (:status r)
      :inserted {:content (:content r)}
      :present  {:error (str "Create" parent "Request already has " (:entity-plural child))}
      {:error (str "Create" parent "Request is not the [:map ...] bb scaffold generate wrote")})))

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

;; =============================================================================
;; Admin entity config (BOU-562)
;; =============================================================================
;;
;; With the admin UI on, an entity needs three things before the admin shows
;; it: resources/conf/<profile>/admin/<plural>.edn, its name in the admin's
;; :allowlist and an `#include` of that file in :entities. The config files are
;; Aero, so they are edited with rewrite-clj, which reads `#include` and
;; `#merge` as syntax and keeps every comment.

(defn- humanize [s]
  (str/capitalize (str/replace (name s) "-" " ")))

(defn- edn-str
  "`x` printed without the commas pr-str puts between map entries. Only
   generated names go in, so no string holds one."
  [x]
  (str/replace (pr-str x) ", " " "))

(defn sensitive-field?
  "Whether a column holds a secret the admin must not show: the admin's own
   hidden names (`common-hidden-fields` in wagoe.admin.core.schema-introspection)
   and any name with a word that says so.

   Pure: true"
  [k]
  (boolean (or (#{:password-hash :password-encrypted :secret :token :api-key :private-key
                  :salt :hash :search-vector :tsv :fts-vector} k)
               (some #{"password" "hash" "secret" "token" "key" "salt"}
                     (str/split (name k) #"-")))))

(defn admin-display-fields
  "The fields that stand for an entity on another's page: its first two own
   fields, not its keys or secrets.

   Pure: true"
  [field-names]
  (vec (take 2 (remove #(or (#{:id :created-at :updated-at :deleted-at} %)
                            (str/ends-with? (name %) "-id")
                            (sensitive-field? %))
                       field-names))))

(defn schema-field-names
  "The keys of the Malli map in `(def schema-name …)` within `source`, or nil.

   Pure: true"
  [source schema-name]
  (when-let [m (schema-map-zloc source schema-name)]
    (vec (for [child (take-while some? (iterate z/right (z/down m)))
               :when (= :vector (z/tag child))
               :let  [k (first (z/sexpr child))]
               :when (keyword? k)]
           k))))

(defn admin-has-many
  "The editable `:has-many` entry a parent's admin config gets for `child`.

   Pure: true"
  [child]
  (let [fk (keyword (str (:belongs-to child) "-id"))]
    (cond-> {:entity      (keyword (:entity-plural child))
             :table       (keyword (:entity-table child))
             :foreign-key fk
             :label       (humanize (:entity-plural child))
             :fields      (vec (take 4 (remove #(or (= fk %) (sensitive-field? %))
                                               (map (comp keyword :field-name-kebab) (:fields child)))))
             :editable    true
             ;; As the migration's foreign key does: a child has no life without its
             ;; parent. The admin restricts a delete unless told otherwise.
             :on-delete   :cascade}
      ;; The admin refuses fewer (BOU-570).
      (:min child) (assoc :min (:min child)))))

(defn admin-entity-file
  "resources/conf/<profile>/admin/<plural>.edn for `entity`.

   `children` are the entities that belong to it, each getting an editable
   `:has-many`; `parent` is {:name \"Invoice\" :fields [...]} when it belongs
   to one, for the banner on its pages. Deletes are hard, as the generated
   API's are, so a child's ON DELETE CASCADE runs.

   Pure: true"
  [entity {:keys [children parent]}]
  (let [fields  (:fields entity)
        secrets (filterv sensitive-field? (map (comp keyword :field-name-kebab) fields))
        names   (into [] (comp (map (comp keyword :field-name-kebab)) (remove sensitive-field?)) fields)
        search  (vec (for [f fields
                           :let [k (keyword (:field-name-kebab f))]
                           :when (and (#{:string :text :email} (:field-type f)) (not (sensitive-field? k)))]
                       k))
        enums   (into {} (for [f fields :when (= :enum (:field-type f))]
                           [(keyword (:field-name-kebab f))
                            {:type    :enum
                             :widget  :select
                             :options (mapv #(vector % (humanize %)) (rest (:malli-type f)))}]))
        fk      (when (:belongs-to entity) (keyword (str (:belongs-to entity) "-id")))
        ;; A workflow's status is shown, and moved only by its transitions.
        wf      (:workflow entity)
        entry   (fn [k v] (format "%-16s %s" k v))
        entries (cond-> [(entry ":label" (pr-str (humanize (:entity-plural entity))))
                         (entry ":table-name" (str ":" (:entity-plural entity)))
                         (entry ":soft-delete" "false")
                         (entry ":list-fields" (pr-str (conj (vec (remove #{fk} names)) :created-at)))]
                  (seq search) (conj (entry ":search-fields" (pr-str search)))
                  ;; Secrets listed, not left to the admin's detection: a
                  ;; manual :hide-fields replaced the set it detects.
                  :always      (conj (entry ":hide-fields" (str "#{" (str/join " " (map str (cons :deleted-at secrets))) "}"))
                                     (entry ":readonly-fields" (str "#{:id :created-at :updated-at"
                                                                    (when wf (str " :" (:field wf))) "}")))
                  wf           (conj (entry ":workflow" (str "{:entity-type :" (:entity-kebab entity) "}")))
                  (seq enums)  (conj (entry ":fields" (edn-str enums)))
                  (seq children)
                  (conj (entry ":has-many" (str "[" (str/join (str "\n" (apply str (repeat 20 " ")))
                                                              (map (comp edn-str admin-has-many) children))
                                                "]")))
                  parent
                  (conj (entry ":parent-context" (edn-str {:label (:name parent) :fields (:fields parent)}))))]
    (str "{:" (:entity-plural entity) "\n"
         " {" (str/join "\n  " entries) "}}\n")))

(defn- key-of [loc]
  (try (z/sexpr loc) (catch Exception _ ::unreadable)))

(defn- children
  "Zippers at the forms inside `loc`, `#_` discards left out: a `#_#_ k v`
   counted as a pair shifts every key after it onto a value."
  [loc]
  (->> (some-> loc z/down) (iterate z/right) (take-while some?)
       (remove #(= :uneval (z/tag %)))))

(defn- map-val
  "Zipper at the value of `k` in the map at `loc`, or nil. Walks key/value
   pairs: `z/get` also matches a value equal to `k`, and :allowlist is both."
  [loc k]
  (when (and loc (= :map (z/tag loc)))
    (some (fn [[kl vl]] (when (= k (key-of kl)) vl))
          (partition 2 (children loc)))))

(defn- column [loc] (dec (second (z/position loc))))

(defn- append-item
  "`loc`'s collection with `node` appended: on a new line at the column of
   its first item when `newline?`, else after a space."
  [loc node newline?]
  (if-let [first-child (first (children loc))]
    (-> loc
        (z/append-child* (if newline? (n/newlines 1) (n/spaces 1)))
        (cond-> newline? (z/append-child* (n/spaces (column first-child))))
        (z/append-child* node))
    (z/append-child* loc node)))

(defn- append-entry
  "The map at `loc` with `k v` added on a line of its own, the value in the
   column the first entry's value is in."
  [loc k v-src]
  (let [first-key (first (children loc))
        col       (or (some-> first-key column) 1)
        val-col   (or (some-> first-key z/right column) 0)]
    (-> loc
        (z/append-child* (n/newlines 1))
        (z/append-child* (n/spaces col))
        (z/append-child* (n/keyword-node k))
        (z/append-child* (n/spaces (max 1 (- val-col col (count (str k))))))
        (z/append-child* (z/node (z/of-string v-src))))))

(defn- admin-node
  "Zipper at the value of :wagoe/admin under :active, whatever its shape."
  [source]
  (-> (z/of-string source {:track-position? true}) (map-val :active) (map-val :wagoe/admin)))

(defn- admin-loc
  "The :wagoe/admin map, when it is a literal map with the admin on. A
   `#profile` or `#include` in its place is not something to edit."
  [source]
  (let [admin (admin-node source)]
    (when (and admin (= :map (z/tag admin))
               (not (false? (some-> (map-val admin :enabled?) key-of))))
      admin)))

(defn admin-active?
  "Whether the config.edn `source` switches the admin UI on. A :wagoe/admin
   that is not a literal map counts: it cannot be read without Aero.

   Pure: true"
  [source]
  (try (let [admin (admin-node source)]
         (boolean (and admin (or (not= :map (z/tag admin)) (admin-loc source)))))
       (catch Exception _ false)))

(defn module-active?
  "Whether the config.edn `source` names module `k` under :active.

   Pure: true"
  [source k]
  (try (some? (-> (z/of-string source) (map-val :active) (map-val k)))
       (catch Exception _ false)))

(defn add-admin-entity
  "config.edn `source` with the entity `plural` in the admin's :allowlist and
   its file in :entities.

   {:status :updated :content s}, :present, :no-admin, or
   {:status :unrecognised :reason s} for an :entities that is not a `#merge`.

   Pure: true"
  [source plural]
  (try
    (let [k       (keyword plural)
          include (str "#include \"admin/" plural ".edn\"")]
      (cond
        (nil? (admin-node source))
        {:status :no-admin}

        (not= :map (z/tag (admin-node source)))
        {:status :unrecognised
         :reason (str ":wagoe/admin is not a literal map, so add :" plural " to its :allowlist and "
                      include " to its :entities yourself")}

        (nil? (admin-loc source))
        {:status :no-admin}

        :else
        (let [allow   (some-> (admin-loc source) (map-val :entity-discovery) (map-val :allowlist))
              step1   (if (and allow (= :set (z/tag allow))
                               (not (some #{k} (map key-of (children allow)))))
                        (z/root-string (append-item allow (n/keyword-node k) false))
                        source)
              admin   (admin-loc step1)
              ents    (map-val admin :entities)
              merged  (when (and ents (= :reader-macro (z/tag ents))
                                 (= "merge" (some-> ents z/down z/string)))
                        (z/right (z/down ents)))
              result  (cond
                        (nil? ents)
                        {:content (z/root-string (append-entry admin :entities (str "#merge [" include "]")))}

                        (not (and merged (= :vector (z/tag merged))))
                        {:reason (str ":entities is not a #merge of #includes, so add " include " to it")}

                        (some #(= include (z/string %)) (children merged))
                        {:content step1}

                        :else
                        {:content (z/root-string (append-item merged (z/node (z/of-string include)) true))})]
          (cond
            (:reason result)            {:status :unrecognised :reason (:reason result)}
            (= source (:content result)) {:status :present}
            :else                        {:status :updated :content (:content result)}))))
    (catch Exception e
      {:status :unrecognised :reason (str "it could not be read: " (.getMessage e))})))

(defn add-admin-has-many
  "The admin entity file `source` of `parent-plural` with an editable
   `:has-many` for `child`. {:status :updated :content s}, :present, or
   {:status :unrecognised :reason s}.

   Pure: true"
  [source parent-plural child]
  (try
    (let [entity (map-val (z/of-string source {:track-position? true}) (keyword parent-plural))
          entry  (admin-has-many child)
          hm     (map-val entity :has-many)]
      (cond
        (nil? entity)
        {:status :unrecognised :reason (str "it does not configure :" parent-plural)}

        (nil? hm)
        {:status :updated :content (z/root-string (append-entry entity :has-many (str "[" (edn-str entry) "]")))}

        (not= :vector (z/tag hm))
        {:status :unrecognised :reason "its :has-many is not a vector"}

        (some #(= (:entity entry) (:entity (try (z/sexpr %) (catch Exception _ nil))))
              (children hm))
        {:status :present}

        :else
        {:status :updated :content (z/root-string (append-item hm (z/node (z/of-string (edn-str entry))) true))}))
    (catch Exception e
      {:status :unrecognised :reason (str "it could not be read: " (.getMessage e))})))
