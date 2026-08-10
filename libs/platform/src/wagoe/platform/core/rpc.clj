(ns wagoe.platform.core.rpc
  "Pure RPC envelope handling for the remote-port adapter.

   A cross-module call goes through a protocol (`ports.clj`). Slicing a module
   into its own process means implementing that same protocol with something
   that makes a network call instead — the seam is already there, only the
   adapter is missing (BOU-90, scaling.adoc → Functional decomposition).

   This namespace is the wire contract and nothing else: building an envelope,
   reading one, and turning a remote failure into the error shape callers
   already handle. No I/O — see `wagoe.platform.shell.rpc.client` and
   `…rpc.server` for that.

   FC/IS: pure. Everything here is data in, data out."
  (:require [clojure.string :as str]))

;; =============================================================================
;; Envelope
;; =============================================================================

(def envelope-schema
  "Malli schema for a request envelope.

   `:operation` is the protocol method name as a keyword, so the wire format
   does not depend on the caller's namespace aliases. `:args` is a vector
   because a protocol method is positional — naming the arguments would make
   the envelope depend on the parameter names in a docstring."
  [:map {:closed true}
   [:operation :keyword]
   [:args [:vector :any]]
   [:correlation-id {:optional true} [:maybe :string]]
   [:tenant-id      {:optional true} [:maybe :string]]
   [:auth-token     {:optional true} [:maybe :string]]])

(def response-schema
  "Malli schema for a response envelope.

   A remote call has two failure modes and they are not the same thing: the
   operation ran and returned an error (`:result` carrying whatever the
   protocol returns), or the call never completed (`:error`). Collapsing them
   would make a network partition indistinguishable from a declined payment."
  [:map {:closed true}
   [:result {:optional true} :any]
   [:error  {:optional true} [:maybe [:map
                                      [:type :keyword]
                                      [:message :string]
                                      [:operation {:optional true} :keyword]
                                      [:status {:optional true} [:maybe :int]]]]]
   [:correlation-id {:optional true} [:maybe :string]]])

(defn request-envelope
  "Build a request envelope for `operation` with positional `args`.

   `context` carries the values that must survive the hop — correlation-id,
   tenant, auth. They are already threaded through the interceptor pipeline
   in-process; across a network they ride headers, and this is what puts them
   there. Absent keys are omitted rather than sent as nil, so the receiving
   side can tell 'not propagated' from 'explicitly none'.

   Returns a map conforming to `envelope-schema`."
  [operation args context]
  (cond-> {:operation (keyword (name operation))
           :args      (vec args)}
    (:correlation-id context) (assoc :correlation-id (str (:correlation-id context)))
    (:tenant-id context)      (assoc :tenant-id      (str (:tenant-id context)))
    (:auth-token context)     (assoc :auth-token     (str (:auth-token context)))))

;; =============================================================================
;; Context propagation
;; =============================================================================

(def context-headers
  "Envelope key → HTTP header.

   `x-correlation-id` is the name the logging schema and the interceptor
   pipeline already use, so a request keeps one id across the hop rather than
   starting a new trace on the far side."
  {:correlation-id "x-correlation-id"
   :tenant-id      "x-tenant-id"
   :auth-token     "authorization"})

(defn context->headers
  "HTTP headers carrying `envelope`'s context.

   The auth token is sent as a bearer credential unless it already names a
   scheme, so a caller that holds a raw token and one that holds a full
   Authorization value both work."
  [envelope]
  (reduce-kv (fn [hs k header]
               (if-let [v (get envelope k)]
                 (assoc hs header
                        (if (and (= :auth-token k) (not (re-find #"^\w+ " v)))
                          (str "Bearer " v)
                          v))
                 hs))
             {}
             context-headers))

(defn headers->context
  "The context an inbound envelope should carry, read from `headers`.

   Header lookup is case-insensitive: Ring lower-cases incoming header names,
   but a caller constructing a request by hand may not, and losing the
   correlation-id to a capital letter would be silent."
  [headers]
  (let [lower (reduce-kv (fn [m k v] (assoc m (str/lower-case (name k)) v)) {} (or headers {}))]
    (reduce-kv (fn [ctx k header]
                 (if-let [v (get lower header)]
                   (assoc ctx k (if (= :auth-token k)
                                  (str/replace v #"^[Bb]earer " "")
                                  v))
                   ctx))
               {}
               context-headers)))

;; =============================================================================
;; Failures
;; =============================================================================

(defn envelope-problem
  "Why `envelope` cannot be invoked, or nil if it can.

   The server is network-facing, so it is reachable by anything that can post
   to it — including something sending a body that is not an envelope at all.
   Naming the problem lets the handler answer with an error envelope; without
   this, reading `:operation` off a malformed body throws, and the caller gets
   a 500 carrying nothing that says what was wrong with their request."
  [{:keys [operation args]}]
  (cond
    (nil? operation)
    "Envelope carried no :operation"

    (not (or (string? operation) (keyword? operation) (symbol? operation)))
    (str "Envelope :operation was not a name: " (pr-str operation))

    (and (some? args) (not (sequential? args)))
    (str "Envelope :args was not a sequence: " (pr-str args))))

(defn transport-error
  "The error map for a call that did not complete.

   Shaped like the `{:error {:type … :message …}}` the codebase already returns
   from adapters, so a caller handling a local failure needs no new branch for
   a remote one. `:type` distinguishes the cases that need different responses:

     :rpc/unavailable  — could not reach the service (connect refused, DNS)
     :rpc/timeout      — reached it, no answer in time
     :rpc/remote-error — it answered with a non-2xx
     :rpc/protocol     — it answered with something that is not an envelope

   `operation` may be nil: a request that never named one still needs an
   answer, and it is the one case where there is no operation to report."
  [type operation message & [status]]
  {:error (cond-> {:type    type
                   :message message}
            operation (assoc :operation (keyword (name operation)))
            status    (assoc :status status))})

(defn classify-exception
  "Map a client exception to an `:rpc/*` type.

   A timeout and a refused connection are both 'no answer' to a naive reader,
   and they need different operator responses — one is a slow service, the
   other a missing one."
  [^Exception e]
  (condp instance? e
    java.net.SocketTimeoutException  :rpc/timeout
    java.net.ConnectException        :rpc/unavailable
    java.net.UnknownHostException    :rpc/unavailable
    java.net.NoRouteToHostException  :rpc/unavailable
    :rpc/remote-error))

(defn revive-error
  "Restore the keywords JSON flattened to strings.

   Callers branch on `:type` — and so does the client's own retry policy. JSON
   has no keywords, so a type built as `:rpc/timeout` arrives as
   `\"rpc/timeout\"`, and every set-membership or `case` test against it misses
   without saying so: the branch simply never fires. This is the boundary where
   that has to be undone, because it is the last place that knows the value
   came off a wire."
  [error]
  (when error
    (cond-> error
      (string? (:type error))      (update :type keyword)
      (string? (:operation error)) (update :operation keyword))))

(defn response->result
  "Unwrap a response envelope into what the protocol method should return.

   A well-formed `{:result …}` is returned as-is. Anything else is a transport
   failure: a body that is not an envelope means the far side is not the
   service we think it is, which is worth saying rather than letting a nil
   propagate into the caller's logic."
  [operation body]
  (cond
    (not (map? body))     (transport-error :rpc/protocol operation
                                           "Remote returned a body that is not an envelope")
    (contains? body :result) (:result body)
    (:error body)         {:error (revive-error (:error body))}
    :else                 (transport-error :rpc/protocol operation
                                           "Remote envelope had neither :result nor :error")))
