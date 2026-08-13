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
  (:require [wagoe.platform.core.circuit-breaker :as cb]
            [wagoe.platform.core.rpc :as rpc]
            [wagoe.platform.shell.rpc.breaker :as breaker]
            [clj-http.client :as http]
            [clojure.tools.logging :as log]
            [muuntaja.core :as m])
  (:import (java.io ByteArrayInputStream)
           (java.lang.reflect InvocationHandler Method Proxy)))

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
  {;; Where the service mounts `rpc-handler`. Configurable because the platform
   ;; router rewrites paths depending on the slot a module puts them in: a
   ;; route returned under `:api` is served at `/api/v1/rpc`, one under `:web`
   ;; at `/rpc`. A client with the wrong one gets a 404, which reads as "the
   ;; service is not there" rather than "it is there, at another path".
   :path         "/rpc"
   :timeout-ms   5000
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

   Takes the bytes rather than a stream. `:as :stream` hands back an
   InputStream that has to be closed to return its connection to the pool, and
   under sustained traffic connections held until GC exhaust the client. Every
   branch below would have had to remember; taking bytes means there is nothing
   to remember. The envelopes are small, so holding one in memory costs
   nothing.

   A failing service is often fronted by something that answers in HTML — a
   proxy's 502 page, a login redirect. Letting the decoder throw there would
   turn 'the service is down' into a stack trace at the call site, which is the
   one thing this adapter promises not to do."
  [^bytes body]
  (try
    (when (and body (pos? (alength body)))
      (m/decode m/instance content-type (ByteArrayInputStream. body)))
    (catch Exception _ nil)))

(defn- post-envelope!
  "POST one envelope. Returns `[:answered value]` or `[:transport {:error …}]`.

   Tagged, because the two are indistinguishable once unwrapped. Adapters in
   this codebase return `{:error {:type …}}` as an ordinary value, so a
   protocol method's legitimate result can be shaped exactly like a transport
   failure — and treating one as the other means re-sending a call that already
   ran. For a non-idempotent operation that is a second charge.

   `:answered` means the service produced this: a result, or an error envelope
   it built on purpose. Retrying it is at best pointless.

   `:transport` means the call may not have completed, which is the only case
   where a retry can help."
  [url envelope opts]
  (let [operation (:operation envelope)]
    (try
      (let [{:keys [status body]}
            (http/post url
                       {:body               (m/encode m/instance content-type envelope)
                        :content-type       content-type
                        :accept             content-type
                        ;; Not :stream — see `decode-body`. clj-http consumes
                        ;; and releases the connection itself for a byte-array,
                        ;; so there is no stream left leased to the pool.
                        :as                 :byte-array
                        :headers            (cond-> (rpc/context->headers envelope)
                                              (:service-key opts)
                                              (assoc rpc/service-key-header
                                                     (:service-key opts)))
                        ;; clj-http sits on Apache HttpClient, which retries
                        ;; low-level I/O failures on its own before this code
                        ;; sees an outcome — so `:retries 0` and keeping
                        ;; `:rpc/timeout` out of `:retry-on` would not actually
                        ;; stop a checkout being submitted twice. Whether to
                        ;; resend is a decision this adapter makes, and it
                        ;; cannot make it if something underneath has already
                        ;; decided.
                        :retry-handler      (constantly false)
                        :socket-timeout     (:timeout-ms opts)
                        :connection-timeout (:timeout-ms opts)
                        ;; Errors as data, like every other outbound adapter
                        ;; here — a 500 from the far side is a result to
                        ;; inspect, not a stack trace to unwind.
                        :throw-exceptions   false})
            body (decode-body body)]
        (cond
          (<= 200 status 299)
          [:answered (rpc/response->result operation body)]

          ;; A non-2xx usually still carries an envelope the server built on
          ;; purpose: the operation is not one it exposes, or the
          ;; implementation threw and it said what. Replacing that with a
          ;; generic "status 500" throws away the only part of the response
          ;; that says what went wrong — and it is an answer, so it is not
          ;; retried however :retry-on is configured.
          (and (map? body) (:error body))
          [:answered {:error (rpc/revive-error (:error body))}]

          ;; A non-2xx that is not an envelope: a proxy's error page, a login
          ;; redirect. The request may never have reached the service, so a
          ;; retry can help — but only if asked for, since it may equally have
          ;; run and had its response lost.
          :else
          [:transport (rpc/transport-error :rpc/remote-error operation
                                           (str "Remote returned status " status)
                                           status)]))
      (catch Exception e
        [:transport (rpc/transport-error (rpc/classify-exception e) operation
                                         (.getMessage e))]))))

(defn- raise-if-thrown!
  "Re-raise an exception the remote implementation threw; pass anything else on.

   In-process a throwing protocol method propagates, and the HTTP boundary maps
   its `:type` to a status. If the same call returned a map across the hop, a
   caller's `try`/`catch` would stop firing and the error would flow on as
   though it were a result — the failure this adapter exists to prevent.

   Transport failures are not raised. `:rpc/unavailable` and friends have no
   in-process equivalent, so no caller has a `catch` for them; returning them
   as data is what leaves the decision with the caller.

   `:rpc/remote true` records that it was raised here rather than thrown here —
   the stack trace describes this process, not the one that actually failed."
  [operation result]
  (let [{:keys [type message data] :as error} (:error result)]
    (when (:rpc/thrown error)
      (throw (ex-info message (merge data
                                     {:type          type
                                      :rpc/remote    true
                                      :rpc/operation (keyword (name operation))})))))
  result)

