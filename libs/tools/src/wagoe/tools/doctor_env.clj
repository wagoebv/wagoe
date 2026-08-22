#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/doctor_env.clj
;;
;; Environment Doctor — checks that required development tools are installed.
;;
;; Usage (via bb.edn task):
;;   bb doctor:env              # Check all prerequisites
;;   bb doctor:env --ci         # Exit non-zero on any error (CI mode)

(ns wagoe.tools.doctor-env
  (:require [wagoe.tools.ansi :refer [bold]]
            [wagoe.tools.report :as report]
            [clojure.string :as str]
            [babashka.process :as process]))

;; =============================================================================
;; Shell helpers
;; =============================================================================

(defn- run-cmd
  "Run a shell command and return its stdout, or nil if the command fails."
  [& cmd-parts]
  (try
    (let [result (process/shell {:out :string :err :string :continue true}
                                (str/join " " cmd-parts))]
      (when (zero? (:exit result))
        (str/trim (:out result))))
    (catch Exception _
      nil)))

(defn- run-cmd-stderr
  "Run a shell command and return its stderr (some tools print version to stderr)."
  [& cmd-parts]
  (try
    (let [result (process/shell {:out :string :err :string :continue true}
                                (str/join " " cmd-parts))]
      (when (zero? (:exit result))
        (str/trim (:err result))))
    (catch Exception _
      nil)))

(defn- port-in-use?
  "Check if a port is currently in use by attempting a socket connection."
  [port]
  (try
    (let [s (java.net.Socket.)]
      (.connect s (java.net.InetSocketAddress. "localhost" (int port)) 100)
      (.close s)
      true)
    (catch Exception _
      false)))

(defn- parse-version
  "Extract a version number (major or major.minor) from a string.
   Returns the major version as a long, or nil."
  [s pattern]
  (when s
    (when-let [m (re-find pattern s)]
      (try
        (Long/parseLong (second m))
        (catch Exception _ nil)))))

;; =============================================================================
;; Pure check functions
;; =============================================================================

