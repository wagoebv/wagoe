(ns wagoe.external.shell.adapters.imap
  "IMAP mailbox adapter implementing IImapMailbox.

   Uses javax.mail for IMAP access. Connections are opened per-call and closed
   in a finally block. IMAP UIDs are accessed by casting the Folder to IMAPFolder.

   Usage:
     (def mailbox (create-imap-mailbox
                    {:host \"imap.gmail.com\"
                     :port 993
                     :username \"user@gmail.com\"
                     :password \"app-password\"
                     :ssl? true}))
     (fetch-unread! mailbox)"
  (:require [wagoe.external.core.imap :as imap-core]
            [wagoe.external.ports :as ports]
            [clojure.tools.logging :as log])
  (:import [javax.mail Session Flags Flags$Flag]
           [javax.mail.internet MimeMultipart]
           [java.util Properties]))

;; =============================================================================
;; Connection Helpers
;; =============================================================================

(defn- imap-properties
  [{:keys [host port ssl?]}]
  (let [props (Properties.)]
    (if ssl?
      (do
        (.put props "mail.imaps.host" (str host))
        (.put props "mail.imaps.port" (str port))
        (.put props "mail.imaps.ssl.enable" "true"))
      (do
        (.put props "mail.imap.host" (str host))
        (.put props "mail.imap.port" (str port))))
    props))

(defn- open-store
  [{:keys [host username password ssl?] :as adapter}]
  (let [props    (imap-properties adapter)
        session  (Session/getInstance props)
        protocol (if ssl? "imaps" "imap")
        store    (.getStore session protocol)]
    (.connect store host username password)
    store))

(defn- with-imap-connection*
  "Open an IMAP store and folder, call f with [store folder], then
   close folder and store in a finally block.

  Args:
    adapter     - ImapMailboxAdapter
    folder-name - string folder name
    read-only?  - boolean
    f           - fn of [store folder] → result"
  [adapter folder-name read-only? f]
  (let [store  (open-store adapter)
        folder (.getFolder store (str folder-name))]
    (try
      (.open folder (if read-only? javax.mail.Folder/READ_ONLY javax.mail.Folder/READ_WRITE))
      (f store folder)
      (finally
        (try (.close folder false) (catch Exception _))
        (try (.close store) (catch Exception _))))))

;; =============================================================================
;; Message Conversion
;; =============================================================================

(defn- get-text-parts
  "Recursively collect content parts from a Message/MimeMultipart."
  [part]
  (let [content-type (str (.getContentType part))]
    (cond
      (.isMimeType part "text/*")
      [{:content-type content-type :content (.getContent part)}]

      (.isMimeType part "multipart/*")
      (let [mp ^MimeMultipart (.getContent part)]
        (mapcat get-text-parts
                (map #(.getBodyPart mp %) (range (.getCount mp)))))

      :else [])))

(defn- message->inbound
  "Convert a javax.mail.Message to an InboundMessage map.
   uid must be pre-fetched from the IMAPFolder."
  [^javax.mail.Message msg uid]
  (let [from     (first (.getFrom msg))
        to-addrs (vec (map str (.getRecipients msg javax.mail.Message$RecipientType/TO)))
        subject  (str (.getSubject msg))
        received (or (.getReceivedDate msg) (.getSentDate msg) (java.util.Date.))
        headers  (let [enum (.getAllHeaders msg)]
                   (loop [result {}]
                     (if (.hasMoreElements enum)
                       (let [header (.nextElement enum)]
                         (recur (assoc result (.getName header) (.getValue header))))
                       result)))
        parts    (try (get-text-parts msg) (catch Exception _ []))
        seen?    (.isSet (.getFlags msg) Flags$Flag/SEEN)]
    (-> (imap-core/build-inbound-message uid (str from) to-addrs subject parts received headers)
        (assoc :seen? seen?))))

;; =============================================================================
;; Adapter Record
;; =============================================================================

(defrecord ImapMailboxAdapter [host port username password ssl? folder])

(extend-protocol ports/IImapMailbox
  ImapMailboxAdapter

  (fetch-messages!
    ([this]
     (ports/fetch-messages! this {}))
    ([this {:keys [folder limit unread-only? since] :as _opts}]
     (let [folder-name (or folder (:folder this) "INBOX")]
       (log/info "Fetching IMAP messages" {:host (:host this) :folder folder-name})
       (try
         (with-imap-connection* this folder-name true
           (fn [_store fld]
             (let [messages  (.getMessages fld)
                   uid-fld   (cast com.sun.mail.imap.IMAPFolder fld)
                   all-msgs  (vec (map-indexed
                                   (fn [_i m]
                                     (message->inbound m (.getUID uid-fld m)))
                                   messages))
                   filtered  (cond-> all-msgs
                               unread-only? imap-core/filter-unread
                               since        (imap-core/filter-by-date since))
                   result    (if limit (vec (take limit filtered)) filtered)]
               {:success? true :messages result :count (count result)})))
         (catch Exception e
           (log/error e "IMAP fetch failed" {:host (:host this)})
           {:success? false
            :messages []
            :count    0
            :error    {:message (.getMessage e) :type "ImapError"}})))))

  (fetch-unread!
    ([this]
     (ports/fetch-unread! this nil))
    ([this limit]
     (ports/fetch-messages! this (cond-> {:unread-only? true}
                                   limit (assoc :limit limit)))))

  (mark-read! [this uid]
    (let [folder-name (or (:folder this) "INBOX")]
      (try
        (with-imap-connection* this folder-name false
          (fn [_store fld]
            (let [uid-fld (cast com.sun.mail.imap.IMAPFolder fld)
                  msg     (.getMessageByUID uid-fld uid)]
              (when msg
                (.setFlags msg (Flags. Flags$Flag/SEEN) true)))
            {:success? true}))
        (catch Exception e
          (log/error e "IMAP mark-read failed" {:uid uid})
          {:success? false
           :error    {:message (.getMessage e) :type "ImapError"}}))))

  (delete-message! [this uid]
    (let [folder-name (or (:folder this) "INBOX")]
      (try
        (with-imap-connection* this folder-name false
          (fn [_store fld]
            (let [uid-fld (cast com.sun.mail.imap.IMAPFolder fld)
                  msg     (.getMessageByUID uid-fld uid)]
              (when msg
                (.setFlags msg (Flags. Flags$Flag/DELETED) true))
              (.expunge fld))
            {:success? true}))
        (catch Exception e
          (log/error e "IMAP delete failed" {:uid uid})
          {:success? false
           :error    {:message (.getMessage e) :type "ImapError"}}))))

  (close! [_this]
    ;; ImapMailboxAdapter is connection-per-call; nothing to close at the adapter level.
    true))

;; =============================================================================
;; Constructor
;; =============================================================================

(defn create-imap-mailbox
  "Create an IMAP mailbox adapter.

  Config keys:
    :host     - IMAP server hostname (required)
    :port     - IMAP server port (required)
    :username - IMAP auth username (required)
    :password - IMAP auth password (required)
    :ssl?     - Enable SSL/TLS (default true)
    :folder   - Default folder name (default \"INBOX\")

  Returns:
    ImapMailboxAdapter implementing IImapMailbox"
  [{:keys [host port username password ssl? folder]
    :or   {ssl? true folder "INBOX"}}]
  {:pre [(string? host) (some? port) (string? username) (string? password)]}
  (log/info "Creating IMAP mailbox adapter" {:host host :port port :ssl? ssl? :folder folder})
  (->ImapMailboxAdapter host port username password ssl? folder))
