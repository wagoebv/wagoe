(ns wagoe.webhooks.shell.ssrf
  "Refusing webhook targets inside the network the application runs in.

   A webhook URL is chosen by whoever creates the subscription, and the request
   is made from the server: without this, a tenant could point one at the cloud
   metadata endpoint or an internal admin port and read the answer in the
   delivery log.

   Checked at create time and again before every attempt, because DNS can
   change in between. The HTTP client resolves the name once more when it
   connects, so a name that switches address between the two lookups is not
   caught; `:allowed-hosts` is the way to reach a private host on purpose."
  (:require [clojure.string :as str]
            [wagoe.webhooks.core.delivery :as delivery])
  (:import (java.net Inet4Address Inet6Address InetAddress UnknownHostException)))

(defn- in-v4-range? [^bytes b first-octet mask second-octet]
  (and (= first-octet (bit-and (aget b 0) 0xff))
       (= second-octet (bit-and (aget b 1) mask))))

(defn private-address?
  "Whether `addr` is loopback, link-local, private, unspecified, multicast,
   carrier-grade NAT or IPv6 unique-local."
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
                  (in-v4-range? b 100 0xc0 64)))         ; 100.64.0.0/10
         (and (instance? Inet6Address addr)
              (= 0xfc (bit-and (aget b 0) 0xfe)))))))    ; fc00::/7

(defn host-problem
  "Why the host of `url` may not be called, or nil.

   `:refused` for a private address, `:unresolvable` when DNS has no answer —
   the first will not change by retrying, the second may."
  [url {:keys [allowed-hosts]}]
  (let [host (delivery/url-host url)]
    (when-not (contains? (into #{} (map str/lower-case) allowed-hosts) host)
      (try
        (when-let [bad (first (filter private-address? (InetAddress/getAllByName host)))]
          {:type    :refused
           :message (str "Host " host " resolves to a private address (" (.getHostAddress ^InetAddress bad)
                         "); add it to :allowed-hosts to allow it")})
        (catch UnknownHostException _
          {:type :unresolvable :message (str "Host " host " does not resolve")})))))
