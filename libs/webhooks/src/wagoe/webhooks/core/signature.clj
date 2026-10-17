(ns wagoe.webhooks.core.signature
  "The `Wagoe-Signature` header: `t=<unix seconds>,v1=<hex HMAC-SHA256>` over
   `<t>.<body>`. Signing the timestamp with the body is what stops a captured
   request being replayed later: the receiver refuses an old `t`, and cannot be
   handed a new `t` with the old signature."
  (:require [buddy.core.bytes :as bytes]
            [buddy.core.codecs :as codecs]
            [buddy.core.mac :as mac]
            [clojure.string :as str]))

(def default-tolerance-s
  "How old a signature a receiver accepts, by default."
  300)

(defn- hmac-hex [secret timestamp body]
  (codecs/bytes->hex (mac/hash (str timestamp "." body) {:key secret :alg :hmac+sha256})))

(defn signature-header
  "The header value for `body` signed with `secret` at `timestamp` (seconds)."
  [secret timestamp body]
  (str "t=" timestamp ",v1=" (hmac-hex secret timestamp body)))

(defn parse-header
  "`{:t long :v1 [hex ...]}` from a header value, or nil when it has no
   timestamp or no v1 signature. Several v1 values are allowed, so a sender can
   sign with an old and a new secret while one is rotated."
  [header]
  (when (string? header)
    (let [pairs (keep #(let [[k v] (str/split (str/trim %) #"=" 2)]
                         (when (and k v) [k v]))
                      (str/split header #","))
          t     (some (fn [[k v]] (when (= "t" k) (parse-long v))) pairs)
          v1    (into [] (keep (fn [[k v]] (when (= "v1" k) v))) pairs)]
      (when (and t (seq v1))
        {:t t :v1 v1}))))

(defn signature-problem
  "Why `header` does not authenticate `body` under `secret` at `now` (seconds),
   or nil when it does: `:malformed`, `:stale` or `:mismatch`."
  [secret body header now {:keys [tolerance-s] :or {tolerance-s default-tolerance-s}}]
  (if-let [{:keys [t v1]} (parse-header header)]
    (let [expected (codecs/to-bytes (hmac-hex secret t body))]
      (cond
        (> (abs (- now t)) tolerance-s) :stale
        (not-any? #(bytes/equals? expected (codecs/to-bytes %)) v1) :mismatch))
    :malformed))
