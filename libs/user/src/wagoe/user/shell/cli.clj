(ns wagoe.user.shell.cli
  "CLI commands for user management.

   This is the SHELL layer in Functional Core / Imperative Shell architecture.
   Responsibilities:
   - Parse command-line arguments using tools.cli
   - Orchestrate service calls (no business logic here)
   - Format output (table or JSON)
   - Handle errors and exit codes

   All business logic lives in wagoe.user.core.* and wagoe.user.shell.service.
   All observability is handled automatically by interceptors."
  (:require [wagoe.core.utils.validation :as validation]
            [wagoe.core.utils.type-conversion :as type-conv]
            [wagoe.core.interceptor :as interceptor]
            [wagoe.core.interceptor-context :as interceptor-context]
            [wagoe.user.shell.interceptors :as user-interceptors]
            [wagoe.user.ports :as user-ports]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.tools.cli :as cli])
  (:import [java.time.format DateTimeFormatter]))

;; =============================================================================
;; Global CLI Options
;; =============================================================================

(def global-options
  [["-f" "--format FORMAT" "Output format: table (default) or json"
    :default "table"
    :validate [validation/valid-output-format? "Must be 'table' or 'json'"]]
   ["-h" "--help" "Show help"]])

;; =============================================================================
;; User Command Options
;; =============================================================================

