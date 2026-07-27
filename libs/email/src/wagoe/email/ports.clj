(ns wagoe.email.ports
  "Port definitions for email module.

   This module defines protocols for sending emails and managing email queues,
   providing abstraction over different email providers (SMTP, SendGrid, etc.)
   and queue implementations (in-memory, Redis, etc.).

   Key Features:
   - Synchronous and asynchronous email sending
   - Email queue management
   - Provider-agnostic interface")

;; =============================================================================
;; Email Sender Ports
;; =============================================================================

(defprotocol EmailSenderProtocol
  "Protocol for email sending operations."

  (send-email! [this email]
    "Send an email synchronously (blocks until sent).

    Args:
      email - Email map with:
              :id - UUID
              :to - Vector of recipient email addresses
              :from - Sender email address
              :subject - Email subject
              :body - Email body (plain text)
              :headers - Optional headers map
              :attachments - Optional attachments vector

    Returns:
      Result map with:
        :success? - Boolean indicating success/failure
        :message-id - Message ID from provider (if successful)
        :error - Error map (if failed) with:
                 :message - Error message
                 :type - Error type
                 :provider-error - Provider-specific error details")

  (send-email-async! [this email]
    "Send an email asynchronously (returns immediately).

    Args:
      email - Email map (same structure as send-email!)

    Returns:
      Future or promise that will contain the result map
      (same structure as send-email!)"))

;; =============================================================================
;; Email Queue Ports
;; =============================================================================

(defprotocol EmailQueueProtocol
  "Protocol for email queue operations."

  (queue-email! [this email]
    "Add email to queue for asynchronous processing.

    Args:
      email - Email map (same structure as EmailSenderProtocol)

    Returns:
      Queue acknowledgment map with:
        :queued? - Boolean indicating if queued successfully
        :queue-id - Queue entry ID (UUID)
        :position - Position in queue (optional)
        :error - Error map (if failed)")

  (process-queue! [this]
    "Process next email in queue.

    Retrieves the next email from the queue, sends it, and removes it
    from the queue. If sending fails, the email may be retried based on
    retry configuration.

    Returns:
      Result map with:
        :processed? - Boolean indicating if an email was processed
        :email-id - ID of processed email (if any)
        :send-result - Result from send-email! (if processed)
        :error - Error map (if processing failed)")

  (queue-size [this]
    "Get number of emails in queue.

    Returns:
      Integer count of queued emails")

  (peek-queue [this]
    "Peek at next email without removing it.

    Returns:
      Email map or nil if queue is empty"))
