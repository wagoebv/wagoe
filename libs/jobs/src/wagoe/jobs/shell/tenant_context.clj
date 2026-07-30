(ns wagoe.jobs.shell.tenant-context
  "Tenant context support for background job processing.
   
   This namespace provides utilities for executing background jobs within
   tenant-specific database schema contexts. It integrates with the multi-tenant
   architecture by extracting tenant-id from job metadata and setting the
   appropriate PostgreSQL search_path before job execution.
   
   Key Features:
   - Extract tenant-id from job metadata
   - Set database search_path to tenant schema
   - Execute job handlers in tenant context
   - Automatic fallback to public schema if no tenant
   
   Usage:
     (require '[wagoe.jobs.shell.tenant-context :as tenant-jobs])
     
     ;; Enqueue job with tenant context
     (tenant-jobs/enqueue-tenant-job! job-queue tenant-id :send-email
                                      {:to \"user@example.com\"})
     
     ;; Process job with tenant context
     (tenant-jobs/process-tenant-job! job handler-fn db-ctx tenant-service)
   
   See ADR-004 for architecture details (lines 525-554)."
  (:require [wagoe.jobs.ports :as ports]
            [wagoe.tenant.ports :as tenant-ports]
            [clojure.tools.logging :as log]))

;; =============================================================================
;; Job Creation with Tenant Context
;; =============================================================================

(defn enqueue-tenant-job!
  "Enqueue a background job with tenant context.
   
   This stores the tenant-id in job metadata so the worker can execute
   the job in the correct tenant schema context.
   
   Args:
     job-queue: IJobQueue implementation
     tenant-id: Tenant UUID
     job-type: Job type keyword (e.g., :send-email, :process-upload)
     args: Job arguments map
     options: Optional map with:
              :priority - :critical, :high, :normal (default), :low
              :max-retries - Retry count (default: 3)
              :queue-name - Queue name (default: :default)
   
   Returns:
     Job UUID
   
   Example:
     (enqueue-tenant-job! job-queue
                          tenant-uuid
                          :send-email
                          {:to \"user@example.com\"
                           :subject \"Welcome!\"
                           :body \"Thanks for signing up.\"}
                          {:priority :high})
   
   Notes:
     - Tenant-id is stored in job :metadata
     - Jobs without tenant-id run in public schema
     - Worker must have access to db-context and tenant-service"
  ([job-queue tenant-id job-type args]
   (enqueue-tenant-job! job-queue tenant-id job-type args {}))
  ([job-queue tenant-id job-type args {:keys [priority max-retries queue-name]
                                       :or {priority :normal
                                            max-retries 3
                                            queue-name :default}}]
   (let [job-input {:job-type job-type
                    :args args
                    :metadata {:tenant-id tenant-id}  ; Store tenant context
                    :priority priority
                    :max-retries max-retries
                    :queue queue-name}
         job-id (java.util.UUID/randomUUID)]

     (log/info "Enqueueing tenant job"
               {:job-type job-type
                :tenant-id tenant-id
                :priority priority
                :queue queue-name})

     ;; Note: create-job is in wagoe.jobs.core.job, but we're in shell
     ;; We use the IJobQueue port directly
     (ports/enqueue-job! job-queue queue-name
                         (assoc job-input :id job-id
                                :status :pending
                                :created-at (java.time.Instant/now)
                                :updated-at (java.time.Instant/now)
                                :retry-count 0))
     job-id)))

;; =============================================================================
;; Tenant Context Extraction
;; =============================================================================

(defn extract-tenant-context
  "Extract tenant context from job metadata.
   
   Args:
     job: Job map with :metadata
     tenant-service: ITenantService implementation
   
   Returns:
     Map with:
       :tenant-id - Tenant UUID (or nil if not found)
       :tenant-schema - Tenant schema name (or nil)
       :tenant-entity - Full tenant entity (or nil)
   
   Notes:
     - Returns nil values if job has no tenant-id
     - Logs warning if tenant-id present but tenant not found
     - Jobs without tenant run in public schema (multi-tenant optional)"
  [job tenant-service]
  (if-let [tenant-id (get-in job [:metadata :tenant-id])]
    (do
      (log/debug "Extracting tenant context for job"
                 {:job-id (:id job)
                  :tenant-id tenant-id})

      (if-let [tenant (tenant-ports/get-tenant tenant-service tenant-id)]
        {:tenant-id tenant-id
         :tenant-slug (:slug tenant)
         :tenant-schema (:schema-name tenant)
         :tenant-entity tenant}
        ;; Tenant not found - FAIL EXPLICITLY instead of fallback to public schema
        ;; Rationale:
        ;; 1. Data safety: prevents accidental queries in public schema
        ;; 2. Clear errors: explicit failure is better than silent fallback
        ;; 3. Retry support: job will retry when tenant is restored
        ;; 4. Audit trail: failure logged and trackable
        (throw (ex-info "Tenant not found for job - cannot execute safely"
                        {:type :tenant-not-found
                         :job-id (:id job)
                         :tenant-id tenant-id
                         :retry-after-seconds 300  ; Suggest 5min retry delay
                         :message "Job requires tenant context but tenant does not exist. This may indicate a deleted tenant with pending jobs, or a data consistency issue."}))))

    ;; No tenant-id in metadata - not a tenant-scoped job
    (do
      (log/debug "Job has no tenant context, using public schema"
                 {:job-id (:id job)})
      {:tenant-id nil
       :tenant-schema nil
       :tenant-entity nil})))

;; =============================================================================
;; Tenant-Aware Job Execution
;; =============================================================================

(defn process-tenant-job!
  "Process a job with tenant schema context.
   
   This wraps job handler execution to:
   1. Extract tenant-id from job metadata
   2. Lookup tenant entity to get schema name
   3. Set PostgreSQL search_path to tenant schema
   4. Execute job handler with database connection
   5. Restore search_path after execution
   
   Args:
     job: Job map with :id, :job-type, :args, :metadata
     handler-fn: Job handler function (fn [args db-ctx] -> result)
     db-ctx: Database context with :datasource, :database-type
     tenant-service: ITenantService implementation
     tenant-schema-provider: ITenantSchemaProvider implementation (optional, required for tenant jobs)
   
   Returns:
     Result map with :success?, :result or :error
   
   Example:
     (process-tenant-job! job
                          (fn [args db-ctx]
                            ;; Handler logic here
                            {:success? true :result {:rows 5}})
                          db-ctx
                          tenant-service
                          tenant-schema-provider)
   
   Notes:
     - Handler receives db-ctx as second argument
     - Handler should use db-ctx for database operations
     - search_path is automatically managed
     - Falls back to public schema if no tenant
     - Only works with PostgreSQL (other databases use public schema)
   
   Security:
     - Tenant isolation enforced via search_path
     - Each job execution gets isolated transaction
     - Schema validation before execution
     - Automatic cleanup on error"
  [job handler-fn db-ctx tenant-service tenant-schema-provider]
  (let [job-id (:id job)
        job-type (:job-type job)
        {:keys [tenant-id tenant-schema]} (extract-tenant-context job tenant-service)]

    (try
      (if (and tenant-schema (= (:database-type db-ctx) :postgresql))
        ;; Execute in tenant schema context (PostgreSQL only)
        (do
          (log/info "Executing job in tenant schema"
                    {:job-id job-id
                     :job-type job-type
                     :tenant-id tenant-id
                     :schema tenant-schema})

          ;; Validate tenant-schema-provider is available
          (when-not tenant-schema-provider
            (log/error "Tenant job requires tenant-schema-provider but none provided"
                       {:job-id job-id
                        :tenant-id tenant-id
                        :schema tenant-schema})
            (throw (ex-info "Tenant schema provider not configured"
                            {:type :configuration-error
                             :job-id job-id
                             :tenant-id tenant-id
                             :message "Tenant jobs require tenant-schema-provider parameter"})))

          ;; Use protocol method to execute in tenant schema
          (tenant-ports/with-tenant-schema tenant-schema-provider db-ctx tenant-schema
            (fn [tx]
              ;; Handler receives args and db context with transaction
              (handler-fn (:args job) (assoc db-ctx :tx tx)))))

        ;; No tenant or non-PostgreSQL - use public schema
        (do
          (when tenant-id
            (log/debug "Job has tenant but not PostgreSQL, using default schema"
                       {:job-id job-id
                        :tenant-id tenant-id
                        :database-type (:database-type db-ctx)}))

          (handler-fn (:args job) db-ctx)))

      (catch Exception e
        (log/error e "Error processing tenant job"
                   {:job-id job-id
                    :job-type job-type
                    :tenant-id tenant-id
                    :error (.getMessage e)})
        {:success? false
         :error {:message (.getMessage e)
                 :type (-> e class .getName)
                 :stacktrace (with-out-str (.printStackTrace e))}}))))

;; =============================================================================
;; Handler Wrapper for Existing Workers
;; =============================================================================

(defn wrap-handler-with-tenant-context
  "Wrap an existing job handler to add tenant context support.
   
   This is a convenience function for integrating tenant context into
   existing job handlers without modifying them.
   
   Args:
     handler-fn: Original handler (fn [args] -> result)
     db-ctx: Database context
     tenant-service: ITenantService implementation
     tenant-schema-provider: ITenantSchemaProvider implementation (optional, required for tenant jobs)
   
   Returns:
     Wrapped handler (fn [job] -> result)
   
   Example:
     (def my-handler
       (wrap-handler-with-tenant-context
         (fn [args db-ctx]
           ;; Original handler logic
           {:success? true :result {}})
         db-ctx
         tenant-service
         tenant-schema-provider))
     
     ;; Register wrapped handler
     (ports/register-handler! registry :send-email my-handler)
   
   Notes:
     - Wrapped handler expects job (not just args)
     - Tenant context extracted and applied automatically
     - Original handler must accept db-ctx as second arg"
  [handler-fn db-ctx tenant-service tenant-schema-provider]
  (fn [job]
    (process-tenant-job! job handler-fn db-ctx tenant-service tenant-schema-provider)))
