(ns wagoe.devtools.shell.dashboard.server-port-test
  "Port selection for the dev dashboard, without binding one.

   Separate from `server-test` because that namespace has a `:once` fixture that
   starts a real Jetty. `use-fixtures :once` runs for the namespace whichever
   tests are selected, so these would have needed a socket even under
   `--focus-meta :unit` — the surface AGENTS.md documents as needing nothing
   installed (BOU-377)."
  (:require [clojure.test :refer [deftest is]]
            [wagoe.devtools.shell.dashboard.server :as server]
            [integrant.core :as ig]
            [ring.adapter.jetty]))

(deftest ^:unit a-host-this-machine-cannot-bind-is-not-a-busy-port
  ;; An unroutable :host raises a BindException too, so treating every bind
  ;; failure as a busy port scanned eleven of them and then reported "all in
  ;; use" — not what went wrong. The host is checked once, up front, which is
  ;; also what lets the scan skip reading the exception message: that text comes
  ;; from the OS and is localised (BOU-377).
  ;;
  ;; The check is stubbed rather than given a private address: a VPN, or Linux
  ;; with non-local binding on, can own one, and the test would start a
  ;; dashboard instead of failing.
  (let [asked (atom 0)]
    (with-redefs [server/check-host-bindable!
                  (fn [_] (throw (java.net.BindException. "Can't assign requested address")))
                  ring.adapter.jetty/run-jetty
                  (fn [_ _] (swap! asked inc) nil)]
      (is (thrown? java.net.BindException
                   (ig/init-key :wagoe/dashboard {:port 9999 :host "10.255.255.1"})))
      (is (zero? @asked) "and it did not scan a single port first"))))

(deftest ^:unit a-failure-that-is-not-a-bind-failure-is-not-swallowed
  ;; Only a bind failure means "try the next port". Anything else is a real
  ;; error and must not be reported as eleven busy ports.
  (with-redefs [server/check-host-bindable! (fn [_] nil)
                ring.adapter.jetty/run-jetty
                (fn [_ _] (throw (java.io.IOException. "disk on fire")))]
    (is (thrown-with-msg? java.io.IOException #"disk on fire"
                          (ig/init-key :wagoe/dashboard {:port 9999})))))

(deftest ^:unit the-scan-stops-at-the-last-real-port
  ;; :port 65530 gave max-port 65540, so a busy run walked past 65535 and Jetty
  ;; threw "port out of range" instead of the component reporting the usable
  ;; ports busy (BOU-377).
  (let [tried (atom [])]
    (with-redefs [server/check-host-bindable! (fn [_] nil)
                  ring.adapter.jetty/run-jetty
                  (fn [_ {:keys [port]}]
                    (swap! tried conj port)
                    (throw (java.io.IOException.
                            "Failed to bind"
                            (java.net.BindException. "Address already in use"))))]
      (is (nil? (ig/init-key :wagoe/dashboard {:port 65530}))
          "every usable port was busy, so it gives up rather than throwing")
      (is (= 65535 (apply max @tried)) "and never asked for a port above 65535"))))

(deftest ^:unit a-port-outside-the-tcp-range-is-a-configuration-error
  ;; Capping the scan at 65535 made {:port 65536} answer nil — "all ports busy"
  ;; — where Jetty had rejected it outright. Out of range is a config error and
  ;; has to keep saying so (BOU-377).
  (doseq [port [0 65536 70000]]
    (let [e (try (ig/init-key :wagoe/dashboard {:port port}) nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e) (str "port " port " must be refused"))
      (is (= :configuration-error (:type (ex-data e)))))))

(deftest ^:unit a-privileged-port-is-refused-rather-than-scanned-past
  ;; Binding below 1024 without root fails with EACCES, which Java reports as
  ;; the same BindException as EADDRINUSE — so a scan would report eleven busy
  ;; ports for a permission problem. Refused up front instead (BOU-377).
  (let [e (try (ig/init-key :wagoe/dashboard {:port 80}) nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (some? e))
    (is (= :configuration-error (:type (ex-data e))))
    (is (re-find #"root" (ex-message e)) "and says why")))
