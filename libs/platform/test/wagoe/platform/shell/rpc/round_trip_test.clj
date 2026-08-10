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
            [ring.adapter.jetty :as jetty]))

(def ^:private port 3811)

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

(defn- with-service
  "Run `f` with the payments protocol served over HTTP on `port`."
  [implementation f]
  (let [handler (wrap-format (server/rpc-handler pay-ports/IPaymentProvider implementation))
        app     (fn [request]
                  (if (= "/rpc" (:uri request))
                    (handler request)
                    {:status 404 :body "{}"}))
        server  (jetty/run-jetty app {:port port :join? false})]
    (try (f) (finally (.stop server)))))

(deftest ^:integration protocol-calls-cross-a-real-socket
  (let [provider (mock/make-mock-provider)]
    (with-service provider
      (fn []
        (let [remote (client/remote-adapter pay-ports/IPaymentProvider
                                            (str "http://localhost:" port)
                                            {:retries 0})]

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
        app     (fn [r] (if (= "/rpc" (:uri r)) (handler r) {:status 404 :body "{}"}))
        srv     (jetty/run-jetty app {:port (inc port) :join? false})]
    (try
      (let [remote (client/remote-adapter pay-ports/IPaymentProvider
                                          (str "http://localhost:" (inc port))
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
          (is (= "tok-xyz" (:auth-token @seen)))))
      (finally (.stop srv)))))

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
        srv      (jetty/run-jetty app {:port (+ port 2) :join? false})
        url      (str "http://localhost:" (+ port 2))]
    (try
      (testing "a remote error is not retried by default"
        ;; A 500 means the far side ran and refused. Re-sending it would
        ;; re-submit a payment the PSP already rejected on its merits.
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
        ;; JSON has no keywords, so `:type` arrives as a string. Comparing it
        ;; against a set of keywords misses silently — the retry simply never
        ;; happens — which is why this asserts on attempts rather than on the
        ;; returned map.
        (reset! attempts 0)
        (client/call! url :get-payment-status ["id"]
                      {:retry-on #{:rpc/unavailable} :retries 2 :retry-delay-ms 1})
        (is (= 1 @attempts) "a remote error is not in that set, so it is not retried"))

      (testing "retries 0 means one attempt"
        (reset! attempts 0)
        (client/call! url :get-payment-status ["id"]
                      {:retry-on #{:rpc/remote-error} :retries 0})
        (is (= 1 @attempts)))
      (finally (.stop srv)))))

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
      (fn []
        (let [url (str "http://localhost:" port)]

          (testing "a remote exception reaches the caller with its message and operation"
            (let [r (client/call! url :create-checkout-session [{}] {:retries 0})]
              (is (= :rpc/remote-error (get-in r [:error :type])))
              (is (= "psp refused the card" (get-in r [:error :message])))
              (is (= :create-checkout-session (get-in r [:error :operation])))))

          (testing "a refused operation keeps its own type rather than becoming generic"
            ;; :rpc/unknown-operation means the caller and the service disagree
            ;; about the contract — a deploy skew. It needs a different response
            ;; from "the payment provider is down", so it must not be flattened
            ;; into :rpc/remote-error.
            (let [r (client/call! url :drop-database [] {:retries 0})]
              (is (= :rpc/unknown-operation (get-in r [:error :type])))
              (is (re-find #"exposes no operation" (get-in r [:error :message]))))))))))

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
      (fn []
        (let [remote (client/remote-adapter pay-ports/IPaymentProvider
                                            (str "http://localhost:" port)
                                            {:retries 0})]

          (testing "a keyword returned as the whole result is still a keyword"
            (is (= :mock (pay-ports/provider-name remote))))

          (testing "a keyword nested in a map is still a keyword"
            (let [status (pay-ports/get-payment-status remote "pay-1")]
              (is (= :paid (:status status)))
              (is (= (pay-ports/get-payment-status provider "pay-1") status)
                  "and the whole value equals what the in-process provider returned")))

          (testing "a boolean is not stringified either"
            (is (true? (pay-ports/verify-webhook-signature remote "{}" "sig")))))))))
