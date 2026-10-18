(ns wagoe.external.shell.adapters.imap-greenmail-test
  "ImapMailboxAdapter against a real IMAP server (GreenMail, in-process)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [malli.core :as m]
            [wagoe.external.ports :as ports]
            [wagoe.external.schema :as schema]
            [wagoe.external.shell.adapters.imap :as imap])
  (:import [com.icegreen.greenmail.util GreenMail ServerSetupTest]
           [java.io ByteArrayInputStream]
           [java.time Instant]
           [java.util Date Properties]
           [javax.activation DataHandler]
           [javax.mail Flags Flags$Flag Part Session]
           [javax.mail.internet MimeBodyPart MimeMessage MimeMultipart]
           [javax.mail.util ByteArrayDataSource]))

(def ^:private user "inbox@example.com")
(def ^:private password "secret")
(def ^:private pdf-bytes (.getBytes "%PDF-1.4 fake invoice" "ISO-8859-1"))
(def ^:private png-bytes (byte-array [-119 80 78 71 13 10 26 10 0 1 2 3]))

(def ^:private ^:dynamic *mailbox* nil)

(defn- session [] (Session/getInstance (Properties.)))

(defn- day [n] (Date/from (Instant/parse (format "2026-01-%02dT10:00:00Z" n))))

(defn- text-part [^String text]
  (doto (MimeBodyPart.) (.setText text "UTF-8")))

(defn- html-part [^String html]
  (doto (MimeBodyPart.) (.setContent html "text/html; charset=UTF-8")))

(defn- file-part [^bytes content ^String content-type ^String filename ^String disposition]
  (doto (MimeBodyPart.)
    (.setDataHandler (DataHandler. (ByteArrayDataSource. content content-type)))
    (.setFileName filename)
    (.setDisposition disposition)))

(defn- multipart ^MimeMultipart [^String subtype & parts]
  (let [mp (MimeMultipart. subtype)]
    (doseq [^MimeBodyPart p parts] (.addBodyPart mp p))
    mp))

(defn- nested-part [^MimeMultipart mp]
  (doto (MimeBodyPart.) (.setContent mp)))

(defn- message ^MimeMessage [subject content]
  (let [msg (doto (MimeMessage. ^Session (session))
              (.setFrom "sender@example.com")
              (.setRecipients javax.mail.Message$RecipientType/TO "inbox@example.com")
              (.setSubject ^String subject))]
    (if (string? content)
      (.setText msg ^String content "UTF-8")
      (.setContent msg ^MimeMultipart content))
    (doto msg .saveChanges)))

(def ^:private fixtures
  "[subject content seen? internal-date], in delivery order."
  [["plain" "Hello plain" false (day 10)]
   ["alternative" (multipart "alternative" (text-part "Alt text") (html-part "<p>Alt html</p>"))
    false (day 11)]
   ["pdf" (multipart "mixed" (text-part "See attached")
                     (file-part pdf-bytes "application/pdf" "invoice.pdf" Part/ATTACHMENT))
    false (day 12)]
   ["text attachments" (multipart "mixed" (html-part "<p>Html only</p>")
                                  (file-part (.getBytes "my notes" "UTF-8") "text/plain" "notes.txt" Part/ATTACHMENT)
                                  (file-part (.getBytes "a,b\n1,2" "UTF-8") "text/csv" "data.csv" Part/ATTACHMENT))
    false (day 13)]
   ["nested" (multipart "mixed"
                        (nested-part (multipart "alternative" (text-part "Nested text") (html-part "<p>Nested html</p>")))
                        ;; A filename with an inline disposition is still an attachment.
                        (file-part png-bytes "image/png" "logo.png" Part/INLINE))
    true (day 14)]
   ["seen" "Already read" true (day 15)]])

(defn- with-greenmail [f]
  (let [gm (GreenMail. (.dynamicPort ServerSetupTest/IMAP))]
    (.start gm)
    (try
      (let [gm-user (.setUser gm user user password)
            inbox   (-> gm .getManagers .getImapHostManager (.getInbox gm-user))]
        (doseq [[subject content seen? date] fixtures]
          (.appendMessage inbox (message subject content)
                          (if seen? (Flags. Flags$Flag/SEEN) (Flags.))
                          date))
        (binding [*mailbox* (imap/create-imap-mailbox
                             {:host "127.0.0.1" :port (.getPort (.getImap gm))
                              :username user :password password :ssl? false})]
          (f)))
      (finally (.stop gm)))))

