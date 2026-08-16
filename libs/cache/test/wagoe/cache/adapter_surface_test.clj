(ns wagoe.cache.adapter-surface-test
  "A deliberate sweep of the cache port's surface, against every adapter.

   `in_memory_test.clj` and `redis_test.clj` test their adapters separately and
   share no cases, so nothing said the two behave alike — and they didn't. Four
   disagreements surfaced during BOU-285, each found by a human reading a diff,
   each a live bug in whichever adapter was wrong, and each invisible to a suite
   that only ever asks one adapter what it does.

   This is the other approach: enumerate the surface rather than wait for it.
   Every protocol method, times every kind of value, times the states a key can
   be in — absent, live, expired. A disagreement is a bug in one of them; a case
   here says only that they must agree, and that what they agree on is what the
   port documents.

   Where they genuinely cannot agree, the case is listed in `class-may-change`
   with its reason, so everything unlisted is required to match and a new entry
   is a decision rather than a surprise."
  {:kaocha.testable/meta {:integration true}}
  (:require [wagoe.cache.ports :as ports]
            [wagoe.cache.shell.adapters.in-memory :as in-memory]
            [wagoe.cache.shell.adapters.redis :as redis]
            [clojure.test :refer [deftest is testing]]))

;; =============================================================================
;; Adapters under test
;; =============================================================================

(defonce ^:private redis-up?
  (delay (try
           (let [pool  (redis/create-redis-pool {:host "localhost" :port 6379 :timeout 1000})
                 cache (redis/create-redis-cache pool)
                 up?   (ports/ping cache)]
             (.close pool)
             up?)
           (catch Exception _ false))))

(defn- unique [] (subs (str (random-uuid)) 0 8))

(defn- adapters
  "Each is [label make]. `make` returns [cache stop!].

   Redis runs on database 15 under a per-call prefix: these run alongside every
   other suite against one Redis, and a sweep that touches unprefixed keys — or
   flushes — takes the others with it."
  ([] (adapters {}))
  ([config]
   (cond-> [["in-memory"
             (fn [] [(in-memory/create-in-memory-cache config) (fn [_] nil)])]]
     @redis-up?
     (conj ["redis"
            (fn []
              (let [pool  (redis/create-redis-pool {:host "localhost" :port 6379 :database 15})
                    pfx   (str "surface-" (unique))
                    cache (redis/create-redis-cache pool (assoc config :prefix pfx))]
                [cache (fn [c]
                         (ports/clear-namespace! c pfx)
                         (redis/close-redis-pool! pool))]))]))))

(defn- each-adapter
  "Call `(f cache label)` with a fresh cache from every adapter."
  ([f] (each-adapter {} f))
  ([config f]
   (doseq [[label make] (adapters config)]
     (testing label
       (let [[cache stop!] (make)]
         (try (f cache label)
              (finally (stop! cache))))))))

(deftest ^:integration the-sweep-covers-more-than-one-adapter
  ;; Everything below reads as passing when only one adapter is in the list,
  ;; which is exactly when it is proving nothing.
  (is (= 2 (count (adapters)))
      "Redis is not reachable on localhost:6379 — this run compared nothing"))

;; =============================================================================
;; Values
;; =============================================================================

