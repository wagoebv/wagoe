(ns wagoe.webhooks.shell.ssrf-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.webhooks.shell.crypto :as crypto]
            [wagoe.webhooks.shell.ssrf :as ssrf])
  (:import (java.net InetAddress)))

(defn- problem [url settings]
  (:problem (ssrf/check-host url settings ssrf/system-resolve)))

(deftest ^:unit private-targets-are-refused
  (doseq [url ["http://127.0.0.1/x" "http://localhost/x" "http://10.1.2.3/x"
               "http://192.168.1.1/x" "http://172.16.0.1/x" "http://169.254.169.254/latest"
               "http://0.0.0.0/x" "http://100.64.0.1/x" "http://[::1]/x"
               "http://[fd00::1]/x" "http://[fe80::1]/x" "http://224.0.0.1/x"
               "http://198.18.0.1/x" "http://255.255.255.255/x" "http://240.0.0.1/x"
               "http://[::127.0.0.1]/x" "http://[64:ff9b::a9fe:a9fe]/x"
               "http://[2002:a00:1::]/x"]]
    (is (= :refused (:type (problem url {}))) url)))

(deftest ^:unit public-targets-pass
  (is (nil? (problem "https://93.184.215.14/hook" {})))
  (is (nil? (problem "https://[2606:4700::1111]/hook" {}))))

(deftest ^:unit the-allowlist-opens-a-host
  (is (nil? (problem "http://127.0.0.1:9000/x" {:allowed-hosts #{"127.0.0.1"}})))
  (is (nil? (problem "http://LocalHost/x" {:allowed-hosts #{"localhost"}})))
  (testing "only that host"
    (is (= :refused (:type (problem "http://10.0.0.1/x" {:allowed-hosts #{"127.0.0.1"}}))))))

(deftest ^:unit the-checked-addresses-are-returned-from-one-lookup
  (let [calls   (atom 0)
        resolve (fn [_] [(InetAddress/getByName (str "203.0.113." (swap! calls inc)))])]
    (is (= ["203.0.113.1"]
           (map #(.getHostAddress ^InetAddress %)
                (:addresses (ssrf/check-host "https://rebind.test/x" {} resolve)))))
    (is (= 1 @calls))
    (testing "an allowed host is left to the client"
      (is (= {} (ssrf/check-host "https://rebind.test/x" {:allowed-hosts #{"rebind.test"}} resolve)))
      (is (= 1 @calls)))))

(deftest ^:unit an-unknown-host-is-not-refused-for-good
  (is (= :unresolvable (:type (problem "https://no-such-host.invalid/x" {})))))

(deftest ^:unit secrets-round-trip-encrypted
  (let [k  (crypto/derive-key "k-at-least-32-characters-long-xxxx")
        ct (crypto/encrypt k "whsec_abc")]
    (is (re-find #"^enc:v1:" ct))
    (is (not (re-find #"whsec_abc" ct)))
    (is (= "whsec_abc" (crypto/decrypt k ct)))
    (is (thrown? Exception (crypto/decrypt (crypto/derive-key "another-key-of-at-least-32-chars!!") ct)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"at least 32" (crypto/derive-key "short")))))
