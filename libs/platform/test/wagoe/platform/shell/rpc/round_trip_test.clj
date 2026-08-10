(ns wagoe.platform.shell.rpc.round-trip-test
  "The remote-port adapter against a real socket.

   Unit tests of the envelope prove the shape; they do not prove that a caller
   holding the protocol gets an answer from another process. These start a
   Jetty server serving the payments protocol from the mock provider, point a
   remote adapter at it, and call the protocol — the pilot BOU-90 asks for."
  (:require [wagoe.platform.core.rpc :as rpc]
            [wagoe.platform.shell.rpc.client :as client]
            [wagoe.platform.shell.rpc.server :as server]
            [wagoe.payments.ports :as pay-ports]
            [wagoe.payments.shell.adapters.mock :as mock]
            [clojure.test :refer [deftest is testing]]
            [muuntaja.core :as m]
            [muuntaja.middleware :as m-mw]
            [ring.adapter.jetty :as jetty])
  (:import (org.eclipse.jetty.server ServerConnector)))

(def ^:private content-type "application/transit+json")

(defn- wrap-format
  "The app's own format middleware, not a stand-in.

   `rpc-handler` reads `:body-params` and returns a Clojure map, relying on
   muuntaja to decode and encode around it. Hand-rolling that here would test a
   pipeline nothing runs — and would have hidden the fact that the wire format
   determines whether keyword values survive the hop at all."
  [handler]
  (m-mw/wrap-format handler))

(defn- encode-body
  "A request body in the wire format, for driving the handler directly."
  [data]
  (m/encode m/instance content-type data))

(defn- decode-body [body] (m/decode m/instance content-type body))

(defn- with-server
  "Serve `app` on an ephemeral port; call `f` with its base URL.

   Port 0 lets the OS pick a free one. A fixed port would make these tests fail
   before reaching any RPC code on a machine that happens to have it bound, or
   whenever two of them run at once — and the failure would look like the
   adapter being broken rather than the port being taken."
  [app f]
  (let [server (jetty/run-jetty app {:port 0 :join? false})
        port   (.getLocalPort ^ServerConnector (first (.getConnectors server)))]
    (try
      (f (str "http://localhost:" port))
      (finally (.stop server)))))

(defn- with-service
  "Run `f` with the payments protocol served over HTTP. `f` receives the URL."
  [implementation f]
  (let [handler (wrap-format (server/rpc-handler pay-ports/IPaymentProvider implementation))]
    (with-server (fn [request]
                   (if (= "/rpc" (:uri request))
                     (handler request)
                     {:status 404 :body "{}"}))
                 f)))

