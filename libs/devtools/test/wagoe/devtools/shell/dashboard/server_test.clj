(ns wagoe.devtools.shell.dashboard.server-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [wagoe.devtools.shell.dashboard.server]
            [clojure.string :as str]
            [integrant.core :as ig]
            [clj-http.lite.client :as http])
  (:import [java.net ServerSocket]))

(defn- free-port []
  (with-open [s (ServerSocket. 0)]
    (.getLocalPort s)))

(defn- start-dashboard
  "Start the dashboard and return `{:server … :port <the port it actually bound>}`.

   `free-port` closes its socket before the server binds, so the port can be
   gone by the time it is used (BOU-377). `:wagoe/dashboard` handles that
   itself — it scans `port` … `port+10` — so the port to talk to is the one it
   reports, not the one we asked for. Reading the candidate back instead sent
   every request in this namespace to a port nothing was listening on.

   It answers nil rather than throwing when all eleven are busy, which is the
   only case worth retrying."
  [attempts]
  (loop [remaining attempts]
    (let [result (ig/init-key :wagoe/dashboard {:port (free-port)})]
      (cond
        result            result
        (<= remaining 1)  (throw (ex-info "dashboard could not bind any port"
                                          {:attempts attempts}))
        :else             (recur (dec remaining))))))

(def ^:dynamic *server* nil)
(def ^:dynamic *port* nil)

(use-fixtures :once
  (fn [f]
    (let [{:keys [port] :as started} (start-dashboard 5)]
      (binding [*server* started *port* port]
        (try (f) (finally (ig/halt-key! :wagoe/dashboard started)))))))

(deftest ^:integration dashboard-pages-return-200
  (doseq [path ["/dashboard" "/dashboard/routes" "/dashboard/requests"
                "/dashboard/schemas" "/dashboard/db" "/dashboard/errors"
                "/dashboard/jobs" "/dashboard/config" "/dashboard/security"]]
    (testing (str "GET " path " returns 200")
      (let [resp (http/get (str "http://localhost:" *port* path) {:throw-exceptions false})]
        (is (= 200 (:status resp)))
        (is (str/includes? (:body resp) "Wagoe Dev"))))))

(deftest ^:integration dashboard-css-served
  (testing "dashboard.css is served from classpath"
    (let [resp (http/get (str "http://localhost:" *port* "/assets/dashboard.css") {:throw-exceptions false})]
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "--bg-base")))))

(deftest ^:integration the-fixture-talks-to-the-port-the-dashboard-bound
  ;; :wagoe/dashboard falls back to a later port when the one it is given is
  ;; busy, and reports which. The fixture used to keep the port it asked for, so
  ;; on any fallback every request in this namespace went somewhere nothing was
  ;; listening (BOU-377).
  ;;
  ;; The squatter is a running dashboard, not a bare ServerSocket. A plain
  ;; socket does not reliably stop Jetty binding the same port — address reuse
  ;; makes it JDK- and OS-dependent, and `ring_jetty_server_test` documents two
  ;; drafts where Jetty bound straight through one.
  (let [squatter (start-dashboard 5)
        taken    (:port squatter)]
    (try
      (is (= 200 (:status (http/get (str "http://localhost:" taken "/dashboard")
                                    {:throw-exceptions false})))
          "the squatter really holds the port")
      (let [started (ig/init-key :wagoe/dashboard {:port taken})]
        (try
          (is (some? started) "it found a port despite the first being taken")
          (is (not= taken (:port started)) "and it is not the one we asked for")
          (is (= 200 (:status (http/get (str "http://localhost:" (:port started) "/dashboard")
                                        {:throw-exceptions false})))
              "the port it reports is the one serving")
          (finally (ig/halt-key! :wagoe/dashboard started))))
      (finally (ig/halt-key! :wagoe/dashboard squatter)))))

(deftest ^:integration a-bind-failure-that-is-not-a-busy-port-is-not-retried
  ;; An unroutable :host raises a BindException too — "Can't assign requested
  ;; address" — and treating that as a busy port scanned eleven of them and then
  ;; reported "all in use", which is not what went wrong (BOU-377).
  (is (thrown? java.io.IOException
               (ig/init-key :wagoe/dashboard {:port 0 :host "10.255.255.1"}))))
