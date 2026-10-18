(ns wagoe.external.shell.adapters.imap
  "IMAP mailbox adapter implementing IImapMailbox.

   Uses javax.mail for IMAP access. Connections are opened per-call and closed
   in a finally block. Selection (unread, since, limit) runs as an IMAP SEARCH on
   envelopes; content is read only for the selected messages.

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
  (:import [com.sun.mail.imap IMAPFolder]
           [java.io ByteArrayOutputStream InputStream]
           [java.util Date Properties]
           [javax.mail FetchProfile FetchProfile$Item Flags Flags$Flag Folder Header
            Message Message$RecipientType Multipart Part Session Store UIDFolder$FetchProfileItem]
           [javax.mail.internet MimeMessage MimeUtility]
           [javax.mail.search AndTerm ComparisonTerm FlagTerm ReceivedDateTerm SearchTerm]))

(set! *warn-on-reflection* true)

;; =============================================================================
;; Connection Helpers
;; =============================================================================

(defn- imap-properties
  [{:keys [host port ssl?]}]
  ;; peek: read with BODY.PEEK, so fetching never sets \Seen; only mark-read! does.
  (let [props (Properties.)]
    (if ssl?
      (do
        (.put props "mail.imaps.host" (str host))
        (.put props "mail.imaps.port" (str port))
        (.put props "mail.imaps.ssl.enable" "true")
        (.put props "mail.imaps.peek" "true"))
      (do
        (.put props "mail.imap.host" (str host))
        (.put props "mail.imap.port" (str port))
        (.put props "mail.imap.peek" "true")))
    props))

