(ns wagoe.devtools.shell.dashboard.server-port-test
  "Port selection for the dev dashboard, without binding one.

   Separate from `server-test` because that namespace has a `:once` fixture that
   starts a real Jetty. `use-fixtures :once` runs for the namespace whichever
   tests are selected, so these would have needed a socket even under
   `--focus-meta :unit` — the surface AGENTS.md documents as needing nothing
   installed (BOU-377)."
  (:require [clojure.test :refer [deftest testing is are]]
            [wagoe.devtools.shell.dashboard.server :as server]
            [integrant.core :as ig]
            [ring.adapter.jetty]))

(deftest ^:unit the-port-plan-says-what-to-try-and-what-running-out-means
  ;; The policy, as data. Every defect in this area was a decision tangled up
  ;; with a socket call, so it is decided here and bound elsewhere (BOU-377).
  (are [port expected] (= expected (server/port-plan port))
    ;; Not a TCP port. Jetty used to reject this; capping the scan at 65535 once
    ;; turned it into a silent "all ports busy" instead.
    0      {:error :out-of-range}
    65536  {:error :out-of-range}
    -1     {:error :out-of-range}

    ;; Below 1024: one attempt, and the failure is told rather than scanned
    ;; past — it may be EACCES, which Java reports as the same BindException as
    ;; EADDRINUSE. Refusing these outright was wrong: root,
    ;; CAP_NET_BIND_SERVICE, Windows and ip_unprivileged_port_start all make
    ;; them legitimate.
    80     {:ports [80]   :on-exhausted :raise}
    1023   {:ports [1023] :on-exhausted :raise})

  (testing "an ordinary port scans eleven, and gives up rather than raising"
    (let [{:keys [ports on-exhausted]} (server/port-plan 9999)]
      (is (= (range 9999 10010) ports))
      (is (= :give-up on-exhausted))))

  (testing "the scan never proposes a port above 65535"
    (is (= [65533 65534 65535] (:ports (server/port-plan 65533))))
    (is (= [65535] (:ports (server/port-plan 65535))))))

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

(deftest ^:unit a-privileged-port-is-tried-once-and-its-failure-surfaces
  ;; Below 1024 a bind failure may be EACCES rather than EADDRINUSE, and Java
  ;; reports both as BindException — so scanning would claim eleven busy ports
  ;; for a permission problem. It is tried once and the real failure propagates.
  ;; Refusing it outright was wrong: root, CAP_NET_BIND_SERVICE, Windows and
  ;; ip_unprivileged_port_start all make low ports legitimate (BOU-377).
  (let [tried (atom [])]
    (with-redefs [server/check-host-bindable! (fn [_] nil)
                  ring.adapter.jetty/run-jetty
                  (fn [_ {:keys [port]}]
                    (swap! tried conj port)
                    (throw (java.io.IOException.
                            "Failed to bind"
                            (java.net.BindException. "Permission denied"))))]
      (is (thrown? java.io.IOException (ig/init-key :wagoe/dashboard {:port 80}))
          "the real failure reaches the caller")
      (is (= [80] @tried) "and only that port was tried"))))

(deftest ^:unit a-privileged-port-the-os-allows-simply-works
  ;; The bind decides, not a guess about privileges.
  (with-redefs [server/check-host-bindable! (fn [_] nil)
                ring.adapter.jetty/run-jetty (fn [_ _] ::server)]
    (is (= {:server ::server :port 80}
           (ig/init-key :wagoe/dashboard {:port 80})))))