(def ^:private value-cases
  [["nil"                    nil]
   ["boolean true"           true]
   ["boolean false"          false]
   ["long"                   42]
   ["zero"                   0]
   ["negative long"          -7]
   ["max long"               Long/MAX_VALUE]
   ["min long"               Long/MIN_VALUE]
   ["boxed integer"          (int 42)]
   ["boxed short"            (short 7)]
   ["double"                 1.5]
   ["double with no fraction" 2.0]
   ["ratio"                  2/3]
   ["big decimal"            1.10M]
   ["big integer"            (biginteger "123456789012345678901234567890")]
   ["string"                 "hello"]
   ["empty string"           ""]
   ["string of digits"       "42"]
   ["unicode string"         "héllo · 世界 · 🎉"]
   ["simple keyword"         :paid]
   ["namespaced keyword"     :order/paid]
   ["symbol"                 'some-symbol]
   ["uuid"                   (java.util.UUID/randomUUID)]
   ["instant"                (.plusNanos (java.time.Instant/now) 123456)]
   ["date"                   (java.util.Date.)]
   ["vector"                 [1 :two "three"]]
   ["empty vector"           []]
   ["list"                   '(1 2 3)]
   ["set"                    #{:a :b}]
   ["empty set"              #{}]
   ["nested map"             {:a {:b {:c [1 #{:d}]}}}]
   ["empty map"              {}]
   ["map with string keys"   {"a" 1}]
   ["map with numeric keys"  {1 :one}]
   ["deeply nested"          {:type :order :lines [{:sku "a" :qty 2}]}]])

(def ^:private class-may-change
  "Values whose concrete class an adapter's storage form cannot preserve.

   Equality still holds — these are listed because `=` cannot see the difference
   and a caller using `instance?` can."
  {"boxed integer"
   (str "Redis stores integers in its native decimal form, so INCR can operate "
        "on them and the TTL survives an increment. That form is a decimal "
        "string and carries no width, so every integer inside 64 bits reads "
        "back as a Long. The values are equal and arithmetic is unaffected.")

   "boxed short"
   "As boxed integer — Redis' native integer form carries no width."})

(deftest ^:integration every-value-type-round-trips-on-every-adapter
  (each-adapter
   (fn [cache label]
     (doseq [[what value] value-cases]
       (let [k (str "v-" (unique))]
         (ports/set-value! cache k value 60)
         (let [got (ports/get-value cache k)]
           (testing what
             (is (= value got)
                 (str label "/" what ": " (pr-str value) " came back as " (pr-str got)))
             (is (or (nil? value)
                     (= (class value) (some-> got class))
                     (contains? class-may-change what))
                 (str label "/" what ": type changed from "
                      (some-> value class .getSimpleName) " to "
                      (some-> got class .getSimpleName)
                      " — if that is acceptable, say so in class-may-change")))))))))

;; =============================================================================
;; Keys
;; =============================================================================

(deftest ^:integration every-key-form-works-on-every-adapter
  (each-adapter
   (fn [cache label]
     (doseq [[what k] [["string"             (str "k" (unique))]
                       ["keyword"            (keyword (str "k" (unique)))]
                       ["namespaced keyword" (keyword (str "ns" (unique)) "k")]
                       ["dotted"             (str "a.b." (unique))]
                       ["colon-separated"    (str "a:b:" (unique))]
                       ["hyphenated"         (str "a-b-" (unique))]]]
       (testing what
         (ports/set-value! cache k {:v what} 60)
         (is (= {:v what} (ports/get-value cache k))
             (str label "/" what ": key form did not round-trip"))
         (is (true? (ports/exists? cache k)))
         (is (true? (ports/delete-key! cache k))))))))

(deftest ^:integration a-keyword-key-and-its-name-are-the-same-key
  ;; `(name key)` is how both adapters normalise, so these are not two keys.
  ;; Pinned because a caller mixing the two forms depends on it.
  (each-adapter
   (fn [cache _label]
     (let [n (str "same-" (unique))]
       (ports/set-value! cache (keyword n) :from-keyword 60)
       (is (= :from-keyword (ports/get-value cache n)))
       (ports/set-value! cache n :from-string 60)
       (is (= :from-string (ports/get-value cache (keyword n))))))))

;; =============================================================================
;; A key that is not there
;; =============================================================================

(deftest ^:integration every-read-agrees-on-a-key-that-is-not-there
  (each-adapter
   (fn [cache label]
     (let [k (str "missing-" (unique))]
       (is (nil?   (ports/get-value cache k))      (str label ": get-value"))
       (is (false? (ports/exists? cache k))        (str label ": exists?"))
       (is (nil?   (ports/ttl cache k))            (str label ": ttl"))
       (is (false? (ports/delete-key! cache k))    (str label ": delete-key!"))
       (is (false? (ports/expire! cache k 60))     (str label ": expire!"))
       (is (= {}   (ports/get-many cache [k]))     (str label ": get-many"))
       (is (zero?  (ports/delete-many! cache [k])) (str label ": delete-many!"))))))

;; =============================================================================
;; A key that has expired
;; =============================================================================
;;
;; The axis the divergences were on. Redis prunes; the in-memory adapter has no
;; sweep, so an expired entry sits in the map until something reads it. That is
;; an implementation difference and must not be an observable one: once the TTL
;; elapses, every operation has to behave as though the key were never there.

(defn- expired-key
  "A key whose one-second TTL has elapsed, unread since."
  [cache]
  (let [k (str "gone-" (unique))]
    (ports/set-value! cache k {:v :original} 1)
    (Thread/sleep 1200)
    k))

(deftest ^:integration an-expired-key-reads-as-absent-on-every-adapter
  (each-adapter
   (fn [cache label]
     (let [k (expired-key cache)]
       (is (nil?   (ports/get-value cache k)) (str label ": get-value"))
       (is (false? (ports/exists? cache k))   (str label ": exists?"))
       (is (nil?   (ports/ttl cache k))       (str label ": ttl"))))))

(deftest ^:integration an-expired-key-cannot-be-deleted-revived-or-found
  (each-adapter
   (fn [cache label]
     (testing "delete-key! reports nothing deleted"
       (let [k (expired-key cache)]
         (is (false? (ports/delete-key! cache k))
             (str label ": deleting an expired key reported a deletion"))))

     (testing "expire! does not bring it back"
       (let [k (expired-key cache)]
         (is (false? (ports/expire! cache k 60))
             (str label ": expire! revived an expired key"))
         (is (nil? (ports/get-value cache k)))))

     (testing "it is not in keys-matching"
       (let [k (expired-key cache)]
         (is (not (contains? (ports/keys-matching cache "gone-*") k))
             (str label ": an expired key was still listed"))
         (is (zero? (ports/count-matching cache k))
             (str label ": an expired key was still counted"))))

     (testing "get-many skips it"
       (let [k (expired-key cache)]
         (is (= {} (ports/get-many cache [k]))
             (str label ": get-many returned an expired value"))))

     (testing "set-if-absent! finds it absent"
       (let [k (expired-key cache)]
         (is (true? (ports/set-if-absent! cache k {:v :new} 60))
             (str label ": an expired lease could not be retaken"))
         (is (= {:v :new} (ports/get-value cache k)))))

     (testing "increment! starts from zero, with no expiry carried over"
       (let [k (str "n-" (unique))]
         (ports/set-value! cache k 5 1)
         (Thread/sleep 1200)
         (is (= 1 (ports/increment! cache k))
             (str label ": a lapsed count carried on"))
         (is (nil? (ports/ttl cache k))
             (str label ": the new counter inherited the lapsed entry's expiry"))))

     (testing "compare-and-swap! sees nil, not the lapsed value"
       (let [k (expired-key cache)]
         (is (false? (ports/compare-and-swap! cache k {:v :original} {:v :next}))
             (str label ": CAS matched the value of an expired key"))
         (is (true? (ports/compare-and-swap! cache k nil {:v :next}))
             (str label ": CAS did not treat an expired key as absent")))))))

;; =============================================================================
;; TTL
;; =============================================================================

(deftest ^:integration ttl-reads-the-same-on-every-adapter
  (each-adapter
   (fn [cache label]
     (testing "a key set with a TTL reports it, without losing a second to rounding"
       (let [k (str "t-" (unique))]
         (ports/set-value! cache k :v 30)
         (is (= 30 (ports/ttl cache k))
             (str label ": a 30s TTL read back as " (ports/ttl cache k)))))

     (testing "a key set without one has none"
       (let [k (str "t-" (unique))]
         (ports/set-value! cache k :v)
         (is (nil? (ports/ttl cache k))
             (str label ": a key with no TTL reported one"))))

     (testing "expire! puts one on a key that had none"
       (let [k (str "t-" (unique))]
         (ports/set-value! cache k :v)
         (is (true? (ports/expire! cache k 30)))
         (is (= 30 (ports/ttl cache k)))))

     (testing "expire! replaces one that was already there"
       (let [k (str "t-" (unique))]
         (ports/set-value! cache k :v 30)
         (is (true? (ports/expire! cache k 90)))
         (is (= 90 (ports/ttl cache k)))))

     (testing "overwriting without a TTL clears it"
       (let [k (str "t-" (unique))]
         (ports/set-value! cache k :v 30)
         (ports/set-value! cache k :w)
         (is (nil? (ports/ttl cache k))
             (str label ": an overwrite kept the old expiry")))))))

(deftest ^:integration the-default-ttl-applies-the-same-way
  ;; The two-argument arities take `:default-ttl` from the config.
  (each-adapter
   {:default-ttl 30}
   (fn [cache label]
     (let [k (str "d-" (unique))]
       (ports/set-value! cache k :v)
       (is (= 30 (ports/ttl cache k))
           (str label ": set-value! ignored :default-ttl")))
     (let [k (str "d-" (unique))]
       (ports/set-if-absent! cache k :v)
       (is (= 30 (ports/ttl cache k))
           (str label ": set-if-absent! ignored :default-ttl")))
     (let [k (str "d-" (unique))]
       (ports/set-many! cache {k :v})
       (is (= 30 (ports/ttl cache k))
           (str label ": set-many! ignored :default-ttl"))))))

;; =============================================================================
;; Batch operations
;; =============================================================================

(deftest ^:integration batch-reads-and-writes-agree
  (each-adapter
   (fn [cache label]
     (testing "get-many returns only the keys that are there, under the keys asked for"
       (let [a (str "b-" (unique)) b (str "b-" (unique)) missing (str "b-" (unique))]
         (ports/set-many! cache {a :one b :two} 60)
         (is (= {a :one b :two} (ports/get-many cache [a b missing]))
             (str label ": get-many"))))

     (testing "a falsey value is a value, not a miss"
       ;; `false` and `nil` are things callers cache — a negative answer is an
       ;; answer. Dropping them makes a hit indistinguishable from a miss, so
       ;; the caller recomputes it every time.
       (let [f (str "b-" (unique)) n (str "b-" (unique))]
         (ports/set-value! cache f false 60)
         (ports/set-value! cache n nil 60)
         (is (= {f false n nil} (ports/get-many cache [f n]))
             (str label ": get-many dropped a stored false or nil"))))

     (testing "set-many! reports how many it wrote"
       (let [a (str "b-" (unique)) b (str "b-" (unique))]
         (is (= 2 (ports/set-many! cache {a 1 b 2} 60)))))

     (testing "delete-many! counts only what was there"
       (let [a (str "b-" (unique)) b (str "b-" (unique))]
         (ports/set-value! cache a 1 60)
         (is (= 1 (ports/delete-many! cache [a b]))
             (str label ": delete-many! counted a key that was not there"))))

     (testing "empty collections are not an error"
       (is (= {} (ports/get-many cache [])))
       (is (zero? (ports/set-many! cache {} 60)))
       (is (zero? (ports/delete-many! cache [])))))))

;; =============================================================================
;; Atomic operations
;; =============================================================================

(deftest ^:integration counters-behave-the-same
  (each-adapter
   (fn [cache label]
     (testing "a counter that is not there starts at zero"
       (let [k (str "c-" (unique))]
         (is (= 1 (ports/increment! cache k)))
         (is (= 3 (ports/increment! cache k 2)))
         (is (= 2 (ports/decrement! cache k)))
         (is (= 0 (ports/decrement! cache k 2)))))

     (testing "the value read back is the value returned"
       (let [k (str "c-" (unique))]
         (ports/increment! cache k 7)
         (is (= 7 (ports/get-value cache k))
             (str label ": get-value disagreed with increment!"))))

     (testing "incrementing keeps the expiry"
       ;; Callers set the TTL once, on the first increment, because that is the
       ;; only moment they can recognise. An increment that clears it produces a
       ;; key that never expires — which is what the rate limiter's window keys
       ;; did for the life of the process.
       (let [k (str "c-" (unique))]
         (ports/increment! cache k)
         (ports/expire! cache k 30)
         (ports/increment! cache k)
         (is (= 30 (ports/ttl cache k))
             (str label ": increment! cleared the TTL"))))

     (testing "a counter seeded with set-value! can still be incremented"
       (let [k (str "c-" (unique))]
         (ports/set-value! cache k 10 60)
         (is (= 11 (ports/increment! cache k))
             (str label ": a seeded counter could not be incremented"))))

     (testing "and one seeded with set-if-absent!"
       (let [k (str "c-" (unique))]
         (ports/set-if-absent! cache k 10 60)
         (is (= 11 (ports/increment! cache k))))))))

(deftest ^:integration set-if-absent-behaves-like-a-lease
  (each-adapter
   (fn [cache label]
     (testing "the first caller wins and the second does not"
       (let [k (str "l-" (unique))]
         (is (true?  (ports/set-if-absent! cache k :mine 60)))
         (is (false? (ports/set-if-absent! cache k :yours 60)))
         (is (= :mine (ports/get-value cache k))
             (str label ": the loser overwrote the winner"))))

     (testing "it sets the TTL it was given"
       (let [k (str "l-" (unique))]
         (ports/set-if-absent! cache k :mine 30)
         (is (= 30 (ports/ttl cache k)))))

     (testing "and nil means no expiry"
       (let [k (str "l-" (unique))]
         (ports/set-if-absent! cache k :mine nil)
         (is (nil? (ports/ttl cache k))))))))

(deftest ^:integration compare-and-swap-behaves-the-same
  (each-adapter
   (fn [cache label]
     (testing "it swaps when the value matches"
       (let [k (str "s-" (unique))]
         (ports/set-value! cache k :a 60)
         (is (true? (ports/compare-and-swap! cache k :a :b)))
         (is (= :b (ports/get-value cache k)))))

     (testing "and does not when it does not"
       (let [k (str "s-" (unique))]
         (ports/set-value! cache k :a 60)
         (is (false? (ports/compare-and-swap! cache k :wrong :b)))
         (is (= :a (ports/get-value cache k)))))

     (testing "nil is how you say the key must be absent"
       (let [k (str "s-" (unique))]
         (is (true? (ports/compare-and-swap! cache k nil :first))
             (str label ": CAS against nil failed on an absent key"))
         (is (= :first (ports/get-value cache k)))
         (is (false? (ports/compare-and-swap! cache k nil :again))
             (str label ": CAS against nil succeeded on a key that exists"))))

     (testing "a successful swap keeps the expiry"
       (let [k (str "s-" (unique))]
         (ports/set-value! cache k :a 30)
         (ports/compare-and-swap! cache k :a :b)
         (is (= 30 (ports/ttl cache k))
             (str label ": compare-and-swap! cleared the TTL")))))))

;; =============================================================================
;; Patterns
;; =============================================================================

(deftest ^:integration pattern-matching-means-the-same-thing
  (each-adapter
   (fn [cache label]
     (let [p (unique)]
       (ports/set-many! cache {(str p ":a")   1
                               (str p ":b")   2
                               (str p ":a:c") 3
                               (str p "-x")   4}
                        60)

       (testing "* matches across separators, as a Redis glob does"
         (is (= #{(str p ":a") (str p ":b") (str p ":a:c")}
                (ports/keys-matching cache (str p ":*")))
             (str label ": :* did not match what a Redis glob matches")))

       (testing "? matches exactly one character"
         (is (= #{(str p ":a") (str p ":b")}
                (ports/keys-matching cache (str p ":?")))
             (str label ": ? matched something other than one character")))

       (testing "a character class matches the characters in it"
         (is (= #{(str p ":a")}
                (ports/keys-matching cache (str p ":[ax]")))
             (str label ": [...] is not a character class")))

       (testing "a pattern with no wildcard matches only itself"
         (is (= #{(str p ":a")} (ports/keys-matching cache (str p ":a")))))

       (testing "count-matching agrees with keys-matching"
         (is (= 3 (ports/count-matching cache (str p ":*")))))

       (testing "nothing matching is an empty set, not nil"
         (is (= #{} (ports/keys-matching cache (str "no-" (unique) "-*"))))
         (is (zero? (ports/count-matching cache (str "no-" (unique) "-*")))))

       (testing "delete-matching! removes them and reports how many"
         (is (= 3 (ports/delete-matching! cache (str p ":*"))))
         (is (= #{} (ports/keys-matching cache (str p ":*"))))
         (is (= 4 (ports/get-value cache (str p "-x")))
             (str label ": delete-matching! took a key the pattern did not name")))

       (testing "deleting nothing is zero, not an error"
         (is (zero? (ports/delete-matching! cache (str "no-" (unique) "-*")))))))))

(deftest ^:integration a-dot-in-a-pattern-is-a-dot
  ;; A glob has no metacharacters beyond * ? and [...]. An adapter that compiles
  ;; the pattern to a regex without escaping the rest matches keys the caller did
  ;; not name — and `.` is in half the key schemes there are.
  (each-adapter
   (fn [cache label]
     (let [p (unique)]
       (ports/set-many! cache {(str p ".a") 1
                               (str p "Xa") 2}
                        60)
       (is (= #{(str p ".a")} (ports/keys-matching cache (str p ".a")))
           (str label ": `.` in a pattern matched any character"))))))

;; =============================================================================
;; Namespaces
;; =============================================================================

(deftest ^:integration namespaces-isolate-the-same-way
  (each-adapter
   (fn [cache label]
     (let [a (ports/with-namespace cache (str "na-" (unique)))
           b (ports/with-namespace cache (str "nb-" (unique)))
           k (str "k-" (unique))]

       (testing "the same key in two namespaces is two keys"
         (ports/set-value! a k :in-a 60)
         (ports/set-value! b k :in-b 60)
         (is (= :in-a (ports/get-value a k)))
         (is (= :in-b (ports/get-value b k))))

       (testing "keys-matching is scoped to the namespace, and strips it"
         (is (= #{k} (ports/keys-matching a "*"))
             (str label ": keys-matching leaked across namespaces or kept the prefix")))

       (testing "deleting in one leaves the other"
         (ports/delete-key! a k)
         (is (nil? (ports/get-value a k)))
         (is (= :in-b (ports/get-value b k))))))))

(deftest ^:integration clear-namespace-clears-that-namespace-only
  (each-adapter
   (fn [cache label]
     (let [doomed (str "nd-" (unique))
           kept   (str "nk-" (unique))
           a      (ports/with-namespace cache doomed)
           b      (ports/with-namespace cache kept)]
       (ports/set-value! a "x" :gone 60)
       (ports/set-value! b "x" :kept 60)
       (ports/clear-namespace! cache doomed)
       (is (nil? (ports/get-value a "x"))
           (str label ": clear-namespace! left the namespace it was given"))
       (is (= :kept (ports/get-value b "x"))
           (str label ": clear-namespace! took a namespace it was not given"))))))

;; =============================================================================
;; Management
;; =============================================================================

(def ^:private stats-keys [:size :hits :misses :hit-rate :evictions :memory-usage])

(deftest ^:integration management-answers-the-same
  (each-adapter
   (fn [cache label]
     (is (true? (ports/ping cache)) (str label ": ping"))
     (let [stats (ports/cache-stats cache)]
       (testing "cache-stats has the documented keys"
         (is (every? #(contains? stats %) stats-keys)
             (str label ": cache-stats is missing "
                  (remove #(contains? stats %) stats-keys)))
         (is (number? (:size stats)))
         (is (number? (:hit-rate stats)))))
     (is (true? (ports/clear-stats! cache)) (str label ": clear-stats!")))))
