(ns wagoe.platform.shell.rpc.breaker-test
  "The breaker against a real socket, counting what the far side receives.

   A breaker that returns the right error while the requests keep arriving has
   not done its job — the point is that the failing service stops being called."
  (:require [wagoe.cache.ports :as cache]
            [wagoe.cache.shell.adapters.in-memory :as cache-mem]
            [wagoe.platform.shell.rpc.breaker :as breaker]
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

(deftest ^:integration a-burst-of-simultaneous-failures-trips-the-breaker
  ;; The case a shared breaker exists for. Read-modify-write loses increments
  ;; here — every caller reads the same count and writes back the same
  ;; successor — so a burst of twenty failures advances the counter by one and
  ;; the breaker keeps sending traffic well past its threshold.
  (let [received (atom 0)
        cache    (cache-mem/create-in-memory-cache {})
        cfg      {:failure-threshold 3 :open-ms 60000}
        callers  20]
    (with-slow-server received
      (fn [url]
        (let [latch (java.util.concurrent.CountDownLatch. 1)
              calls (doall (for [_ (range callers)]
                             (future
                               (.await latch)
                               (client/call! url :get-payment-status ["id"]
                                             (opts cache {:circuit-breaker cfg})))))]
          (.countDown latch)
          (doseq [f calls] (deref f 10000 nil))

          (testing "the failures were all counted"
            (is (<= 3 (cache/get-value cache (str "wagoe:rpc:breaker:" url ":failures")))
                "increments were lost — the counter is not atomic"))

          (testing "and the breaker is open afterwards"
            (let [r (client/call! url :get-payment-status ["id"]
                                  (opts cache {:circuit-breaker cfg}))]
              (is (= :rpc/circuit-open (get-in r [:error :type]))
                  "a burst of failures left the breaker closed")))

          (testing "and the next wave is refused without reaching it"
            ;; The burst itself is not what the breaker protects: twenty calls
            ;; issued at the same instant all pass `allow?` before any of them
            ;; has failed, so all twenty reach the service. Nothing can prevent
            ;; that without knowing the future. What the breaker protects is
            ;; every request after it, which is where the load actually is.
            (let [before @received]
              (dotimes [_ callers]
                (client/call! url :get-payment-status ["id"]
                              (opts cache {:circuit-breaker cfg})))
              (is (= before @received)
                  (str "calls kept reaching the service after the breaker opened")))))))))

(deftest ^:integration the-open-moment-is-the-first-crossing-not-the-last
  ;; `set-if-absent!` on the marker. Otherwise every later failure pushes the
  ;; window forward and a busy caller can keep a breaker open indefinitely.
  (let [received (atom 0)
        cache    (cache-mem/create-in-memory-cache {})
        cfg      {:failure-threshold 1 :open-ms 60000}]
    (with-slow-server received
      (fn [url]
        (client/call! url :get-payment-status ["id"] (opts cache {:circuit-breaker cfg}))
        (let [opened-at (:at (cache/get-value cache (str "wagoe:rpc:breaker:" url ":opened-at")))]
          (is (some? opened-at))
          (Thread/sleep 50)
          ;; Refused, so no new failure is recorded — but assert the marker is
          ;; untouched regardless.
          (client/call! url :get-payment-status ["id"] (opts cache {:circuit-breaker cfg}))
          (is (= opened-at
                 (:at (cache/get-value cache (str "wagoe:rpc:breaker:" url ":opened-at"))))
              "the open window was pushed forward by a later failure"))))))

(deftest ^:integration every-concurrent-failure-is-counted
  ;; The counter itself, with the simultaneity forced. The RPC-level burst
  ;; above cannot prove this: twenty calls time out at slightly different
  ;; moments, so even a read-modify-write that loses updates still crawls past
  ;; a threshold of three. Here every thread increments at the same instant,
  ;; which is where read-modify-write loses them — each reads the same value
  ;; and writes back the same successor.
  (let [cache   (cache-mem/create-in-memory-cache {})
        cfg     {:failure-threshold 1000 :open-ms 60000}   ; never opens; count only
        url     "http://concurrent.invalid"
        threads 50
        latch   (java.util.concurrent.CountDownLatch. 1)
        writers (doall (for [_ (range threads)]
                         (future (.await latch)
                                 (breaker/record-failure! cache cfg url 1000))))]
    (.countDown latch)
    (doseq [f writers] (deref f 10000 nil))

    (is (= threads (cache/get-value cache (str "wagoe:rpc:breaker:" url ":failures")))
        (str "counted " (cache/get-value cache (str "wagoe:rpc:breaker:" url ":failures"))
             " of " threads " concurrent failures — increments were lost"))))

(deftest ^:integration a-failed-probe-reopens-the-window
  ;; Without this the window runs out from the *original* outage. A probe
  ;; delayed until late in the marker's lifetime fails, the marker is left
  ;; alone, it expires shortly after, and traffic resumes against a service
  ;; that has just demonstrated it is still down.
  (let [received (atom 0)
        cache    (cache-mem/create-in-memory-cache {})
        cfg      {:failure-threshold 1 :open-ms 400}
        opened   #(:at (cache/get-value cache (str "wagoe:rpc:breaker:" % ":opened-at")))]
    (with-slow-server received
      (fn [url]
        (client/call! url :get-payment-status ["id"] (opts cache {:circuit-breaker cfg}))
        (let [first-open (opened url)]
          (is (some? first-open))

          (testing "the probe is allowed once the window elapses"
            (Thread/sleep 600)
            (let [before @received]
              (client/call! url :get-payment-status ["id"] (opts cache {:circuit-breaker cfg}))
              (is (= (inc before) @received) "no probe was made")))

          (testing "and its failure moves the window forward"
            (is (> (opened url) first-open)
                "the failed probe left the original open time in place"))

          (testing "so the service is protected again immediately"
            (let [before @received]
              (client/call! url :get-payment-status ["id"] (opts cache {:circuit-breaker cfg}))
              (is (= before @received) "traffic resumed after a failed probe"))))))))

(deftest ^:integration a-second-probe-is-allowed-after-the-new-window
  ;; The lease is released when the probe fails, or the next window has nothing
  ;; to take and the breaker never probes again.
  (let [received (atom 0)
        cache    (cache-mem/create-in-memory-cache {})
        cfg      {:failure-threshold 1 :open-ms 400}]
    (with-slow-server received
      (fn [url]
        (client/call! url :get-payment-status ["id"] (opts cache {:circuit-breaker cfg}))
        (Thread/sleep 600)
        (client/call! url :get-payment-status ["id"] (opts cache {:circuit-breaker cfg}))
        (let [after-first-probe @received]
          (Thread/sleep 600)
          (client/call! url :get-payment-status ["id"] (opts cache {:circuit-breaker cfg}))
          (is (= (inc after-first-probe) @received)
              "the probe lease was never released, so no further probe was made"))))))

(deftest ^:integration a-misconfigured-breaker-is-refused-loudly
  ;; Inert and working look identical from the outside.
  (let [cache (cache-mem/create-in-memory-cache {})]
    (doseq [[what bad] [["a typo in trip-on"    {:trip-on #{:rpc/unavailble}}]
                        ["a zero threshold"     {:failure-threshold 0}]
                        ["a negative threshold" {:failure-threshold -1}]
                        ["a zero window"        {:open-ms 0}]]]
      (testing what
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"[Cc]ircuit breaker"
             (client/call! "http://unused.invalid" :get-payment-status ["id"]
                           (opts cache {:circuit-breaker bad}))))))

    (testing "a valid configuration is not refused"
      (let [r (client/call! "http://localhost:1" :get-payment-status ["id"]
                            (opts cache {:circuit-breaker {:failure-threshold 2}}))]
        (is (= :rpc/unavailable (get-in r [:error :type])))))))

(deftest ^:integration a-fractional-window-does-not-expire-early
  ;; End-to-end cover for a window that is not a whole number of seconds. The
  ;; conversion itself is pinned in circuit-breaker-test/millisecond-windows-…,
  ;; which is the assertion that fails under flooring; this one would keep
  ;; passing, because converting after doubling already survives 1500. It is
  ;; here so a future change to how TTLs are derived is caught at the level
  ;; where it is observable.
  (let [received (atom 0)
        cache    (cache-mem/create-in-memory-cache {})
        cfg      {:failure-threshold 1 :open-ms 1500}]
    (with-slow-server received
      (fn [url]
        (client/call! url :get-payment-status ["id"] (opts cache {:circuit-breaker cfg}))
        ;; Past where a floored TTL would have dropped the state (2s), but well
        ;; inside where a ceilinged one keeps it (3s).
        (Thread/sleep 2300)
        (let [before @received]
          (dotimes [_ 5]
            (client/call! url :get-payment-status ["id"] (opts cache {:circuit-breaker cfg})))
          (is (= (inc before) @received)
              (str "expected one probe, " (- @received before)
                   " calls reached the service — the breaker state expired early")))))))

(deftest ^:integration a-configured-trip-policy-is-honoured
  ;; `config-problem` accepts `:rpc/protocol` in `:trip-on`, so the client has
  ;; to act on it. A 2xx carrying a body that is not an envelope is an answer —
  ;; the recording used to key off that and record a success, so the breaker
  ;; could never open for a configuration the validator had approved.
  (let [received (atom 0)
        cache    (cache-mem/create-in-memory-cache {})
        cfg      {:failure-threshold 2 :open-ms 60000 :trip-on #{:rpc/protocol}}
        app      (fn [_] (swap! received inc)
                   {:status 200 :headers {"content-type" "text/html"}
                    :body "<html>not an envelope</html>"})
        server   (jetty/run-jetty app {:port 0 :join? false})
        port     (.getLocalPort ^ServerConnector (first (.getConnectors server)))
        url      (str "http://localhost:" port)]
    (try
      (testing "the malformed answers are counted"
        (dotimes [_ 2]
          (let [r (client/call! url :get-payment-status ["id"] (opts cache {:circuit-breaker cfg}))]
            (is (= :rpc/protocol (get-in r [:error :type])))))
        (is (= 2 @received)))

      (testing "and the breaker opens, as the configuration asked"
        (let [r (client/call! url :get-payment-status ["id"] (opts cache {:circuit-breaker cfg}))]
          (is (= :rpc/circuit-open (get-in r [:error :type]))
              "the configured trip policy was ignored")
          (is (= 2 @received) "a call reached the service after the breaker opened")))
      (finally (.stop server)))))

(deftest ^:integration the-default-policy-still-ignores-answers
  ;; The counterpart. Widening the rule must not make a remote error trip a
  ;; breaker that was not configured to care.
  (let [received (atom 0)
        cache    (cache-mem/create-in-memory-cache {})
        cfg      {:failure-threshold 2 :open-ms 60000}
        app      (fn [_] (swap! received inc)
                   {:status 200 :headers {"content-type" "text/html"} :body "<html>x</html>"})
        server   (jetty/run-jetty app {:port 0 :join? false})
        port     (.getLocalPort ^ServerConnector (first (.getConnectors server)))
        url      (str "http://localhost:" port)]
    (try
      (dotimes [_ 5]
        (client/call! url :get-payment-status ["id"] (opts cache {:circuit-breaker cfg})))
      (is (= 5 @received)
          "a protocol error tripped a breaker whose :trip-on does not name it")
      (finally (.stop server)))))

(deftest ^:integration the-probe-lease-covers-the-whole-probe
  ;; End-to-end cover for a probe that retries. The arithmetic is pinned in
  ;; `the-probe-lease-budget-includes-the-backoff`, which is what fails when the
  ;; backoff is left out; this one keeps passing either way, because the
  ;; timings involved are too indirect to isolate it. Kept as a guard that a
  ;; retrying probe still holds its lease at all.
  (let [received (atom 0)
        cache    (cache-mem/create-in-memory-cache {})
        cfg      {:failure-threshold 1 :open-ms 400}
        ;; 3 attempts × 300ms, plus 500 + 1000ms of backoff: 2.4s of probe
        ;; against a 400ms window.
        slow     {:retries 2 :retry-delay-ms 500 :timeout-ms 300
                  ;; Timeouts are not retried by default — without this the
                  ;; "slow" probe gives up after one attempt and is not slow.
                  :retry-on #{:rpc/timeout}
                  :service-key service-key :cache cache
                  :circuit-breaker cfg}]
    (with-slow-server received
      (fn [url]
        (client/call! url :get-payment-status ["id"] (opts cache {:circuit-breaker cfg}))
        (Thread/sleep 600)

        (let [before @received
              probe  (future (client/call! url :get-payment-status ["id"] slow))]
          ;; While that probe is retrying, a second caller must find the lease
          ;; taken — even though the open window elapsed long ago.
          (Thread/sleep 1200)
          (let [r (client/call! url :get-payment-status ["id"] (opts cache {:circuit-breaker cfg}))]
            (is (= :rpc/circuit-open (get-in r [:error :type]))
                "the lease expired while the probe was still running"))
          (deref probe 10000 nil)
          (is (<= (- @received before) 3)
              "a second probe was sent while the first was in flight"))))))

(deftest ^:unit the-probe-lease-budget-includes-the-backoff
  ;; A probe with retries spends its timeouts *and* the backoff slept between
  ;; them — delay×1 + delay×2 + … A lease covering only the timeouts expires
  ;; while the probe is still running and a second replica takes it, which is
  ;; the one thing half-open exists to prevent.
  (let [seen (atom nil)]
    (with-redefs [breaker/allow? (fn [_ config _ _] (reset! seen config) false)]
      (client/call! "http://unused.invalid" :get-payment-status ["id"]
                    {:retries 2 :retry-delay-ms 500 :timeout-ms 300
                     :service-key service-key
                     :cache (cache-mem/create-in-memory-cache {})}))

    (testing "three attempts of 300ms, plus 500 + 1000ms of backoff"
      (is (= (+ (* 3 300) 500 1000) (:probe-lease-ms @seen))))

    (testing "and with no retries it is just the one timeout"
      (let [seen2 (atom nil)]
        (with-redefs [breaker/allow? (fn [_ config _ _] (reset! seen2 config) false)]
          (client/call! "http://unused.invalid" :get-payment-status ["id"]
                        {:retries 0 :retry-delay-ms 500 :timeout-ms 300
                         :service-key service-key
                         :cache (cache-mem/create-in-memory-cache {})}))
        (is (= 300 (:probe-lease-ms @seen2)))))))
