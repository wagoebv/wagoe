(ns wagoe.email.shell.jobs-integration
  "Optional integration with jobs module for async email sending.
  
   This namespace provides async email sending via the jobs module.
   The jobs module is an OPTIONAL dependency - add it to your deps.edn:
   
     {:deps {org.wagoe/wagoe-jobs {:mvn/version \"0.1.0\"}}}
   
   If jobs module is not available, async functions will throw descriptive errors
   with :type :missing-dependency.
   
   Usage:
     ;; Queue email for async sending
     (queue-email-job! job-queue email-sender prepared-email)
     
     ;; Register email job handler with jobs module
     (register-email-job-handler! job-registry)
     
   The job handler will:
     - Create a new SMTP sender from config stored in job args
     - Send the email using that sender
     - Return job result map with :success? and :result or :error"
  (:require [wagoe.email.ports :as ports]
            [wagoe.email.shell.adapters.smtp :as smtp]
            [clojure.stacktrace]
            [clojure.tools.logging :as log]))

;; =============================================================================
;; Job Queue Integration
;; =============================================================================

(defn queue-email-job!
  "Queue email for async sending via jobs module.
  
   This function enqueues an email sending job to be processed asynchronously
   by the jobs module worker pool.
   
   Args:
     job-queue - IJobQueue instance from jobs module
     email-sender - SmtpEmailSender instance (used to extract config)
     email - Email map (from prepare-email) with:
             :id - UUID
             :to - Vector of recipient addresses
             :from - Sender address
             :subject - Email subject
             :body - Email body (plain text)
             :headers - Optional headers map
   
   Returns:
     Job ID (UUID) from jobs module
   
   Throws:
     ex-info with :type :missing-dependency if jobs module not available
   
   Requires:
     Jobs module must be added to deps.edn:
       {:deps {org.wagoe/wagoe-jobs {:mvn/version \\\"0.1.0\\\"}}}"
  [job-queue email-sender email]
  (if-let [enqueue-fn (requiring-resolve 'wagoe.jobs.ports/enqueue-job!)]
    (let [;; Extract SMTP configuration from sender
          sender-config {:host (:host email-sender)
                        :port (:port email-sender)
                        :username (:username email-sender)
                        :password (:password email-sender)
                        :tls? (:tls? email-sender)
                        :ssl? (:ssl? email-sender)}
          
          ;; Build job map
          job {:job-type :send-email
               :args {:email email
                      :sender-config sender-config}
               :priority :normal
               :metadata {:email-id (:id email)
                         :to (:to email)
                         :subject (:subject email)}}]
      
      (log/info "Queueing email for async sending via jobs module"
                {:email-id (:id email)
                 :to (:to email)
                 :subject (:subject email)})
      
      ;; Enqueue job to :emails queue
      (enqueue-fn job-queue :emails job))
    
    (throw (ex-info "Jobs module not available. Add org.wagoe/wagoe-jobs to deps.edn"

                    {:type :missing-dependency
                     :module "wagoe-jobs"
                     :required-for "Async email sending"
                     :documentation "https://github.com/thijs-creemers/boundary/tree/main/libs/jobs"}))))

;; =============================================================================
;; Email Job Processor
;; =============================================================================

(defn process-email-job
  "Job handler for sending emails.
  
   This function is registered with the jobs module to process email sending jobs.
   It receives job args containing the email and SMTP sender configuration,
   creates a sender instance, and sends the email.
   
   Job args structure:
     :email - Email map (from prepare-email) with:
              :id - UUID
              :to - Vector of recipient addresses
              :from - Sender address
              :subject - Email subject
              :body - Email body (plain text)
              :headers - Optional headers map
     :sender-config - SMTP sender configuration with:
                      :host - SMTP server host
                      :port - SMTP server port
                      :username - SMTP auth username (optional)
                      :password - SMTP auth password (optional)
                      :tls? - Enable STARTTLS
                      :ssl? - Enable SSL
   
   Returns:
     Job result map with:
       :success? - Boolean indicating success/failure
       :result - Result data (if successful) with:
                 :email-id - Email UUID
                 :message-id - Message ID from SMTP provider
                 :sent-at - Instant when sent
       :error - Error map (if failed) with:
                :message - Error message
                :type - Error type
                :stacktrace - Stack trace string
   
   Example successful result:
     {:success? true
      :result {:email-id #uuid \"...\"
               :message-id \"<msg-123@smtp.example.com>\"
               :sent-at #inst \"2026-01-27T10:30:00Z\"}}
   
   Example error result:
     {:success? false
      :error {:message \"Connection timeout\"
              :type \"SmtpError\"
              :stacktrace \"...\"}}"
  [job-args]
  (try
    (let [{:keys [email sender-config]} job-args
          _ (log/debug "Processing email job"
                       {:email-id (:id email)
                        :to (:to email)
                        :subject (:subject email)
                        :sender-host (:host sender-config)})
          
          ;; Create SMTP sender from config
          smtp-sender (smtp/create-smtp-sender sender-config)
          
          ;; Send email
          result (ports/send-email! smtp-sender email)]
      
      (if (:success? result)
        (do
          (log/info "Email job completed successfully"
                    {:email-id (:id email)
                     :message-id (:message-id result)})
          
          {:success? true
           :result {:email-id (:id email)
                    :message-id (:message-id result)
                    :sent-at (java.time.Instant/now)}})
        
        (do
          (log/error "Email job failed"
                     {:email-id (:id email)
                      :error (:error result)})
          
          {:success? false
           :error (:error result)})))
    
    (catch Exception e
      (log/error e "Email job threw exception"
                 {:email-id (get-in job-args [:email :id])
                  :error-message (.getMessage e)})
      
      {:success? false
       :error {:message (.getMessage e)
               :type "EmailJobError"
               :stacktrace (with-out-str (clojure.stacktrace/print-stack-trace e))}})))

;; =============================================================================
;; Registration
;; =============================================================================

(defn register-email-job-handler!
  "Register email job handler with jobs module.
  
   This function registers the :send-email job type with the jobs module's
   job registry. After registration, jobs with :job-type :send-email will
   be processed by the process-email-job handler.
   
   Args:
     job-registry - IJobRegistry instance from jobs module
   
   Returns:
     :send-email (the job type keyword)
   
   Throws:
     ex-info with :type :missing-dependency if jobs module not available
   
   Usage:
     (def job-registry (create-job-registry))
     (register-email-job-handler! job-registry)
     ;; Now email jobs can be processed
   
   Requires:
     Jobs module must be added to deps.edn:
       {:deps {org.wagoe/wagoe-jobs {:mvn/version \\\"0.1.0\\\"}}}"
  [job-registry]
  (if-let [register-fn (requiring-resolve 'wagoe.jobs.ports/register-handler!)]
    (do
      (log/info "Registering email job handler with jobs module"
                {:job-type :send-email})
      
      (register-fn job-registry :send-email process-email-job))
    
    (throw (ex-info "Jobs module not available. Add org.wagoe/wagoe-jobs to deps.edn"
                    {:type :missing-dependency
                     :module "wagoe-jobs"
                     :required-for "Email job handler registration"
                     :documentation "https://github.com/thijs-creemers/boundary/tree/main/libs/jobs"}))))
