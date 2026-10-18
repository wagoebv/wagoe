(ns wagoe.webhooks.shell.ssrf
  "Refusing webhook targets inside the network the application runs in.

   A webhook URL is chosen by whoever creates the subscription, and the request
   is made from the server: without this, a tenant could point one at the cloud
   metadata endpoint or an internal admin port and read the answer in the
   delivery log.

   Checked at create time and again before every attempt, because DNS can
   change in between. The attempt connects to the addresses that check
   approved, so a name that answers differently a second time (DNS rebinding)
   gets no second lookup. `:allowed-hosts` is the way to reach a private host
   on purpose."
  (:require [wagoe.webhooks.core.delivery :as delivery])
  (:import (java.net Inet4Address Inet6Address InetAddress UnknownHostException)))

(defn- in-v4-range? [^bytes b first-octet mask second-octet]
  (and (= first-octet (bit-and (aget b 0) 0xff))
       (= second-octet (bit-and (aget b 1) mask))))

(defn- embedded-v4
  "The IPv4 address an IPv6 one carries — IPv4-compatible (::a.b.c.d),
   NAT64 (64:ff9b::/96) or 6to4 (2002::/16) — or nil."
  [^bytes b]
  (let [octet #(bit-and (aget b %) 0xff)
        v4    #(InetAddress/getByAddress (byte-array (map (fn [i] (aget b i)) %&)))]
    (cond
      (every? zero? (map octet (range 12)))
      (v4 12 13 14 15)

      (= [0 0x64 0xff 0x9b 0 0 0 0 0 0 0 0] (map octet (range 12)))
      (v4 12 13 14 15)

      (and (= 0x20 (octet 0)) (= 0x02 (octet 1)))
      (v4 2 3 4 5))))

(defn private-address?
  "Whether `addr` is loopback, link-local, private, unspecified, multicast,
   carrier-grade NAT, benchmarking, reserved, IPv6 unique-local, or an IPv6
   address that carries one of those."
  [^InetAddress addr]
  (let [b (.getAddress addr)]
    (boolean
     (or (.isAnyLocalAddress addr)
         (.isLoopbackAddress addr)
         (.isLinkLocalAddress addr)
         (.isSiteLocalAddress addr)
         (.isMulticastAddress addr)
         (and (instance? Inet4Address addr)
              (or (zero? (aget b 0))                     ; 0.0.0.0/8
                  (in-v4-range? b 100 0xc0 64)           ; 100.64.0.0/10
                  (in-v4-range? b 198 0xfe 18)           ; 198.18.0.0/15
                  (<= 240 (bit-and (aget b 0) 0xff))))   ; 240.0.0.0/4, broadcast
         (and (instance? Inet6Address addr)
              (or (= 0xfc (bit-and (aget b 0) 0xfe))     ; fc00::/7
                  (some-> (embedded-v4 b) private-address?)))))))

(defn system-resolve
  "Every address `host` resolves to; throws UnknownHostException."
  [host]
  (InetAddress/getAllByName host))

(defn check-host
  "Resolve the host of `url` once with `resolve` and judge the answer: `{:addresses [..]}` to
   connect to, `{}` for a host in `:allowed-hosts` (not resolved), or
   `{:problem {:type .. :message ..}}`. `:refused` for a private address,
   `:unresolvable` when DNS has no answer — the first will not change by
   retrying, the second may."
  [url {:keys [allowed-hosts]} resolve]   ; lower case
  (let [host (delivery/url-host url)]
    (if (contains? allowed-hosts host)
      {}
      (let [addrs (try (vec (resolve host)) (catch UnknownHostException _ []))
            bad   (first (filter private-address? addrs))]
        (cond
          (empty? addrs)
          {:problem {:type :unresolvable :message (str "Host " host " does not resolve")}}

          bad
          {:problem {:type    :refused
                     :message (str "Host " host " resolves to a private address ("
                                   (.getHostAddress ^InetAddress bad)
                                   "); add it to :allowed-hosts to allow it")}}

          :else
          {:addresses addrs})))))