(use-fixtures :each with-greenmail)

(defn- fetch [opts]
  (let [result (ports/fetch-messages! *mailbox* opts)]
    (is (:success? result) (pr-str (:error result)))
    (:messages result)))

(defn- by-subject [msgs] (into {} (map (juxt :subject identity)) msgs))

(defn- attachment-summary [msg]
  (mapv #(-> (select-keys % [:filename :content-type :size])
             (assoc :text (String. ^bytes (:content %) "UTF-8")))
        (:attachments msg)))

(deftest ^:integration body-and-attachments-test
  (let [all  (fetch {})
        msgs (by-subject all)]
    (is (= (mapv first fixtures) (mapv :subject all)))
    (is (every? #(m/validate schema/InboundMessage %) (vals msgs)))

    (testing "plain message"
      (is (= "Hello plain" (get-in msgs ["plain" :body])))
      (is (nil? (get-in msgs ["plain" :html-body])))
      (is (= [] (get-in msgs ["plain" :attachments]))))

    (testing "text/html alternatives are both read"
      (is (= "Alt text" (get-in msgs ["alternative" :body])))
      (is (= "<p>Alt html</p>" (get-in msgs ["alternative" :html-body]))))

    (testing "a PDF attachment keeps its bytes"
      (let [msg (msgs "pdf")
            [att] (:attachments msg)]
        (is (= "See attached" (:body msg)))
        (is (= 1 (count (:attachments msg))))
        (is (= {:filename "invoice.pdf" :content-type "application/pdf" :size (alength ^bytes pdf-bytes)}
               (select-keys att [:filename :content-type :size])))
        (is (java.util.Arrays/equals ^bytes pdf-bytes ^bytes (:content att)))))

    (testing "a .txt or .csv attachment never becomes the body"
      (let [msg (msgs "text attachments")]
        (is (nil? (:body msg)))
        (is (= "<p>Html only</p>" (:html-body msg)))
        (is (= [{:filename "notes.txt" :content-type "text/plain" :size 8 :text "my notes"}
                {:filename "data.csv" :content-type "text/csv" :size 7 :text "a,b\n1,2"}]
               (attachment-summary msg)))))

    (testing "nested multiparts are walked"
      (let [msg (msgs "nested")]
        (is (= "Nested text" (:body msg)))
        (is (= "<p>Nested html</p>" (:html-body msg)))
        (is (= ["logo.png"] (mapv :filename (:attachments msg))))
        (is (java.util.Arrays/equals ^bytes png-bytes ^bytes (:content (first (:attachments msg)))))))

    (testing "seen flag, message-id and received-at"
      (is (= [false false false false true true] (mapv (comp :seen? msgs first) fixtures)))
      (is (every? #(str/starts-with? (str (:message-id %)) "<") (vals msgs)))
      (is (= (day 10) (get-in msgs ["plain" :received-at]))))

    (testing "raw bytes only on request"
      (is (not-any? :raw (vals msgs)))
      (let [[msg] (fetch {:limit 1 :include-raw? true})
            parsed (MimeMessage. ^Session (session) (ByteArrayInputStream. ^bytes (:raw msg)))]
        (is (= "plain" (.getSubject parsed)))))))

(deftest ^:integration unread-limit-since-test
  (testing "unread-only? selects unseen messages"
    (is (= ["plain" "alternative" "pdf" "text attachments"]
           (mapv :subject (fetch {:unread-only? true})))))

  (testing "limit takes the oldest matches"
    (is (= ["plain" "alternative"] (mapv :subject (fetch {:limit 2}))))
    (is (= ["plain"] (mapv :subject (:messages (ports/fetch-unread! *mailbox* 1))))))

  (testing "since is exact to the instant, not just the date"
    (is (= ["text attachments" "nested" "seen"]
           (mapv :subject (fetch {:since (Date/from (Instant/parse "2026-01-12T12:00:00Z"))}))))
    (is (= ["text attachments"]
           (mapv :subject (fetch {:since (Instant/parse "2026-01-12T12:00:00Z")
                                  :unread-only? true :limit 1})))))

  (testing "fetching does not mark messages read; mark-read! does"
    (let [uid (:uid (first (fetch {:unread-only? true})))]
      (is (= 4 (count (fetch {:unread-only? true}))))
      (is (:success? (ports/mark-read! *mailbox* uid)))
      (is (= ["alternative" "pdf" "text attachments"]
             (mapv :subject (fetch {:unread-only? true})))))))
