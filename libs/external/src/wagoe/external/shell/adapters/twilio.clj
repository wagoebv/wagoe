(ns wagoe.external.shell.adapters.twilio
  "Twilio messaging adapter implementing ITwilioMessaging.

   Uses clj-http with Basic auth (account-sid + auth-token).
   SMS and WhatsApp share the same Messages endpoint; the only difference
   is the whatsapp: prefix added by core/twilio.

   Usage:
     (def twilio (create-twilio-adapter
                   {:account-sid \"ACxxx\"
                    :auth-token  \"token\"
                    :from-number \"+15005550006\"}))
     (send-sms! twilio {:to \"+31612345678\" :body \"Hello!\"})"
  (:require [wagoe.external.core.twilio :as twilio-core]
            [wagoe.external.ports :as ports]
            [clj-http.client :as http]
            [clojure.tools.logging :as log]))

;; =============================================================================
;; HTTP Helpers
;; =============================================================================

(def ^:private default-base-url "https://api.twilio.com")

(defn- messages-url
  [{:keys [account-sid base-url]}]
  (str (or base-url default-base-url)
       "/2010-04-01/Accounts/" account-sid "/Messages.json"))

(defn- post-message
  [{:keys [account-sid auth-token] :as adapter} form-params]
  (http/post (messages-url adapter)
             {:basic-auth       [account-sid auth-token]
              :form-params      form-params
              :as               :json
              :coerce           :always
              :throw-exceptions false}))

(defn- handle-response
  [response]
  (let [body   (:body response)
        status (:status response)]
    (if (< status 400)
      (let [parsed (twilio-core/parse-message-response body)]
        {:success?    true
         :message-sid (:message-sid parsed)
         :status      (:status parsed)})
      {:success? false
       :error    (let [err (twilio-core/parse-twilio-error body status)]
                   {:message (:message err)
                    :type    :twilio-error})})))

(defn- handle-exception
  [e context]
  (log/error e "Twilio API call failed" context)
  {:success? false
   :error    {:message (.getMessage e)
              :type    :network-error}})

;; =============================================================================
;; Adapter Record
;; =============================================================================

(defrecord TwilioAdapter [account-sid auth-token from-number base-url])

(extend-protocol ports/ITwilioMessaging
  TwilioAdapter

  (send-sms! [this input]
    (log/info "Sending SMS via Twilio" {:to (:to input)})
    (try
      (let [params   (twilio-core/build-sms-params input (:from-number this))
            response (post-message this params)]
        (handle-response response))
      (catch Exception e
        (handle-exception e {:op :send-sms :to (:to input)}))))

  (send-whatsapp! [this input]
    (log/info "Sending WhatsApp message via Twilio" {:to (:to input)})
    (try
      (let [params   (twilio-core/build-whatsapp-params input (:from-number this))
            response (post-message this params)]
        (handle-response response))
      (catch Exception e
        (handle-exception e {:op :send-whatsapp :to (:to input)}))))

  (get-message-status! [this sid]
    (log/info "Getting Twilio message status" {:sid sid})
    (try
      (let [url      (str (or (:base-url this) default-base-url)
                          "/2010-04-01/Accounts/" (:account-sid this)
                          "/Messages/" sid ".json")
            response (http/get url
                               {:basic-auth       [(:account-sid this) (:auth-token this)]
                                :as               :json
                                :coerce           :always
                                :throw-exceptions false})
            body     (:body response)
            status   (:status response)]
        (if (< status 400)
          {:success? true
           :status   (get body "status")}
          {:success? false
           :error    (let [err (twilio-core/parse-twilio-error body status)]
                       {:message (:message err)
                        :type    :twilio-error})}))
      (catch Exception e
        (handle-exception e {:op :get-message-status :sid sid})))))

;; =============================================================================
;; Constructor
;; =============================================================================

(defn create-twilio-adapter
  "Create a Twilio messaging adapter.

  Config keys:
    :account-sid  - Twilio Account SID (required)
    :auth-token   - Twilio Auth Token (required)
    :from-number  - Default From phone number in E.164 format (required)
    :base-url     - Override API base URL for testing (default Twilio production)

  Returns:
    TwilioAdapter implementing ITwilioMessaging"
  [{:keys [account-sid auth-token from-number base-url]}]
  {:pre [(string? account-sid) (string? auth-token) (string? from-number)]}
  (log/info "Creating Twilio adapter" {:account-sid account-sid})
  (->TwilioAdapter account-sid auth-token from-number base-url))
