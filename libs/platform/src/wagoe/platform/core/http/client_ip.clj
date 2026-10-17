(ns wagoe.platform.core.http.client-ip
  "The address a request came from, as opposed to the proxy that delivered it.

   `:remote-addr` is the peer, which behind a reverse proxy is the proxy. Any
   client can send X-Forwarded-For, so it is read only when the peer is a
   trusted proxy, and from the right: each proxy appends the address it saw,
   so the first hop that is not one of ours is the client."
  (:require [clojure.string :as str]))

(defn- parse-ipv4 [s]
  (when (re-matches #"\d{1,3}(?:\.\d{1,3}){3}" s)
    (let [octets (mapv parse-long (str/split s #"\."))]
      (when (every? #(<= % 255) octets)
        octets))))

(defn- hextets [s]
  (if (= "" s)
    []
    (let [groups (str/split s #":" -1)]
      (when (every? #(re-matches #"[0-9a-fA-F]{1,4}" %) groups)
        (mapv #(Long/parseLong % 16) groups)))))

(defn- embedded-ipv4->hex
  "`::ffff:1.2.3.4` as `::ffff:102:304`, or nil when the dotted tail is not an
   IPv4 address."
  [s]
  (if-not (str/includes? s ".")
    s
    ;; No colon left once a zone is stripped: "1.2.3.4%:a".
    (when-let [i (str/last-index-of s ":")]
      (when-let [[a b c d] (parse-ipv4 (subs s (inc i)))]
        (str (subs s 0 (inc i))
             (Long/toHexString (+ (* a 256) b)) ":"
             (Long/toHexString (+ (* c 256) d)))))))

(defn- parse-ipv6 [s]
  (when-let [s (embedded-ipv4->hex (first (str/split s #"%" 2)))]
    (when-let [groups (let [halves (str/split s #"::" -1)]
                        (case (count halves)
                          1 (let [hs (hextets s)]
                              (when (= 8 (count hs)) hs))
                          2 (let [l (hextets (first halves))
                                  r (hextets (second halves))]
                              (when (and l r (< (+ (count l) (count r)) 8))
                                (concat l (repeat (- 8 (count l) (count r)) 0) r)))
                          nil))]
      (vec (mapcat (fn [h] [(quot h 256) (mod h 256)]) groups)))))

(defn- unwrap-ipv4-mapped [bs]
  (if (= [0 0 0 0 0 0 0 0 0 0 255 255] (subvec bs 0 12))
    (subvec bs 12)
    bs))

(defn parse-ip
  "The bytes of an IPv4 (4) or IPv6 (16) literal, or nil. An IPv4-mapped IPv6
   address parses as its IPv4 address, so a dual-stack socket matches IPv4
   ranges."
  [s]
  (when (string? s)
    (let [s (str/trim s)]
      (if (str/includes? s ":")
        (some-> (parse-ipv6 s) unwrap-ipv4-mapped)
        (parse-ipv4 s)))))

(defn- host-part
  "The address in a hop, without the port or IPv6 brackets some proxies add."
  [s]
  (when (string? s)
    (let [s (str/trim s)]
      (or (second (re-matches #"\[([^\]]+)\](?::\d+)?" s))
          (second (re-matches #"([^:]+):\d+" s))
          s))))

(defn parse-cidr
  "`{:bytes .. :prefix n}` for \"10.0.0.0/8\", \"2001:db8::/32\", or a bare
   address (a /32 or /128); nil when it is none of these."
  [s]
  (when (string? s)
    (let [[addr bits & more] (str/split (str/trim s) #"/" -1)
          bs     (parse-ip addr)
          ;; ::ffff:a.b.c.d/104 parses to IPv4 bytes, so its prefix loses 96.
          mapped (if (and (= 4 (count bs)) (str/includes? addr ":")) 96 0)
          prefix (if bits
                   (when (re-matches #"\d{1,3}" bits) (- (parse-long bits) mapped))
                   (* 8 (count bs)))]
      (when (and bs prefix (empty? more) (<= 0 prefix (* 8 (count bs))))
        {:bytes bs :prefix prefix}))))

(defn parse-trusted-proxies
  "`{:cidrs [..]}` for the `:trusted-proxies` setting, or a configuration error
   naming the entries that are not CIDRs. Absent means trust nothing."
  [entries]
  (let [parsed (when (sequential? entries) (mapv parse-cidr entries))
        bad    (cond
                 (nil? entries)              []
                 (not (sequential? entries)) [entries]
                 :else                       (vec (keep-indexed #(when-not (parsed %1) %2) entries)))]
    (if (seq bad)
      {:error {:type    :configuration-error
               :message (str ":wagoe/http :trusted-proxies must be a vector of IPv4/IPv6 "
                             "CIDRs such as \"10.0.0.0/8\"; not a CIDR: " (pr-str bad))
               :invalid bad}}
      {:cidrs (or parsed [])})))

(defn- in-cidr? [bs {cidr :bytes prefix :prefix}]
  (and (= (count bs) (count cidr))
       (let [whole (quot prefix 8)
             bits  (mod prefix 8)
             mask  (bit-and 0xff (bit-shift-left 0xff (- 8 bits)))]
         (and (= (subvec bs 0 whole) (subvec cidr 0 whole))
              (or (zero? bits)
                  (= (bit-and mask (nth bs whole))
                     (bit-and mask (nth cidr whole))))))))

(defn- trusted? [cidrs bs]
  (boolean (some #(in-cidr? bs %) cidrs)))

(defn- ->ip
  "Bytes for an address string, read as a hop is (port and brackets allowed),
   or for bytes from `parse-ip`; nil otherwise."
  [ip]
  (cond
    (string? ip) (parse-ip (host-part ip))
    (and (vector? ip) (#{4 16} (count ip)) (every? #(and (int? %) (<= 0 % 255)) ip))
    (cond-> ip (= 16 (count ip)) unwrap-ipv4-mapped)))

(defn- ->cidr [cidr]
  (cond
    (string? cidr) (parse-cidr cidr)
    ;; As parse-cidr returns it: an IPv4-mapped range already folded.
    (and (map? cidr) (some? (:bytes cidr)) (= (:bytes cidr) (->ip (:bytes cidr))) (int? (:prefix cidr))
         (<= 0 (:prefix cidr) (* 8 (count (:bytes cidr)))))
    cidr))

(defn in-network?
  "True when `ip` lies in `cidr`. Both may be strings or the values of
   `parse-ip` / `parse-cidr`, and are read exactly as the trust walk reads them:
   a port, brackets or a zone id are ignored, and an IPv4-mapped IPv6 address
   is its IPv4 address. Anything that does not parse, nil included, is false."
  [ip cidr]
  (let [bs (->ip ip)
        c  (->cidr cidr)]
    (boolean (and bs c (in-cidr? bs c)))))

(defn in-any?
  "True when `ip` lies in one of `cidrs`; see `in-network?`."
  [ip cidrs]
  (boolean (some->> (->ip ip) (trusted? (keep ->cidr cidrs)))))

(defn client-ip
  "The client's address: `:remote-addr`, unless that is a trusted proxy, in
   which case the right-most X-Forwarded-For hop that is not one. Nil when the
   hop the walk stops at is not an address (`unknown`): the proxy's own address
   would put an outside client inside the proxy's network. `cidrs` comes from
   `parse-trusted-proxies`."
  [request cidrs]
  (let [peer (:remote-addr request)]
    (if-not (and (seq cidrs) (trusted? cidrs (parse-ip (host-part peer))))
      peer
      ;; Ring joins repeated headers with ",", so this reads all of them.
      (loop [hops    (some-> (get-in request [:headers "x-forwarded-for"])
                             (str/split #",")
                             reverse)
             nearest peer]
        (if-let [hop (first hops)]
          (let [addr (host-part hop)
                bs   (parse-ip addr)]
            (cond
              (nil? bs)           nil
              (trusted? cidrs bs) (recur (rest hops) addr)
              :else               addr))
          nearest)))))

(defn request-ip
  "The client address resolved on the request; nil when the middleware could
   not resolve one. The peer only when the request did not pass through the
   client-ip middleware."
  [request]
  (if (contains? request :client-ip)
    (:client-ip request)
    (:remote-addr request)))
