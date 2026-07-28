#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/doctor_env.clj
;;
;; Environment Doctor — checks that required development tools are installed.
;;
;; Usage (via bb.edn task):
;;   bb doctor:env              # Check all prerequisites
;;   bb doctor:env --ci         # Exit non-zero on any error (CI mode)

(ns wagoe.tools.doctor-env
  (:require [wagoe.tools.ansi :refer [bold green red yellow dim]]
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

(defn check-ai-providers
  "Check if AI providers (Ollama, MLX) are running. Warn level only."
  []
  (let [ollama-up? (port-in-use? 11434)
        mlx-up?    (port-in-use? 8080)
        parts      (cond-> []
                     ollama-up? (conj "Ollama (port 11434)")
                     mlx-up?    (conj "MLX (port 8080)"))]
    (if (seq parts)
      {:id :ai-providers :level :pass
       :msg (str "AI providers running: " (str/join ", " parts))}
      {:id :ai-providers :level :warn
       :msg "No AI providers detected (Ollama on 11434, MLX on 8080)"
       :fix "Optional: start Ollama with `ollama serve` or MLX with `mlx_lm.server`"})))

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

(defn- format-result [{:keys [id level msg fix]}]
  (let [icon   (case level
                 :pass  (green "✓")
                 :warn  (yellow "⚠")
                 :error (red "✗"))
        id-str (format "%-20s" (name id))]
    (str "  " icon " " id-str " " msg
         (when fix
           (str "\n" (dim (str "                       Fix: " fix)))))))

(defn- print-results [results]
  (println)
  (println (bold "Wagoe Environment Doctor"))
  (println)
  (doseq [r results]
    (println (format-result r)))
  (let [passed (count (filter #(= :pass (:level %)) results))
        warns  (count (filter #(= :warn (:level %)) results))
        errors (count (filter #(= :error (:level %)) results))]
    (println)
    (println (str "Summary: " (green (str passed " passed"))
                  ", " (yellow (str warns " warning" (when (not= warns 1) "s")))
                  ", " (red (str errors " error" (when (not= errors 1) "s")))))
    {:passed passed :warnings warns :errors errors}))

;; =============================================================================
;; Argument parsing
;; =============================================================================

(defn- parse-args [args]
  (loop [[flag & more :as remaining] args
         opts {:ci false}]
    (cond
      (empty? remaining) opts
      (or (= flag "--help") (= flag "-h")) (assoc opts :help true)
      (= flag "--ci") (recur more (assoc opts :ci true))
      :else (recur more opts))))

(defn- print-help []
  (println (bold "bb doctor:env") " — Check development environment prerequisites")
  (println)
  (println "Usage:")
  (println "  bb doctor:env              Check all prerequisites")
  (println "  bb doctor:env --ci         Exit non-zero on any error (CI mode)")
  (println)
  (println "Checks:")
  (println "  java              Java >= 17 installed")
  (println "  clojure-cli       Clojure CLI installed")
  (println "  babashka          Babashka installed")
  (println "  node              Node.js installed (warn only)")
  (println "  ports             Dev ports 3000, 7888, 9999 available")
  (println "  clj-kondo         clj-kondo linter installed (warn only)")
  (println "  ai-providers      Ollama / MLX running (warn only)"))

;; =============================================================================
;; Main entry point
;; =============================================================================

(defn -main [& args]
  (let [opts (parse-args args)]
    (when (:help opts)
      (print-help)
      (System/exit 0))
    (let [results (run-checks)
          summary (print-results results)]
      (when (and (:ci opts) (pos? (:errors summary)))
        (System/exit 1)))))

;; Run when executed directly (not via bb.edn task)
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
