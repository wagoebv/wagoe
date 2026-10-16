(ns wagoe.platform.core.http.client-ip-test
  (:require [wagoe.platform.core.http.client-ip :as client-ip]
            [clojure.test :refer [deftest testing is]]))

(defn- cidrs [& entries]
  (:cidrs (client-ip/parse-trusted-proxies (vec entries))))

(defn- req [peer xff]
  (cond-> {:remote-addr peer}
    xff (assoc-in [:headers "x-forwarded-for"] xff)))

(deftest ^:unit ^:security parse-ip-test
  (testing "IPv4"
    (is (= [10 0 0 1] (client-ip/parse-ip "10.0.0.1")))
    (is (nil? (client-ip/parse-ip "256.0.0.1")))
    (is (nil? (client-ip/parse-ip "10.0.0")))
    (is (nil? (client-ip/parse-ip "example.com"))))
  (testing "IPv6, compressed, with a zone or an embedded IPv4"
    (is (= (into [0x20 0x01 0x0d 0xb8] (concat (repeat 11 0) [1]))
           (client-ip/parse-ip "2001:db8::1")))
    (is (= (vec (repeat 16 0)) (client-ip/parse-ip "::")))
    (is (= (client-ip/parse-ip "fe80::1") (client-ip/parse-ip "fe80::1%eth0")))
    (is (= (client-ip/parse-ip "64:ff9b::c000:201") (client-ip/parse-ip "64:ff9b::192.0.2.1"))))
  (testing "an IPv4-mapped address is the IPv4 address"
    (is (= [10 1 2 3] (client-ip/parse-ip "::ffff:10.1.2.3"))))
  (testing "malformed IPv6"
    (doseq [s ["1::2::3" ":1:2:3:4:5:6:7" "1:2:3:4:5:6:7:8:9" "12345::" "g::1" "::ffff:1.2.3"]]
      (is (nil? (client-ip/parse-ip s)) s))))

(deftest ^:unit ^:security parse-trusted-proxies-test
  (testing "absent or empty trusts nothing"
    (is (= {:cidrs []} (client-ip/parse-trusted-proxies nil)))
    (is (= {:cidrs []} (client-ip/parse-trusted-proxies []))))
  (testing "bare addresses are single hosts"
    (is (= [{:bytes [10 0 0 1] :prefix 32}] (cidrs "10.0.0.1")))
    (is (= 128 (:prefix (first (cidrs "::1"))))))
  (testing "invalid entries are named in a configuration error"
    (let [{:keys [error]} (client-ip/parse-trusted-proxies ["10.0.0.0/8" "10.0.0.0/33" "proxy.local" "::/129"])]
      (is (= :configuration-error (:type error)))
      (is (= ["10.0.0.0/33" "proxy.local" "::/129"] (:invalid error)))))
  (testing "a string instead of a vector is an error"
    (is (= :configuration-error (get-in (client-ip/parse-trusted-proxies "10.0.0.0/8") [:error :type])))))

(deftest ^:unit ^:security client-ip-test
  (let [trusted (cidrs "10.0.0.0/8" "2001:db8::/32")]
    (testing "X-Forwarded-For from an untrusted peer is ignored"
      (is (= "203.0.113.9" (client-ip/client-ip (req "203.0.113.9" "1.2.3.4") trusted))))
    (testing "with no trusted proxies the header is always ignored"
      (is (= "10.0.0.5" (client-ip/client-ip (req "10.0.0.5" "1.2.3.4") []))))
    (testing "a trusted chain yields the right-most hop that is not a proxy"
      (is (= "198.51.100.7"
             (client-ip/client-ip (req "10.0.0.5" "6.6.6.6, 198.51.100.7, 10.0.0.9") trusted)))
      (testing "even when the client prepends a forged hop"
        (is (= "198.51.100.7"
               (client-ip/client-ip (req "10.0.0.5" "10.0.0.1, 198.51.100.7") trusted)))))
    (testing "IPv6 peers, hops and bracketed hops with ports"
      (is (= "2001:db9::1"
             (client-ip/client-ip (req "2001:db8::5" "2001:db9::1, 2001:db8::7") trusted)))
      (is (= "2001:db9::1"
             (client-ip/client-ip (req "2001:db8::5" "[2001:db9::1]:4711") trusted)))
      (is (= "198.51.100.7"
             (client-ip/client-ip (req "::ffff:10.0.0.5" "198.51.100.7:8080") trusted))))
    (testing "malformed hops stop the walk at the nearest trusted address"
      (is (= "10.0.0.9" (client-ip/client-ip (req "10.0.0.5" "198.51.100.7, garbage, 10.0.0.9") trusted)))
      (is (= "10.0.0.5" (client-ip/client-ip (req "10.0.0.5" "unknown") trusted)))
      (is (= "10.0.0.5" (client-ip/client-ip (req "10.0.0.5" "") trusted)))
      (is (= "10.0.0.5" (client-ip/client-ip (req "10.0.0.5" " , ,") trusted))))
    (testing "a trusted peer with no header, or every hop trusted"
      (is (= "10.0.0.5" (client-ip/client-ip (req "10.0.0.5" nil) trusted)))
      (is (= "10.0.0.1" (client-ip/client-ip (req "10.0.0.5" "10.0.0.1, 10.0.0.2") trusted))))
    (testing "no peer at all"
      (is (nil? (client-ip/client-ip {} trusted))))))

(deftest ^:unit request-ip-test
  (is (= "198.51.100.7" (client-ip/request-ip {:client-ip "198.51.100.7" :remote-addr "10.0.0.5"})))
  (is (= "10.0.0.5" (client-ip/request-ip {:remote-addr "10.0.0.5"}))))
