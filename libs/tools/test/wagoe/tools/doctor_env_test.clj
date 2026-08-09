(ns wagoe.tools.doctor-env-test
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.tools.doctor-env :as doctor-env]
            [wagoe.tools.doctor :as doctor]))

;; =============================================================================
;; parse-version
;; =============================================================================

(deftest ^:unit parse-version-test
  (testing "parses Java version strings"
    (is (= 21 (#'doctor-env/parse-version
               "openjdk version \"21.0.2\" 2024-01-16"
               #"(?:version\s+\"?)(\d+)")))
    (is (= 17 (#'doctor-env/parse-version
               "openjdk version \"17.0.10\" 2024-01-16"
               #"(?:version\s+\"?)(\d+)"))))

  (testing "returns nil for unparseable input"
    (is (nil? (#'doctor-env/parse-version nil #"(\d+)")))
    (is (nil? (#'doctor-env/parse-version "no version here" #"version\s+(\d+)")))))

;; =============================================================================
;; Argument parsing
;; =============================================================================

(deftest ^:unit parse-args-test
  (testing "defaults when no args"
    (let [opts (#'doctor-env/parse-args [])]
      (is (false? (:ci opts)))
      (is (nil? (:help opts)))))

  (testing "parses --ci"
    (is (true? (:ci (#'doctor-env/parse-args ["--ci"])))))

  (testing "parses --help"
    (is (true? (:help (#'doctor-env/parse-args ["--help"]))))))

;; =============================================================================
;; Check functions (these hit real system state, so we verify structure)
;; =============================================================================

(deftest ^:unit check-functions-return-valid-maps
  (testing "each check returns a map with :id, :level, :msg"
    (doseq [[check-name check-fn] {"java"       doctor-env/check-java
                                   "clojure"    doctor-env/check-clojure-cli
                                   "babashka"   doctor-env/check-babashka
                                   "node"       doctor-env/check-node
                                   "ports"      doctor-env/check-ports
                                   "kondo"      doctor-env/check-clj-kondo
                                   "ai"         doctor-env/check-ai-providers}]
      (let [result (check-fn)]
        (is (keyword? (:id result)) (str check-name " missing :id"))
        (is (contains? #{:pass :warn :error} (:level result))
            (str check-name " has invalid :level " (:level result)))
        (is (string? (:msg result)) (str check-name " missing :msg"))))))

;; =============================================================================
;; AI provider detection
;; =============================================================================

(deftest ^:unit ai-provider-check-sees-hosted-providers
  ;; The check probed only localhost, so a machine with a working
  ;; REPLICATE_API_TOKEN was told "No AI providers detected" while `bb ai`
  ;; ran against it — advice pointing away from a working setup.
  ;; `env` is passed in: reading the developer's own shell would make the
  ;; result depend on who runs the suite.
  (testing "a hosted provider env var counts as a provider"
    (let [result (doctor-env/check-ai-providers {"REPLICATE_API_TOKEN" "r8_xxx"})]
      (is (= :pass (:level result)))
      (is (str/includes? (:msg result) "Replicate"))))

  (testing "each supported variable is recognised"
    (doseq [[var' label] doctor-env/ai-provider-env-vars]
      (let [result (doctor-env/check-ai-providers {var' "set"})]
        (is (= :pass (:level result)) (str var' " was not recognised"))
        (is (str/includes? (:msg result) label)))))

  (testing "an empty variable is not a provider"
    ;; `export ANTHROPIC_API_KEY=` is a common way to unset one.
    (let [result (doctor-env/check-ai-providers {"ANTHROPIC_API_KEY" "  "})]
      (is (not (str/includes? (:msg result) "Anthropic")))))

  (testing "the remedy names every supported variable"
    (let [result (doctor-env/check-ai-providers {})]
      ;; Only meaningful when nothing is listening locally either; when a
      ;; local provider is up the check passes and there is no :fix.
      (when (= :warn (:level result))
        (doseq [[var' _] doctor-env/ai-provider-env-vars]
          (is (str/includes? (:fix result) var')
              (str var' " missing from the remedy")))))))

(deftest ^:unit ai-provider-vars-match-the-cli-fallback-chain
  ;; doctor:env keeps its own list because it is Babashka and cannot load the
  ;; Clojure lib. A copy drifts — that is how REPLICATE_API_TOKEN came to be
  ;; supported by `bb ai` and invisible to `bb doctor:env`.
  (let [src (or (some #(when (.exists (io/file %)) (slurp %))
                      ["libs/ai/src/wagoe/ai/shell/cli_entry.clj"
                       "../ai/src/wagoe/ai/shell/cli_entry.clj"])
                (throw (ex-info "cli_entry.clj not found — cannot compare"
                                {:cwd (System/getProperty "user.dir")})))
        ;; The cond arms of make-service-from-env, which is the real chain.
        chain (->> (re-seq #"\(System/getenv \"([A-Z_]+)\"\)" src)
                   (map second)
                   distinct
                   (remove #{"AI_MODEL" "OLLAMA_URL"})   ; not hosted-provider selectors
                   set)
        known (set (map first doctor-env/ai-provider-env-vars))]

    (testing "the source parsed — otherwise this passes vacuously"
      (is (<= 4 (count chain))
          (str "only found " (pr-str chain) " in cli_entry")))

    (testing "doctor:env reports every variable the CLI acts on"
      (is (empty? (set/difference chain known))
          (str "bb ai uses " (pr-str (set/difference chain known))
               " and bb doctor:env would not mention it")))

    (testing "and claims none the CLI ignores"
      (is (empty? (set/difference known chain))
          (str "bb doctor:env reports " (pr-str (set/difference known chain))
               " but bb ai never reads it")))))

;; =============================================================================
;; Doctor --all flag
;; =============================================================================

(deftest ^:unit doctor-parse-args-all-flag
  (testing "doctor parses --all flag"
    (let [opts (#'wagoe.tools.doctor/parse-args ["--all"])]
      (is (true? (:all opts)))))

  (testing "doctor --all combined with --ci"
    (let [opts (#'wagoe.tools.doctor/parse-args ["--all" "--ci"])]
      (is (true? (:all opts)))
      (is (true? (:ci opts)))))

  (testing "doctor defaults to no --all"
    (let [opts (#'wagoe.tools.doctor/parse-args [])]
      (is (false? (:all opts))))))
