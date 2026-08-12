(ns wagoe.cache.shell.adapters.redis
  "Redis-backed distributed cache implementation.

   Features:
   - Distributed caching across multiple processes
   - Automatic TTL and expiration
   - Atomic operations (INCR, DECR, SETNX)
   - Pattern matching with SCAN
   - Namespace support
   - Connection pooling

   Suitable for:
   - Production deployments
   - Distributed systems
   - High-availability applications
   - Microservices architecture

   Redis Data Structures Used:
   - Strings: For cache values (Nippy serialized, stored as binary)
   - TTL: Built-in Redis expiration
   - Atomic ops: INCR, DECR, SETNX, etc."
  (:require [wagoe.cache.ports :as ports]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [taoensso.nippy :as nippy])
  (:import [java.nio.charset StandardCharsets]
           [redis.clients.jedis Jedis JedisPool JedisPoolConfig]
           [redis.clients.jedis.params SetParams ScanParams]))

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn- with-redis
  "Execute function with Redis connection from pool."
  [^JedisPool pool f]
  (with-open [^Jedis redis (.getResource pool)]
    (f redis)))

(defn- add-namespace
  "Add namespace prefix to key."
  [namespace key]
  (if namespace
    (str namespace ":" (name key))
    (name key)))

(defn- strip-namespace
  "Remove namespace prefix from key."
  [namespace key]
  (if namespace
    (let [prefix (str namespace ":")]
      (if (.startsWith key prefix)
        (.substring key (count prefix))
        key))
    key))

(defn- key-bytes
  "Encode a namespaced key string to UTF-8 bytes.

   Matches how Jedis encodes String keys, so keys written via the binary
   value commands remain reachable by the String key-only commands (DEL,
   EXISTS, TTL, EXPIRE, SCAN, INCR, ...)."
  ^bytes [^String s]
  (.getBytes s StandardCharsets/UTF_8))

(defn- integer-value?
  "True for integer types Redis can store as a native decimal string and operate
   on with the atomic INCR/DECR commands."
  [v]
  (or (instance? Long v)
      (instance? Integer v)
      (instance? Short v)
      (instance? Byte v)
      (instance? clojure.lang.BigInt v)
      (instance? java.math.BigInteger v)))

(defn- serialize-value
  "Serialize a value for storage in Redis.

   Integers are stored in Redis' native decimal-string form so the atomic
   INCR/DECR commands operate on them directly and the key's TTL is preserved
   across increments. (A value seeded with set-if-absent! and then advanced with
   increment! must share one representation — Nippy-freezing the seed made INCR
   fail with \"ERR value is not an integer or out of range\".)

   All other values are Nippy-encoded. Unlike JSON, Nippy preserves Clojure
   types (keywords, sets, ratios) and java.time/Temporal values, so cached
   values round-trip with full fidelity matching the in-memory adapter."
  ^bytes [value]
  (if (integer-value? value)
    (.getBytes (str value) StandardCharsets/UTF_8)
    (nippy/freeze value)))

(defn- parse-native-long
  "Parse Redis' native decimal-integer form (written by serialize-value or
   produced by INCR/DECR). Returns nil when the bytes are not such an integer."
  [^bytes ba]
  (try
    (Long/parseLong (String. ba StandardCharsets/UTF_8))
    (catch Exception _ nil)))

(defn- deserialize-value
  "Deserialize a value read from Redis.

   Tries the native integer form first (INCR/DECR counters and integer values),
   then Nippy. Returns nil (treated as a cache miss) when the bytes are neither
   — e.g. entries written by the previous JSON format before the serialization
   change, or otherwise corrupt. This lets the cache self-heal on rollout
   instead of throwing on every stale read."
  [^bytes ba]
  (when ba
    (if-let [n (parse-native-long ba)]
      n
      (try
        (nippy/thaw ba)
        (catch Exception e
          (log/warn e "Unreadable cache entry; treating as a cache miss")
          nil)))))

;; =============================================================================
;; Cache Operations
;; =============================================================================

