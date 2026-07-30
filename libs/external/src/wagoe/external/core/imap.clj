(ns wagoe.external.core.imap
  "Pure functions for IMAP message parsing and filtering.
   No I/O, no side effects.")

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
                  (.toLowerCase)
                  (.replace " " "-")
                  keyword)]
       (assoc acc kw v)))
   {}
   (or header-map {})))

;; =============================================================================
;; Body Extraction
;; =============================================================================

(defn extract-body-text
  "Extract plain text and HTML from a sequence of body parts.

  Args:
    parts - sequence of {:content-type string :content string}
            content-type is checked for \"text/plain\" and \"text/html\"

  Returns:
    {:text \"...\" :html \"...\"} — either or both may be nil"
  [parts]
  (reduce
   (fn [acc {:keys [content-type content]}]
     (let [ct (some-> content-type str .toLowerCase)]
       (cond
         (and ct (.contains ct "text/html"))  (assoc acc :html (str content))
         (and ct (.contains ct "text/plain")) (assoc acc :text (str content))
         :else acc)))
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
;; Filtering
;; =============================================================================

(defn filter-by-date
  "Keep only messages received at or after since-inst.

  Args:
    messages   - seq of InboundMessage maps
    since-inst - java.util.Date or inst?

  Returns:
    Filtered vector"
  [messages since-inst]
  (if (nil? since-inst)
    (vec messages)
    (vec (filter #(not (.before ^java.util.Date (:received-at %) since-inst)) messages))))

(defn filter-unread
  "Keep only messages with :seen? false or absent.

  Args:
    messages - seq of InboundMessage maps

  Returns:
    Filtered vector"
  [messages]
  (vec (filter #(not (:seen? %)) messages)))

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
