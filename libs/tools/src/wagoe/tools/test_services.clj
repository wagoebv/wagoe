#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/test_services.clj
;;
;; `bb test:services` — the backing services the adapter sweeps need.
;;
;; Why this exists: several suites compare every adapter they can reach and fail
;; when one is missing, because a sweep that silently compares two backends
;; instead of three reports green while proving less. That is the right
;; behaviour, but it costs a four-minute run to learn that Redis was not up —
;; the failure arrives at the end, phrased as a count mismatch. So the registry
;; below is read by three places: `bb test:services` starts them, `bb test:all`
;; names the missing ones before running anything, and `bb doctor:env` reports
;; them alongside the other prerequisites.
;;
;; Usage:
;;   bb test:services          # what is up and what is not
;;   bb test:services up       # start them (docker compose, waits for healthy)
;;   bb test:services down     # stop them

(ns wagoe.tools.test-services
  (:require [babashka.process :as process]
            [clojure.string :as str]
            [wagoe.tools.ansi :refer [bold green red yellow dim]]))

(def compose-file
  "Where the services are declared. A test pins `services` below to this file, so
   adding one to either without the other fails rather than drifting."
  "docker-compose.test.yml")

(defn- env-port
  [var default]
  (or (some-> (System/getenv var) parse-long) default))

(def services
  "Each service a test surface needs, and what fails without it.

   `:needed-by` is not decoration — it is what the preflight prints, so someone
   seeing `4858 tests, 7 failures` knows the failures are an absent container
   rather than their change."
  [{:id        :redis
    :host      "localhost"
    :port      6379
    :needed-by ["wagoe.jobs.adapter-surface-test"
                "wagoe.cache.adapter-surface-test"]}
   {:id        :mysql
    :host      "127.0.0.1"
    ;; `:port-env` is not documentation — a test requires the compose file to
    ;; publish on the same variable. Probing one port while starting a container
    ;; on another is a `bb test:services up` that ends by reporting the service
    ;; it just started as down.
    :port-env  "WAGOE_TEST_MYSQL_PORT"
    :port      (env-port "WAGOE_TEST_MYSQL_PORT" 3306)
    :needed-by ["wagoe.audience-dialect-test"]}])

(defn reachable?
  "True when something accepts a TCP connection at `host`:`port`.

   The same probe the sweeps themselves make, so this cannot report a service as
   up that they then fail to reach."
  [{:keys [host port]}]
  (try
    (with-open [s (java.net.Socket.)]
      (.connect s (java.net.InetSocketAddress. ^String host (int port)) 300)
      true)
    (catch Exception _ false)))

(defn missing
  "The services that are not reachable right now."
  []
  (remove reachable? services))

(defn- service-line
  [{:keys [id host port needed-by] :as svc}]
  (format "      %-6s %-20s %s %s"
          (name id)
          (str host ":" port)
          (if (reachable? svc) (green "up") (red "down"))
          (dim (str "→ " (str/join ", " needed-by)))))

(defn print-preflight
  "Warn about missing services before a long run. Returns the missing seq.

   Deliberately a warning rather than a refusal: the tools, cli and mcp surfaces
   need none of these, and blocking them to protect one sweep trades a known
   failure for an unrunnable command."
  []
  (let [absent (missing)]
    (when (seq absent)
      (println)
      (println (yellow (format "  ⚠ %d service(s) the adapter sweeps need are not reachable:"
                               (count absent))))
      (doseq [svc absent]
        (println (service-line svc)))
      (println (str "    Start them:  " (bold "bb test:services up")))
      (println (dim "    Without them those sweeps fail on purpose — they refuse to")
               (dim "report green while comparing fewer adapters than they claim.")))
    absent))

(defn- compose [& args]
  (apply process/shell {:continue true} "docker" "compose" "-f" compose-file args))

(defn- status []
  (println)
  (println (bold "Test services"))
  (println)
  (doseq [svc services]
    (println (service-line svc)))
  (println)
  (if (seq (missing))
    (println (str "Start them with " (bold "bb test:services up")))
    (println (green "All test services are reachable.")))
  0)

(defn -main [& args]
  (case (first args)
    nil     (System/exit (status))
    "status" (System/exit (status))
    "up"    (let [{:keys [exit]} (compose "up" "-d" "--wait")]
              (when (zero? exit) (status))
              (System/exit exit))
    "down"  (System/exit (:exit (compose "down")))
    (do (println (red (str "Unknown argument: " (first args))))
        (println "Usage: bb test:services [status|up|down]")
        (System/exit 2))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
