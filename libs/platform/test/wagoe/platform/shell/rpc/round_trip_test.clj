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
            [clj-http.client :as http]
            [clojure.test :refer [deftest is testing]]
            [muuntaja.core :as m]
            [muuntaja.middleware :as m-mw]
            [ring.adapter.jetty :as jetty])
  (:import (org.eclipse.jetty.server ServerConnector)))

(def ^:private content-type "application/transit+json")

(def ^:private service-key
  "A key of the length the handler requires. Tests run the authenticated path
   by default, because that is the only path a deployment is allowed to use."
  "test-service-key-at-least-32-chars-long")

(def ^:private client-opts {:retries 0 :service-key service-key})

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
  "Run `f` with the payments protocol served over HTTP. `f` receives the URL.

   Serves `rpc-app` — the thing callers are actually given — rather than a
   stack assembled here. A hand-built one keeps working after the shipped
   mounting breaks, which is how two route-slot defects survived a green
   suite."
  [implementation f]
  (with-server (server/rpc-app pay-ports/IPaymentProvider implementation
                               {:service-key service-key})
    f))

(deftest ^:integration protocol-calls-cross-a-real-socket
  (let [provider (mock/make-mock-provider)]
    (with-service provider
      (fn [url]
        (let [remote (client/remote-adapter pay-ports/IPaymentProvider url client-opts)]

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
                                              "http://localhost:1" client-opts)
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
                   ((server/rpc-handler pay-ports/IPaymentProvider recorder {:service-key service-key}) request)))
        app     (fn [r] (if (= "/rpc" (:uri r)) (handler r) {:status 404 :body "{}"}))]
    (with-server app
      (fn [url]
        (let [remote (client/remote-adapter pay-ports/IPaymentProvider url
                                            (assoc client-opts
                                                   :context {:correlation-id "corr-123"
                                                             :tenant-id      "tenant-a"
                                                             :auth-token     "tok-xyz"}))]
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
        handler (wrap-format (server/rpc-handler pay-ports/IPaymentProvider boom {:service-key service-key}))
        request {:uri "/rpc" :request-method :post
                 :headers {"content-type" content-type "accept" content-type
                           "x-rpc-service-key" service-key}
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
        ;; A proxy's error page: a non-2xx whose body is not an envelope. That
        ;; is the only failure a retry can help with, because it is the only
        ;; one where the service may never have been reached. A stub returning
        ;; a proper error envelope would be an *answer*, and answers are not
        ;; retried at all — see the test below.
        app      (fn [_]
                   (swap! attempts inc)
                   {:status  502
                    :headers {"content-type" "text/html"}
                    :body    "<html>502 Bad Gateway</html>"})
        run      (fn [url]
                   (testing "a remote error is not retried by default"
                     ;; Even a failure that may not have reached the service is
                     ;; not retried unless asked for: it may equally have run
                     ;; and had its response lost on the way back.
                     (reset! attempts 0)
                     (client/call! url :get-payment-status ["id"] {:service-key service-key})
                     (is (= 1 @attempts)))

                   (testing "opting a failure in retries it exactly :retries more times"
                     (reset! attempts 0)
                     (let [r (client/call! url :get-payment-status ["id"]
                                           (merge client-opts {:retry-on #{:rpc/remote-error}
                                                               :retries  2
                                                               :retry-delay-ms 1}))]
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
                                   (merge client-opts {:retry-on       #{:rpc/unavailable}
                                                       :retries        2
                                                       :retry-delay-ms 1}))
                     (is (= 1 @attempts) "a remote error is not in that set, so it is not retried"))

                   (testing "retries 0 means one attempt"
                     (reset! attempts 0)
                     (client/call! url :get-payment-status ["id"]
                                   (merge client-opts {:retry-on #{:rpc/remote-error}}))
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
                                                      [{}] client-opts)))]
            (is (= :create-checkout-session (:rpc/operation (ex-data e))))
            (is (true? (:rpc/remote (ex-data e)))
                "marked as raised here, since the stack trace is this process")))

        (testing "a refused operation keeps its own type rather than becoming generic"
          ;; :rpc/unknown-operation means the caller and the service disagree
          ;; about the contract — a deploy skew. It needs a different response
          ;; from "the payment provider is down", so it must not be flattened
          ;; into :rpc/remote-error.
          (let [r (client/call! url :drop-database [] client-opts)]
            (is (= :rpc/unknown-operation (get-in r [:error :type])))
            (is (re-find #"exposes no operation" (get-in r [:error :message])))))))))

(deftest ^:integration a-body-that-is-not-an-envelope-gets-an-answer
  ;; This endpoint is reachable by anything that can post to it. A body with no
  ;; :operation used to throw on `(name nil)` before the error map could be
  ;; built, so the promise that errors are returned rather than thrown held
  ;; only for requests that were already well-formed.
  (let [provider (mock/make-mock-provider)
        handler  (wrap-format (server/rpc-handler pay-ports/IPaymentProvider provider {:service-key service-key}))
        post     (fn [body-map]
                   (handler {:uri "/rpc" :request-method :post
                             :headers {"content-type" content-type "accept" content-type
                                       "x-rpc-service-key" service-key}
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
                               :headers {"content-type"      content-type
                                         "accept"            content-type
                                         "x-rpc-service-key" service-key}
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
        (let [remote (client/remote-adapter pay-ports/IPaymentProvider url client-opts)]

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
                                        "http://localhost:1" client-opts)
        cache    (client/remote-adapter ICacheLike "http://localhost:2" client-opts)]

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
      (let [a (client/remote-adapter pay-ports/IPaymentProvider "http://localhost:1" client-opts)
            b (client/remote-adapter pay-ports/IPaymentProvider "http://localhost:2" client-opts)]
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
        (let [remote (client/remote-adapter pay-ports/IPaymentProvider url client-opts)]

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
                                    "http://localhost:1" client-opts)]
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
                                                        (typed error-type)
                                                        {:service-key service-key}))]
                       (:status (handler {:uri "/rpc" :request-method :post
                                          :headers {"content-type"      content-type
                                                    "accept"            content-type
                                                    "x-rpc-service-key" service-key}
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
        handler  (wrap-format (server/rpc-handler pay-ports/IPaymentProvider provider {:service-key service-key}))
        post     (fn [body]
                   (handler {:uri "/rpc" :request-method :post
                             :headers {"content-type" content-type "accept" content-type
                                       "x-rpc-service-key" service-key}
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
        handler  (wrap-format (server/rpc-handler pay-ports/IPaymentProvider provider {:service-key service-key}))
        app      (fn [request]
                   (reset! seen (:uri request))
                   (handler request))]
    (with-server app
      (fn [url]
        (testing "by default it posts to /rpc"
          (reset! seen nil)
          (client/call! url :get-payment-status ["id"] client-opts)
          (is (= "/rpc" @seen)))

        (testing "and to the configured path when the service mounts elsewhere"
          ;; The versioned case: a module that returned the handler under :api.
          (reset! seen nil)
          (client/call! url :get-payment-status ["id"] (assoc client-opts :path "/api/v1/rpc"))
          (is (= "/api/v1/rpc" @seen)))

        (testing "the adapter passes it through too, not just call!"
          (reset! seen nil)
          (let [remote (client/remote-adapter pay-ports/IPaymentProvider url
                                              (assoc client-opts :path "/internal/rpc"))]
            (pay-ports/get-payment-status remote "id")
            (is (= "/internal/rpc" @seen))))))))

(deftest ^:integration rpc-app-serves-where-the-default-client-looks
  ;; The two defaults live in different namespaces and have to agree. Nothing
  ;; else would notice if one moved.
  (is (= server/default-path (:path client/default-opts))))

(deftest ^:integration rpc-app-is-not-a-module-route-map
  ;; It returns a handler, not routes, and that is the point. Both module route
  ;; slots rewrite the path — `:api` gains the version prefix, `:web` gains
  ;; `/web` — so a client on the default `:path` gets a 404 either way, and a
  ;; POST under `/web` is additionally CSRF-validated with a token no
  ;; service-to-service caller has.
  (let [app (server/rpc-app pay-ports/IPaymentProvider (mock/make-mock-provider)
                            {:service-key service-key})]
    (is (fn? app))
    (is (not (map? app)) "a route map would be merged into :api or :web and rewritten")))

(deftest ^:integration rpc-app-serves-one-endpoint-and-nothing-else
  (let [app (server/rpc-app pay-ports/IPaymentProvider (mock/make-mock-provider)
                            {:service-key service-key})
        req (fn [method uri]
              (app {:request-method method :uri uri
                    :headers {"content-type"      content-type
                              "accept"            content-type
                              "x-rpc-service-key" service-key}
                    :body (encode-body {:operation :get-payment-status :args ["id"]})}))]

    (testing "a POST to the path is served"
      (is (= 200 (:status (req :post "/rpc")))))

    (testing "another path is not"
      (is (= 404 (:status (req :post "/anything-else")))))

    (testing "and neither is another method on the right path"
      ;; This listener serves one endpoint; it should not look like it serves
      ;; a browsable API.
      (is (= 404 (:status (req :get "/rpc")))))))

(deftest ^:integration rpc-app-carries-its-own-format-middleware
  ;; A handler that needed the app's muuntaja stack could not be served on a
  ;; listener of its own, which is the only place this endpoint belongs.
  (let [provider (mock/make-mock-provider)]
    (with-server (server/rpc-app pay-ports/IPaymentProvider provider
                                 {:service-key service-key} "/internal/rpc")
      (fn [url]
        (let [remote (client/remote-adapter pay-ports/IPaymentProvider url
                                            (assoc client-opts :path "/internal/rpc"))]
          (is (= :mock (pay-ports/provider-name remote))))))))

(deftest ^:integration an-answer-is-never-retried-whatever-it-contains
  ;; The dangerous case. Adapters in this codebase return {:error {:type …}} as
  ;; an ordinary value, so a protocol method's legitimate result can be shaped
  ;; exactly like a transport failure. If the retry policy reads the shape, a
  ;; call that already ran gets sent again — and for create-checkout-session
  ;; that is a second charge.
  (let [attempts (atom 0)
        ;; A 200 carrying a result that happens to look like a transport error.
        ;; Contrived on purpose: the point is that the client must not be
        ;; deciding from the shape.
        returns-error-shaped-value
        (reify pay-ports/IPaymentProvider
          (provider-name [_] :error-shaped)
          (create-checkout-session [_ _]
            (swap! attempts inc)
            {:error {:type :rpc/unavailable :message "a value, not a failure"}})
          (create-off-session-payment [_ _] nil)
          (get-payment-status [_ _] nil)
          (expire-checkout-session [_ _] nil)
          (process-webhook [_ _ _] nil)
          (verify-webhook-signature [_ _ _] false))]
    (with-service returns-error-shaped-value
      (fn [url]
        (testing "a returned value in the default retry set is not re-sent"
          (reset! attempts 0)
          (let [r (client/call! url :create-checkout-session [{}]
                                (merge client-opts {:retries 3 :retry-delay-ms 1}))]
            (is (= 1 @attempts) "the operation ran once and must not run again")
            (is (= :rpc/unavailable (get-in r [:error :type]))
                "and the caller still gets the value the method returned")))

        (testing "not even when the caller has opted that type in"
          ;; :retry-on cannot make an answer retryable — it selects among
          ;; failures, and this is not one.
          (reset! attempts 0)
          (client/call! url :create-checkout-session [{}]
                        (merge client-opts {:retry-on #{:rpc/unavailable}
                                            :retries 3 :retry-delay-ms 1}))
          (is (= 1 @attempts)))))))

(deftest ^:integration a-deliberate-error-envelope-is-an-answer-too
  ;; The server builds one for a refused operation and for a thrown
  ;; implementation. Both mean the request reached the service, so neither is
  ;; retryable however :retry-on is set.
  (let [attempts (atom 0)
        boom     (reify pay-ports/IPaymentProvider
                   (provider-name [_] :boom)
                   (create-checkout-session [_ _]
                     (swap! attempts inc)
                     (throw (ex-info "psp refused" {:type :conflict})))
                   (create-off-session-payment [_ _] nil)
                   (get-payment-status [_ _] nil)
                   (expire-checkout-session [_ _] nil)
                   (process-webhook [_ _ _] nil)
                   (verify-webhook-signature [_ _ _] false))]
    (with-service boom
      (fn [url]
        (testing "a thrown implementation runs once, even with its type opted in"
          (reset! attempts 0)
          (is (thrown? clojure.lang.ExceptionInfo
                       (client/call! url :create-checkout-session [{}]
                                     (merge client-opts {:retry-on #{:conflict}
                                                         :retries 3 :retry-delay-ms 1}))))
          (is (= 1 @attempts)))

        (testing "and a refused operation is not retried either"
          (let [r (client/call! url :drop-database []
                                (merge client-opts {:retry-on #{:rpc/unknown-operation}
                                                    :retries 3 :retry-delay-ms 1}))]
            (is (= :rpc/unknown-operation (get-in r [:error :type])))))))))

(deftest ^:security ^:integration the-endpoint-refuses-unauthenticated-callers
  ;; This endpoint invokes port methods. An unauthenticated one hands
  ;; create-checkout-session to anything that can reach the port — and
  ;; "internal listener" is a deployment claim, not something enforced by this
  ;; code. Restricting
  ;; operations to the protocol's :sigs bounds what can be called, not who may
  ;; call it.
  (let [invoked  (atom 0)
        counting (reify pay-ports/IPaymentProvider
                   (provider-name [_] (swap! invoked inc) :counting)
                   (create-checkout-session [_ _] (swap! invoked inc) {:ok true})
                   (create-off-session-payment [_ _] nil)
                   (get-payment-status [_ _] (swap! invoked inc) {:ok true})
                   (expire-checkout-session [_ _] nil)
                   (process-webhook [_ _ _] nil)
                   (verify-webhook-signature [_ _ _] false))
        handler  (wrap-format (server/rpc-handler pay-ports/IPaymentProvider counting
                                                  {:service-key service-key}))
        post     (fn [key-header]
                   (handler {:uri "/rpc" :request-method :post
                             :headers (cond-> {"content-type" content-type
                                               "accept"       content-type}
                                        key-header (assoc "x-rpc-service-key" key-header))
                             :body (encode-body {:operation :create-checkout-session
                                                 :args      [{}]})}))]

    (testing "no key at all is refused"
      (reset! invoked 0)
      (let [response (post nil)]
        (is (= 401 (:status response)))
        (is (zero? @invoked) "and the implementation was never reached")))

    (testing "a wrong key is refused"
      (reset! invoked 0)
      (is (= 401 (:status (post "wrong-key-also-at-least-32-characters"))))
      (is (zero? @invoked)))

    (testing "a prefix of the real key is refused"
      ;; The comparison is constant-time, which also means it is not a prefix
      ;; match.
      (reset! invoked 0)
      (is (= 401 (:status (post (subs service-key 0 20)))))
      (is (zero? @invoked)))

    (testing "the rejection does not say which it was"
      ;; "no key" and "wrong key" are distinguishable only to someone guessing.
      (is (= (decode-body (:body (post nil)))
             (decode-body (:body (post "wrong-key-also-at-least-32-characters"))))))

    (testing "and the right key gets through, so the guard is not refusing everything"
      (reset! invoked 0)
      (is (= 200 (:status (post service-key))))
      (is (= 1 @invoked)))

    (testing "header case does not decide authentication"
      (is (= 200 (:status (handler {:uri "/rpc" :request-method :post
                                    :headers {"content-type"      content-type
                                              "accept"            content-type
                                              "X-RPC-Service-Key" service-key}
                                    :body (encode-body {:operation :get-payment-status
                                                        :args      ["id"]})})))))))

(deftest ^:security ^:integration a-service-cannot-start-without-a-key
  ;; At construction rather than per request: a service configured without a
  ;; key must fail to start, not come up serving port methods to anyone.
  (let [provider (mock/make-mock-provider)]

    (testing "no opts at all is refused"
      (is (thrown? clojure.lang.ExceptionInfo
                   (server/rpc-handler pay-ports/IPaymentProvider provider {}))))

    (testing "a nil key is refused"
      (is (thrown? clojure.lang.ExceptionInfo
                   (server/rpc-handler pay-ports/IPaymentProvider provider
                                       {:service-key nil}))))

    (testing "a short key is refused"
      ;; Same 32-character floor as the JWT secret. A key someone typed by hand
      ;; protects an endpoint that can create payments.
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"at least 32"
                            (server/rpc-handler pay-ports/IPaymentProvider provider
                                                {:service-key "short"}))))

    (testing "and rpc-app refuses too, since that is the documented path"
      (is (thrown? clojure.lang.ExceptionInfo
                   (server/rpc-app pay-ports/IPaymentProvider provider {}))))

    (testing "opting out is possible but has to be said out loud"
      ;; For a listener that is not reachable from outside. Spelled so it can
      ;; be grepped for, and so it never happens by omission.
      (is (fn? (server/rpc-handler pay-ports/IPaymentProvider provider {:auth :none}))))))

(deftest ^:security ^:integration an-unauthenticated-client-sees-why-it-was-refused
  (let [provider (mock/make-mock-provider)]
    (with-service provider
      (fn [url]
        (testing "a client with no service key gets :unauthorized, not a mystery 500"
          (let [r (client/call! url :get-payment-status ["id"] {:retries 0})]
            (is (= :unauthorized (get-in r [:error :type])))))

        (testing "and a 401 is not retried — a rejected credential stays rejected"
          (let [r (client/call! url :get-payment-status ["id"]
                                {:retries 3 :retry-delay-ms 1
                                 :retry-on #{:unauthorized}})]
            (is (= :unauthorized (get-in r [:error :type])))))))))

(deftest ^:integration the-http-client-does-not-retry-behind-our-back
  ;; clj-http sits on Apache HttpClient, which retries low-level I/O failures
  ;; on its own before this code sees an outcome. `:retries 0` and keeping
  ;; :rpc/timeout out of :retry-on would not stop a checkout being submitted
  ;; twice if something underneath resent it first.
  ;;
  ;; Asserted on the request rather than by provoking a mid-send socket failure,
  ;; which is timing-dependent and would be flaky in CI. What is being checked
  ;; is that the decision is ours: the option that hands it to HttpClient is
  ;; explicitly turned off.
  (let [sent (atom nil)]
    (with-redefs [http/post (fn [_url opts]
                                         (reset! sent opts)
                                         {:status 200 :body nil})]
      (client/call! "http://example.invalid" :get-payment-status ["id"]
                    {:retries 0 :service-key service-key}))

    (testing "the underlying client is told not to retry"
      (is (contains? @sent :retry-handler))
      (is (false? ((:retry-handler @sent) (java.io.IOException. "boom") 1 nil))
          "and the handler refuses every attempt, not just the first"))

    (testing "the service key is sent, and not as the user's Authorization"
      ;; They authenticate different things; sharing a header would let a valid
      ;; user token invoke port methods directly.
      (is (= service-key (get-in @sent [:headers "x-rpc-service-key"])))
      (is (nil? (get-in @sent [:headers "authorization"]))))))

(deftest ^:integration what-gets-retried-is-what-never-executed
  ;; Ties classification to its consequence, against the real default rather
  ;; than a set written out in the test. A type in :retry-on must mean the call
  ;; did not run.
  (let [retried? #(contains? (:retry-on client/default-opts) %)]

    (testing "a connection that was never established is retried"
      (is (retried? (rpc/classify-exception (java.net.ConnectException. "refused"))))
      (is (retried? (rpc/classify-exception
                     (java.net.SocketException. "Network is unreachable")))
          "including the form a restricted network raises"))

    (testing "and nothing that may have executed is"
      (is (not (retried? (rpc/classify-exception (java.net.SocketTimeoutException. "t"))))
          "a timed-out call may well have run")
      (is (not (retried? (rpc/classify-exception (java.net.SocketException. "Connection reset"))))
          "a reset happens once the request is on the wire"))))