(defrecord RedisCache [^JedisPool pool config namespace]
  ports/ICache

  (get-value [_ key]
    (with-redis pool
      (fn [^Jedis redis]
        (let [namespaced-key (add-namespace namespace key)
              ba (.get redis (key-bytes namespaced-key))]
          (when ba
            (deserialize-value ba))))))

  (set-value! [this key value]
    (ports/set-value! this key value (:default-ttl config)))

  (set-value! [_ key value ttl-seconds]
    (with-redis pool
      (fn [^Jedis redis]
        (let [kb (key-bytes (add-namespace namespace key))
              serialized (serialize-value value)]
          (if ttl-seconds
            (.setex redis kb (long ttl-seconds) serialized)
            (.set redis kb serialized))
          true))))

  (delete-key! [_ key]
    (with-redis pool
      (fn [^Jedis redis]
        (let [namespaced-key (add-namespace namespace key)
              result (.del redis (into-array String [namespaced-key]))]
          (pos? result)))))

  (exists? [_ key]
    (with-redis pool
      (fn [^Jedis redis]
        (let [namespaced-key (add-namespace namespace key)]
          ;; Jedis' varargs EXISTS returns the Long count of present keys;
          ;; the protocol contract is a boolean (matching the in-memory adapter).
          (pos? (.exists redis (into-array String [namespaced-key])))))))

  (ttl [_ key]
    (with-redis pool
      (fn [^Jedis redis]
        (let [namespaced-key (add-namespace namespace key)
              ttl (.ttl redis namespaced-key)]
          (when (pos? ttl)
            ttl)))))

  (expire! [_ key ttl-seconds]
    (with-redis pool
      (fn [^Jedis redis]
        (let [namespaced-key (add-namespace namespace key)
              result (.expire redis namespaced-key (long ttl-seconds))]
          (= result 1)))))

  ;; =============================================================================
  ;; Batch Operations
  ;; =============================================================================

  ports/IBatchCache

  (get-many [_ keys]
    (with-redis pool
      (fn [^Jedis redis]
        (let [namespaced-keys (mapv #(add-namespace namespace %) keys)
              key-byte-arrays (into-array (Class/forName "[B")
                                          (map key-bytes namespaced-keys))
              values (.mget redis key-byte-arrays)]
          (into {}
                (keep-indexed
                 (fn [idx value]
                   (when value
                     [(nth keys idx) (deserialize-value value)]))
                 values))))))

  (set-many! [this key-value-map]
    (ports/set-many! this key-value-map (:default-ttl config)))

  (set-many! [_ key-value-map ttl-seconds]
    (with-redis pool
      (fn [^Jedis redis]
        ;; Use pipeline for efficiency
        (let [pipeline (.pipelined redis)]
          (doseq [[k v] key-value-map]
            (let [kb (key-bytes (add-namespace namespace k))
                  serialized (serialize-value v)]
              (if ttl-seconds
                (.setex pipeline kb (long ttl-seconds) serialized)
                (.set pipeline kb serialized))))
          (.sync pipeline)
          (count key-value-map)))))

  (delete-many! [_ keys]
    (with-redis pool
      (fn [^Jedis redis]
        (let [namespaced-keys (mapv #(add-namespace namespace %) keys)
              result (.del redis (into-array String namespaced-keys))]
          result))))

  ;; =============================================================================
  ;; Atomic Operations
  ;; =============================================================================

  ports/IAtomicCache

  (increment! [this key]
    (ports/increment! this key 1))

  (increment! [_ key delta]
    (with-redis pool
      (fn [^Jedis redis]
        (let [namespaced-key (add-namespace namespace key)]
          (.incrBy redis namespaced-key (long delta))))))

  (decrement! [this key]
    (ports/decrement! this key 1))

  (decrement! [_ key delta]
    (with-redis pool
      (fn [^Jedis redis]
        (let [namespaced-key (add-namespace namespace key)]
          (.decrBy redis namespaced-key (long delta))))))

  (set-if-absent! [this key value]
    (ports/set-if-absent! this key value (:default-ttl config)))

  (set-if-absent! [_ key value ttl-seconds]
    (with-redis pool
      (fn [^Jedis redis]
        (let [kb (key-bytes (add-namespace namespace key))
              serialized (serialize-value value)
              params (SetParams/setParams)]
          (.nx params)
          (when ttl-seconds
            (.ex params (long ttl-seconds)))
          (let [result (.set redis kb serialized params)]
            (= result "OK"))))))

  (compare-and-swap! [_ key expected-value new-value]
    (with-redis pool
      (fn [^Jedis redis]
        (let [kb (key-bytes (add-namespace namespace key))]
          ;; Watch key for changes
          (.watch redis (into-array (Class/forName "[B") [kb]))
          (let [current-ba (.get redis kb)
                current-value (when current-ba (deserialize-value current-ba))]
            (if (= current-value expected-value)
              (let [transaction (.multi redis)
                    new-ba (serialize-value new-value)]
                (.set transaction kb new-ba)
                (let [result (.exec transaction)]
                  (some? result)))
              (do
                (.unwatch redis)
                false)))))))

  ;; =============================================================================
  ;; Pattern Operations
  ;; =============================================================================

  ports/IPatternCache

  (keys-matching [_ pattern]
    (with-redis pool
      (fn [^Jedis redis]
        (let [namespaced-pattern (add-namespace namespace pattern)
              scan-params (doto (ScanParams.)
                            (.match namespaced-pattern)
                            (.count (int 100)))
              keys (java.util.HashSet.)]
          ;; Use SCAN for better performance than KEYS
          (loop [cursor "0"]
            (let [result (.scan redis cursor scan-params)]
              (.addAll keys (.getResult result))
              (let [next-cursor (.getCursor result)]
                (when-not (= next-cursor "0")
                  (recur next-cursor)))))
          (into #{}
                (map #(strip-namespace namespace %))
                keys)))))

  (delete-matching! [this pattern]
    (let [matching-keys (ports/keys-matching this pattern)]
      (if (seq matching-keys)
        (ports/delete-many! this matching-keys)
        0)))

  (count-matching [this pattern]
    (count (ports/keys-matching this pattern)))

  ;; =============================================================================
  ;; Namespace Operations
  ;; =============================================================================

  ports/INamespacedCache

  (with-namespace [_ new-namespace]
    (->RedisCache pool config new-namespace))

  (clear-namespace! [_ ns]
    ;; Clear the given namespace absolutely. delete-matching! prefixes the
    ;; pattern with the cache's own namespace, so on a cache that itself has a
    ;; namespace the naive (delete-matching! this "ns:*") would look for
    ;; "<own>:ns:*" and clear nothing. Run it through a namespace-free view so
    ;; "ns:*" is matched as written.
    (ports/delete-matching! (->RedisCache pool config nil) (str ns ":*")))

  ;; =============================================================================
  ;; Cache Statistics
  ;; =============================================================================

  ports/ICacheStats

  (cache-stats [_this]
    (with-redis pool
      (fn [^Jedis redis]
        (let [info (.info redis "stats")
              ;; Parse Redis INFO output
              stats-map (into {}
                              (map (fn [line]
                                     (let [[k v] (str/split line #":")]
                                       [(keyword k) v]))
                                   (str/split-lines info)))
              hits (Long/parseLong (get stats-map :keyspace_hits "0"))
              misses (Long/parseLong (get stats-map :keyspace_misses "0"))
              total-requests (+ hits misses)
              hit-rate (if (pos? total-requests)
                         (/ hits (double total-requests))
                         0.0)]
          {:size (.dbSize redis)
           :hits hits
           :misses misses
           :hit-rate hit-rate
           :memory-usage nil
           :evictions nil}))))

  (clear-stats! [_this]
    (with-redis pool
      (fn [^Jedis redis]
        (.configResetStat redis)
        true)))

  ;; =============================================================================
  ;; Cache Management
  ;; =============================================================================

  ports/ICacheManagement

  (flush-all! [_this]
    (with-redis pool
      (fn [^Jedis redis]
        (let [size (.dbSize redis)]
          (.flushDB redis)
          size))))

  (ping [_this]
    (try
      (with-redis pool
        (fn [^Jedis redis]
          (= "PONG" (.ping redis))))
      (catch Exception e
        (log/error e "Redis ping failed")
        false)))

  (close! [_this]
    (try
      (.close pool)
      true
      (catch Exception e
        (log/error e "Failed to close Redis pool")
        false))))

;; =============================================================================
;; Factory Functions
;; =============================================================================

(defn configured-password
  "The Redis password to authenticate with, or nil for none.

   Blank is not a password. `#env REDIS_PASSWORD` yields \"\" for a variable
   that is set but empty — which is what a Kubernetes Secret with an empty value
   and a compose file with a placeholder both produce — and `\"\"` is truthy, so
   the pool authenticated against a Redis that has no password. Jedis then fails
   with an AUTH error naming Redis, sending whoever reads it to look at the
   server rather than at the variable."
  [config]
  (let [password (:password config)]
    (when (and (string? password) (not (str/blank? password)))
      password)))

(defn create-redis-pool
  "Create a Jedis connection pool.

   Args:
     config - Configuration map:
              :host - Redis host (default: localhost)
              :port - Redis port (default: 6379)
              :password - Redis password (optional)
              :database - Redis database number (default: 0)
              :timeout - Connection timeout in ms (default: 2000)
              :max-total - Max connections (default: 20)
              :max-idle - Max idle connections (default: 10)
              :min-idle - Min idle connections (default: 2)

   Returns:
     JedisPool instance"
  [config]
  (let [pool-config (doto (JedisPoolConfig.)
                      (.setMaxTotal (or (:max-total config) 20))
                      (.setMaxIdle (or (:max-idle config) 10))
                      (.setMinIdle (or (:min-idle config) 2))
                      (.setTestOnBorrow true)
                      (.setTestOnReturn true))
        host (or (:host config) "localhost")
        port (or (:port config) 6379)
        timeout (or (:timeout config) 2000)
        password (configured-password config)
        database (or (:database config) 0)]

    ;; One constructor, always. The four-argument form takes no database, so
    ;; branching on the password quietly moved a passwordless deployment to
    ;; DB 0 — and `:database` is how deployments keep environments apart in one
    ;; Redis, so the data went somewhere plausible rather than nowhere. Jedis
    ;; skips AUTH when the password is nil, which is what makes the single form
    ;; correct for both.
    (JedisPool. pool-config host port timeout ^String password ^int database)))

(defn create-redis-cache
  "Create Redis cache instance.

   Args:
     pool - JedisPool instance
     config - Optional configuration map:
              :default-ttl - Default TTL in seconds
              :prefix - Key prefix for all operations

   Returns:
     RedisCache instance implementing all cache protocols"
  ([pool]
   (create-redis-cache pool {}))
  ([pool config]
   (->RedisCache pool config (:prefix config))))

(defn close-redis-pool!
  "Close Redis connection pool.

   Args:
     pool - JedisPool instance"
  [^JedisPool pool]
  (.close pool))
