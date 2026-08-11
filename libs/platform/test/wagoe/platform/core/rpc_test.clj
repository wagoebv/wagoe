(ns wagoe.platform.core.rpc-test
  (:require [wagoe.platform.core.rpc :as rpc]
            [clojure.test :refer [deftest is testing]]))

(deftest ^:unit request-envelope-carries-only-what-is-present
  (testing "operation and args are always there"
    (is (= {:operation :get-payment-status :args ["abc"]}
           (rpc/request-envelope :get-payment-status ["abc"] {}))))

  (testing "a symbol operation is normalised to a keyword"
    ;; The protocol's :sigs give symbols; the wire format must not depend on
    ;; how the caller happened to name it.
    (is (= :get-payment-status (:operation (rpc/request-envelope 'get-payment-status [] {})))))

  (testing "absent context keys are omitted, not sent as nil"
    ;; So the far side can tell "not propagated" from "explicitly none".
    (let [e (rpc/request-envelope :op [] {:correlation-id "c"})]
      (is (= "c" (:correlation-id e)))
      (is (not (contains? e :tenant-id)))
      (is (not (contains? e :auth-token))))))

(deftest ^:unit context-survives-a-round-trip-through-headers
  (let [envelope (rpc/request-envelope :op [] {:correlation-id "corr-1"
                                               :tenant-id      "t-1"
                                               :auth-token     "tok"})
        headers  (rpc/context->headers envelope)]

    (testing "it uses the header the pipeline already uses"
      (is (= "corr-1" (get headers "x-correlation-id"))))

    (testing "a raw token is sent as a bearer credential"
      (is (= "Bearer tok" (get headers "authorization"))))

    (testing "a token that already names a scheme is left alone"
      (is (= "Basic abc"
             (get (rpc/context->headers {:auth-token "Basic abc"}) "authorization"))))

    (testing "and reading them back yields what went in"
      (is (= {:correlation-id "corr-1" :tenant-id "t-1" :auth-token "tok"}
             (rpc/headers->context headers))))

    (testing "header lookup is case-insensitive"
      ;; Ring lower-cases inbound names, a hand-built request may not, and
      ;; losing the correlation id to a capital letter would be silent.
      (is (= "corr-1" (:correlation-id (rpc/headers->context {"X-Correlation-Id" "corr-1"})))))))

(deftest ^:unit failures-keep-their-kind
  (testing "a timeout and a refused connection are not the same thing"
    ;; One is a slow service, the other a missing one; they need different
    ;; operator responses.
    (is (= :rpc/timeout     (rpc/classify-exception (java.net.SocketTimeoutException. "t"))))
    (is (= :rpc/unavailable (rpc/classify-exception (java.net.ConnectException. "c"))))
    (is (= :rpc/unavailable (rpc/classify-exception (java.net.UnknownHostException. "h")))))

  (testing "the error shape matches what adapters already return"
    (let [e (rpc/transport-error :rpc/timeout :op "too slow" 504)]
      (is (= :rpc/timeout (get-in e [:error :type])))
      (is (= :op (get-in e [:error :operation])))
      (is (= 504 (get-in e [:error :status]))))))

(deftest ^:unit a-body-that-is-not-an-envelope-is-a-failure
  (testing "a result is unwrapped"
    (is (= {:ok true} (rpc/response->result :op {:result {:ok true}}))))

  (testing "a nil result is still a result, not a missing one"
    ;; `(contains? body :result)` rather than a truthiness check: a protocol
    ;; method returning nil is a legitimate answer.
    (is (nil? (rpc/response->result :op {:result nil}))))

  (testing "a remote error is passed through"
    (is (= :psp/declined (get-in (rpc/response->result :op {:error {:type :psp/declined
                                                                   :message "no"}})
                                 [:error :type]))))

  (testing "anything else says the far side is not what we think it is"
    (is (= :rpc/protocol (get-in (rpc/response->result :op "<html>") [:error :type])))
    (is (= :rpc/protocol (get-in (rpc/response->result :op {:unexpected 1}) [:error :type])))))

(deftest ^:unit a-request-that-cannot-be-invoked-is-named-not-thrown
  ;; The endpoint is reachable by anything that can post to it, so the envelope
  ;; is untrusted input. Every one of these used to throw while reading the
  ;; operation, before the error map describing the failure could be built.
  (testing "a well-formed envelope has no problem"
    (is (nil? (rpc/envelope-problem {:operation :op :args []})))
    (is (nil? (rpc/envelope-problem {:operation "op" :args nil}))
        "absent args are legitimate — a zero-argument method"))

  (testing "a missing operation is reported"
    (is (re-find #"no :operation" (rpc/envelope-problem {:args []}))))

  (testing "an operation that is not a name is reported"
    (is (some? (rpc/envelope-problem {:operation 42 :args []})))
    (is (some? (rpc/envelope-problem {:operation {:a 1} :args []}))))

  (testing "args that cannot be applied are reported"
    ;; Otherwise `apply` throws and the failure is attributed to the service
    ;; rather than to the caller that sent it.
    (is (some? (rpc/envelope-problem {:operation :op :args "not-a-sequence"})))))

(deftest ^:unit error-types-are-keywords-again-after-the-wire
  ;; JSON has no keywords. A caller — and the client's own retry policy —
  ;; branches on :type, and comparing a string against a set of keywords misses
  ;; without any sign that it did: the branch simply never runs.
  (testing "a type that crossed as a string comes back a keyword"
    (is (= :rpc/timeout (:type (rpc/revive-error {:type "rpc/timeout" :message "x"})))))

  (testing "so does the operation"
    (is (= :get-payment-status
           (:operation (rpc/revive-error {:type "rpc/timeout" :message "x"
                                          :operation "get-payment-status"})))))

  (testing "a type that is already a keyword is left alone"
    (is (= :rpc/timeout (:type (rpc/revive-error {:type :rpc/timeout :message "x"})))))

  (testing "nil is nil"
    (is (nil? (rpc/revive-error nil))))

  (testing "and a response envelope revives its error the same way"
    (is (= :psp/declined
           (get-in (rpc/response->result :op {:error {:type "psp/declined" :message "no"}})
                   [:error :type])))))

(deftest ^:unit an-error-without-an-operation-is-still-an-error
  ;; A request that never named an operation has none to report; building the
  ;; error map must not require one.
  (let [e (rpc/transport-error :rpc/protocol nil "no operation")]
    (is (= :rpc/protocol (get-in e [:error :type])))
    (is (not (contains? (:error e) :operation)))))

(deftest ^:unit only-carryable-data-crosses-the-wire
  (testing "plain Clojure data is kept"
    (is (= {:status 401 :provider :stripe :tags #{:a} :nested {:n [1 "x" :k]}}
           (rpc/plain-data {:status 401 :provider :stripe :tags #{:a}
                            :nested {:n [1 "x" :k]}}))))

  (testing "values a wire format cannot carry are dropped"
    ;; ex-data is written for a local reader: providers put response objects
    ;; and connections in it. Encoding would fail on those, turning a typed
    ;; domain error into a transport error — the opposite of preserving it.
    (is (= {:status 401} (rpc/plain-data {:status 401 :connection (Object.)}))))

  (testing "a collection is dropped if anything inside it cannot be carried"
    (is (= {} (rpc/plain-data {:xs [1 2 (Object.)]}))))

  (testing "nil and non-maps are not data"
    (is (nil? (rpc/plain-data nil)))
    (is (nil? (rpc/plain-data "not a map")))))

(deftest ^:unit a-thrown-exception-keeps-the-type-callers-branch-on
  (testing "a typed ex-info keeps its own type"
    ;; Providers throw {:type :internal-error …} and the HTTP boundary maps
    ;; that to a status. Reporting :rpc/remote-error instead would produce a
    ;; generic 500 and a warning about a missing :type.
    (let [e (rpc/thrown-error :create-checkout-session
                              (ex-info "nope" {:type :not-implemented :feature :x}))]
      (is (= :not-implemented (get-in e [:error :type])))
      (is (= "nope" (get-in e [:error :message])))
      (is (= :create-checkout-session (get-in e [:error :operation])))
      (is (= {:feature :x} (get-in e [:error :data])) ":type is not repeated inside :data")
      (is (true? (get-in e [:error :rpc/thrown])) "so the client knows to raise it again")))

  (testing "an untyped exception gets one, rather than reaching the boundary without"
    (let [e (rpc/thrown-error :op (RuntimeException. "boom"))]
      (is (= :rpc/remote-error (get-in e [:error :type])))
      (is (true? (get-in e [:error :rpc/thrown])))))

  (testing "an exception with no message still produces one"
    (is (string? (get-in (rpc/thrown-error :op (RuntimeException.)) [:error :message]))))

  (testing "unserialisable ex-data does not sink the error"
    (let [e (rpc/thrown-error :op (ex-info "x" {:type :internal-error :conn (Object.)}))]
      (is (= :internal-error (get-in e [:error :type])))
      (is (nil? (:conn (get-in e [:error :data])))))))

(deftest ^:unit an-envelope-that-is-not-a-map-is-named-as-such
  ;; transit decodes a vector or a string happily, so these reach the handler.
  ;; Reporting them as "no :operation" would send an operator looking for a
  ;; missing key in a body that was never an envelope.
  (testing "non-maps are rejected with a message that says why"
    (is (re-find #"not a map" (rpc/envelope-problem [1 2 3])))
    (is (re-find #"not a map" (rpc/envelope-problem "not an envelope")))
    (is (re-find #"not a map" (rpc/envelope-problem 42))))

  (testing "a two-element vector too, which `merge` would have silently accepted"
    (is (re-find #"not a map" (rpc/envelope-problem [:operation :drop-database]))))

  (testing "nil is reported, not passed through"
    (is (some? (rpc/envelope-problem nil))))

  (testing "and a map is still fine, so the guard did not reject everything"
    (is (nil? (rpc/envelope-problem {:operation :op :args []})))))

(deftest ^:security ^:unit a-service-key-must-be-worth-having
  (testing "a key of the required length is accepted"
    (is (nil? (rpc/service-key-problem (apply str (repeat 32 "a"))))))

  (testing "a short key is refused, with the requirement in the message"
    ;; This endpoint invokes port methods; a key someone typed by hand guards
    ;; the ability to create payments.
    (is (re-find #"at least 32" (rpc/service-key-problem "short"))))

  (testing "a missing key is refused"
    (is (some? (rpc/service-key-problem nil)))
    (is (some? (rpc/service-key-problem 12345)))))

(deftest ^:security ^:unit key-comparison-does-not-leak-a-prefix
  (let [key (apply str (repeat 40 "k"))]
    (testing "the right key matches"
      (is (rpc/service-key-matches? key key)))

    (testing "a wrong key does not"
      (is (not (rpc/service-key-matches? key (apply str (repeat 40 "x"))))))

    (testing "nor does a correct prefix"
      ;; `=` on strings returns at the first differing character, so the time
      ;; it takes leaks how much of a guess was right. A prefix must be as
      ;; wrong as anything else.
      (is (not (rpc/service-key-matches? key (subs key 0 39))))
      (is (not (rpc/service-key-matches? key (str key "x")))))

    (testing "and nothing matches a missing key"
      (is (not (rpc/service-key-matches? key nil)))
      (is (not (rpc/service-key-matches? nil nil)))
      (is (not (rpc/service-key-matches? nil "anything"))))))

(deftest ^:security ^:unit the-service-key-is-not-the-users-token
  ;; One says which service is calling, the other on whose behalf. Sharing a
  ;; header would mean any caller holding a valid user token could invoke port
  ;; methods directly.
  (is (not= rpc/service-key-header (:auth-token rpc/context-headers)))
  (testing "and reading context back never picks the service key up"
    (is (empty? (rpc/headers->context {rpc/service-key-header "secret"})))))