(defn check-java
  "Verify Java >= 17 is installed."
  []
  (let [;; java -version prints to stderr
        stderr (run-cmd-stderr "java" "-version")
        stdout (run-cmd "java" "-version")
        output (or stderr stdout)]
    (if-not output
      {:id :java :level :error
       :msg "Java not found"
       :fix "Install Java 17+: https://adoptium.net/ or `brew install openjdk@17`"}
      (let [major (parse-version output #"(?:version\s+\"?)(\d+)")]
        (cond
          (nil? major)
          {:id :java :level :warn
           :msg (str "Java found but could not parse version: " (first (str/split-lines output)))
           :fix "Ensure Java >= 17 is installed"}

          (< major 17)
          {:id :java :level :error
           :msg (str "Java " major " found, but >= 17 is required")
           :fix "Upgrade Java: https://adoptium.net/ or `brew install openjdk@17`"}

          :else
          {:id :java :level :pass
           :msg (str "Java " major " installed")})))))

(defn check-clojure-cli
  "Verify the Clojure CLI is installed."
  []
  (let [output (run-cmd "clojure" "--version")]
    (if output
      {:id :clojure-cli :level :pass
       :msg (str "Clojure CLI installed (" output ")")}
      {:id :clojure-cli :level :error
       :msg "Clojure CLI not found"
       :fix "Install: https://clojure.org/guides/install_clojure or `brew install clojure/tools/clojure`"})))

(defn check-babashka
  "Verify Babashka is installed."
  []
  (let [output (run-cmd "bb" "--version")]
    (if output
      {:id :babashka :level :pass
       :msg (str "Babashka installed (" output ")")}
      {:id :babashka :level :error
       :msg "Babashka not found"
       :fix "Install: https://github.com/babashka/babashka#installation or `brew install borkdude/brew/babashka`"})))

(defn check-node
  "Verify Node.js is installed (for UI assets). Warn if missing."
  []
  (let [output (run-cmd "node" "--version")]
    (if output
      {:id :node :level :pass
       :msg (str "Node.js installed (" output ")")}
      {:id :node :level :warn
       :msg "Node.js not found (needed for UI asset compilation)"
       :fix "Install: https://nodejs.org/ or `brew install node`"})))

(defn check-ports
  "Check that development ports 3000 (HTTP), 7888 (nREPL), 9999 (shadow-cljs) are available."
  []
  (let [ports      {3000 "HTTP server"
                    7888 "nREPL"
                    9999 "shadow-cljs"}
        in-use     (filter (fn [[port _]] (port-in-use? port)) ports)]
    (if (seq in-use)
      {:id :ports :level :warn
       :msg (str "Ports in use: "
                 (str/join ", " (map (fn [[port desc]] (str port " (" desc ")")) in-use)))
       :fix (str "Free the ports or configure alternatives. Check with: lsof -i :"
                 (str/join ",:" (map first in-use)))}
      {:id :ports :level :pass
       :msg "Ports 3000, 7888, 9999 are available"})))

(defn check-clj-kondo
  "Verify clj-kondo is installed."
  []
  (let [output (run-cmd "clj-kondo" "--version")]
    (if output
      {:id :clj-kondo :level :pass
       :msg (str "clj-kondo installed (" output ")")}
      {:id :clj-kondo :level :warn
       :msg "clj-kondo not found (linting will not work)"
       :fix "Install: https://github.com/clj-kondo/clj-kondo#installation or `brew install borkdude/brew/clj-kondo`"})))

(def ai-provider-env-vars
  "Environment variables that select a provider, in the order
   `wagoe.ai.shell.cli-entry/make-service-from-env` consults them.

   Kept in the same order so what this reports is what `bb ai` will use.
   `OLLAMA_URL` is last because it is: the first four short-circuit, and
   `OLLAMA_URL` then redirects the Ollama fallback, which `bb ai` counts as a
   deliberate choice. Probing localhost only happens when none is set."
  [["ANTHROPIC_API_KEY"   "Anthropic"]
   ["OPENAI_BASE_URL"     "an OpenAI-compatible endpoint"]
   ["OPENAI_API_KEY"      "OpenAI"]
   ["REPLICATE_API_TOKEN" "Replicate"]
   ["OLLAMA_URL"          "Ollama"]])

(defn check-ai-providers
  "Report which AI provider `bb ai` would use. Warn level only.

   Probing localhost was the whole check, so a machine with a working
   REPLICATE_API_TOKEN was told 'No AI providers detected' while `bb ai
   gen-tests` ran fine against it — advice pointing away from a working setup.
   `OLLAMA_URL` pointing at a remote or non-default Ollama had the same
   problem: `bb ai` uses that endpoint, nothing is on localhost, and the check
   reported none. Environment variables come first here because they come
   first there.

   `env` is a parameter so the test does not depend on the developer's own
   shell — a test that reads the ambient environment passes or fails by
   accident."
  ([] (check-ai-providers (into {} (map (fn [[v _]] [v (System/getenv v)]))
                                ai-provider-env-vars)))
  ([env]
   (let [set?       (fn [v] (not (str/blank? (get env v))))
         from-env   (->> ai-provider-env-vars
                         (filter (comp set? first))
                         (map (fn [[v label]] (str label " (" v ")"))))
         ;; Only worth probing when nothing was chosen: with OLLAMA_URL set,
         ;; `bb ai` talks to that endpoint whatever is on localhost, so
         ;; reporting the local port too would name a provider it will not use.
         probe?     (not (set? "OLLAMA_URL"))
         ollama-up? (and probe? (port-in-use? 11434))
         mlx-up?    (and probe? (port-in-use? 8080))
         parts      (cond-> (vec from-env)
                      ollama-up? (conj "Ollama (port 11434)")
                      mlx-up?    (conj "MLX (port 8080)"))]
     (if (seq parts)
       {:id :ai-providers :level :pass
        :msg (str "AI providers available: " (str/join ", " parts))}
       {:id :ai-providers :level :warn
        :msg "No AI provider detected (no provider env var set, nothing on 11434 or 8080)"
        :fix (str "Optional. Set one of ANTHROPIC_API_KEY, OPENAI_API_KEY, "
                  "OPENAI_BASE_URL, REPLICATE_API_TOKEN or OLLAMA_URL, or start "
                  "Ollama with `ollama serve`")}))))

;; =============================================================================
;; Check orchestration
;; =============================================================================

(defn run-checks
  "Run all environment checks. Returns a seq of check result maps."
  []
  [(check-java)
   (check-clojure-cli)
   (check-babashka)
   (check-node)
   (check-ports)
   (check-clj-kondo)
   (check-ai-providers)])

;; =============================================================================
;; Output formatting
;; =============================================================================

;; =============================================================================
;; Argument parsing
;; =============================================================================

(defn- parse-args [args]
  (loop [[flag & more :as remaining] args
         opts {:ci false}]
    (cond
      (empty? remaining) opts
      (or (= flag "--help") (= flag "-h")) (assoc opts :help true)
      (= flag "--ci")  (recur more (assoc opts :ci true))
      (= flag "--edn") (recur more (assoc opts :edn true))
      :else (recur more opts))))

(defn- print-help []
  (println (bold "bb doctor:env") " — Check development environment prerequisites")
  (println)
  (println "Usage:")
  (println "  bb doctor:env              Check all prerequisites")
  (println "  bb doctor:env --ci         Exit non-zero on any error (CI mode)")
  (println "  bb doctor:env --edn        Print results as data (what `wagoe doctor` reads)")
  (println)
  (println "Checks:")
  (println "  java              Java >= 17 installed")
  (println "  clojure-cli       Clojure CLI installed")
  (println "  babashka          Babashka installed")
  (println "  node              Node.js installed (warn only)")
  (println "  ports             Dev ports 3000, 7888, 9999 available")
  (println "  clj-kondo         clj-kondo linter installed (warn only)")
  (println "  ai-providers      A provider env var is set, or Ollama / MLX is running (warn only)"))

;; =============================================================================
;; Main entry point
;; =============================================================================

(defn -main [& args]
  (let [opts (parse-args args)]
    (when (:help opts)
      (print-help)
      (System/exit 0))
    (let [results (run-checks)
          summary (if (:edn opts)
                    (do (report/print-edn :environment results) (report/tally results))
                    (report/print-results "Wagoe Environment Doctor" results))]
      (when (and (:ci opts) (pos? (:errors summary)))
        (System/exit 1)))))

;; Run when executed directly (not via bb.edn task)
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
