(ns wagoe.platform.shell.rpc.client
  "Generic remote-port adapter: implement any module protocol over HTTP.

   Cross-module calls go through a protocol, so slicing a module into its own
   process needs one thing the codebase did not have — an implementation of
   that protocol that makes a network call. This builds one for an arbitrary
   protocol, so the caller keeps calling `(ports/create-checkout-session svc …)`
   and does not learn that the answer now comes over a socket (BOU-90).

   Modelled on libs/external's outbound adapters: clj-http, `:throw-exceptions
   false`, errors as data rather than exceptions.

   FC/IS: shell. The wire contract is pure and lives in
   `wagoe.platform.core.rpc`."
  (:require [wagoe.platform.core.rpc :as rpc]
            [clj-http.client :as http]
            [clojure.tools.logging :as log]
            [muuntaja.core :as m]))

;; =============================================================================
;; Wire format
;; =============================================================================

(def ^:private content-type
  "transit+json, not JSON.

   Both ends are Clojure and the protocol's values are Clojure values. JSON has
   no keywords, so `{:status :paid}` comes back as `{:status \"paid\"}` and a
   caller's `(= :paid (:status r))` — which held in-process — quietly stops
   holding across the hop. That is precisely the transparency the adapter
   exists to provide, and payments returns keyword-valued statuses today.

   Transit also carries sets, UUIDs and instants, all of which JSON flattens.
   muuntaja negotiates this format already, so the server side needs nothing:
   the app's existing format middleware decodes it into `:body-params`."
  "application/transit+json")

;; =============================================================================
;; Retry
;; =============================================================================

(def default-opts
  {:timeout-ms   5000
   :retries      2
   :retry-delay-ms 100
   ;; Only failures that a second attempt could plausibly fix. Retrying a
   ;; remote error would re-send a payment that the far side already rejected
   ;; on its merits, and retrying a timeout risks charging twice — a call that
   ;; timed out may well have been executed.
   :retry-on     #{:rpc/unavailable}})

(defn- retryable?
  [opts {:keys [error]}]
  (contains? (:retry-on opts) (:type error)))

;; =============================================================================
;; The call
;; =============================================================================

(defn- decode-body
  "Decode a response body, or nil if it is not in our format.

   A failing service is often fronted by something that answers in HTML — a
   proxy's 502 page, a login redirect. Letting the decoder throw there would
   turn 'the service is down' into a stack trace at the call site, which is the
   one thing this adapter promises not to do."
  [body]
  (try
    (when body (m/decode m/instance content-type body))
    (catch Exception _ nil)))

(defn- post-envelope!
  "POST one envelope. Returns the parsed body, or a transport error map."
  [url envelope opts]
  (let [operation (:operation envelope)]
    (try
      (let [{:keys [status body]}
            (http/post url
                       {:body               (m/encode m/instance content-type envelope)
                        :content-type       content-type
                        :accept             content-type
                        :as                 :stream
                        :headers            (rpc/context->headers envelope)
                        :socket-timeout     (:timeout-ms opts)
                        :connection-timeout (:timeout-ms opts)
                        ;; Errors as data, like every other outbound adapter
                        ;; here — a 500 from the far side is a result to
                        ;; inspect, not a stack trace to unwind.
                        :throw-exceptions   false})
            body (decode-body body)]
        (cond
          (<= 200 status 299)
          (rpc/response->result operation body)

          ;; A non-2xx usually still carries an envelope the server built on
          ;; purpose: the operation is not one it exposes, or the
          ;; implementation threw and it said what. Replacing that with a
          ;; generic "status 500" throws away the only part of the response
          ;; that says what went wrong.
          (and (map? body) (:error body))
          {:error (rpc/revive-error (:error body))}

          :else
          (rpc/transport-error :rpc/remote-error operation
                               (str "Remote returned status " status)
                               status)))
      (catch Exception e
        (rpc/transport-error (rpc/classify-exception e) operation (.getMessage e))))))

(defn call!
  "Invoke `operation` on the service at `base-url` with positional `args`.

   `context` supplies correlation-id / tenant / auth to propagate. Retries only
   the failures a retry can fix — see `default-opts`.

   Returns whatever the remote protocol method returned, or an `{:error …}` map."
  [base-url operation args {:keys [context] :as opts}]
  (let [opts     (merge default-opts opts)
        envelope (rpc/request-envelope operation args context)
        url      (str base-url "/rpc")]
    (loop [attempt 0]
      (let [result (post-envelope! url envelope opts)]
        (cond
          (not (and (map? result) (retryable? opts result)))
          result

          (< attempt (:retries opts))
          (do (log/warn "rpc retrying" {:operation operation
                                        :attempt   (inc attempt)
                                        :error     (get-in result [:error :type])})
              (Thread/sleep (* (inc attempt) (:retry-delay-ms opts)))
              (recur (inc attempt)))

          :else
          (do (log/warn "rpc gave up" {:operation operation
                                       :attempts  (inc attempt)
                                       :error     (get-in result [:error :type])})
              result))))))

;; =============================================================================
;; Protocol proxy
;; =============================================================================

(defn protocol-operations
  "The method names a protocol declares, as keywords.

   Read from the protocol's own `:sigs`, so a method added to a port is carried
   without editing anything here — the adapter is generic or it is not useful."
  [protocol]
  (->> protocol :sigs vals (map :name) (map keyword) set))

(defrecord RemoteProxy [base-url opts])

(defn remote-adapter
  "A value implementing `protocol` by calling the service at `base-url`.

   The returned object satisfies the protocol, so a caller cannot tell it from
   the in-process implementation — which is the point: the protocol is the
   contract, and where a module runs is deployment, not code.

   Built from the protocol's own `:sigs` rather than written out per protocol,
   so every module's port works without a bespoke client.

   The implementations read `base-url` and `opts` off `this` rather than
   closing over them. `extend` mutates the class, so closures would mean a
   second adapter for the same protocol silently redirecting the first — two
   payment providers at different URLs would end up sharing whichever was
   constructed last.

   Args:
     protocol - the protocol map, e.g. wagoe.payments.ports/IPaymentProvider
     base-url - service root, e.g. \"http://payments:3001\"
     opts     - :timeout-ms :retries :retry-delay-ms :retry-on :context

   Example:
     (remote-adapter payments-ports/IPaymentProvider \"http://payments:3001\" {})"
  [protocol base-url & [opts]]
  ;; `extend` keys the map by KEYWORD method name; `:sigs` gives symbols.
  ;; Passing symbols registers the class — `satisfies?` returns true — while no
  ;; method resolves, so the failure surfaces at the first call as "No
  ;; implementation of method", not at construction.
  (let [impls (into {}
                    (for [{method :name} (vals (:sigs protocol))]
                      [(keyword method)
                       (fn [this & args]
                         (call! (:base-url this) method args (:opts this)))]))]
    (extend RemoteProxy protocol impls)
    (->RemoteProxy base-url (or opts {}))))