(deftest ^:integration protocol-calls-cross-a-real-socket
  (let [provider (mock/make-mock-provider)]
    (with-service provider
      (fn [url]
        (let [remote (client/remote-adapter pay-ports/IPaymentProvider url {:retries 0})]

          (testing "the adapter satisfies the protocol it was built from"
            (is (satisfies? pay-ports/IPaymentProvider remote)))

          (testing "a call returns what the in-process provider returns"
            (let [local  (pay-ports/create-checkout-session
                          provider {:amount-cents 1000 :currency "EUR"
                                    :description "t" :redirect-url "http://x"
                                    :reference "r1"})
                  remote' (pay-ports/create-checkout-session
                           remote {:amount-cents 1000 :currency "EUR"
                                   :description "t" :redirect-url "http://x"
                                   :reference "r1"})]
              ;; The ids are generated per call, so they differ by design. The
              ;; shape must not: a hop that drops or renames a key would still
              ;; "work" for a caller that only reads :checkout-url.
              (is (= (set (keys local)) (set (keys remote'))))
              (is (every? string? (vals remote')))
              (is (re-find #"^/web/payment/mock-return" (:checkout-url remote')))))

          (testing "an unreachable service is an error value, not an exception"
            ;; Port 1 is privileged and nothing binds it, so this is reliably a
            ;; refused connection rather than a port that might be in use.
            (let [dead (client/remote-adapter pay-ports/IPaymentProvider
                                              "http://localhost:1" {:retries 0})
                  r    (pay-ports/get-payment-status dead "anything")]
              (is (= :rpc/unavailable (get-in r [:error :type])))
              (is (= :get-payment-status (get-in r [:error :operation]))))))))))

(deftest ^:integration context-propagates-across-the-hop
  ;; correlation-id, tenant and auth already flow through the interceptor
  ;; pipeline in-process. Across a network they have to ride headers, or a
  ;; sliced-out service starts a fresh trace and loses the tenant.
  (let [seen (atom nil)
        ;; A stand-in implementation that records what the server saw.
        recorder (reify pay-ports/IPaymentProvider
                   (provider-name [_] :recorder)
                   (create-checkout-session [_ _] {:ok true})
                   (create-off-session-payment [_ _] {:ok true})
                   (get-payment-status [_ _] {:ok true})
                   (expire-checkout-session [_ _] {:ok true})
                   (process-webhook [_ _ _] {:ok true})
                   (verify-webhook-signature [_ _ _] true))
        handler (wrap-format
                 (fn [request]
                   (reset! seen (rpc/headers->context (:headers request)))
                   ((server/rpc-handler pay-ports/IPaymentProvider recorder) request)))
        app     (fn [r] (if (= "/rpc" (:uri r)) (handler r) {:status 404 :body "{}"}))]
    (with-server app
      (fn [url]
        (let [remote (client/remote-adapter pay-ports/IPaymentProvider url
                                            {:retries 0
                                             :context {:correlation-id "corr-123"
                                                       :tenant-id      "tenant-a"
                                                       :auth-token     "tok-xyz"}})]
          (pay-ports/get-payment-status remote "id")

          (testing "the correlation id survives the hop"
            (is (= "corr-123" (:correlation-id @seen))))

          (testing "so does the tenant"
            (is (= "tenant-a" (:tenant-id @seen))))

          (testing "and the auth token, unwrapped from its Bearer scheme"
            (is (= "tok-xyz" (:auth-token @seen)))))))))

(deftest ^:integration unknown-operations-are-refused
  ;; The envelope names an operation. If the server invoked anything it was
  ;; asked to, the endpoint would be a remote eval; it resolves only against
  ;; the protocol's own sigs.
  (let [provider (mock/make-mock-provider)
        response (server/handle-envelope pay-ports/IPaymentProvider provider
                                         {:operation :drop-database :args []})]
    (testing "an operation the protocol does not declare is rejected"
      (is (= :rpc/unknown-operation (get-in response [:error :type]))))

    (testing "and the message says so rather than reporting a generic failure"
      (is (re-find #"exposes no operation" (get-in response [:error :message]))))))

(deftest ^:integration a-throwing-implementation-answers-rather-than-hanging
  (let [boom (reify pay-ports/IPaymentProvider
               (provider-name [_] :boom)
               (create-checkout-session [_ _] (throw (ex-info "psp down" {})))
               (create-off-session-payment [_ _] nil)
               (get-payment-status [_ _] nil)
               (expire-checkout-session [_ _] nil)
               (process-webhook [_ _ _] nil)
               (verify-webhook-signature [_ _ _] false))
        handler (wrap-format (server/rpc-handler pay-ports/IPaymentProvider boom))
        request {:uri "/rpc" :request-method :post
                 :headers {"content-type" content-type "accept" content-type}
                 :body (encode-body {:operation :create-checkout-session :args [{}]})}
        response (handler request)]

    (testing "the failure is a response, not an escaped exception"
      (is (= 500 (:status response))))

    (testing "and it names the operation that failed"
      (let [body (decode-body (:body response))]
        (is (= "psp down" (get-in body [:error :message])))
        ;; A keyword, not the string JSON would have left here. Callers branch
        ;; on this.
        (is (= :create-checkout-session (get-in body [:error :operation])))))))

(deftest ^:integration retries-are-counted-not-just-configured
  ;; The retry policy is the part most likely to be wrong in a way nothing
  ;; notices: retrying too little is invisible under a healthy service, and
  ;; retrying a non-idempotent call too much charges a customer twice. Count
  ;; the attempts the far side actually sees.
  (let [attempts (atom 0)
        ;; A real error envelope, as the server sends: the retry decision is
        ;; made on the type that came off the wire, so a stub returning
        ;; something else would test a path production never takes.
        app      (fn [_]
                   (swap! attempts inc)
                   {:status  500
                    :headers {"content-type" content-type}
                    :body    (encode-body {:error {:type    :rpc/remote-error
                                                   :message "psp down"}})})
        run      (fn [url]
                   (testing "a remote error is not retried by default"
                     ;; A 500 means the far side ran and refused. Re-sending it
                     ;; would re-submit a payment the PSP already rejected on
                     ;; its merits.
                     (reset! attempts 0)
                     (client/call! url :get-payment-status ["id"] {})
                     (is (= 1 @attempts)))

                   (testing "opting a failure in retries it exactly :retries more times"
                     (reset! attempts 0)
                     (let [r (client/call! url :get-payment-status ["id"]
                                           {:retry-on #{:rpc/remote-error}
                                            :retries  2
                                            :retry-delay-ms 1})]
                       (is (= 3 @attempts) "one initial attempt plus two retries")
                       (testing "and the caller still gets the failure once they are spent"
                         (is (= :rpc/remote-error (get-in r [:error :type]))))))

                   (testing "the decision is made on the type the server sent, not a local guess"
                     ;; JSON has no keywords, so `:type` arrives as a string.
                     ;; Comparing it against a set of keywords misses silently —
                     ;; the retry simply never happens — which is why this
                     ;; asserts on attempts rather than on the returned map.
                     (reset! attempts 0)
                     (client/call! url :get-payment-status ["id"]
                                   {:retry-on #{:rpc/unavailable} :retries 2 :retry-delay-ms 1})
                     (is (= 1 @attempts) "a remote error is not in that set, so it is not retried"))

                   (testing "retries 0 means one attempt"
                     (reset! attempts 0)
                     (client/call! url :get-payment-status ["id"]
                                   {:retry-on #{:rpc/remote-error} :retries 0})
                     (is (= 1 @attempts))))]
    (with-server app run)))

(deftest ^:integration a-timeout-is-not-retried-by-default
  ;; Explicit because it is the dangerous one: a call that timed out may have
  ;; executed. `:rpc/timeout` must stay out of the default retry set.
  (is (not (contains? (:retry-on client/default-opts) :rpc/timeout))
      "retrying a timed-out call risks executing it twice")
  (is (contains? (:retry-on client/default-opts) :rpc/unavailable)
      "a refused connection did not execute, so it is safe to retry"))

(deftest ^:integration remote-error-envelopes-survive-the-status-code
  ;; The server reports a refused operation and a thrown implementation in the
  ;; body, on a non-2xx. Testing `handle-envelope` directly proves it builds
  ;; that body; it says nothing about whether the client can still read it. A
  ;; client that replaced it with "status 500" would pass every server-side
  ;; test while giving callers nothing to branch on.
  (let [boom (reify pay-ports/IPaymentProvider
               (provider-name [_] :boom)
               (create-checkout-session [_ _] (throw (ex-info "psp refused the card" {})))
               (create-off-session-payment [_ _] nil)
               (get-payment-status [_ _] nil)
               (expire-checkout-session [_ _] nil)
               (process-webhook [_ _ _] nil)
               (verify-webhook-signature [_ _ _] false))]
    (with-service boom
      (fn [url]
        (testing "a remote exception reaches the caller as an exception"
          ;; In-process this method throws, and nothing between here and the
          ;; HTTP boundary catches it. Returning a map instead would stop the
          ;; caller's `catch` firing and let the failure flow on as a result.
          (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                        #"psp refused the card"
                                        (client/call! url :create-checkout-session
                                                      [{}] {:retries 0})))]
            (is (= :create-checkout-session (:rpc/operation (ex-data e))))
            (is (true? (:rpc/remote (ex-data e)))
                "marked as raised here, since the stack trace is this process")))

        (testing "a refused operation keeps its own type rather than becoming generic"
          ;; :rpc/unknown-operation means the caller and the service disagree
          ;; about the contract — a deploy skew. It needs a different response
          ;; from "the payment provider is down", so it must not be flattened
          ;; into :rpc/remote-error.
          (let [r (client/call! url :drop-database [] {:retries 0})]
            (is (= :rpc/unknown-operation (get-in r [:error :type])))
            (is (re-find #"exposes no operation" (get-in r [:error :message])))))))))

(deftest ^:integration a-body-that-is-not-an-envelope-gets-an-answer
  ;; This endpoint is reachable by anything that can post to it. A body with no
  ;; :operation used to throw on `(name nil)` before the error map could be
  ;; built, so the promise that errors are returned rather than thrown held
  ;; only for requests that were already well-formed.
  (let [provider (mock/make-mock-provider)
        handler  (wrap-format (server/rpc-handler pay-ports/IPaymentProvider provider))
        post     (fn [body-map]
                   (handler {:uri "/rpc" :request-method :post
                             :headers {"content-type" content-type "accept" content-type}
                             :body (encode-body body-map)}))]

    (testing "a body with no operation is answered, not thrown"
      (let [response (post {:args []})
            body     (decode-body (:body response))]
        (is (= 400 (:status response)) "the caller sent something unusable, so it is their error")
        (is (= :rpc/protocol (get-in body [:error :type])))
        (is (re-find #"no :operation" (get-in body [:error :message])))))

    (testing "an operation that is not a name is answered too"
      (let [body (decode-body (:body (post {:operation 42 :args []})))]
        (is (= :rpc/protocol (get-in body [:error :type])))))

    (testing "args that are not a sequence are answered"
      ;; `apply` over a non-sequence throws; catching it as :rpc/remote-error
      ;; would blame the service for the caller's malformed request.
      (let [body (decode-body (:body (post {:operation :get-payment-status
                                             :args      "not-a-vector"})))]
        (is (= :rpc/protocol (get-in body [:error :type])))))

    (testing "an empty body is answered"
      (let [response (handler {:uri "/rpc" :request-method :post
                                :headers {"content-type" content-type "accept" content-type}
                                :body nil})]
        (is (= 400 (:status response)))))

    (testing "a well-formed request still works, so the guard did not swallow everything"
      ;; Without this, every assertion above would pass if the handler rejected
      ;; all requests.
      (let [body (decode-body
                  (:body (post {:operation :get-payment-status :args ["pay-1"]})))]
        (is (nil? (:error body)))
        (is (contains? body :result))))))

(deftest ^:integration a-malformed-request-is-not-retried
  ;; :rpc/protocol means the request was wrong. Sending it again unchanged
  ;; cannot help, and against a non-idempotent operation it is the same double
  ;; -submission the timeout rule exists to avoid.
  (is (not (contains? (:retry-on client/default-opts) :rpc/protocol)))
  (is (not (contains? (:retry-on client/default-opts) :rpc/unknown-operation))))

(deftest ^:integration keyword-values-survive-the-hop
  ;; The reason the wire format is transit and not JSON. Payments returns
  ;; keyword-valued statuses (`{:status :paid}`, `(provider-name) => :mock`);
  ;; over JSON those arrive as strings, so `(= :paid (:status r))` holds
  ;; in-process and silently stops holding through the adapter — a caller that
  ;; switched to a remote payments service would see every status comparison
  ;; fail, with nothing in any log to say why.
  (let [provider (mock/make-mock-provider)]
    (with-service provider
      (fn [url]
        (let [remote (client/remote-adapter pay-ports/IPaymentProvider url {:retries 0})]

          (testing "a keyword returned as the whole result is still a keyword"
            (is (= :mock (pay-ports/provider-name remote))))

          (testing "a keyword nested in a map is still a keyword"
            (let [status (pay-ports/get-payment-status remote "pay-1")]
              (is (= :paid (:status status)))
              (is (= (pay-ports/get-payment-status provider "pay-1") status)
                  "and the whole value equals what the in-process provider returned")))

          (testing "a boolean is not stringified either"
            (is (true? (pay-ports/verify-webhook-signature remote "{}" "sig")))))))))

(defprotocol ^:private ICacheLike
  "A second protocol, to prove one adapter does not answer for another."
  (cache-get [this k])
  (cache-put [this k v]))

(deftest ^:integration one-adapter-does-not-answer-for-another-protocol
  ;; The obvious implementation — `extend` on a shared record type — mutates
  ;; that class, so adapting a second protocol makes every adapter already
  ;; built satisfy it too. A payments adapter would answer `satisfies?` for a
  ;; cache protocol, and cache calls through it would be posted to the payments
  ;; URL: no error, no log, just an operation the payments service has never
  ;; heard of.
  (let [payments (client/remote-adapter pay-ports/IPaymentProvider
                                        "http://localhost:1" {:retries 0})
        cache    (client/remote-adapter ICacheLike "http://localhost:2" {:retries 0})]

    (testing "each adapter satisfies the protocol it was built from"
      (is (satisfies? pay-ports/IPaymentProvider payments))
      (is (satisfies? ICacheLike cache)))

    (testing "and only that one"
      (is (not (satisfies? ICacheLike payments)))
      (is (not (satisfies? pay-ports/IPaymentProvider cache))))

    (testing "adapting the second protocol does not redirect the first"
      ;; Both point at dead ports, so the assertion is about which URL was
      ;; tried, not about the answer.
      (is (= :get-payment-status
             (get-in (pay-ports/get-payment-status payments "id") [:error :operation]))))

    (testing "two adapters for the same protocol keep their own URLs"
      ;; A shared class carrying the URL in a closure would give both whichever
      ;; was constructed last.
      (let [a (client/remote-adapter pay-ports/IPaymentProvider "http://localhost:1" {:retries 0})
            b (client/remote-adapter pay-ports/IPaymentProvider "http://localhost:2" {:retries 0})]
        (is (not= (str a) (str b)))
        (is (re-find #"localhost:1" (str a)))
        (is (re-find #"localhost:2" (str b)))))))

(deftest ^:integration typed-errors-keep-their-type-across-the-hop
  ;; Payment providers throw typed ex-info — Stripe raises
  ;; {:type :internal-error :provider-id … :status 401} on an auth failure —
  ;; and the HTTP boundary maps that :type to a status, warning when there is
  ;; none. Flattening every throw to :rpc/remote-error would turn a mapped
  ;; error into a generic 500 with a warning about a missing :type.
  (let [typed (reify pay-ports/IPaymentProvider
                (provider-name [_] :typed)
                (create-checkout-session [_ _]
                  (throw (ex-info "not supported by this provider"
                                  {:type :not-implemented :feature :setup-future-usage})))
                (create-off-session-payment [_ _]
                  (throw (ex-info "auth failed"
                                  {:type :internal-error :status 401
                                   ;; Not carryable, and must not break the rest.
                                   :connection (Object.)})))
                (get-payment-status [_ _] nil)
                (expire-checkout-session [_ _] nil)
                (process-webhook [_ _ _] nil)
                (verify-webhook-signature [_ _ _] false))]
    (with-service typed
      (fn [url]
        (let [remote (client/remote-adapter pay-ports/IPaymentProvider url {:retries 0})]

          (testing "the type the implementation threw is the type the caller catches"
            (let [e (is (thrown? clojure.lang.ExceptionInfo
                                 (pay-ports/create-checkout-session remote {})))]
              (is (= :not-implemented (:type (ex-data e)))
                  "not :rpc/remote-error — this is the value the HTTP boundary maps")
              (is (= "not supported by this provider" (ex-message e)))))

          (testing "the rest of the ex-data comes with it"
            (let [e (is (thrown? clojure.lang.ExceptionInfo
                                 (pay-ports/create-checkout-session remote {})))]
              (is (= :setup-future-usage (:feature (ex-data e))))))

          (testing "ex-data a wire format cannot carry is dropped, not fatal"
            ;; ex-data is written for a local reader and may hold a connection
            ;; or a response object. Failing to encode it would turn a typed
            ;; domain error into a transport error — the opposite of carrying it.
            (let [e (is (thrown? clojure.lang.ExceptionInfo
                                 (pay-ports/create-off-session-payment remote {})))]
              (is (= :internal-error (:type (ex-data e))))
              (is (= 401 (:status (ex-data e))))
              (is (nil? (:connection (ex-data e)))))))))))

(deftest ^:integration transport-failures-are-still-returned-not-raised
  ;; The counterpart to the rule above. :rpc/unavailable has no in-process
  ;; equivalent, so no caller has a `catch` for it; raising it would replace a
  ;; decision the caller can make with one it cannot.
  (let [dead (client/remote-adapter pay-ports/IPaymentProvider
                                    "http://localhost:1" {:retries 0})]
    (is (= :rpc/unavailable (get-in (pay-ports/get-payment-status dead "id")
                                    [:error :type])))))

(deftest ^:integration typed-errors-keep-their-http-status-too
  ;; The type surviving the hop is only half of it. The status is what proxies,
  ;; dashboards and alerting read, and a :not-found answered as 500 counts a
  ;; missing record as a server error — moving an error budget for something
  ;; that is not an error.
  (let [typed (fn [error-type]
                (reify pay-ports/IPaymentProvider
                  (provider-name [_] :typed)
                  (create-checkout-session [_ _]
                    (throw (ex-info "nope" {:type error-type})))
                  (create-off-session-payment [_ _] nil)
                  (get-payment-status [_ _] nil)
                  (expire-checkout-session [_ _] nil)
                  (process-webhook [_ _ _] nil)
                  (verify-webhook-signature [_ _ _] false)))
        status-for (fn [error-type]
                     (let [handler (wrap-format
                                    (server/rpc-handler pay-ports/IPaymentProvider
                                                        (typed error-type)))]
                       (:status (handler {:uri "/rpc" :request-method :post
                                          :headers {"content-type" content-type
                                                    "accept" content-type}
                                          :body (encode-body
                                                 {:operation :create-checkout-session
                                                  :args      [{}]})}))))]

    (testing "the platform's documented mapping applies across the hop"
      (is (= 404 (status-for :not-found)))
      (is (= 400 (status-for :validation-error)))
      (is (= 401 (status-for :unauthorized)))
      (is (= 403 (status-for :forbidden)))
      (is (= 409 (status-for :conflict))))

    (testing "and an unmapped or untyped failure is still a server error"
      (is (= 500 (status-for :internal-error)))
      (is (= 500 (status-for :something-nobody-mapped))))))

(deftest ^:integration a-decodable-body-that-is-not-a-map-is-answered
  ;; transit will happily decode a vector or a string. Those reached the
  ;; handler and threw in `merge` — before `envelope-problem` could name the
  ;; fault — so the endpoint answered 500 for a request it had promised to
  ;; answer 400.
  (let [provider (mock/make-mock-provider)
        handler  (wrap-format (server/rpc-handler pay-ports/IPaymentProvider provider))
        post     (fn [body]
                   (handler {:uri "/rpc" :request-method :post
                             :headers {"content-type" content-type "accept" content-type}
                             :body (encode-body body)}))]

    (testing "a vector body"
      (let [response (post [1 2 3])]
        (is (= 400 (:status response)))
        (is (= :rpc/protocol (get-in (decode-body (:body response)) [:error :type])))))

    (testing "a two-element vector, which merge would have accepted as a pair"
      ;; The nastier one: `(conj {} [:operation :drop-database])` does not
      ;; throw, it silently produces a map. The status would have been 400
      ;; either way — via :rpc/unknown-operation — so this asserts the type,
      ;; which is the part that says the request was never an envelope.
      (let [response (post [:operation :drop-database])]
        (is (= 400 (:status response)))
        (is (= :rpc/protocol (get-in (decode-body (:body response)) [:error :type])))))

    (testing "a string body"
      (is (= 400 (:status (post "not an envelope")))))

    (testing "a number body"
      (is (= 400 (:status (post 42)))))

    (testing "and the message says what was wrong"
      (is (re-find #"not a map"
                   (get-in (decode-body (:body (post ["x"]))) [:error :message]))))))

(deftest ^:integration the-client-posts-where-the-service-mounts
  ;; The path is not a constant on either side. The platform router rewrites
  ;; :api route paths with the version prefix, so a handler mounted there is
  ;; served at /api/v1/rpc while a client using the default posts to /rpc —
  ;; a 404 that reads as the service being down rather than being elsewhere.
  (let [seen     (atom nil)
        provider (mock/make-mock-provider)
        handler  (wrap-format (server/rpc-handler pay-ports/IPaymentProvider provider))
        app      (fn [request]
                   (reset! seen (:uri request))
                   (handler request))]
    (with-server app
      (fn [url]
        (testing "by default it posts to /rpc"
          (reset! seen nil)
          (client/call! url :get-payment-status ["id"] {:retries 0})
          (is (= "/rpc" @seen)))

        (testing "and to the configured path when the service mounts elsewhere"
          ;; The versioned case: a module that returned the handler under :api.
          (reset! seen nil)
          (client/call! url :get-payment-status ["id"] {:retries 0 :path "/api/v1/rpc"})
          (is (= "/api/v1/rpc" @seen)))

        (testing "the adapter passes it through too, not just call!"
          (reset! seen nil)
          (let [remote (client/remote-adapter pay-ports/IPaymentProvider url
                                              {:retries 0 :path "/internal/rpc"})]
            (pay-ports/get-payment-status remote "id")
            (is (= "/internal/rpc" @seen))))))))

(deftest ^:integration rpc-routes-mounts-where-the-default-client-looks
  ;; The two defaults have to agree, and they live in different namespaces.
  ;; This is the assertion that fails if either moves.
  (let [provider (mock/make-mock-provider)
        routes   (server/rpc-routes pay-ports/IPaymentProvider provider)
        route    (first (:web routes))]

    (testing "the routes go in the :web slot, which versioning does not rewrite"
      (is (contains? routes :web))
      (is (not (contains? routes :api))
          ":api would be served at /api/v1/rpc and the default client would 404"))

    (testing "at the path the client posts to by default"
      (is (= (:path route) (:path client/default-opts))))

    (testing "on POST, since that is what the client sends"
      (is (fn? (get-in route [:methods :post :handler]))))

    (testing "and kept out of the published API document"
      ;; Service-to-service plumbing, not public surface.
      (is (true? (get-in route [:methods :post :no-doc]))))

    (testing "a service that wants it elsewhere can say so"
      (is (= "/internal/rpc"
             (:path (first (:web (server/rpc-routes pay-ports/IPaymentProvider
                                                    provider "/internal/rpc")))))))))

(deftest ^:integration routes-from-rpc-routes-actually-serve-the-protocol
  ;; Without this, the two tests above would pass on a route map that no
  ;; server can use.
  (let [provider (mock/make-mock-provider)
        route    (first (:web (server/rpc-routes pay-ports/IPaymentProvider provider)))
        handler  (wrap-format (get-in route [:methods :post :handler]))
        app      (fn [request]
                   (if (= (:path route) (:uri request))
                     (handler request)
                     {:status 404 :body ""}))]
    (with-server app
      (fn [url]
        (let [remote (client/remote-adapter pay-ports/IPaymentProvider url {:retries 0})]
          (is (= :mock (pay-ports/provider-name remote))))))))
