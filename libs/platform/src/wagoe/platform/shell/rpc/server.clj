(ns wagoe.platform.shell.rpc.server
  "Expose a module's protocol over HTTP, so another process can call it.

   The counterpart to `…rpc.client`: that turns protocol calls into requests,
   this turns requests back into protocol calls against the local
   implementation. Together they are what lets a module run as its own service
   without its callers changing (BOU-90).

   FC/IS: shell. The envelope contract is pure and lives in
   `wagoe.platform.core.rpc`."
  (:require [wagoe.platform.core.http.problem-details :as problem-details]
            [wagoe.platform.core.rpc :as rpc]
            [clojure.tools.logging :as log]))

(defn- operation->fn
  "The protocol function implementing `operation`, or nil.

   Resolved from the protocol's `:sigs`, so an endpoint exposes exactly the
   methods its protocol declares — no more. An operation the protocol does not
   name cannot be invoked, which is what stops the envelope from becoming a
   general-purpose remote eval."
  [protocol operation]
  (some (fn [{:keys [name]}]
          (when (= (clojure.core/name operation) (clojure.core/name name))
            (ns-resolve (-> protocol :var meta :ns)
                        name)))
        (vals (:sigs protocol))))

(defn handle-envelope
  "Invoke one envelope against `implementation`. Returns a response envelope.

   Errors are returned, never thrown: the client reads status and body, and an
   exception escaping here would surface as a 500 with no operation named — the
   caller would know something failed but not what it had asked for.

   That includes a body that is not an envelope. This endpoint is reachable by
   anything that can post to it, so `:operation` may be missing or may not be a
   name at all; reading it unguarded would throw before the error map that
   promises to describe the failure could be built."
  [protocol implementation {:keys [operation args correlation-id] :as envelope}]
  (let [carry-correlation #(cond-> % correlation-id (assoc :correlation-id correlation-id))]
    (if-let [problem (rpc/envelope-problem envelope)]
      (do
        (log/warn "rpc malformed envelope" {:problem problem})
        (carry-correlation (rpc/transport-error :rpc/protocol nil problem)))

      (if-let [f (operation->fn protocol operation)]
        (try
          (carry-correlation {:result (apply f implementation args)})
          (catch Exception e
            ;; Carried, not flattened. Payment providers throw typed ex-info
            ;; ({:type :internal-error …}) and nothing catches them — the HTTP
            ;; boundary reads that :type and maps it to a status. Reporting
            ;; every throw as :rpc/remote-error would lose the distinction the
            ;; caller had in-process.
            (log/warn e "rpc operation threw" {:operation operation
                                               :type      (:type (ex-data e))})
            (carry-correlation (rpc/thrown-error operation e))))

        (do
          (log/warn "rpc unknown operation" {:operation operation})
          (carry-correlation
           (rpc/transport-error :rpc/unknown-operation operation
                                (str "This service exposes no operation " operation))))))))

(def ^:private malformed-request-types
  "Error types that mean the caller sent something this service cannot use."
  #{:rpc/protocol :rpc/unknown-operation})

(defn- error->status
  "HTTP status for an error type.

   Three cases, and collapsing them loses information an operator needs.

   A request this service could not understand is 400: reading the logs of a
   sliced-out service, a caller sending nonsense has to be distinguishable from
   the service itself falling over.

   A typed domain error keeps the status it has in-process. The same
   `:not-found` that answers 404 through the HTTP boundary must not answer 500
   because it happened to cross a hop — the status is what proxies, dashboards
   and alerting read, and a 404 counted as a server error moves an error budget
   for something that is not an error. `default-error-mappings` is the same
   pure data the HTTP boundary uses, so the two cannot drift.

   Everything else is 500. The client reads the body either way; the status is
   for everything between the two, and for humans."
  [error-type]
  (cond
    (nil? error-type)                             200
    (malformed-request-types error-type)          400
    :else (or (first (get problem-details/default-error-mappings error-type))
              500)))

(defn rpc-handler
  "A Ring handler serving `protocol` backed by `implementation`.

   Mount at `/rpc` — `…rpc.client` posts there.

   Expects the app's format middleware to have decoded the body into
   `:body-params`, and leaves encoding the response to it as well — the client
   asks for `application/transit+json`, so setting a content type here would
   override the negotiation and mislabel the body. transit rather than JSON
   because JSON flattens every keyword to a string, and a protocol returning a
   keyword status would answer with a string across the hop and nowhere else.

   The context an inbound request carries (correlation-id, tenant, auth) is
   read from the headers and merged onto the envelope, so a downstream call
   made while handling this one keeps the same correlation-id rather than
   starting a fresh trace.

   Args:
     protocol       - the protocol map, e.g. wagoe.payments.ports/IPaymentProvider
     implementation - a value satisfying it, in this process

   Example:
     {:path \"/rpc\"
      :methods {:post {:handler (rpc-handler payments-ports/IPaymentProvider provider)}}}"
  [protocol implementation]
  (fn [{:keys [body-params headers] :as _request}]
    (let [;; Only merge context onto something that can carry it. A valid
          ;; transit body decoding to a vector or a string reaches here, and
          ;; `merge` on one throws before `envelope-problem` can name the
          ;; fault — turning a 400 this endpoint promises into an escaped 500.
          envelope (if (map? body-params)
                     (merge (rpc/headers->context headers) body-params)
                     body-params)
          response (handle-envelope protocol implementation envelope)]
      ;; No content type: the format middleware negotiates it from the
      ;; request's Accept, and hardcoding one here would label a transit body
      ;; as JSON.
      {:status (error->status (get-in response [:error :type]))
       :body   response})))
