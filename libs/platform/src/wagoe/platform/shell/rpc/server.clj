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
            [clojure.string]
            [clojure.tools.logging :as log]
            [muuntaja.middleware :as muuntaja-middleware]))

(defn- resolve-operation
  "`{:f fn :arities #{n}}` for `operation`, or nil if the protocol has no such
   method.

   Resolved from the protocol's `:sigs`, so an endpoint exposes exactly the
   methods its protocol declares — no more. An operation the protocol does not
   name cannot be invoked, which is what stops the envelope from becoming a
   general-purpose remote eval.

   `:arities` counts declared arguments without `this`, which the server
   supplies. It comes from the same `:sigs`, so a method whose signature
   changes carries the new arity across without anything here being edited."
  [protocol operation]
  (some (fn [{:keys [name arglists]}]
          (when (= (clojure.core/name operation) (clojure.core/name name))
            {:f       (ns-resolve (-> protocol :var meta :ns) name)
             :arities (into #{} (map #(dec (count %))) arglists)}))
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

      (if-let [{:keys [f arities]} (resolve-operation protocol operation)]
        (cond
          ;; Checked before invoking, because `apply` with the wrong count
          ;; throws an ArityException that is indistinguishable here from the
          ;; implementation failing — so a caller's mistake, or a client built
          ;; against a different version of the protocol, would be reported as
          ;; the service breaking, and re-raised on the near side as one.
          (not (contains? arities (count args)))
          (carry-correlation
           (rpc/transport-error
            :rpc/protocol operation
            (str "Operation " (clojure.core/name operation) " takes "
                 (clojure.string/join " or " (sort arities))
                 " argument(s), got " (count args))))

          :else
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
              (carry-correlation (rpc/thrown-error operation e)))))

        (do
          (log/warn "rpc unknown operation" {:operation operation})
          (carry-correlation
           (rpc/transport-error :rpc/unknown-operation operation
                                (str "This service exposes no operation " operation))))))))

(defn- lower-case-headers
  "Headers keyed by lower-case name.

   Ring lower-cases inbound header names, but a handler driven directly — a
   test, another middleware stack — may not, and missing the service key
   because of a capital letter would reject a legitimate caller."
  [headers]
  (reduce-kv (fn [m k v] (assoc m (clojure.string/lower-case (name k)) v))
             {} (or headers {})))

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

   Use `rpc-app` unless you have a reason not to — it is this handler with the
   format middleware it needs, ready for its own listener. Do not merge this
   into a module's `:api` or `:web` routes: the router rewrites both paths, and
   a POST under `/web` is CSRF-validated.

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
     opts           - {:service-key \"…\"}, or {:auth :none} to opt out

   Callers must present the service key in the `x-rpc-service-key` header. A
   missing or wrong one is 401 before anything reaches the implementation —
   `:sigs` bounds which operations exist, not who may invoke them. The key is
   validated at construction, so a service configured without one fails to
   start instead of coming up unprotected.

   Example:
     (rpc-app payments-ports/IPaymentProvider provider {:service-key key})"
  [protocol implementation {:keys [service-key auth] :as _opts}]
  (when-not (= auth :none)
    (when-let [problem (rpc/service-key-problem service-key)]
      ;; At construction, not per request: a service that cannot authenticate
      ;; callers must fail to start rather than come up serving port methods to
      ;; anyone who can reach the port.
      (throw (ex-info problem {:type :configuration-error}))))
  (fn [{:keys [body-params headers] :as _request}]
    (if-not (or (= auth :none)
                (rpc/service-key-matches?
                 service-key (get (lower-case-headers headers) rpc/service-key-header)))
      (do
        ;; No detail: which of "absent" and "wrong" it was is information a
        ;; caller guessing keys would use. The correlation id is enough to find
        ;; the request in the access log.
        (log/warn "rpc rejected an unauthenticated call")
        {:status 401
         :body   {:error {:type    :unauthorized
                          :message "This endpoint requires a service key"}}})

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
         :body   response}))))

(def default-path
  "Where `rpc-app` serves the endpoint. Matches `client/default-opts`."
  "/rpc")

(defn rpc-app
  "A standalone Ring app serving `protocol` — for the service's own listener.

   Deliberately not a route map for a module's `:api` or `:web` slot. Both are
   rewritten by the router: `:api` paths gain the version prefix and `:web`
   paths gain `/web`, so a client on the default `:path` gets a 404 either way.
   `:web` is worse than wrong — a POST there is CSRF-validated when CSRF is
   enabled, so the call would be rejected 403 by a check meant for browser
   forms, which a service-to-service caller has no token for.

   Underneath that: this endpoint invokes port methods, and the public listener
   is not where it belongs. A sliced-out service should serve it on its own
   listener, reachable only from inside the deployment — which is what the
   service launch mode (BOU-91) will start. Until then, mounting is the
   caller's decision to make explicitly, rather than one this namespace makes
   look routine.

   Format middleware is included, so this handler is complete: it decodes the
   transit body the client sends and encodes the response.

   Args:
     protocol       - the protocol map
     implementation - a value satisfying it, in this process
     opts           - {:service-key \"…\"}, or {:auth :none}
     path           - defaults to `default-path`

   Example:
     (jetty/run-jetty (rpc-app ports/IPaymentProvider provider
                               {:service-key key})
                      {:port 3001 :join? false})"
  ([protocol implementation opts] (rpc-app protocol implementation opts default-path))
  ([protocol implementation opts path]
   (let [handler (muuntaja-middleware/wrap-format (rpc-handler protocol implementation opts))]
     (fn [request]
       (if (and (= :post (:request-method request))
                (= path (:uri request)))
         (handler request)
         ;; Anything else, including a GET on the right path: this listener
         ;; serves one endpoint and should not look like it serves more.
         {:status 404 :headers {} :body ""})))))