(defn- open-store
  ^Store [{:keys [host username password ssl?] :as adapter}]
  (let [props    (imap-properties adapter)
        session  (Session/getInstance props)
        protocol (if ssl? "imaps" "imap")
        store    (.getStore session ^String protocol)]
    (.connect store ^String host ^String username ^String password)
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
  (let [^Store store (open-store adapter)
        folder      (.getFolder store (str folder-name))]
    (try
      (.open folder (if read-only? Folder/READ_ONLY Folder/READ_WRITE))
      (f store folder)
      (finally
        (try (.close folder false) (catch Exception _))
        (try (.close store) (catch Exception _))))))

;; =============================================================================
;; Message Conversion
;; =============================================================================

(defn- leaf-parts
  "Depth-first leaves of a MIME tree; nested multiparts are walked."
  [^Part part]
  (if (.isMimeType part "multipart/*")
    (let [^Multipart mp (.getContent part)]
      (mapcat #(leaf-parts (.getBodyPart mp (int %))) (range (.getCount mp))))
    [part]))

(defn- decoded-filename [^Part part]
  (when-let [raw (.getFileName part)]
    (try (MimeUtility/decodeText raw) (catch Exception _ raw))))

(defn- read-bytes [^Part part]
  (with-open [^InputStream in (.getInputStream part)]
    (.readAllBytes in)))

(defn- message-content
  "Body parts and attachments of msg. A text/plain attachment stays an attachment."
  [^Message msg]
  (reduce (fn [acc ^Part part]
            (let [info {:content-type (.getContentType part)
                        :disposition  (.getDisposition part)
                        :filename     (decoded-filename part)}]
              (if (imap-core/attachment-part? info)
                (update acc :attachments conj (imap-core/build-attachment info (read-bytes part)))
                (update acc :body-parts conj (assoc info :content (str (.getContent part)))))))
          {:body-parts [] :attachments []}
          (leaf-parts msg)))

(defn- raw-bytes [^Message msg]
  (let [out (ByteArrayOutputStream.)]
    (.writeTo msg out)
    (.toByteArray out)))

(defn- headers-map [^Message msg]
  (reduce (fn [acc ^Header h] (assoc acc (.getName h) (.getValue h)))
          {}
          (enumeration-seq (.getAllHeaders msg))))

(defn- received-at ^Date [^Message msg]
  (or (.getReceivedDate msg) (.getSentDate msg) (Date.)))

(defn- message->inbound
  "Convert a javax.mail.Message to an InboundMessage map.
   uid must be pre-fetched from the IMAPFolder."
  [^Message msg uid include-raw?]
  (let [from       (first (.getFrom msg))
        to-addrs   (mapv str (.getRecipients msg Message$RecipientType/TO))
        {:keys [body-parts attachments]}
        (try (message-content msg)
             (catch Exception e
               (log/warn e "Could not read IMAP message content" {:uid uid})
               {:body-parts [] :attachments []}))
        message-id (when (instance? MimeMessage msg) (.getMessageID ^MimeMessage msg))]
    (cond-> (assoc (imap-core/build-inbound-message uid (str from) to-addrs (str (.getSubject msg))
                                                    body-parts (received-at msg) (headers-map msg))
                   :seen?       (.isSet msg Flags$Flag/SEEN)
                   :attachments attachments)
      message-id   (assoc :message-id message-id)
      include-raw? (assoc :raw (raw-bytes msg)))))

;; =============================================================================
;; Server-side Selection
;; =============================================================================

(defn- search-term
  "IMAP SEARCH for the options, or nil to take the whole folder. SINCE matches by
   date only, so the exact instant is checked again on the envelope."
  ^SearchTerm [{:keys [unread-only? since]}]
  (let [terms (cond-> []
                unread-only? (conj (FlagTerm. (Flags. Flags$Flag/SEEN) false))
                since        (conj (ReceivedDateTerm. ComparisonTerm/GE ^Date since)))]
    (case (count terms)
      0 nil
      1 (first terms)
      (AndTerm. ^"[Ljavax.mail.search.SearchTerm;" (into-array SearchTerm terms)))))

(defn- select-messages
  "Messages matching opts, at most limit of them, oldest first. Only envelope,
   flags and UID are fetched; content is read later for the selected ones."
  [^IMAPFolder fld {:keys [limit since] :as opts}]
  (let [term    (search-term opts)
        found   (if term (.search fld term) (.getMessages fld))
        ;; Without since no date check follows, so limit can cut before the fetch.
        msgs    (into-array Message (cond->> found (and limit (not since)) (take limit)))
        profile (doto (FetchProfile.)
                  (.add FetchProfile$Item/ENVELOPE)
                  (.add FetchProfile$Item/FLAGS)
                  (.add UIDFolder$FetchProfileItem/UID))
        _       (.fetch fld msgs profile)
        dated   (imap-core/filter-by-date (map (fn [m] {:message m :received-at (received-at m)}) msgs)
                                          since)]
    (cond->> (map :message dated)
      limit (take limit))))

;; =============================================================================
;; Adapter Record
;; =============================================================================

(defrecord ImapMailboxAdapter [host port username password ssl? folder])

(extend-protocol ports/IImapMailbox
  ImapMailboxAdapter

  (fetch-messages!
    ([this]
     (ports/fetch-messages! this {}))
    ([this {:keys [folder include-raw? since] :as opts}]
     (let [folder-name (or folder (:folder this) "INBOX")]
       (log/info "Fetching IMAP messages" {:host (:host this) :folder folder-name})
       (try
         (with-imap-connection* this folder-name true
           (fn [_store ^IMAPFolder fld]
             (let [opts   (cond-> opts since (assoc :since (Date. (long (inst-ms since)))))
                   result (mapv #(message->inbound % (.getUID fld ^Message %) include-raw?)
                                (select-messages fld opts))]
               {:success? true :messages result :count (count result)})))
         (catch Exception e
           (log/error e "IMAP fetch failed" {:host (:host this)})
           {:success? false
            :messages []
            :count    0
            :error    {:message (.getMessage e) :type :imap-error}})))))

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
          (fn [_store ^IMAPFolder fld]
            (when-let [msg (.getMessageByUID fld (long uid))]
              (.setFlags msg (Flags. Flags$Flag/SEEN) true))
            {:success? true}))
        (catch Exception e
          (log/error e "IMAP mark-read failed" {:uid uid})
          {:success? false
           :error    {:message (.getMessage e) :type :imap-error}}))))

  (delete-message! [this uid]
    (let [folder-name (or (:folder this) "INBOX")]
      (try
        (with-imap-connection* this folder-name false
          (fn [_store ^IMAPFolder fld]
            (when-let [msg (.getMessageByUID fld (long uid))]
              (.setFlags msg (Flags. Flags$Flag/DELETED) true))
            (.expunge fld)
            {:success? true}))
        (catch Exception e
          (log/error e "IMAP delete failed" {:uid uid})
          {:success? false
           :error    {:message (.getMessage e) :type :imap-error}}))))

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
