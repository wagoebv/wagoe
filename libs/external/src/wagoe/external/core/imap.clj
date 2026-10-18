(ns wagoe.external.core.imap
  "Pure functions for IMAP message parsing.
   No I/O, no side effects."
  (:require [wagoe.core.utils.text :as text]
            [clojure.string :as str]))

;; =============================================================================
;; Header Parsing
;; =============================================================================

(defn parse-message-headers
  "Convert a string-keyed header map to kebab-case keyword keys.

  Args:
    header-map - map of {\"Header-Name\" \"value\"}

  Returns:
    map of {:header-name \"value\"}"
  [header-map]
  (reduce-kv
   (fn [acc k v]
     (let [kw (-> (str k)
                  text/lower-case
                  (.replace " " "-")
                  keyword)]
       (assoc acc kw v)))
   {}
   (or header-map {})))

;; =============================================================================
;; MIME Part Classification
;; =============================================================================

(defn base-content-type
  "Lower-cased media type without parameters: \"text/plain; charset=UTF-8\" → \"text/plain\"."
  [content-type]
  (when content-type
    (-> (str content-type) (str/split #";" 2) first str/trim text/lower-case not-empty)))

(defn attachment-part?
  "True when a leaf MIME part is an attachment rather than body text: it has
   Content-Disposition attachment, a filename, or is not text/plain or text/html.
   A text/plain .txt or .csv file is an attachment, never the body.

  Args:
    part - {:content-type string :disposition string-or-nil :filename string-or-nil}"
  [{:keys [content-type disposition filename]}]
  (boolean
   (or (.equalsIgnoreCase "attachment" (str disposition))
       (some? filename)
       (not (#{"text/plain" "text/html"} (base-content-type content-type))))))

(defn build-attachment
  "InboundAttachment map for an attachment part and its decoded bytes."
  [{:keys [content-type filename]} ^bytes content]
  (cond-> {:content-type (or (base-content-type content-type) "application/octet-stream")
           :size         (alength content)
           :content      content}
    filename (assoc :filename filename)))

;; =============================================================================
;; Body Extraction
;; =============================================================================

(defn extract-body-text
  "Extract plain text and HTML from a sequence of body parts. The first part of
   each type wins.

  Args:
    parts - sequence of {:content-type string :content string}
            content-type is checked for \"text/plain\" and \"text/html\"

  Returns:
    {:text \"...\" :html \"...\"} — either or both may be nil"
  [parts]
  (reduce
   (fn [acc {:keys [content-type content]}]
     (case (base-content-type content-type)
       "text/html"  (update acc :html #(or % (str content)))
       "text/plain" (update acc :text #(or % (str content)))
       acc))
   {:text nil :html nil}
   (or parts [])))

;; =============================================================================
;; Message Building
;; =============================================================================

(defn build-inbound-message
  "Construct an InboundMessage map from IMAP message fields.

  Args:
    uid          - long, IMAP UID
    from         - string, sender address
    to           - vector of strings
    subject      - string
    body-parts   - seq of {:content-type :content}
    received-at  - java.util.Date or inst?
    headers      - string-keyed header map (will be converted to kebab keywords)

  Returns:
    InboundMessage map"
  [uid from to subject body-parts received-at headers]
  (let [{:keys [text html]} (extract-body-text body-parts)]
    (cond-> {:uid         uid
             :from        (str from)
             :to          (vec (map str (or to [])))
             :subject     (str subject)
             :received-at received-at
             :headers     (parse-message-headers headers)}
      text (assoc :body text)
      html (assoc :html-body html))))

;; =============================================================================
;; Summary
;; =============================================================================

(defn message-summary
  "Return a concise summary of a single InboundMessage.

  Returns:
    {:uid :from :subject :received-at :seen?}"
  [msg]
  {:uid         (:uid msg)
   :from        (:from msg)
   :subject     (:subject msg)
   :received-at (:received-at msg)
   :seen?       (boolean (:seen? msg))})
