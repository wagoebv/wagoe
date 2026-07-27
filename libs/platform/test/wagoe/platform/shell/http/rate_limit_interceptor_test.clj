(ns wagoe.platform.shell.http.rate-limit-interceptor-test
  "Tests for BOU-87: the config-driven rate-limit interceptor wired into the
   default HTTP pipeline. Verifies opt-in enforcement, the 429 response, the
   in-memory fallback, and a limit shared across replicas via a single cache."
  (:require [wagoe.cache.ports :as cache-ports]
            [wagoe.platform.shell.http.interceptors :as itc]
            [clojure.test :refer [deftest testing is use-fixtures]]))

;; The in-memory fallback counter is a process-global atom; reset it before each
;; test so cases (and kaocha's repeated suite runs) don't see each other's counts.
(use-fixtures :each
  (fn [t]
    (reset! @#'itc/rate-limit-state {})
    (t)))

(defn- shared-counter-cache
  "Minimal IAtomicCache/ICache backed by one atom — stands in for a Redis
   counter shared by every replica. Two pipelines using the same instance see
   the same counts, exactly like Redis across processes."
  []
  (let [state (atom {})]
    (reify
      cache-ports/IAtomicCache
      (increment! [_ key delta] (get (swap! state update key (fnil + 0) delta) key))
      cache-ports/ICache
      (expire! [_ _key _ttl] true))))

(defn- run
  "Drive a request through only the rate-limit interceptor and return the response."
  [system client-ip]
  (itc/run-http-interceptors
   (fn [_req] {:status 200 :headers {} :body "ok"})
   [itc/http-rate-limit-protection]
   {:request-method :get :uri "/api/v1/thing" :remote-addr client-ip}
   system))

(deftest ^:unit ^:security disabled-is-a-noop
  (testing "with :enabled? false the interceptor never limits and adds no headers"
    (let [system {:rate-limit {:enabled? false :limit 1 :window-ms 60000}}]
      (dotimes [_ 5]
        (let [resp (run system "10.0.0.1")]
          (is (= 200 (:status resp)))
          (is (nil? (get-in resp [:headers "X-RateLimit-Limit"]))))))))

(deftest ^:unit ^:security absent-config-is-a-noop
  (testing "no :rate-limit in system → opt-in default off, never limits"
    (is (= 200 (:status (run {} "10.0.0.2"))))))

(deftest ^:unit ^:security in-memory-fallback-enforces-limit
  (testing "enabled with no cache: allow up to limit, then 429 (single-node)"
    (let [system {:rate-limit {:enabled? true :limit 3 :window-ms 60000}}
          ip     "10.0.0.99"
          codes  (vec (for [_ (range 5)] (:status (run system ip))))]
      (is (= [200 200 200 429 429] codes)
          "first 3 allowed, rest rejected"))))

(deftest ^:unit ^:security exceeding-limit-returns-429-shape
  (testing "429 carries Retry-After + rate-limit headers and typed body"
    (let [system {:rate-limit {:enabled? true :limit 1 :window-ms 60000}}
          ip     "10.0.0.50"]
      (is (= 200 (:status (run system ip))))
      (let [resp (run system ip)]
        (is (= 429 (:status resp)))
        (is (= "60" (get-in resp [:headers "Retry-After"])))
        (is (= "1" (get-in resp [:headers "X-RateLimit-Limit"])))
        (is (= :rate-limit-exceeded (get-in resp [:body :type])))))))

(deftest ^:unit ^:security allowed-response-carries-remaining-header
  (testing "allowed requests expose X-RateLimit-Remaining on the way out (Redis path)"
    (let [system {:rate-limit {:enabled? true :limit 5 :window-ms 60000}
                  :cache      (shared-counter-cache)}
          resp   (run system "10.0.0.7")]
      (is (= 200 (:status resp)))
      (is (= "5" (get-in resp [:headers "X-RateLimit-Limit"])))
      ;; fixed-window counter includes this request → 5 - 1 = 4 remaining
      (is (= "4" (get-in resp [:headers "X-RateLimit-Remaining"]))))))

(deftest ^:unit ^:security shared-cache-enforces-limit-across-replicas
  (testing "two pipelines sharing one cache enforce a single combined limit"
    (let [cache  (shared-counter-cache)
          policy {:enabled? true :limit 4 :window-ms 60000}
          ;; same client hitting two different replicas (same shared cache)
          replica-a {:rate-limit policy :cache cache}
          replica-b {:rate-limit policy :cache cache}
          ip        "203.0.113.5"
          codes     [(:status (run replica-a ip))   ; 1
                     (:status (run replica-b ip))   ; 2
                     (:status (run replica-a ip))   ; 3
                     (:status (run replica-b ip))   ; 4
                     (:status (run replica-a ip))   ; 5 -> over
                     (:status (run replica-b ip))]] ; 6 -> over
      (is (= [200 200 200 200 429 429] codes)
          "combined limit of 4 enforced regardless of which replica served the request"))))

(deftest ^:unit ^:security wired-into-default-stack
  (testing "the config-driven limiter is part of the default HTTP interceptor stack"
    (is (some #(= % itc/http-rate-limit-protection) itc/default-http-interceptors))))

;; =============================================================================
;; In-memory fallback is bounded (heap-leak guard)
;; =============================================================================

(def ^:private prune-stale-clients #'itc/prune-stale-clients)
(def ^:private check-rate-limit-memory #'itc/check-rate-limit-memory)

(deftest ^:unit ^:security prune-stale-clients-drops-out-of-window-and-empty
  (testing "prune keeps only clients with at least one in-window timestamp"
    (let [now    1000000
          cutoff (- now 60000)
          state  {"recent" [(- now 100) (- now 200)]
                  "stale"  [(- now 999999)]
                  "empty"  []
                  "mixed"  [(- now 999999) (- now 50)]}
          pruned (prune-stale-clients state cutoff)]
      (is (= #{"recent" "mixed"} (set (keys pruned))) "stale and empty clients removed")
      (is (= [(- now 50)] (get pruned "mixed")) "mixed client trimmed to in-window timestamps"))))

(deftest ^:unit ^:security in-memory-limiter-bounds-tracked-clients
  (testing "exceeding the client cap sweeps out stale clients instead of growing unbounded"
    (let [window-ms 60000
          ;; Seed the global state with more than the cap of clients whose only
          ;; timestamps are far outside the window (i.e. dead clients).
          stale-ts  (- (System/currentTimeMillis) (* 10 window-ms))
          seeded    (into {} (for [i (range (+ @#'itc/max-tracked-clients 50))]
                               [(str "dead-" i) [stale-ts]]))]
      (reset! @#'itc/rate-limit-state seeded)
      (is (> (count @@#'itc/rate-limit-state) @#'itc/max-tracked-clients))
      ;; One check for a fresh client triggers the sweep; dead clients are dropped.
      (let [res (check-rate-limit-memory "fresh-client" 100 window-ms)]
        (is (:allowed? res))
        (is (<= (count @@#'itc/rate-limit-state) 1)
            "stale clients swept; only the fresh client remains")))))

(deftest ^:unit ^:security in-memory-limiter-hard-caps-when-all-clients-in-window
  (testing "a stream of fresh clients can't grow the map past the cap even when every client is in-window"
    (let [window-ms 60000
          cap       @#'itc/max-tracked-clients
          now       (System/currentTimeMillis)
          ;; Fill to exactly the cap with clients that are ALL in-window (recent),
          ;; so pruning can drop nothing — eviction is the only way to make room.
          seeded    (into {} (for [i (range cap)] [(str "live-" i) [now]]))]
      (reset! @#'itc/rate-limit-state seeded)
      (is (= cap (count @@#'itc/rate-limit-state)))
      ;; Several brand-new clients arrive; the map must stay at the cap.
      (doseq [i (range 5)]
        (check-rate-limit-memory (str "newcomer-" i) 100 window-ms))
      (is (= cap (count @@#'itc/rate-limit-state))
          "map held at the cap — least-recently-active clients evicted, no unbounded growth")
      (is (contains? @@#'itc/rate-limit-state "newcomer-4")
          "the newest client is tracked"))))
