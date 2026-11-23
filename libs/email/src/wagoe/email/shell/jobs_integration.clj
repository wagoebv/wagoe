(ns wagoe.email.shell.jobs-integration
  "Optional integration with jobs module for async email sending.
  
   This namespace provides async email sending via the jobs module.
   The jobs module is an OPTIONAL dependency - add it to your deps.edn:
   
     {:deps {com.wagoe/wagoe-jobs {:mvn/version \"0.1.0\"}}}
   
   If jobs module is not available, async functions will throw descriptive errors
   with :type :missing-dependency.
   
   Usage:
     ;; Queue email for async sending
     (queue-email-job! job-queue email-sender prepared-email)
     
     ;; Register email job handler with jobs module
     (register-email-job-handler! job-registry email-sender)
     
   The job handler sends with the sender the worker was registered with: a job
   carries the email, never SMTP credentials, as jobs are stored (BOU-1100)."
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
     _email-sender - Unused; the worker sends with its own sender (BOU-1100)
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
       {:deps {com.wagoe/wagoe-jobs {:mvn/version \\\"0.1.0\\\"}}}"
  [job-queue _email-sender email]
  (if-let [enqueue-fn (requiring-resolve 'wagoe.jobs.ports/enqueue-job!)]
    (let [;; The email only: a stored job held the SMTP password (BOU-1100).
          job {:job-type :send-email
               :args {:email email}
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

    (throw (ex-info "Jobs module not available. Add com.wagoe/wagoe-jobs to deps.edn"

                    {:type :missing-dependency
                     :module "wagoe-jobs"
                     :required-for "Async email sending"
                     :documentation "https://github.com/wagoebv/wagoe/tree/main/libs/jobs"}))))

;; =============================================================================
;; Email Job Processor
;; =============================================================================

(defn process-email-job
  "Job handler for sending emails.
  
   Registered with the jobs module through `register-email-job-handler!`, which
   gives it `email-sender`. A job queued before BOU-1100 also carries
   `:sender-config`; it is sent with that, so the queue drains.
   
   Job args structure:
     :email - Email map (from prepare-email) with:
              :id - UUID
              :to - Vector of recipient addresses
              :from - Sender address
              :subject - Email subject
              :body - Email body (plain text)
              :headers - Optional headers map
     :sender-config - Only in jobs queued before BOU-1100. SMTP configuration with:
                      :host - SMTP server host
                      :port - SMTP server port
                      :username - SMTP auth username (optional)
                      :password - SMTP auth password (optional)
                      :tls? - Enable STARTTLS
                      :ssl? - Enable SSL
                      :ssl-trust, :check-server-identity? - TLS trust (optional)
   
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
  [email-sender job-args]
  (try
    (let [{:keys [email sender-config]} job-args
          _ (log/debug "Processing email job"
                       {:email-id (:id email)
                        :to (:to email)
                        :subject (:subject email)})
          sender (if sender-config (smtp/create-smtp-sender sender-config) email-sender)
          result (ports/send-email! sender email)]

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
               :type :email-job-error
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
     email-sender - The EmailSenderProtocol the worker sends with, usually
                    the system's :wagoe/email (BOU-1100)
   
   Returns:
     :send-email (the job type keyword)
   
   Throws:
     ex-info with :type :missing-dependency if jobs module not available
   
   Usage:
     (def job-registry (create-job-registry))
     (register-email-job-handler! job-registry email-sender)
     ;; Now email jobs can be processed
   
   Requires:
     Jobs module must be added to deps.edn:
       {:deps {com.wagoe/wagoe-jobs {:mvn/version \\\"0.1.0\\\"}}}"
  ([_job-registry]
   ;; Jobs no longer carry SMTP credentials, so the handler needs a sender.
   (throw (ex-info "register-email-job-handler! needs the sender: (register-email-job-handler! job-registry email-sender)"
                   {:type :configuration-error :see "BOU-1100"})))
  ([job-registry email-sender]
   (when-not (satisfies? ports/EmailSenderProtocol email-sender)
     ;; Refused here, not on every job: a nil sender would retry and dead-letter each mail.
     (throw (ex-info "register-email-job-handler! needs an email sender"
                     {:type :configuration-error :see "BOU-1100"})))
   (if-let [register-fn (requiring-resolve 'wagoe.jobs.ports/register-handler!)]
     (do
       (log/info "Registering email job handler with jobs module"
                 {:job-type :send-email})
       (register-fn job-registry :send-email (partial process-email-job email-sender)))

     (throw (ex-info "Jobs module not available. Add com.wagoe/wagoe-jobs to deps.edn"
                     {:type :missing-dependency
                      :module "wagoe-jobs"
                      :required-for "Email job handler registration"
                      :documentation "https://github.com/wagoebv/wagoe/tree/main/libs/jobs"})))))
