(ns wagoe.platform.core.circuit-breaker-test
  (:require [wagoe.platform.core.circuit-breaker :as cb]
            [clojure.test :refer [deftest is testing]]))

(def ^:private config (assoc cb/default-config :failure-threshold 3 :open-ms 1000))

(deftest ^:unit a-breaker-trips-at-the-threshold-and-not-before
  (let [failures (iterate #(cb/on-failure % config 100) {})
        after    (fn [n] (nth failures n))]

    (testing "below the threshold it stays closed"
      (is (= :closed (cb/state (after 1) config 100)))
      (is (= :closed (cb/state (after 2) config 100))))

    (testing "at the threshold it opens"
      (is (= :open (cb/state (after 3) config 100))))

    (testing "one blip does not take a service out of service"
      (is (= :closed (cb/state (cb/on-failure {} config 100) config 100))))))

(deftest ^:unit success-resets-the-run
  (testing "the count is of consecutive failures"
    (let [two-failures (-> {} (cb/on-failure config 0) (cb/on-failure config 0))]
      (is (= 2 (:failures two-failures)))
      (is (zero? (:failures (cb/on-success two-failures))))

      (testing "so a failure after a success starts again"
        (let [restarted (cb/on-failure (cb/on-success two-failures) config 0)]
          (is (= 1 (:failures restarted)))
          (is (= :closed (cb/state restarted config 0))))))))

(deftest ^:unit an-open-breaker-becomes-half-open-when-the-window-elapses
  (let [tripped (reduce (fn [b _] (cb/on-failure b config 1000)) {} (range 3))]
    (is (= :open (cb/state tripped config 1000)))
    (is (= :open (cb/state tripped config 1999)) "still inside the window")

    (testing "half-open at the boundary, without anything running on a timer"
      (is (= :half-open (cb/state tripped config 2000)))
      (is (= :half-open (cb/state tripped config 99999))))))

(deftest ^:unit only-failures-that-never-reached-the-service-count
  (testing "unreachable and timed out"
    (is (cb/counts-as-failure? config :rpc/unavailable))
    (is (cb/counts-as-failure? config :rpc/timeout)))

  (testing "a timeout counts even though it is never retried"
    ;; Retrying a timeout risks running a non-idempotent call twice; declining
    ;; to make a new call risks nothing. The decisions read alike and differ.
    (is (cb/counts-as-failure? config :rpc/timeout)))

  (testing "an answer does not, whatever it says"
    ;; The service responded, so it is up. Refusing to call it because its
    ;; answers are unwelcome would be a different feature.
    (is (not (cb/counts-as-failure? config :rpc/remote-error)))
    (is (not (cb/counts-as-failure? config :rpc/unknown-operation)))
    (is (not (cb/counts-as-failure? config :rpc/protocol)))
    (is (not (cb/counts-as-failure? config :not-found)))
    (is (not (cb/counts-as-failure? config nil)))))

(deftest ^:unit the-refusal-is-distinguishable-from-a-failed-attempt
  ;; ":rpc/circuit-open" means we declined to try; ":rpc/unavailable" means we
  ;; tried and could not reach it. An operator reading a log needs both.
  (let [e (cb/error :get-payment-status "http://payments:3001" 1500)]
    (is (= :rpc/circuit-open (get-in e [:error :type])))
    (is (= :get-payment-status (get-in e [:error :operation])))
    (is (= 1500 (get-in e [:error :retry-after-ms])))
    (is (re-find #"payments:3001" (get-in e [:error :message]))))

  (testing "an operation is optional"
    (is (not (contains? (:error (cb/error nil "http://x" nil)) :operation)))))

(deftest ^:unit retry-after-counts-down
  (let [tripped {:failures 3 :opened-at-ms 1000}]
    (is (= 1000 (cb/retry-after-ms tripped config 1000)))
    (is (= 400 (cb/retry-after-ms tripped config 1600)))
    (is (zero? (cb/retry-after-ms tripped config 5000)) "never negative")
    (is (nil? (cb/retry-after-ms {} config 1000)) "closed breakers have nothing to wait for")))

(deftest ^:unit a-breaker-that-can-never-open-is-refused
  (testing "a valid config"
    (is (nil? (cb/config-problem {})))
    (is (nil? (cb/config-problem {:failure-threshold 1 :open-ms 5}))))

  (testing "thresholds that make no sense"
    (is (some? (cb/config-problem {:failure-threshold 0})))
    (is (some? (cb/config-problem {:failure-threshold -1})))
    (is (some? (cb/config-problem {:open-ms 0}))))

  (testing "and a trip-on set the client can never produce"
    ;; Configured, inert, and indistinguishable from working.
    (is (re-find #"no error type" (cb/config-problem {:trip-on #{:some/typo}})))
    (is (some? (cb/config-problem {:trip-on #{}})))))
