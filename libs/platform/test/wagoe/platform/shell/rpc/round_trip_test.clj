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
            [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [ring.adapter.jetty :as jetty])
  (:import (java.io ByteArrayInputStream)))

(def ^:private port 3811)

(defn- json-body-middleware
  "Parse the JSON body into :body-params, as the app's muuntaja stack does.

   The handler under test expects :body-params; supplying it here keeps the
   test about the RPC layer rather than about middleware configuration."
  [handler]
  (fn [request]
    (let [body     (some-> (:body request) slurp)
          parsed   (when (seq body) (json/parse-string body true))
          response (handler (assoc request :body-params parsed))]
      (update response :body #(if (string? %) % (json/generate-string %))))))

(defn- with-service
  "Run `f` with the payments protocol served over HTTP on `port`."
  [implementation f]
  (let [handler (json-body-middleware (server/rpc-handler pay-ports/IPaymentProvider implementation))
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
        handler (json-body-middleware
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

(defn- ->stream [s] (ByteArrayInputStream. (.getBytes ^String s "UTF-8")))

(deftest ^:integration a-throwing-implementation-answers-rather-than-hanging
  (let [boom (reify pay-ports/IPaymentProvider
               (provider-name [_] :boom)
               (create-checkout-session [_ _] (throw (ex-info "psp down" {})))
               (create-off-session-payment [_ _] nil)
               (get-payment-status [_ _] nil)
               (expire-checkout-session [_ _] nil)
               (process-webhook [_ _ _] nil)
               (verify-webhook-signature [_ _ _] false))
        handler (json-body-middleware (server/rpc-handler pay-ports/IPaymentProvider boom))
        request {:uri "/rpc" :request-method :post :headers {}
                 :body (->stream (json/generate-string
                                  {:operation "create-checkout-session" :args [{}]}))}
        response (handler request)]

    (testing "the failure is a response, not an escaped exception"
      (is (= 500 (:status response))))

    (testing "and it names the operation that failed"
      (let [body (json/parse-string (:body response) true)]
        (is (= "psp down" (get-in body [:error :message])))
        (is (= "create-checkout-session" (get-in body [:error :operation])))))))

(deftest ^:integration retries-are-counted-not-just-configured
  ;; The retry policy is the part most likely to be wrong in a way nothing
  ;; notices: retrying too little is invisible under a healthy service, and
  ;; retrying a non-idempotent call too much charges a customer twice. Count
  ;; the attempts the far side actually sees.
  (let [attempts (atom 0)
        app      (fn [_]
                   (swap! attempts inc)
                   {:status 500 :headers {} :body "{\"error\":{\"type\":\"boom\"}}"})
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