(def user-create-options
  [[nil "--email EMAIL" "User email address (required)"
    :validate [#(re-matches #"^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$" %)
               "Must be a valid email address"]]
   [nil "--name NAME" "User full name (required)"]
   [nil "--role ROLE" "User role: admin, user, or viewer (required)"
    :parse-fn keyword
    :validate [#(contains? #{:admin :user :viewer} %) "Must be admin, user, or viewer"]]
   [nil "--password PASSWORD" "User password (optional, will be hashed according to policy)"]
   [nil "--password-prompt" "Prompt for password (hidden input; avoids putting password in shell history)"]
   [nil "--active BOOL" "User active status (default: true)"
    :default true
    :parse-fn type-conv/parse-bool
    :validate [some? "Must be true, false, yes, no, 1, or 0"]]])

(def user-list-options
  [[nil "--limit N" "Maximum number of results (default: 20)"
    :default 20
    :parse-fn type-conv/parse-int
    :validate [some? "Must be a positive integer"]]
   [nil "--offset N" "Number of results to skip (default: 0)"
    :default 0
    :parse-fn #(Integer/parseInt %)
    :validate [#(>= % 0) "Must be non-negative"]]
   [nil "--role ROLE" "Filter by role: admin, user, or viewer"
    :parse-fn keyword
    :validate [#(contains? #{:admin :user :viewer} %) "Must be admin, user, or viewer"]]
   [nil "--active BOOL" "Filter by active status"
    :parse-fn type-conv/parse-bool
    :validate [some? "Must be true, false, yes, no, 1, or 0"]]])

(def user-find-options
  [[nil "--id UUID" "User ID"
    :parse-fn type-conv/parse-uuid-string
    :validate [some? "Must be a valid UUID"]]
   [nil "--email EMAIL" "User email address"
    :validate [#(re-matches #"^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$" %)
               "Must be a valid email address"]]])

(def user-update-options
  [[nil "--id UUID" "User ID (required)"
    :parse-fn type-conv/parse-uuid-string
    :validate [some? "Must be a valid UUID"]]
   [nil "--name NAME" "User full name"]
   [nil "--role ROLE" "User role: admin, user, or viewer"
    :parse-fn keyword
    :validate [#(contains? #{:admin :user :viewer} %) "Must be admin, user, or viewer"]]
   [nil "--active BOOL" "User active status"
    :parse-fn type-conv/parse-bool
    :validate [some? "Must be true, false, yes, no, 1, or 0"]]])

(def user-delete-options
  [[nil "--id UUID" "User ID (required)"
    :parse-fn type-conv/parse-uuid-string
    :validate [some? "Must be a valid UUID"]]])

;; =============================================================================
;; Session Command Options
;; =============================================================================

(def session-create-options
  [[nil "--user-id UUID" "User UUID (required)"
    :parse-fn type-conv/parse-uuid-string
    :validate [some? "Must be a valid UUID"]]
   [nil "--user-agent AGENT" "User agent string"]
   [nil "--ip-address IP" "IP address"]])

(def session-invalidate-options
  [[nil "--token TOKEN" "Session token (required)"]])

(def session-list-options
  [[nil "--user-id UUID" "User UUID (required)"
    :parse-fn type-conv/parse-uuid-string
    :validate [some? "Must be a valid UUID"]]])

;; =============================================================================
;; Output Formatting - Time
;; =============================================================================

(def default-datetime-formatter
  (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss"))

(defn format-instant
  "Format an Instant as a human-readable string."
  [instant]
  (when instant
    (.format default-datetime-formatter
             (.atZone instant (java.time.ZoneId/systemDefault)))))

(defn truncate-string
  "Truncate string to max-length with ellipsis."
  [s max-length]
  (if (and s (> (count s) max-length))
    (str (subs s 0 (- max-length 3)) "...")
    s))

;; =============================================================================
;; Output Formatting - Table
;; =============================================================================

(defn format-table-row
  "Format a single row with column widths."
  [row widths]
  (str "| "
       (str/join " | "
                 (map (fn [val width]
                        (let [s (str val)]
                          (format (str "%-" width "s") s)))
                      row
                      widths))
       " |"))

(defn format-table-separator
  "Create a table separator line."
  [widths]
  (str "+-"
       (str/join "-+-"
                 (map #(apply str (repeat % "-")) widths))
       "-+"))

(defn render-table
  "Render data as a formatted table.

   Args:
     headers: Vector of column header strings
     rows: Vector of vectors containing row data

   Returns:
     Formatted table string"
  [headers rows]
  (if (empty? rows)
    "No results found."
    (let [;; Calculate column widths
          widths (reduce (fn [ws row]
                           (map max ws (map #(count (str %)) row)))
                         (map count headers)
                         rows)
          separator (format-table-separator widths)
          header-row (format-table-row headers widths)]
      (str separator "\n"
           header-row "\n"
           separator "\n"
           (str/join "\n" (map #(format-table-row % widths) rows)) "\n"
           separator))))

(defn format-user-table
  "Format users as a table."
  [users]
  (let [headers ["ID" "Email" "Name" "Role" "Active" "Created"]
        rows (map (fn [user]
                    [(truncate-string (str (:id user)) 36)
                     (truncate-string (:email user) 30)
                     (truncate-string (:name user) 25)
                     (or (some-> (:role user) name) "")
                     (str (:active user))
                     (format-instant (:created-at user))])
                  users)]
    (render-table headers rows)))

(defn format-session-table
  "Format sessions as a table."
  [sessions]
  (let [headers ["Token" "User ID" "Created" "Expires" "Revoked"]
        rows (map (fn [session]
                    [(:session-token session) ; Don't truncate token - needed for invalidation
                     (truncate-string (str (:user-id session)) 36)
                     (format-instant (:created-at session))
                     (format-instant (:expires-at session))
                     (if (:revoked-at session) "Yes" "No")])
                  sessions)]
    (render-table headers rows)))

;; =============================================================================
;; Output Formatting - JSON
;; =============================================================================

(defn format-instant-json
  "Format Instant for JSON output."
  [instant]
  (when instant
    (str instant)))

(defn user->json
  "Transform user entity for JSON output."
  [user]
  (-> user
      (update :id str)
      (update :role #(some-> % name))
      (update :created-at format-instant-json)
      (update :updated-at format-instant-json)
      (update :deleted-at format-instant-json)
      (update :last-login format-instant-json)))

(defn session->json
  "Transform session entity for JSON output."
  [session]
  (-> session
      (update :id str)
      (update :user-id str)
      (update :created-at format-instant-json)
      (update :expires-at format-instant-json)
      (update :last-accessed-at format-instant-json)
      (update :revoked-at format-instant-json)))

(defn format-json
  "Format data as pretty JSON."
  [data]
  (json/generate-string data {:pretty true}))

;; =============================================================================
;; Output Formatting - Dispatcher
;; =============================================================================

(defn format-success
  "Format successful result based on output format.

   Args:
     format-type: :table or :json
     entity-type: :user, :user-list, :session, :session-list
     data: Entity or collection to format"
  [format-type entity-type data]
  (case format-type
    :json (case entity-type
            :user (format-json (user->json data))
            :user-list (format-json {:users (map user->json data)
                                     :count (count data)})
            :session (format-json (session->json data))
            :session-list (format-json {:sessions (map session->json data)
                                        :count (count data)})
            ;; Fallback for unknown entity types in JSON mode
            (format-json data))
    :table (case entity-type
             :user (format-user-table [data])
             :user-list (format-user-table data)
             :session (format-session-table [data])
             :session-list (format-session-table data)
             ;; Fallback for unknown entity types in table mode
             (str data))
    ;; Default to table-style string formatting on unknown format-type
    (str data)))

(defn format-error
  "Format error message based on output format."
  [format-type error-data]
  (let [message (or (:message error-data) (:detail error-data) "Unknown error")
        details (or (:details error-data)
                    (when (:validation-details error-data)
                      (str "Missing fields: " (str/join ", " (:missing-fields error-data)))))]
    (case format-type
      :json (format-json {:error error-data})
      :table (str "Error: " message
                  (when details
                    (str "\nDetails: " details)))
      ;; Safe default: plain string formatting
      (str "Error: " message
           (when details
             (str "\nDetails: " details))))))

;; =============================================================================
;; Password Input
;; =============================================================================

(defn read-hidden-password
  "Read a password without echoing input.
   When called from bb create-admin the password is piped via stdin, so
   System/console returns null and read-line reads the piped value.
   When invoked standalone with a real TTY, System/console provides hidden input."
  [label]
  (if-let [console (System/console)]
    (String. (.readPassword console (str label ": ") (into-array Object [])))
    (do
      (print (str label ": "))
      (flush)
      (str/trim (or (read-line) "")))))

;; =============================================================================
;; Command Execution
;; =============================================================================

(defn extract-observability-services
  "Extracts observability services from user-service for interceptor context.
   
   Note: Since service layer cleanup removed direct observability dependencies,
   the interceptor context will obtain these services from the system wiring."
  [user-service]
  {:user-service user-service})

(defn create-cli-interceptor-context
  "Creates interceptor context for CLI operations with real observability services."
  [operation-type user-service args options]
  (-> (interceptor-context/create-cli-context
       operation-type
       (extract-observability-services user-service)
       args
       options)
      (assoc :opts options)))

(defn execute-user-create
  "Execute user create command using interceptor pipeline.

   This version demonstrates the interceptor-based approach that eliminates
   manual observability boilerplate while providing comprehensive tracking."
  [service _error-reporter opts]
  (let [;; Resolve --password-prompt before entering the pipeline
        opts (if (and (:password-prompt opts) (not (:password opts)))
               (assoc opts :password (read-hidden-password "Password"))
               opts)

        ;; Create context for the operation
        context (create-cli-interceptor-context
                 :user-create
                 service
                 [] ;; args (not used for this CLI pattern)
                 opts)

        ;; Create the interceptor pipeline for user creation
        pipeline (user-interceptors/create-user-creation-pipeline :cli)

        ;; Execute the pipeline
        result-context (interceptor/run-pipeline context pipeline)
        response (:response result-context)]

    ;; Convert interceptor response format to CLI expected format
    (cond
      ;; Success case - CLI interceptor already formats correctly
      (and response (= (:status response) 0))
      response

      ;; Success case - HTTP-style format (status 200)
      (and response (= (:status response) 200))
      {:status 0
       :entity-type :user
       :data (:body response)}

      ;; Error case - response contains error details
      (and response (not (#{0 200} (:status response))))
      (let [error-body (:body response)
            format-type (keyword (get opts :format "table"))]
        {:status 1
         :message (format-error format-type error-body)})

      ;; Fallback - no response (shouldn't happen)
      :else
      {:status 1
       :message (format-error (keyword (get opts :format "table"))
                              {:type :internal-error
                               :message "No response received from operation"})})))

(defn execute-user-get
  "Execute user get command using interceptor pipeline."
  [service _error-reporter opts]
  (let [context (create-cli-interceptor-context
                 :user-get
                 service
                 []
                 opts)
        pipeline (user-interceptors/create-user-get-pipeline :cli)
        result-context (interceptor/run-pipeline context pipeline)]
    (:response result-context)))

(defn execute-user-list
  "Execute user list command using interceptor pipeline."
  [service _error-reporter opts]
  (let [context (create-cli-interceptor-context
                 :user-list
                 service
                 []
                 opts)
        pipeline (user-interceptors/create-user-list-pipeline :cli)
        result-context (interceptor/run-pipeline context pipeline)]
    (:response result-context)))

(defn execute-user-update
  "Execute user update command using interceptor pipeline."
  [service _error-reporter opts]
  (let [context (create-cli-interceptor-context
                 :user-update
                 service
                 []
                 opts)
        pipeline (user-interceptors/create-user-update-pipeline :cli)
        result-context (interceptor/run-pipeline context pipeline)]
    (:response result-context)))

(defn execute-user-delete
  "Execute user delete command using interceptor pipeline."
  [service _error-reporter opts]
  (let [context (create-cli-interceptor-context
                 :user-delete
                 service
                 []
                 opts)
        pipeline (user-interceptors/create-user-delete-pipeline :cli)
        result-context (interceptor/run-pipeline context pipeline)]
    (:response result-context)))

(defn execute-session-create
  "Execute session create command using interceptor pipeline."
  [service _error-reporter opts]
  (let [context (create-cli-interceptor-context
                 :session-create
                 service
                 []
                 opts)
        pipeline (user-interceptors/create-session-creation-pipeline :cli)
        result-context (interceptor/run-pipeline context pipeline)]
    (:response result-context)))

(defn execute-session-validate-v2
  "Execute session validate command using interceptor pipeline."
  [service _error-reporter opts]
  (let [context (create-cli-interceptor-context
                 :session-validate
                 service
                 []
                 opts)
        pipeline (user-interceptors/create-session-validation-pipeline :cli)
        result-context (interceptor/run-pipeline context pipeline)]
    (:response result-context)))

(defn execute-session-invalidate
  "Execute session invalidate command using interceptor pipeline."
  [service _error-reporter opts]
  (let [context (create-cli-interceptor-context
                 :session-invalidate
                 service
                 []
                 opts)
        pipeline (user-interceptors/create-session-invalidation-pipeline :cli)
        result-context (interceptor/run-pipeline context pipeline)]
    (:response result-context)))

(defn execute-session-list
  "Execute session list command."
  [service _error-reporter opts]
  (let [format-type (keyword (get opts :format "table"))]
    (try
      (if-let [user-id (:user-id opts)]
        (let [sessions (user-ports/get-user-sessions service user-id)]
          {:status 0
           :entity-type :session-list
           :data sessions})
        {:status 1
         :message (format-error format-type
                                {:type :validation-error
                                 :message "Missing required option: --user-id"})})
      (catch Exception e
        {:status 1
         :message (format-error format-type
                                {:type :session-list-error
                                 :message (.getMessage e)})}))))

;; =============================================================================
;; Command Dispatch
;; =============================================================================

(defn dispatch-command
  "Dispatch command to appropriate executor using interceptor pipeline.

   Args:
     domain: :user or :session
     verb: :create, :list, :find, :update, :delete, :invalidate
     opts: Parsed command options
     service: User service instance

   Returns:
     Map with :status, :entity-type, :data, or :message"
  [domain verb opts service]
  (case domain
    :user (case verb
            :create (execute-user-create service nil opts)
            :list (execute-user-list service nil opts)
            :find (execute-user-get service nil opts) ; Note: find -> get mapping
            :update (execute-user-update service nil opts)
            :delete (execute-user-delete service nil opts)
            (throw (ex-info (str "Unknown user command: " (name verb))
                            {:type :unknown-command
                             :message (str "Unknown command: user " (name verb))})))
    :session (case verb
               :create (execute-session-create service nil opts)
               :invalidate (execute-session-invalidate service nil opts)
               :list (execute-session-list service nil opts)
               (throw (ex-info (str "Unknown session command: " (name verb))
                               {:type :unknown-command
                                :message (str "Unknown command: session " (name verb))})))
    (throw (ex-info (str "Unknown domain: " (name domain))
                    {:type :unknown-domain
                     :message (str "Unknown domain: " (name domain))}))))

;; =============================================================================
;; Help Text
;; =============================================================================

(def root-help
  "Boundary CLI - User and Session Management

Usage: boundary <domain> <command> [options]

Domains:
  user       User management commands
  session    Session management commands

Global Options:
  -f, --format FORMAT  Output format: table (default) or json
  -h, --help           Show help

Examples:
  boundary user create --email john@example.com --name \"John Doe\" --role user
  boundary user list --format json
  boundary session create --user-id UUID

For domain-specific help:
  boundary user --help
  boundary session --help")

(def user-help
  "User Management Commands

Usage: boundary user <command> [options]

Commands:
  create    Create a new user
  list      List users
  find      Find a user by ID or email
  update    Update user properties
  delete    Soft-delete a user

Options for 'create':
  --email EMAIL        User email address (required)
  --name NAME          User full name (required)
  --role ROLE          User role: admin, user, or viewer (required)
  --active BOOL        User active status (default: true)

Options for 'list':
  --limit N            Maximum results (default: 20)
  --offset N           Results to skip (default: 0)
  --role ROLE          Filter by role
  --active BOOL        Filter by active status

Options for 'find':
  --id UUID            User ID
  --email EMAIL        User email
  Note: Use --id OR --email

Options for 'update':
  --id UUID            User ID (required)
  --name NAME          New name
  --role ROLE          New role
  --active BOOL        New active status
  Note: At least one of --name, --role, or --active required

Options for 'delete':
  --id UUID            User ID (required)

Examples:
  boundary user create --email john@example.com --name \"John\" --role user
  boundary user list --limit 10
  boundary user find --id UUID
  boundary user find --email john@example.com
  boundary user update --id UUID --role admin
  boundary user delete --id UUID")

(def session-help
  "Session Management Commands

Usage: boundary session <command> [options]

Commands:
  create       Create a new session (login)
  invalidate   Invalidate a session (logout)
  list         List sessions for a user

Options for 'create':
  --user-id UUID       User UUID (required)
  --user-agent AGENT   User agent string (optional)
  --ip-address IP      IP address (optional)

Options for 'invalidate':
  --token TOKEN        Session token (required)

Options for 'list':
  --user-id UUID       User UUID (required)

Examples:
  boundary session create --user-id UUID
  boundary session invalidate --token TOKEN
  boundary session list --user-id UUID")

;; =============================================================================
;; Main CLI Entry Point
;; =============================================================================

(defn run-cli!
  "Main CLI entry point. Parses arguments, executes commands, and returns status.

   This is module-scoped (user) CLI: we expect `<command> [options]` where
   `<command>` is one of: create, list, find, update, delete.

   Args:
     service: User service instance
     args: Command-line arguments vector

   Returns:
     Exit status: 0 for success, 1 for error

   Side effects:
     Prints to stdout/stderr based on command and format"
  [service args]
  (try
    (let [;; Parse to extract verb (skip option flags and their values)
          ;; We need to skip pairs like ["--format" "json"] and ["--help"]
          parsed-for-verb (cli/parse-opts args global-options :in-order true)
          global-errors (:errors parsed-for-verb)
          verb-args (:arguments parsed-for-verb)
          [verb-str] verb-args
          domain :user
          verb (when verb-str (keyword verb-str))

          ;; Check for help flags early
          has-help-flag? (or (:help (:options parsed-for-verb))
                             (some #(= % "--help") args))]
      (cond
        ;; No args -> show user help
        (empty? args)
        (do
          (println user-help)
          0)

        ;; Global option errors (e.g., invalid --format value)
        (seq global-errors)
        (do
          (binding [*out* *err*]
            (println (format-error :table
                                   {:type :parse-error
                                    :message "Invalid arguments"
                                    :details (str/join ", " global-errors)})))
          1)

        ;; Global --help or no command
        (or has-help-flag? (nil? verb))
        (do
          (println user-help)
          0)

        ;; Legacy: `help` as verb
        (= verb :help)
        (do
          (println user-help)
          0)

        ;; Execute command - parse options now
        :else
        (let [;; Get all args after the verb
              verb-count 1
              remaining-args (vec (drop verb-count args))

              ;; Get command-specific options (domain is always :user here)
              cmd-options (case verb
                            :create user-create-options
                            :list user-list-options
                            :find user-find-options
                            :update user-update-options
                            :delete user-delete-options
                            nil)]
          (if-not cmd-options
            (do
              (binding [*out* *err*]
                (println (format-error :table
                                       {:type :unknown-command
                                        :message (str "Unknown command: " (name verb))})))
              1)
            (let [;; Merge global options with command options
                  all-options (into global-options cmd-options)
                  ;; Parse with merged options
                  parsed (cli/parse-opts remaining-args all-options)
                  opts (:options parsed)
                  errors (:errors parsed)
                  format-type (keyword (get opts :format "table"))]
              (if errors
                (do
                  (binding [*out* *err*]
                    (println (format-error format-type
                                           {:type :parse-error
                                            :message "Invalid arguments"
                                            :details (str/join ", " errors)})))
                  1)
                (let [result (dispatch-command domain verb opts service)]
                  (if (:message result)
                    (println (:message result))
                    (println (format-success format-type
                                             (:entity-type result)
                                             (:data result))))
                  (:status result))))))))
    (catch Exception e
      (let [ex-data (ex-data e)
            format-type (or (try (keyword (get-in (cli/parse-opts args global-options)
                                                  [:options :format]))
                                 (catch Exception _ :table))
                            :table)
            ;; Prefer domain error metadata over generic wrapper messages
            raw-type (:type ex-data)
            original-data (:original-data ex-data)
            domain-type (or raw-type
                            (:type original-data)
                            :error)
            ;; For password-policy-violation, ensure we show the original message
            raw-message (.getMessage e)
            wrapped-message (:message ex-data)
            original-message (:message original-data)
            domain-message (or original-message wrapped-message raw-message)
            ;; Prefer "violations" over generic data for password policy errors
            violations (or (:violations ex-data)
                           (:violations original-data))
            base-details (or (dissoc ex-data :type :message :original-data)
                             original-data)
            domain-details (if violations
                             (assoc base-details :violations violations)
                             base-details)
            error-data {:type domain-type
                        :message domain-message
                        :details domain-details}]
        (binding [*out* *err*]
          (println (format-error format-type error-data)))
        1))))
