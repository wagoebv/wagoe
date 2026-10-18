(ns wagoe.external.ports
  "Protocol definitions for external service adapters.

   Defines three integration protocols:
   - ISmtpProvider    — outbound email via SMTP
   - IImapMailbox     — inbound email via IMAP
   - ITwilioMessaging — Twilio SMS and WhatsApp messaging
   - IFileSource      — remote files to import (FTPS)")

;; =============================================================================
;; SMTP Protocol
;; =============================================================================

(defprotocol ISmtpProvider
  "Protocol for outbound SMTP email sending."

  (send-email! [this email]
    "Send an email synchronously.

    Args:
      email - OutboundEmail map with :to :from :subject :body etc.

    Returns:
      Map with:
        :success?    - boolean
        :message-id  - string (if successful)
        :error       - map {:message :type} (if failed)")

  (send-email-async! [this email]
    "Send an email asynchronously. Returns a future of the send-email! result.")

  (test-connection! [this]
    "Verify SMTP server is reachable and credentials are valid.

    Returns:
      Map with:
        :success? - boolean
        :error    - map {:message :type} (if failed)"))

;; =============================================================================
;; IMAP Protocol
;; =============================================================================

(defprotocol IImapMailbox
  "Protocol for reading messages from an IMAP mailbox."

  (fetch-messages! [this] [this opts]
    "Fetch messages from the mailbox, oldest first. Selection runs on the
    server; content is read only for the selected messages, and reading does
    not mark them read.

    Args:
      opts - ImapFetchOptions map (optional):
             :folder       - folder name (default INBOX)
             :limit        - max messages to fetch
             :unread-only? - fetch only unread messages
             :since        - only messages received at or after this inst
             :include-raw? - add :raw, the RFC 822 bytes, to each message

    Returns:
      Map with:
        :success?  - boolean
        :messages  - vector of InboundMessage maps, with :attachments
        :count     - integer
        :error     - map {:message :type} (if failed)")

  (fetch-unread! [this] [this limit]
    "Fetch unread messages. Returns same shape as fetch-messages!.")

  (mark-read! [this uid]
    "Mark a message as read by UID.

    Returns:
      Map with :success? and optional :error")

  (delete-message! [this uid]
    "Delete a message by UID.

    Returns:
      Map with :success? and optional :error")

  (close! [this]
    "Close IMAP connection. Returns true."))

;; =============================================================================
;; Twilio Protocol
;; =============================================================================

(defprotocol ITwilioMessaging
  "Protocol for sending SMS and WhatsApp messages via Twilio."

  (send-sms! [this input]
    "Send an SMS message.

    Args:
      input - SendMessageInput map with :to :body and optional :from

    Returns:
      MessageResult map with :success? :message-sid :status :error")

  (send-whatsapp! [this input]
    "Send a WhatsApp message. Automatically prefixes whatsapp: to To/From.

    Args:
      input - SendMessageInput map with :to :body and optional :from

    Returns:
      MessageResult map with :success? :message-sid :status :error")

  (get-message-status! [this sid]
    "Get the delivery status of a message by SID.

    Returns:
      Map with:
        :success? - boolean
        :status   - string (e.g. \"delivered\", \"failed\")
        :error    - map {:message :type} (if failed)"))

;; =============================================================================
;; File Source Protocol
;; =============================================================================

(defprotocol IFileSource
  "Protocol for reading files a remote system drops in a directory."

  (list-files! [this dir] [this dir opts]
    "List the regular files in dir, sorted by name.

    Args:
      opts - FileListOptions map (optional):
             :match - exact name, regex (whole name) or collection of names
             :skip  - names to leave out, e.g. files already processed

    Returns:
      Map with:
        :success? - boolean
        :files    - vector of RemoteFile maps {:name :path :size :modified-at}
        :error    - map {:type :external-error :message :host :attempts :cause} (if failed)")

  (fetch-file! [this path]
    "Download the file at path.

    Returns:
      Map with:
        :success? - boolean
        :path     - string
        :content  - bytes
        :size     - integer
        :error    - map, as for list-files! (if failed)"))
