(ns wagoe.platform.shell.rpc.breaker-test
  "The breaker against a real socket, counting what the far side receives.

   A breaker that returns the right error while the requests keep arriving has
   not done its job — the point is that the failing service stops being called."
  (:require [wagoe.cache.ports :as cache]
            [wagoe.cache.shell.adapters.in-memory :as cache-mem]
            [wagoe.platform.shell.rpc.client :as client]
            [wagoe.payments.ports :as pay-ports]
            [clojure.test :refer [deftest is testing]]
            [ring.adapter.jetty :as jetty])
  (:import (org.eclipse.jetty.server ServerConnector)))

(def ^:private service-key "breaker-test-service-key-at-least-32-chars")

(defn- with-slow-server
  "Serve `f` a URL whose handler sleeps past the client's timeout.

   A timeout is the failure this needs: `:rpc/unavailable` means nothing is
   listening, and nothing listening cannot count the requests it received."
  [received f]
  (let [app    (fn [_] (swap! received inc) (Thread/sleep 900) {:status 200 :body ""})
        server (jetty/run-jetty app {:port 0 :join? false})
        port   (.getLocalPort ^ServerConnector (first (.getConnectors server)))]
    (try (f (str "http://localhost:" port))
         (finally (.stop server)))))

(defn- opts [cache extra]
  (merge {:retries 0 :timeout-ms 300 :service-key service-key :cache cache} extra))

(deftest ^:integration a-failing-service-stops-being-called
  (let [received (atom 0)
        cache    (cache-mem/create-in-memory-cache {})
        cfg      {:failure-threshold 3 :open-ms 60000}]
    (with-slow-server received
      (fn [url]
        (dotimes [_ 3]
          (client/call! url :get-payment-status ["id"] (opts cache {:circuit-breaker cfg})))

        (testing "the first calls reach it"
          (is (= 3 @received)))

        (testing "and after the threshold the requests stop arriving"
          (let [r (client/call! url :get-payment-status ["id"] (opts cache {:circuit-breaker cfg}))]
            (is (= 3 @received) "a call got through after the circuit opened")
            (is (= :rpc/circuit-open (get-in r [:error :type])))))

        (testing "the refusal says how long it will last"
          (let [r (client/call! url :get-payment-status ["id"] (opts cache {:circuit-breaker cfg}))]
            (is (pos? (get-in r [:error :retry-after-ms])))))))))

(deftest ^:integration the-breaker-lets-one-call-through-once-the-window-elapses
  (let [received (atom 0)
        cache    (cache-mem/create-in-memory-cache {})
        cfg      {:failure-threshold 2 :open-ms 700}]
    (with-slow-server received
      (fn [url]
        (dotimes [_ 2]
          (client/call! url :get-payment-status ["id"] (opts cache {:circuit-breaker cfg})))
        (is (= 2 @received))

        (testing "closed to traffic while the window is open"
          (client/call! url :get-payment-status ["id"] (opts cache {:circuit-breaker cfg}))
          (is (= 2 @received)))

        (testing "then a probe is allowed through"
          (Thread/sleep 900)
          (client/call! url :get-payment-status ["id"] (opts cache {:circuit-breaker cfg}))
          (is (= 3 @received) "no probe reached the service after the window elapsed"))))))

(deftest ^:integration two-adapters-on-one-url-share-a-breaker
  ;; The state is in the cache, not in the adapter. Two `remote-adapter` calls
  ;; for the same service — which happens whenever two modules each build one —
  ;; must not each have to discover the outage separately.
  (let [received (atom 0)
        cache    (cache-mem/create-in-memory-cache {})
        cfg      {:failure-threshold 2 :open-ms 60000}]
    (with-slow-server received
      (fn [url]
        (let [a (client/remote-adapter pay-ports/IPaymentProvider url
                                       (opts cache {:circuit-breaker cfg}))
              b (client/remote-adapter pay-ports/IPaymentProvider url
                                       (opts cache {:circuit-breaker cfg}))]
          (dotimes [_ 2] (pay-ports/get-payment-status a "id"))
          (is (= 2 @received) "the first adapter's calls reached the service")

          (testing "the second adapter is refused without trying"
            (let [r (pay-ports/get-payment-status b "id")]
              (is (= :rpc/circuit-open (get-in r [:error :type])))
              (is (= 2 @received) "the second adapter opened its own connection"))))))))

(deftest ^:integration a-service-that-answers-keeps-the-breaker-closed
  ;; Only failures that never reached the service count. An answer — even an
  ;; error one — means it is up.
  (let [received (atom 0)
        cache    (cache-mem/create-in-memory-cache {})
        cfg      {:failure-threshold 2 :open-ms 60000}
        app      (fn [_] (swap! received inc) {:status 500 :headers {} :body "<html>502</html>"})
        server   (jetty/run-jetty app {:port 0 :join? false})
        port     (.getLocalPort ^ServerConnector (first (.getConnectors server)))
        url      (str "http://localhost:" port)]
    (try
      (dotimes [_ 5]
        (client/call! url :get-payment-status ["id"] (opts cache {:circuit-breaker cfg})))

      (testing "every call reaches it — a remote error is not a reason to stop"
        (is (= 5 @received)))
      (finally (.stop server)))))

(deftest ^:integration without-a-cache-the-breaker-is-not-there
  ;; Off by default: a caller that supplies no cache behaves exactly as it did
  ;; before this existed.
  (let [received (atom 0)]
    (with-slow-server received
      (fn [url]
        (dotimes [_ 4]
          (client/call! url :get-payment-status ["id"]
                        {:retries 0 :timeout-ms 300 :service-key service-key}))
        (is (= 4 @received) "a breaker engaged without one being configured")))))

(deftest ^:integration a-broken-cache-does-not-stop-calls-going-out
  ;; Failing open. The worst case is the behaviour of no breaker at all, which
  ;; is what every caller had before this existed — far better than a cache
  ;; outage taking every remote call with it.
  (let [received (atom 0)
        broken   (reify cache/ICache
                   (get-value [_ _] (throw (ex-info "cache is down" {})))
                   (set-value! [_ _ _] (throw (ex-info "cache is down" {})))
                   (set-value! [_ _ _ _] (throw (ex-info "cache is down" {})))
                   (delete-key! [_ _] (throw (ex-info "cache is down" {})))
                   (exists? [_ _] (throw (ex-info "cache is down" {})))
                   (ttl [_ _] (throw (ex-info "cache is down" {})))
                   (expire! [_ _ _] (throw (ex-info "cache is down" {}))))]
    (with-slow-server received
      (fn [url]
        (dotimes [_ 3]
          (client/call! url :get-payment-status ["id"]
                        (opts broken {:circuit-breaker {:failure-threshold 1}})))
        (is (= 3 @received) "a cache outage stopped calls the service could have served")))))
