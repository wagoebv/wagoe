# Email Module

[![Status](https://img.shields.io/badge/status-stable-brightgreen)]()
[![Clojure](https://img.shields.io/badge/clojure-1.12+-blue)]()
[![License](https://img.shields.io/badge/license-EPL--2.0-green)]()
[![Clojars Project](https://img.shields.io/clojars/v/org.boundary-app/boundary-email.svg)](https://clojars.org/org.boundary-app/boundary-email)

**Email sending for Boundary Framework**

Simple, robust email delivery with:

- ✅ SMTP email sending (Gmail, SES, Mailgun, SendGrid, etc.)
- ✅ Email composition and validation
- ✅ Multiple recipients (to, cc, bcc)
- ✅ Custom headers (Reply-To, etc.)
- ✅ Synchronous and asynchronous sending
- ✅ Optional background job integration
- ✅ Pure functional core (FC/IS pattern)
- ✅ Pluggable adapters (SMTP, future: SendGrid API, etc.)

---

## Table of Contents

- [Quick Start](#quick-start)
- [Core Concepts](#core-concepts)
- [Usage Examples](#usage-examples)
- [Configuration](#configuration)
- [Sync vs Async Sending](#sync-vs-async-sending)
- [Jobs Module Integration](#jobs-module-integration)
- [API Reference](#api-reference)
- [Troubleshooting](#troubleshooting)
- [Best Practices](#best-practices)

---

## Quick Start

### 1. Add Dependency

```clojure
;; deps.edn
{:deps {org.boundary-app/boundary-email {:mvn/version "1.0.0-beta-1"}}}
```

### 2. Create SMTP Sender

```clojure
(require '[boundary.email.shell.adapters.smtp :as smtp])

;; Gmail example (use App Password, not regular password!)
(def email-sender
  (smtp/create-smtp-sender
    {:host "smtp.gmail.com"
     :port 587
     :username "your-email@gmail.com"
     :password "your-app-password"  ; Generate this in Google Account settings
     :tls? true}))
```

### 3. Send Email

```clojure
(require '[boundary.email.core.email :as email-core]
         '[boundary.email.ports :as ports])

;; Prepare email (pure function, no side effects)
(def prepared-email
  (email-core/prepare-email
    {:to "user@example.com"
     :from "no-reply@myapp.com"
     :subject "Welcome to MyApp!"
     :body "Thanks for signing up. We're excited to have you!"}))

;; Send email (I/O operation)
(def result (ports/send-email! email-sender prepared-email))

;; Check result
(if (:success? result)
  (println "Email sent! Message ID:" (:message-id result))
  (println "Failed to send:" (get-in result [:error :message])))
```

---

## Core Concepts

### Email Lifecycle

```
1. Create input → 2. Prepare (validate) → 3. Send → 4. Handle result
   (User data)      (Pure function)         (I/O)     (Check :success?)
```

**Email Flow:**
- **Input**: User provides email data (to, from, subject, body)
- **Prepare**: `prepare-email` validates and normalizes (pure function)
- **Send**: SMTP adapter sends via network (side effect)
- **Result**: Returns `{:success? true :message-id "..."}` or `{:success? false :error {...}}`

### Functional Core / Imperative Shell

This module follows the FC/IS pattern:

| Layer | Namespace | Responsibility |
|-------|-----------|----------------|
| **Core** | `boundary.email.core.email` | Pure functions: validate, prepare, format |
| **Ports** | `boundary.email.ports` | Protocol definitions (interfaces) |
| **Shell** | `boundary.email.shell.adapters.smtp` | I/O: SMTP sending, error handling |
| **Integration** | `boundary.email.shell.jobs-integration` | Optional: background job queuing |

### Email Structure

```clojure
;; Prepared email (after calling prepare-email)
{:id #uuid "..."                    ; Auto-generated UUID
 :to ["user@example.com"]           ; Normalized to vector
 :from "no-reply@myapp.com"
 :subject "Welcome!"
 :body "Thanks for signing up."
 :headers {:reply-to "support@myapp.com"}  ; Optional
 :metadata {:user-id "123"}         ; Optional, for tracking
 :created-at #inst "2026-01-27T10:30:00Z"}
```

---

## Usage Examples

### Example 1: Welcome Email

```clojure
(defn send-welcome-email!
  "Send welcome email to new user."
  [email-sender user]
  (let [prepared-email (email-core/prepare-email
                         {:to (:email user)
                          :from "no-reply@myapp.com"
                          :subject "Welcome to MyApp!"
                          :body (str "Hi " (:name user) ",\n\n"
                                    "Thanks for signing up!\n\n"
                                    "Best regards,\n"
                                    "The MyApp Team")
                          :metadata {:user-id (:id user)
                                    :email-type :welcome}})
        result (ports/send-email! email-sender prepared-email)]
    
    (if (:success? result)
      (log/info "Welcome email sent" {:user-id (:id user)
                                      :message-id (:message-id result)})
      (log/error "Failed to send welcome email"
                 {:user-id (:id user)
                  :error (:error result)}))
    
    result))
```

### Example 2: Multiple Recipients

```clojure
(defn send-team-notification!
  "Send notification to multiple team members."
  [email-sender team-emails message]
  (let [prepared-email (email-core/prepare-email
                         {:to team-emails  ; Vector of email addresses
                          :from "notifications@myapp.com"
                          :subject "Team Update"
                          :body message})
        result (ports/send-email! email-sender prepared-email)]
    
    (if (:success? result)
      (println "Notification sent to" (count team-emails) "recipients")
      (println "Failed to send notification:" (get-in result [:error :message])))))
```

### Example 3: Email with CC and BCC

```clojure
(defn send-invoice-email!
  "Send invoice with CC to accounting."
  [email-sender customer-email invoice]
  (let [base-email (email-core/prepare-email
                     {:to customer-email
                      :from "billing@myapp.com"
                      :subject (str "Invoice #" (:invoice-number invoice))
                      :body (format-invoice-body invoice)})
        
        ;; Add CC and BCC using helper functions
        email-with-cc (email-core/add-cc base-email "accounting@myapp.com")
        email-with-bcc (email-core/add-bcc email-with-cc "archive@myapp.com")
        
        result (ports/send-email! email-sender email-with-bcc)]
    
    result))
```

### Example 4: Password Reset Email

```clojure
(defn send-password-reset-email!
  "Send password reset email with Reply-To support address."
  [email-sender user reset-token]
  (let [reset-url (str "https://myapp.com/reset-password?token=" reset-token)
        base-email (email-core/prepare-email
                     {:to (:email user)
                      :from "no-reply@myapp.com"
                      :subject "Reset Your Password"
                      :body (str "Hi " (:name user) ",\n\n"
                                "Click the link below to reset your password:\n\n"
                                reset-url "\n\n"
                                "This link expires in 1 hour.\n\n"
                                "If you didn't request this, please ignore this email.")
                      :metadata {:user-id (:id user)
                                :email-type :password-reset
                                :reset-token reset-token}})
        
        ;; Add Reply-To for support
        email-with-reply-to (email-core/add-reply-to base-email "support@myapp.com")
        
        result (ports/send-email! email-sender email-with-reply-to)]
    
    (if (:success? result)
      (log/info "Password reset email sent" {:user-id (:id user)})
      (log/error "Failed to send password reset email"
                 {:user-id (:id user)
                  :error (:error result)}))
    
    result))
```

### Example 5: Batch Sending (Notifications)

```clojure
(defn send-bulk-notifications!
  "Send notification to many users (synchronous batch)."
  [email-sender users notification-message]
  (let [results (doall
                  (for [user users]
                    (let [prepared-email (email-core/prepare-email
                                           {:to (:email user)
                                            :from "notifications@myapp.com"
                                            :subject "System Notification"
                                            :body (str "Hi " (:name user) ",\n\n"
                                                      notification-message)
                                            :metadata {:user-id (:id user)}})
                          result (ports/send-email! email-sender prepared-email)]
                      
                      {:user-id (:id user)
                       :email (:email user)
                       :success? (:success? result)
                       :message-id (:message-id result)
                       :error (when-not (:success? result)
                                (:error result))})))]
    
    (let [succeeded (count (filter :success? results))
          failed (count (remove :success? results))]
      (log/info "Bulk send completed" {:total (count results)
                                       :succeeded succeeded
                                       :failed failed})
      
      {:total (count results)
       :succeeded succeeded
       :failed failed
       :results results})))
```

---

## Configuration

### SMTP Provider Configurations

#### Gmail

**Important**: Gmail requires an **App Password**, not your regular Gmail password.

1. Enable 2-Factor Authentication in your Google Account
2. Generate an App Password: [https://myaccount.google.com/apppasswords](https://myaccount.google.com/apppasswords)
3. Use the generated 16-character password

```clojure
(def gmail-sender
  (smtp/create-smtp-sender
    {:host "smtp.gmail.com"
     :port 587
     :username "your-email@gmail.com"
     :password "your-app-password"  ; 16-character App Password
     :tls? true}))
```

#### Amazon SES

1. Verify your sender email address or domain in AWS SES
2. Generate SMTP credentials in AWS SES Console

```clojure
(def ses-sender
  (smtp/create-smtp-sender
    {:host "email-smtp.us-east-1.amazonaws.com"  ; Change region as needed
     :port 587
     :username "YOUR-SMTP-USERNAME"  ; From SES SMTP credentials
     :password "YOUR-SMTP-PASSWORD"  ; From SES SMTP credentials
     :tls? true}))
```

#### Mailgun

```clojure
(def mailgun-sender
  (smtp/create-smtp-sender
    {:host "smtp.mailgun.org"
     :port 587
     :username "postmaster@your-domain.mailgun.org"
     :password "your-mailgun-smtp-password"
     :tls? true}))
```

#### SendGrid

```clojure
(def sendgrid-sender
  (smtp/create-smtp-sender
    {:host "smtp.sendgrid.net"
     :port 587
     :username "apikey"  ; Literal string "apikey"
     :password "YOUR-SENDGRID-API-KEY"
     :tls? true}))
```

#### Local Development (Mailhog / MailCatcher)

For local testing, use Mailhog or MailCatcher to capture emails without sending them:

```bash
# Start Mailhog (catches emails on port 1025, web UI on port 8025)
docker run -d -p 1025:1025 -p 8025:8025 mailhog/mailhog
```

```clojure
(def dev-sender
  (smtp/create-smtp-sender
    {:host "localhost"
     :port 1025
     :tls? false
     :ssl? false}))

;; Send test email
(ports/send-email! dev-sender (email-core/prepare-email
                                {:to "test@example.com"
                                 :from "dev@myapp.com"
                                 :subject "Test Email"
                                 :body "This is a test."}))

;; View in browser: http://localhost:8025
```

### Environment-Based Configuration

**Production config** (`config/prod.edn`):

```clojure
{:wagoe/email
 {:smtp {:host #env SMTP_HOST
         :port #long #env [SMTP_PORT 587]
         :username #env SMTP_USERNAME
         :password #env SMTP_PASSWORD
         :tls? true}
  
  :from-address #env [EMAIL_FROM_ADDRESS "no-reply@myapp.com"]}}
```

**Environment variables**:

```bash
export SMTP_HOST="smtp.gmail.com"
export SMTP_PORT="587"
export SMTP_USERNAME="your-email@gmail.com"
export SMTP_PASSWORD="your-app-password"
export EMAIL_FROM_ADDRESS="no-reply@myapp.com"
```

---

## Sync vs Async Sending

### Synchronous Sending

**When to use:**
- Small volume (< 10 emails/minute)
- Immediate confirmation required
- Critical emails (password reset, 2FA codes)
- Development and testing

**Pros:**
- Simple to implement
- Immediate error handling
- No additional infrastructure

**Cons:**
- Blocks HTTP response
- No retry on failure
- Slow for bulk operations

```clojure
;; Synchronous send - waits for SMTP response
(def result (ports/send-email! email-sender prepared-email))

(if (:success? result)
  {:status 200 :body {:message "Email sent"}}
  {:status 500 :body {:error "Failed to send email"}})
```

### Asynchronous Sending (Future)

**When to use:**
- Non-critical emails
- Don't need immediate confirmation
- No additional infrastructure available

```clojure
;; Async send using Clojure future (simple, no retry)
(def email-future (ports/send-email-async! email-sender prepared-email))

;; Return immediately
{:status 200 :body {:message "Email queued"}}

;; Optional: Check result later
@email-future  ; => {:success? true :message-id "..."}
```

### Asynchronous Sending (Jobs Module)

**When to use:**
- High volume (> 10 emails/minute)
- Need automatic retries
- Need monitoring and statistics
- Horizontal scaling required

**Pros:**
- Non-blocking (instant response)
- Automatic retries with exponential backoff
- Persistent queue (survives restarts)
- Monitoring and statistics
- Horizontal scaling (multiple workers)

**Cons:**
- Requires Redis
- More complex setup
- Delayed error reporting

See [Jobs Module Integration](#jobs-module-integration) for details.

---

## Jobs Module Integration

For high-volume email sending with automatic retries and monitoring, integrate with the **Jobs Module**.

### 1. Add Jobs Module Dependency

```clojure
;; deps.edn
{:deps {org.boundary-app/boundary-email {:mvn/version "1.0.0-beta-1"}
        org.boundary-app/boundary-jobs {:mvn/version "1.0.0-beta-1"}
        redis.clients/jedis {:mvn/version "5.2.0"}}}
```

### 2. Setup Job Queue

```clojure
(require '[boundary.jobs.shell.adapters.redis :as redis-jobs]
         '[boundary.email.shell.jobs-integration :as email-jobs])

;; Create Redis job queue
(def redis-pool (redis-jobs/create-redis-pool
                  {:host "localhost"
                   :port 6379}))

(def job-queue (redis-jobs/create-redis-job-queue redis-pool))
(def job-registry (atom {}))

;; Register email job handler
(email-jobs/register-email-job-handler! job-registry)
```

### 3. Queue Emails for Async Sending

```clojure
(defn send-email-async!
  "Queue email for background processing."
  [job-queue email-sender email-input]
  (let [prepared-email (email-core/prepare-email email-input)]
    
    ;; Queue email job (returns immediately)
    (email-jobs/queue-email-job! job-queue email-sender prepared-email)
    
    ;; Return instantly
    {:queued? true
     :email-id (:id prepared-email)}))

;; Usage
(send-email-async! job-queue email-sender
                   {:to "user@example.com"
                    :from "no-reply@myapp.com"
                    :subject "Welcome!"
                    :body "Thanks for signing up."})
;; => {:queued? true :email-id #uuid "..."}
```

### 4. Start Job Workers

```clojure
(defn start-email-workers!
  "Start background workers to process email jobs."
  [job-queue job-registry worker-count]
  (log/info "Starting email workers" {:count worker-count})
  
  (doall
    (for [i (range worker-count)]
      (future
        (loop []
          (try
            ;; Process one job from :emails queue
            (when-let [job (ports/dequeue-job! job-queue :emails)]
              (let [handler (get @job-registry (:job-type job))]
                (when handler
                  (let [result (handler (:args job))]
                    (if (:success? result)
                      (log/info "Email job completed" {:email-id (get-in job [:metadata :email-id])})
                      (log/error "Email job failed" {:email-id (get-in job [:metadata :email-id])
                                                     :error (:error result)}))))))
            
            (Thread/sleep 1000)  ; Poll every second
            (catch Exception e
              (log/error e "Email worker error")))
          (recur))))))

;; Start 5 workers
(start-email-workers! job-queue job-registry 5)
```

### 5. Benefits of Jobs Integration

| Feature | Benefit |
|---------|---------|
| **Non-blocking** | HTTP responses return instantly |
| **Automatic retries** | Failed emails retry with exponential backoff |
| **Persistent queue** | Emails survive server restarts |
| **Monitoring** | Track success/failure rates |
| **Horizontal scaling** | Run multiple worker processes |
| **Priority queues** | Send urgent emails first |

### 6. Example: High-Volume Welcome Emails

```clojure
(defn send-welcome-emails-bulk!
  "Queue welcome emails for 10,000 users (completes in seconds)."
  [job-queue email-sender users]
  (let [queued-count (atom 0)]
    
    (doseq [user users]
      (send-email-async! job-queue email-sender
                         {:to (:email user)
                          :from "no-reply@myapp.com"
                          :subject "Welcome!"
                          :body (str "Hi " (:name user) ", welcome!")
                          :metadata {:user-id (:id user)}})
      (swap! queued-count inc))
    
    (log/info "Queued welcome emails" {:count @queued-count})
    
    {:queued @queued-count}))

;; Queue 10,000 emails (completes in ~2 seconds)
(send-welcome-emails-bulk! job-queue email-sender users)
;; => {:queued 10000}

;; Workers process emails in background
;; Rate: ~100-200 emails/minute (depends on SMTP provider limits)
```

---

## API Reference

### Core Functions

**boundary.email.core.email**

#### `prepare-email`

Prepare email for sending (pure function, no side effects).

```clojure
(prepare-email email-input)
```

**Args:**
- `email-input` - Map with:
  - `:to` - Email address string OR vector of addresses (required)
  - `:from` - Email address string (required)
  - `:subject` - Subject string (required)
  - `:body` - Body string (required)
  - `:headers` - Headers map (optional, e.g., `{:reply-to "..."}`)
  - `:metadata` - Metadata map (optional, for tracking)

**Returns:** Email map with:
- `:id` - Generated UUID
- `:to` - Normalized vector of recipients
- `:from`, `:subject`, `:body` - As provided
- `:headers`, `:metadata` - If provided
- `:created-at` - Timestamp

**Example:**
```clojure
(prepare-email {:to "user@example.com"
                :from "no-reply@myapp.com"
                :subject "Welcome!"
                :body "Thanks for signing up."})
;; => {:id #uuid "..."
;;     :to ["user@example.com"]
;;     :from "no-reply@myapp.com"
;;     :subject "Welcome!"
;;     :body "Thanks for signing up."
;;     :created-at #inst "..."}
```

#### `validate-email`

Validate complete email structure (pure function).

```clojure
(validate-email email)
```

**Args:**
- `email` - Email map (from `prepare-email`)

**Returns:** Map with:
- `:valid?` - Boolean
- `:errors` - Vector of error messages (if invalid)

**Example:**
```clojure
(validate-email prepared-email)
;; => {:valid? true :errors []}

(validate-email {:to "user@example.com"})
;; => {:valid? false :errors ["Missing required field: from" "Missing required field: subject" ...]}
```

#### `validate-recipients`

Validate recipient email addresses (pure function).

```clojure
(validate-recipients recipients)
```

**Args:**
- `recipients` - String or vector of email addresses

**Returns:** Map with:
- `:valid?` - Boolean (true if all valid)
- `:valid-emails` - Vector of valid addresses
- `:invalid-emails` - Vector of invalid addresses

**Example:**
```clojure
(validate-recipients ["valid@example.com" "invalid-email" "another@example.com"])
;; => {:valid? false
;;     :valid-emails ["valid@example.com" "another@example.com"]
;;     :invalid-emails ["invalid-email"]}
```

#### `add-reply-to`

Add Reply-To header (pure function).

```clojure
(add-reply-to email reply-to-address)
```

**Example:**
```clojure
(add-reply-to prepared-email "support@myapp.com")
;; => Email with :headers {:reply-to "support@myapp.com"}
```

#### `add-cc`

Add CC recipients (pure function).

```clojure
(add-cc email cc-recipients)
```

**Args:**
- `email` - Email map
- `cc-recipients` - String or vector of email addresses

**Example:**
```clojure
(add-cc prepared-email ["manager@myapp.com" "team@myapp.com"])
;; => Email with :headers {:cc "manager@myapp.com, team@myapp.com"}
```

#### `add-bcc`

Add BCC recipients (pure function).

```clojure
(add-bcc email bcc-recipients)
```

**Args:**
- `email` - Email map
- `bcc-recipients` - String or vector of email addresses

**Example:**
```clojure
(add-bcc prepared-email "archive@myapp.com")
;; => Email with :headers {:bcc "archive@myapp.com"}
```

#### `email-summary`

Create summary for logging/monitoring (pure function).

```clojure
(email-summary email)
```

**Returns:** Summary map with:
- `:id` - Email UUID
- `:to` - Recipient count
- `:from` - Sender address
- `:subject` - Subject
- `:has-attachments?` - Boolean
- `:created-at` - Timestamp

**Example:**
```clojure
(email-summary prepared-email)
;; => {:id #uuid "..." :to 1 :from "no-reply@myapp.com" :subject "Welcome!" ...}
```

### Ports (Protocols)

**boundary.email.ports/EmailSenderProtocol**

#### `send-email!`

Send email synchronously (blocks until complete).

```clojure
(send-email! email-sender email)
```

**Args:**
- `email-sender` - SmtpEmailSender instance
- `email` - Prepared email map

**Returns:** Result map with:
- `:success?` - Boolean
- `:message-id` - Message ID from SMTP (if successful)
- `:error` - Error map (if failed) with:
  - `:message` - Error message
  - `:type` - Error type ("SmtpError", etc.)
  - `:provider-error` - Provider-specific error details

**Example:**
```clojure
(send-email! email-sender prepared-email)
;; Success: {:success? true :message-id "<msg-123@smtp.example.com>"}
;; Failure: {:success? false :error {:message "Connection timeout" :type "SmtpError"}}
```

#### `send-email-async!`

Send email asynchronously using Clojure future.

```clojure
(send-email-async! email-sender email)
```

**Args:**
- `email-sender` - SmtpEmailSender instance
- `email` - Prepared email map

**Returns:** Future that will contain result map

**Example:**
```clojure
(def future-result (send-email-async! email-sender prepared-email))
;; Returns immediately

;; Check result later
@future-result
;; => {:success? true :message-id "..."}
```

### SMTP Adapter

**boundary.email.shell.adapters.smtp**

#### `create-smtp-sender`

Create SMTP email sender.

```clojure
(create-smtp-sender config)
```

**Args:**
- `config` - Map with:
  - `:host` - SMTP server host (required)
  - `:port` - SMTP server port (required)
  - `:username` - SMTP auth username (optional)
  - `:password` - SMTP auth password (optional)
  - `:tls?` - Enable STARTTLS (default: true)
  - `:ssl?` - Enable SSL (default: false)

**Returns:** SmtpEmailSender instance implementing EmailSenderProtocol

**Example:**
```clojure
(create-smtp-sender {:host "smtp.gmail.com"
                     :port 587
                     :username "user@gmail.com"
                     :password "app-password"
                     :tls? true})
;; => #boundary.email.shell.adapters.smtp.SmtpEmailSender{...}
```

### Jobs Integration

**boundary.email.shell.jobs-integration**

#### `queue-email-job!`

Queue email for async sending via jobs module.

```clojure
(queue-email-job! job-queue email-sender email)
```

**Args:**
- `job-queue` - IJobQueue instance from jobs module
- `email-sender` - SmtpEmailSender instance
- `email` - Prepared email map

**Returns:** Job ID (UUID)

**Requires:** Jobs module must be in deps.edn

**Example:**
```clojure
(queue-email-job! job-queue email-sender prepared-email)
;; => #uuid "..."
```

#### `register-email-job-handler!`

Register email job handler with jobs module.

```clojure
(register-email-job-handler! job-registry)
```

**Args:**
- `job-registry` - IJobRegistry instance from jobs module

**Returns:** `:send-email` (job type keyword)

**Example:**
```clojure
(register-email-job-handler! job-registry)
;; => :send-email
```

---

## Troubleshooting

### Email Not Sending

**Symptom:** `send-email!` returns `{:success? false}`

**Check:**

1. **Verify SMTP credentials:**
   ```clojure
   ;; Test credentials with minimal email
   (send-email! email-sender
                (prepare-email {:to "your-email@example.com"
                               :from "your-email@example.com"
                               :subject "Test"
                               :body "Test"}))
   ```

2. **Check error message:**
   ```clojure
   (def result (send-email! email-sender prepared-email))
   (when-not (:success? result)
     (println "Error:" (get-in result [:error :message])))
   ```

3. **Common issues:**
   - **Invalid credentials**: Double-check username/password
   - **Gmail**: Use App Password, not regular password
   - **Amazon SES**: Verify sender email in SES console
   - **Firewall**: Ensure port 587 or 465 is not blocked

### Connection Timeout

**Symptom:** Error message contains "timeout" or "connection refused"

**Solutions:**

1. **Check network connectivity:**
   ```bash
   telnet smtp.gmail.com 587
   # Should connect successfully
   ```

2. **Try different port:**
   ```clojure
   ;; Port 587 (TLS)
   {:port 587 :tls? true :ssl? false}
   
   ;; Port 465 (SSL)
   {:port 465 :tls? false :ssl? true}
   
   ;; Port 25 (plain, not recommended)
   {:port 25 :tls? false :ssl? false}
   ```

3. **Check firewall rules:**
   - Ensure outbound SMTP ports are not blocked
   - Corporate networks often block port 25

### Authentication Failed

**Symptom:** Error message contains "authentication failed" or "invalid credentials"

**Solutions:**

1. **Gmail - Use App Password:**
   - Enable 2FA in Google Account
   - Generate App Password: [https://myaccount.google.com/apppasswords](https://myaccount.google.com/apppasswords)
   - Use 16-character App Password, not Gmail password

2. **Amazon SES - Check credentials:**
   - Verify SMTP credentials in AWS SES Console
   - Ensure IAM user has `ses:SendRawEmail` permission
   - Check AWS region matches SMTP endpoint

3. **Mailgun/SendGrid - Verify API keys:**
   - Regenerate SMTP credentials if needed
   - Check domain verification status

### SSL/TLS Errors

**Symptom:** Error message contains "SSL handshake failed" or "certificate"

**Solutions:**

1. **Match TLS/SSL to port:**
   ```clojure
   ;; Port 587 - use TLS
   {:port 587 :tls? true :ssl? false}
   
   ;; Port 465 - use SSL
   {:port 465 :tls? false :ssl? true}
   ```

2. **Check Java version:**
   ```bash
   java -version
   # Ensure Java 11+ for modern TLS support
   ```

3. **Trust certificate (last resort):**
   - Some providers use self-signed certificates
   - For development only, not production!

### Emails Going to Spam

**Symptom:** Emails send successfully but land in spam folder

**Solutions:**

1. **Add SPF record** (DNS):
   ```
   TXT @ "v=spf1 include:_spf.google.com ~all"
   ```

2. **Add DKIM record** (DNS):
   - Configure in email provider (Gmail, SES, Mailgun, etc.)
   - Add DKIM TXT record to DNS

3. **Add DMARC record** (DNS):
   ```
   TXT _dmarc "v=DMARC1; p=none; rua=mailto:dmarc@yourdomain.com"
   ```

4. **Use verified sender address:**
   - Amazon SES: Verify sender email or domain
   - Gmail: Use Gmail address or custom domain with Gmail

5. **Avoid spam triggers:**
   - Don't use ALL CAPS in subject
   - Avoid spam words (FREE, URGENT, BUY NOW, etc.)
   - Include unsubscribe link for bulk emails

---

## Best Practices

### 1. Validate Before Sending

Always validate email addresses before attempting to send:

```clojure
(defn send-email-safe!
  "Send email with validation."
  [email-sender email-input]
  (let [prepared-email (email-core/prepare-email email-input)
        validation (email-core/validate-email prepared-email)]
    
    (if (:valid? validation)
      (ports/send-email! email-sender prepared-email)
      {:success? false
       :error {:message "Invalid email"
               :validation-errors (:errors validation)}})))
```

### 2. Use Reply-To for Support Emails

Set Reply-To header so users can respond:

```clojure
(-> (prepare-email email-input)
    (add-reply-to "support@myapp.com")
    (send-email! email-sender))
```

### 3. Log Email Activity

Always log email sends for debugging and monitoring:

```clojure
(defn send-and-log!
  "Send email and log result."
  [email-sender email]
  (let [summary (email-core/email-summary email)
        result (ports/send-email! email-sender email)]
    
    (if (:success? result)
      (log/info "Email sent" (assoc summary :message-id (:message-id result)))
      (log/error "Email failed" (assoc summary :error (:error result))))
    
    result))
```

### 4. Handle Errors Gracefully

Never let email failures crash your application:

```clojure
(defn send-with-fallback!
  "Send email with fallback behavior."
  [email-sender email fallback-fn]
  (let [result (ports/send-email! email-sender email)]
    
    (when-not (:success? result)
      (log/error "Email send failed, executing fallback"
                 {:email-id (:id email)
                  :error (:error result)})
      (fallback-fn email (:error result)))
    
    result))

;; Usage
(send-with-fallback! email-sender prepared-email
                     (fn [email error]
                       ;; Save to database for manual retry
                       (db/save-failed-email! email error)))
```

### 5. Use Environment Variables for Credentials

**Never hardcode credentials in source code:**

```clojure
;; ❌ BAD - Hardcoded credentials
(def email-sender
  (smtp/create-smtp-sender
    {:host "smtp.gmail.com"
     :password "my-secret-password"}))  ; DON'T DO THIS!

;; ✅ GOOD - Environment variables
(def email-sender
  (smtp/create-smtp-sender
    {:host (System/getenv "SMTP_HOST")
     :port (Integer/parseInt (System/getenv "SMTP_PORT"))
     :username (System/getenv "SMTP_USERNAME")
     :password (System/getenv "SMTP_PASSWORD")
     :tls? true}))
```

### 6. Rate Limit Bulk Sends

Respect SMTP provider rate limits:

| Provider | Limit |
|----------|-------|
| **Gmail** | 100/day (free), 2,000/day (Workspace) |
| **Amazon SES** | 14/second (default, request increase) |
| **Mailgun** | 100/hour (free), 10,000/month (flex) |
| **SendGrid** | 100/day (free), custom (paid) |

```clojure
(defn send-bulk-with-rate-limit!
  "Send bulk emails with rate limiting."
  [email-sender emails rate-per-minute]
  (let [delay-ms (/ 60000 rate-per-minute)]  ; Milliseconds per email
    
    (doseq [email emails]
      (send-email! email-sender email)
      (Thread/sleep delay-ms))))  ; Rate limit

;; Send 60 emails/minute
(send-bulk-with-rate-limit! email-sender emails 60)
```

### 7. Use Metadata for Tracking

Add metadata for debugging and analytics:

```clojure
(prepare-email {:to "user@example.com"
                :from "no-reply@myapp.com"
                :subject "Welcome!"
                :body "..."
                :metadata {:user-id "123"
                          :email-type :welcome
                          :campaign-id "onboarding-2024"
                          :ab-test-variant "b"}})
```

### 8. Test with Local SMTP Server

Use Mailhog or MailCatcher for development:

```bash
# Start Mailhog
docker run -d -p 1025:1025 -p 8025:8025 mailhog/mailhog

# Configure email sender
(def dev-sender (smtp/create-smtp-sender
                  {:host "localhost"
                   :port 1025
                   :tls? false}))

# View emails at http://localhost:8025
```

---

## License

Part of Boundary Framework - See main LICENSE file.

---

**Next Steps:**
- **[Jobs Module](../jobs/README.md)** - Background job processing for high-volume async sending
- **[Platform Module](../platform/README.md)** - HTTP and database infrastructure
- **[User Module](../user/README.md)** - Authentication and authorization