(defn call!
  "Invoke `operation` on the service at `base-url` with positional `args`.

   `context` supplies correlation-id / tenant / auth to propagate. Retries only
   the failures a retry can fix — see `default-opts`.

   Returns whatever the remote protocol method returned, or an `{:error …}` map
   for a transport failure. An exception the remote implementation threw is
   raised again here rather than returned — see `raise-if-thrown!`."
  [base-url operation args {:keys [context] :as opts}]
  (let [opts     (merge default-opts opts)
        envelope (rpc/request-envelope operation args context)
        url      (rpc/service-url base-url (:path opts))
        cache    (:cache opts)
        cb-cfg   (merge cb/default-config (:circuit-breaker opts))
        _        (when-let [problem (and (:circuit-breaker opts) (cb/config-problem cb-cfg))]
                   ;; A misconfigured breaker is inert or wrong, and either way
                   ;; looks like a working one. `:trip-on #{:rpc/unavailble}`
                   ;; never fires and nothing says so.
                   (throw (ex-info problem {:type :configuration-error
                                            :circuit-breaker (:circuit-breaker opts)})))
        now      #(System/currentTimeMillis)]
    (raise-if-thrown!
     operation
     (if-not (breaker/allow? cache cb-cfg base-url (now))
       (do (log/warn "rpc circuit open; not attempting" {:base-url base-url
                                                         :operation operation})
           (breaker/open-error cache cb-cfg base-url operation (now)))
       (loop [attempt 0]
         (let [[outcome result] (post-envelope! url envelope opts)
               error-type (get-in result [:error :type])]
         ;; The service answered, or it did not. Recorded here rather than after
         ;; the retry loop, so a call that succeeded on its third attempt still
         ;; closes the breaker.
           (if (and (= outcome :transport) (cb/counts-as-failure? cb-cfg error-type))
             (breaker/record-failure! cache cb-cfg base-url (now))
             (breaker/record-success! cache base-url))
           (cond
           ;; The service answered. Whatever the shape of that answer, the
           ;; operation ran — re-sending it would run it a second time.
             (or (= outcome :answered)
                 (not (and (map? result) (retryable? opts result))))
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
                 result))))))))

;; =============================================================================
;; Protocol proxy
;; =============================================================================

(defn protocol-operations
  "The method names a protocol declares, as keywords.

   Read from the protocol's own `:sigs`, so a method added to a port is carried
   without editing anything here — the adapter is generic or it is not useful."
  [protocol]
  (->> protocol :sigs vals (map :name) (map keyword) set))

(defn- munged->operation
  "Java method name → protocol method name.

   `defprotocol` compiles each method into an interface method with the name
   munged (`create-checkout-session` becomes `create_checkout_session`), and
   that munged name is what arrives at the invocation handler."
  [protocol]
  (into {} (for [{method :name} (vals (:sigs protocol))]
             [(munge (name method)) method])))

(defn remote-adapter
  "A value implementing `protocol` by calling the service at `base-url`.

   The returned object satisfies the protocol, so a caller cannot tell it from
   the in-process implementation — which is the point: the protocol is the
   contract, and where a module runs is deployment, not code.

   Built from the protocol's own `:sigs` rather than written out per protocol,
   so every module's port works without a bespoke client.

   Each adapter is its own object implementing that protocol's interface. The
   obvious alternative — `extend` on one shared record type — is wrong in a way
   that shows up only once a second protocol is adapted: `extend` mutates the
   class, so every adapter already built starts satisfying the new protocol
   too. A payments adapter would answer `satisfies?` for `ICache`, and cache
   calls made through it would be posted to the payments URL. Nothing would
   report a problem until a remote service was asked for an operation it has
   never heard of.

   Args:
     protocol - the protocol map, e.g. wagoe.payments.ports/IPaymentProvider
     base-url - service root, e.g. \"http://payments:3001\"
     opts     - :path :service-key :timeout-ms :retries :retry-delay-ms
                :retry-on :context

   `:path` defaults to \"/rpc\". Set it to \"/api/v1/rpc\" if the service mounts
   the handler as an `:api` route, since versioning rewrites those.

   Example:
     (remote-adapter payments-ports/IPaymentProvider \"http://payments:3001\" {})"
  [protocol base-url & [opts]]
  (let [^Class iface (:on-interface protocol)
        operations   (munged->operation protocol)
        opts         (or opts {})]
    (Proxy/newProxyInstance
     (.getClassLoader iface)
     (into-array Class [iface])
     (reify InvocationHandler
       (invoke [_ proxy method args]
         (if-let [operation (get operations (.getName ^Method method))]
           (call! base-url operation (seq args) opts)
           ;; Object's own methods reach the handler too, and a proxy that
           ;; threw on `toString` would be unprintable — including in the
           ;; exception messages describing what went wrong with it.
           (case (.getName ^Method method)
             "toString" (str "#remote-adapter[" (.getName iface) " " base-url "]")
             "hashCode" (System/identityHashCode proxy)
             "equals"   (identical? proxy (first args))
             (throw (UnsupportedOperationException.
                     (str "remote-adapter has no method " (.getName ^Method method)))))))))))
