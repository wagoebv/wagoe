(ns wagoe.payments.shell.adapters.stripe
  "Stripe payment provider adapter."
  (:require [wagoe.payments.core.provider :as provider]
            [wagoe.payments.ports :as ports]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.util UUID]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

(def ^:private stripe-api-base "https://api.stripe.com/v1")

(defn- stripe-headers [api-key]
  {"Authorization" (str "Bearer " api-key)
   "Accept"        "application/json"
   "User-Agent"    "wagoe-payments/1.0"})

(defn- url-encode [^String s]
  (java.net.URLEncoder/encode s "UTF-8"))

(defn- form-encode
  "Encode a flat string->string map as application/x-www-form-urlencoded."
  [^java.util.Map params]
  (->> params
       (map (fn [[k v]] (str (url-encode (name k)) "=" (url-encode (str v)))))
       (str/join "&")))

(defn- hmac-sha256 ^bytes [^bytes key-bytes ^bytes data-bytes]
  (let [mac (Mac/getInstance "HmacSHA256")
        key (SecretKeySpec. key-bytes "HmacSHA256")]
    (.init mac key)
    (.doFinal mac data-bytes)))

(defn- hex-encode [^bytes b]
  (apply str (map #(format "%02x" (bit-and % 0xff)) b)))

(defn- constant-time-equal? [^String a ^String b]
  (MessageDigest/isEqual (.getBytes a "UTF-8") (.getBytes b "UTF-8")))

(defn- compute-stripe-signature [raw-body timestamp webhook-secret]
  (let [signed-payload (str timestamp "." raw-body)
        key-bytes      (.getBytes ^String webhook-secret StandardCharsets/UTF_8)
        data-bytes     (.getBytes ^String signed-payload StandardCharsets/UTF_8)]
    (hex-encode (hmac-sha256 key-bytes data-bytes))))

(defn- parse-stripe-signature-header
  "Parse Stripe-Signature header into {:t timestamp :v1 signature}."
  [header]
  (when header
    (into {}
          (for [part (str/split (str header) #",")
                :let [[k v] (str/split part #"=" 2)]
                :when (and k v)]
            [k v]))))

(defn- post-form
  "POST form-encoded params to a Stripe endpoint. Returns {:status int :body parsed-map}.
   Optional extra-headers (e.g. {\"Idempotency-Key\" \"...\"}) merge over the defaults."
  ([api-key path params] (post-form api-key path params nil))
  ([api-key path params extra-headers]
   (let [response (http/post (str stripe-api-base path)
                             {:headers          (merge (stripe-headers api-key)
                                                       {"Content-Type" "application/x-www-form-urlencoded"}
                                                       extra-headers)
                              :body             (form-encode params)
                              :as               :string
                              :throw-exceptions false})]
     {:status (:status response)
      :body   (json/parse-string (:body response) true)})))

(defn- pending-payment-status
  "Degrade a failed status poll to :pending — same convention as the Mollie
   adapter — except for auth/config errors (401/403), which throw.
   :provider-payment-id is deliberately absent (not nil) so callers merging
   the result cannot clobber a stored payment id."
  [provider-id status body]
  (if (contains? #{401 403} status)
    (throw (ex-info "Stripe get-payment-status authentication failed"
                    {:type        :internal-error
                     :provider-id provider-id
                     :status      status
                     :body        body}))
    (do (log/warnf "Stripe get-payment-status: %s returned %s — reporting :pending"
                   provider-id status)
        {:status :pending})))

(defn- checkout-error-fields
  "Diagnostic fields pulled from a non-2xx Stripe response body. Stripe returns
   the reason under :error (message / param / code / type); surfacing them turns
   an opaque \"status=400 id=null\" line into an actionable one (BOU-127)."
  [status body]
  {:status  status
   :type    (get-in body [:error :type])
   :code    (get-in body [:error :code])
   :param   (get-in body [:error :param])
   :message (get-in body [:error :message])})

(defrecord StripePaymentProvider [api-key webhook-secret]
  ports/IPaymentProvider

  (provider-name [_] :stripe)

  (create-checkout-session [_ opts]
    (let [checkout-id (str (UUID/randomUUID))
          params      (provider/stripe-checkout-params (assoc opts :checkout-id checkout-id))
          _           (doseq [k ["success_url" "cancel_url"]]
                        ;; Fail fast on an invalid return URL instead of POSTing
                        ;; it to Stripe, which answers with an opaque 400. Both
                        ;; URLs resolve from :success-url/:cancel-url with a
                        ;; blank-safe fallback to :redirect-url; a broken upstream
                        ;; config (e.g. an unset PUBLIC_BASE_URL) yields either an
                        ;; empty string (`parameter_invalid_empty`, BOU-148) or a
                        ;; scheme-less relative path like
                        ;; "/web/license/payment/return?…" (`url_invalid`,
                        ;; BOU-149). Stripe requires an ABSOLUTE http(s) URL, so
                        ;; reject anything that is not — surfaced here, named,
                        ;; rather than as a provider error.
                        (when-not (re-find #"(?i)^https?://.+" (str (get params k)))
                          (throw (ex-info (str "Stripe checkout: " k " is not an absolute http(s) URL")
                                          {:type  :config-error
                                           :param k
                                           :value (get params k)
                                           :fix   "Provide an absolute https:// return URL (:success-url/:cancel-url, or :redirect-url as fallback); set PUBLIC_BASE_URL in acc/prod so the configured URL is not left relative"}))))
          {:keys [status body]} (post-form api-key "/checkout/sessions" params)]
      (log/infof "Stripe create-checkout: status=%d id=%s" status (:id body))
      (when-not (#{200 201} status)
        ;; Log the Stripe error reason (message/param/code) so the 400 is not
        ;; opaque in the logs; the full body still travels on the ex-info.
        (log/errorf "Stripe checkout creation failed: %s"
                    (pr-str (checkout-error-fields status body)))
        (throw (ex-info "Stripe checkout creation failed"
                        {:type   :internal-error
                         :status status
                         :body   body})))
      (cond-> {:checkout-url         (:url body)
               :provider-checkout-id (:id body)
               ;; Internal correlation id, also embedded in the PaymentIntent
               ;; metadata (payment_intent_data[metadata][checkout_id]) so the
               ;; webhook can recover it. Consumers store this to match webhooks.
               :correlation-id       checkout-id}
        (:payment_intent body)
        (assoc :provider-payment-id (provider/stripe-object-id (:payment_intent body))))))

  (create-off-session-payment [_ opts]
    ;; Confirmed off-session PaymentIntent against a stored customer/mandate.
    ;; Card outcomes (card_declined, authentication_required, ...) are a normal
    ;; business result of an unattended charge → returned as :failed. Auth and
    ;; config errors still throw.
    ;; A provider-side Idempotency-Key (the stable business identity of the
    ;; charge, e.g. "incasso-<sub>-<period>") makes a retry after a lost response
    ;; return the original PaymentIntent instead of double-charging.
    (let [params  (provider/stripe-off-session-params opts)
          headers (when-let [k (:idempotency-key opts)]
                    {"Idempotency-Key" k})
          {:keys [status body]} (post-form api-key "/payment_intents" params headers)]
      (cond
        (#{200 201} status)
        (do (log/infof "Stripe off-session payment: id=%s status=%s" (:id body) (:status body))
            {:provider-payment-id (:id body)
             :status              (provider/stripe-intent-status->payment-status
                                   (:status body) {:off-session? true})})

        (= "card_error" (get-in body [:error :type]))
        (let [pi-id (provider/stripe-object-id (get-in body [:error :payment_intent]))]
          (log/warnf "Stripe off-session payment declined: code=%s pi=%s"
                     (get-in body [:error :code]) pi-id)
          (if pi-id
            {:provider-payment-id pi-id :status :failed}
            (throw (ex-info "Stripe off-session payment declined without a PaymentIntent"
                            {:type   :internal-error
                             :status status
                             :body   body}))))

        :else
        (throw (ex-info "Stripe off-session payment failed"
                        {:type   :internal-error
                         :status status
                         :body   body})))))

  (expire-checkout-session [_ provider-checkout-id]
    (let [{:keys [status body]} (post-form api-key
                                           (str "/checkout/sessions/" provider-checkout-id "/expire")
                                           {})]
      (log/infof "Stripe expire-checkout: status=%s id=%s" status (:id body))
      (when-not (= 200 status)
        (throw (ex-info "Stripe expire-checkout-session failed"
                        {:type                 :internal-error
                         :provider-checkout-id provider-checkout-id
                         :status               status
                         :body                 body})))
      {:provider-checkout-id (or (:id body) provider-checkout-id)
       :status               :expired}))

  (get-payment-status [_ provider-id]
    ;; Accepts both Checkout Session ids (cs_...) and PaymentIntent ids
    ;; (pi_...), dispatched by prefix. Session polls expand the PaymentIntent
    ;; so completed checkouts expose provider-customer-id and
    ;; provider-payment-method-id for mandate storage.
    (if (provider/stripe-payment-intent-id? provider-id)
      (let [response (http/get (str stripe-api-base "/payment_intents/" provider-id)
                               {:headers          (stripe-headers api-key)
                                :as               :string
                                :throw-exceptions false})
            body     (json/parse-string (:body response) true)]
        (if (= 200 (:status response))
          {:status                     (provider/stripe-intent-status->payment-status (:status body))
           :provider-payment-id        (:id body)
           :provider-customer-id       (provider/stripe-object-id (:customer body))
           :provider-payment-method-id (provider/stripe-object-id (:payment_method body))}
          (pending-payment-status provider-id (:status response) body)))
      (let [response (http/get (str stripe-api-base "/checkout/sessions/" provider-id)
                               {:headers          (stripe-headers api-key)
                                :query-params     {"expand[]" "payment_intent"}
                                :as               :string
                                :throw-exceptions false})
            body     (json/parse-string (:body response) true)
            pi       (:payment_intent body)]
        (if (= 200 (:status response))
          {:status                     (provider/stripe-session-status->payment-status
                                        (:status body) (:payment_status body))
           :provider-payment-id        (provider/stripe-object-id pi)
           :provider-customer-id       (provider/stripe-object-id (:customer body))
           :provider-payment-method-id (when (map? pi)
                                         (provider/stripe-object-id (:payment_method pi)))}
          (pending-payment-status provider-id (:status response) body)))))

  (verify-webhook-signature [_ raw-body headers]
    (let [sig-header (or (get headers "stripe-signature")
                         (get headers "Stripe-Signature"))
          parts      (parse-stripe-signature-header sig-header)
          ts-str     (get parts "t")
          v1         (get parts "v1")]
      (if (and ts-str v1 webhook-secret)
        (try
          (let [ts        (Long/parseLong ts-str)
                now-epoch (quot (System/currentTimeMillis) 1000)
                age       (Math/abs (- now-epoch ts))]
            (and (<= age 300)
                 (constant-time-equal?
                  (compute-stripe-signature raw-body ts-str webhook-secret) v1)))
          (catch NumberFormatException _
            (log/warnf "Stripe webhook: malformed timestamp in Stripe-Signature header: %s" ts-str)
            false))
        false)))

  (process-webhook [_ raw-body _headers]
    (let [event      (if (string? raw-body)
                       (json/parse-string raw-body true)
                       (or raw-body {}))
          event-type (provider/stripe-event->event-type (:type event))
          pi-obj     (get-in event [:data :object])]
      ;; event-type is nil for any Stripe type outside the generic payment.*
      ;; mapping (checkout.session.*, charge.dispute.*, and everything else a
      ;; connected endpoint receives by default). Those are NOT errors: the
      ;; billing layer's event-action routes them by payload type
      ;; (checkout.session.completed → paid, …) or ignores them. A
      ;; signature-verified event MUST be acknowledged with HTTP 200 — throwing
      ;; here surfaces as a 500 and makes Stripe retry for days (BOU-147) — so we
      ;; return a nil-event-type result carrying the full payload instead.
      (log/infof "Stripe webhook: type=%s → event=%s" (:type event) event-type)
      ;; payment_intent.* events carry the PaymentIntent id (pi_…) and the
      ;; internal correlation id from metadata, but NOT the cs_… session id —
      ;; so we surface :correlation-id (round-trips CheckoutResult) and omit the
      ;; misleading :provider-checkout-id rather than aliasing the UUID to it.
      {:event-type            event-type
       :provider-payment-id   (:id pi-obj)
       :correlation-id        (get-in pi-obj [:metadata :checkout_id])
       :payload               event})))
