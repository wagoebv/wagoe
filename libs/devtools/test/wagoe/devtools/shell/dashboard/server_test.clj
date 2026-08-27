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

(defn- start-on-free-port
  "Start the dashboard on a free port, retrying when it is taken.

   `free-port` closes its socket before the server binds, so the port can be
   gone by the time it is used — the race that failed the full suite
   intermittently (BOU-377)."
  [attempts]
  (loop [remaining attempts]
    (let [port   (free-port)
          result (try
                   {:server (ig/init-key :wagoe/dashboard {:port port}) :port port}
                   (catch Exception e
                     (when (<= remaining 1) (throw e))))]
      (or result (recur (dec remaining))))))

(def ^:dynamic *server* nil)
(def ^:dynamic *port* nil)

(use-fixtures :once
  (fn [f]
    (let [{srv :server port :port} (start-on-free-port 5)]
      (binding [*server* srv *port* port]
        (try (f) (finally (ig/halt-key! :wagoe/dashboard srv)))))))

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
