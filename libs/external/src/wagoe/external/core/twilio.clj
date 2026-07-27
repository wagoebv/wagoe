(ns wagoe.external.core.twilio
  "Pure functions for Twilio API data transformation.
   No I/O, no side effects.")

;; =============================================================================
;; Parameter Building
;; =============================================================================

(defn build-sms-params
  "Build Twilio SMS POST params.

  Args:
    input       - SendMessageInput map with :to :body and optional :from
    from-number - default From number string (used if :from not in input)

  Returns:
    Map of string keys for use as :form-params in clj-http"
  [{:keys [to body from media-url]} from-number]
  (cond-> {"To"   (str to)
           "From" (str (or from from-number))
           "Body" (str body)}
    media-url (assoc "MediaUrl" media-url)))

(defn build-whatsapp-params
  "Build Twilio WhatsApp POST params.
   Automatically prefixes 'whatsapp:' to the To and From numbers.

  Args:
    input       - SendMessageInput map with :to :body and optional :from
    from-number - default From number (used if :from not in input)

  Returns:
    Map of string keys for use as :form-params in clj-http"
  [{:keys [to body from media-url]} from-number]
  (let [to-num   (str to)
        from-num (str (or from from-number))
        wa-to    (if (.startsWith to-num "whatsapp:") to-num (str "whatsapp:" to-num))
        wa-from  (if (.startsWith from-num "whatsapp:") from-num (str "whatsapp:" from-num))]
    (cond-> {"To"   wa-to
             "From" wa-from
             "Body" (str body)}
      media-url (assoc "MediaUrl" media-url))))

;; =============================================================================
;; Response Parsing
;; =============================================================================

(defn parse-message-response
  "Parse a successful Twilio message API response body.

  Args:
    body - parsed JSON map (string keys) from Twilio API response

  Returns:
    Map with :message-sid :status :to :from"
  [body]
  {:message-sid (get body "sid")
   :status      (get body "status")
   :to          (get body "to")
   :from        (get body "from")})

(defn parse-twilio-error
  "Parse a Twilio error response body.

  Args:
    body        - parsed JSON map (string keys) from Twilio error response
    status-code - HTTP status code integer

  Returns:
    Map with :message :code :more-info :status-code"
  [body status-code]
  {:message     (get body "message" "Unknown Twilio error")
   :code        (get body "code")
   :more-info   (get body "more_info")
   :status-code status-code})
