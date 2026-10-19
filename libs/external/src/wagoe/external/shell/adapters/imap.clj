(ns wagoe.external.shell.adapters.imap
  "IMAP mailbox adapter implementing IImapMailbox.

   Uses Jakarta Mail for IMAP access. Connections are opened per-call and closed
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
            [wagoe.external.core.mail-tls :as mail-tls]
            [wagoe.external.ports :as ports]
            [wagoe.external.schema :as schema]
            [wagoe.external.shell.resilience :as resilience]
            [clojure.tools.logging :as log])
  (:import [org.eclipse.angus.mail.imap IMAPFolder IMAPFolder$FetchProfileItem IMAPStore]
           [java.io ByteArrayInputStream ByteArrayOutputStream InputStream]
           [java.util Date Properties]
           [jakarta.mail FetchProfile FetchProfile$Item Flags Flags$Flag Folder Header
            Message Message$RecipientType Multipart Part Session Store UIDFolder$FetchProfileItem]
           [jakarta.mail.internet MimeMessage MimePart MimeUtility]
           [jakarta.mail.search AndTerm ComparisonTerm FlagTerm ReceivedDateTerm SearchTerm]))

(set! *warn-on-reflection* true)

;; =============================================================================
;; Connection Helpers
;; =============================================================================

(defn- imap-properties
  [{:keys [host port ssl?] :as config}]
  (let [prefix (if ssl? "mail.imaps" "mail.imap")
        props  (doto (Properties.)
                 (.put (str prefix ".host") (str host))
                 (.put (str prefix ".port") (str port))
                 ;; BODY.PEEK, so fetching never sets \Seen; only mark-read! does.
                 (.put (str prefix ".peek") "true"))]
    (when ssl? (.put props "mail.imaps.ssl.enable" "true"))
    (doseq [[k v] (mail-tls/tls-properties prefix config)]
      (.put props k v))
    props))

(defn- open-store
  ^Store [{:keys [host username password ssl?] :as adapter}]
  (let [props    (imap-properties adapter)
        session  (Session/getInstance props)
        protocol (if ssl? "imaps" "imap")
        store    (.getStore session ^String protocol)]
    (resilience/call! (:resilience adapter)
                      #(.connect store ^String host ^String username ^String password))
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
  (when-let [raw (not-empty (.getFileName part))]
    (not-empty (try (MimeUtility/decodeText raw) (catch Exception _ raw)))))

(defn- read-bytes [^Part part]
  (with-open [^InputStream in (.getInputStream part)]
    (.readAllBytes in)))

(defn- message-content
  "Body parts and attachments of msg. A text/plain attachment stays an attachment."
  [^Message msg]
  (reduce (fn [acc ^Part part]
            ;; One unreadable part must not cost the body and the other attachments.
            (try
              (let [info {:content-type (.getContentType part)
                          :disposition  (.getDisposition part)
                          :filename     (decoded-filename part)
                          ;; From BODYSTRUCTURE; getHeader would cost a round trip per part.
                          :content-id   (when (instance? MimePart part) (.getContentID ^MimePart part))}]
                (if (imap-core/attachment-part? info)
                  (update acc :attachments conj (imap-core/build-attachment info (read-bytes part)))
                  (update acc :body-parts conj (assoc info :content (str (.getContent part))))))
              (catch Exception e
                (log/warn e "Skipping unreadable IMAP message part")
                acc)))
          {:body-parts [] :attachments []}
          (leaf-parts msg)))

(defn- raw-bytes [^Message msg]
  (let [out (ByteArrayOutputStream.)]
    (.writeTo msg out)
    (.toByteArray out)))

(defn- parse-raw ^MimeMessage [^bytes raw]
  (MimeMessage. (Session/getInstance (Properties.)) (ByteArrayInputStream. raw)))

(defn- headers-map [^Message msg]
  (reduce (fn [acc ^Header h] (assoc acc (.getName h) (.getValue h)))
          {}
          (enumeration-seq (.getAllHeaders msg))))

(defn- received-at ^Date [^Message msg]
  (or (.getReceivedDate msg) (.getSentDate msg) (Date.)))

(defn- message->inbound
  "Convert a jakarta.mail.Message to an InboundMessage map. With include-raw? the
   message is downloaded once and parsed locally, rather than part by part."
  [^Message msg uid include-raw?]
  (let [raw          (when include-raw? (raw-bytes msg))
        ^Message src (if raw (parse-raw raw) msg)
        {:keys [body-parts attachments]}
        (try (message-content src)
             (catch Exception e
               (log/warn e "Could not read IMAP message content" {:uid uid})
               {:body-parts [] :attachments []}))
        message-id (when (instance? MimeMessage src) (.getMessageID ^MimeMessage src))]
    (cond-> (assoc (imap-core/build-inbound-message uid (str (first (.getFrom src)))
                                                    (mapv str (.getRecipients src Message$RecipientType/TO))
                                                    (str (.getSubject src))
                                                    body-parts (received-at msg) (headers-map src))
                   :seen?       (.isSet msg Flags$Flag/SEEN)
                   :attachments (imap-core/mark-embedded body-parts attachments))
      message-id (assoc :message-id message-id)
      raw        (assoc :raw raw))))

;; =============================================================================
;; Server-side Selection
;; =============================================================================

(defn- search-term
  "IMAP SEARCH for the options. A message flagged \\Deleted is never selected:
   without UIDPLUS that is all delete-message! does."
  ^SearchTerm [unread-only? ^Date since]
  (reduce (fn [^SearchTerm a ^SearchTerm b] (AndTerm. a b))
          (cond-> [(FlagTerm. (Flags. Flags$Flag/DELETED) false)]
            unread-only? (conj (FlagTerm. (Flags. Flags$Flag/SEEN) false))
            since        (conj (ReceivedDateTerm. ComparisonTerm/GE since)))))

(defn- fetch! [^IMAPFolder fld msgs & items]
  (let [profile (FetchProfile.)]
    (doseq [^FetchProfile$Item item items]
      (.add profile item))
    (.fetch fld (into-array Message msgs) profile)))

(def ^:private default-limit
  "Messages per fetch when the caller gives no :limit. Every selected message is
   held in memory with its attachments, so a fetch must have a bound."
  100)

(defn- select-messages
  "Messages matching opts, at most limit of them, oldest first. Only the selected
   ones are fetched beyond their date, in one batch."
  [^IMAPFolder fld {:keys [limit since unread-only? include-raw?]}]
  (let [limit    (or limit default-limit)
        since-ms (when since (inst-ms since))
        since    (when since-ms (Date. (long since-ms)))
        ;; SEARCH SINCE takes a date in the JVM's timezone and the server compares
        ;; it in its own, so widen by two days; the exact check follows below.
        found    (.search fld (search-term unread-only?
                                           (when since-ms (Date. (long (- since-ms (* 2 24 60 60 1000)))))))
        selected (vec (if since
                        (do (fetch! fld found IMAPFolder$FetchProfileItem/INTERNALDATE)
                            (->> found (remove #(.before (received-at %) since)) (take limit)))
                        (take limit found)))]
    (apply fetch! fld selected FetchProfile$Item/ENVELOPE FetchProfile$Item/FLAGS UIDFolder$FetchProfileItem/UID
           (when-not include-raw? [IMAPFolder$FetchProfileItem/HEADERS FetchProfile$Item/CONTENT_INFO]))
    selected))

;; =============================================================================
;; Adapter Record
;; =============================================================================

(defrecord ImapMailboxAdapter [host port username password ssl? folder])

(defn- folder-name [adapter opts]
  (or (:folder opts) (:folder adapter) "INBOX"))

(defn- uidplus? [^IMAPFolder fld]
  (.hasCapability ^IMAPStore (.getStore fld) "UIDPLUS"))

(defn- with-message
  "Open the folder read-write and call f with [folder msg] for the message
   with uid; a missing message is a :not-found error."
  [adapter uid opts op f]
  (let [fld-name (folder-name adapter opts)]
    (try
      (with-imap-connection* adapter fld-name false
        (fn [_store ^IMAPFolder fld]
          (if-let [msg (.getMessageByUID fld (long uid))]
            (f fld msg)
            {:success? false
             :error    {:message (str "No message with UID " uid " in " fld-name) :type :not-found}})))
      (catch Exception e
        (log/error e (str "IMAP " op " failed") {:uid uid :folder fld-name})
        {:success? false
         :error    {:message (.getMessage e) :type (resilience/error-type e :imap-error)}}))))

(extend-protocol ports/IImapMailbox
  ImapMailboxAdapter

  (fetch-messages!
    ([this]
     (ports/fetch-messages! this {}))
    ([this {:keys [include-raw?] :as opts}]
     (let [fld-name (folder-name this opts)]
       (log/info "Fetching IMAP messages" {:host (:host this) :folder fld-name})
       (try
         (with-imap-connection* this fld-name true
           (fn [_store ^IMAPFolder fld]
             (let [result (mapv #(assoc (message->inbound % (.getUID fld ^Message %) include-raw?)
                                        :folder fld-name)
                                (select-messages fld opts))]
               {:success? true :messages result :count (count result)})))
         (catch Exception e
           (log/error e "IMAP fetch failed" {:host (:host this)})
           {:success? false
            :messages []
            :count    0
            :error    {:message (.getMessage e) :type (resilience/error-type e :imap-error)}})))))

  (fetch-unread!
    ([this]
     (ports/fetch-unread! this nil))
    ([this limit]
     (ports/fetch-messages! this (cond-> {:unread-only? true}
                                   limit (assoc :limit limit)))))

  (mark-read!
    ([this uid]
     (ports/mark-read! this uid {}))
    ([this uid opts]
     (with-message this uid opts "mark-read"
       (fn [_fld ^Message msg]
         (.setFlags msg (Flags. Flags$Flag/SEEN) true)
         {:success? true}))))

  (delete-message!
    ([this uid]
     (ports/delete-message! this uid {}))
    ([this uid opts]
     (with-message this uid opts "delete"
       (fn [^IMAPFolder fld ^Message msg]
         (.setFlags msg (Flags. Flags$Flag/DELETED) true)
         ;; A plain EXPUNGE would also remove what other clients flagged \Deleted.
         (let [expunged? (and (uidplus? fld)
                              (pos? (alength (.expunge fld (into-array Message [msg])))))]
           {:success? true :expunged? expunged?})))))

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
    :ssl-trust, :check-server-identity?
              - TLS trust; see wagoe.external.schema/MailTls
    :retries, :retry-delay-ms, :cache, :circuit-breaker
              - see wagoe.external.shell.resilience/settings

  Returns:
    ImapMailboxAdapter implementing IImapMailbox"
  [{:keys [host port username password ssl? folder]
    :or   {ssl? true folder "INBOX"}
    :as   config}]
  {:pre [(string? host) (some? port) (string? username) (string? password)]}
  (schema/assert-mail-tls! config)
  (log/info "Creating IMAP mailbox adapter" {:host host :port port :ssl? ssl? :folder folder})
  (-> (->ImapMailboxAdapter host port username password ssl? folder)
      (merge (select-keys config [:ssl-trust :check-server-identity?]))
      (assoc :resilience (resilience/settings config (str "imap:" host ":" port) nil))))
