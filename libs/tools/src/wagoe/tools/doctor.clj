#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/doctor.clj
;;
;; Config Doctor — rule-based validation of Wagoe config files.
;;
;; Usage (via bb.edn task):
;;   bb doctor                      # Check dev environment
;;   bb doctor --env prod           # Check specific environment
;;   bb doctor --env all            # Check all environments
;;   bb doctor --all                # Run both config + environment checks
;;   bb doctor --ci                 # Exit non-zero on any error (CI mode)

(ns wagoe.tools.doctor
  (:require [wagoe.tools.ansi :refer [bold]]
            [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [wagoe.tools.report :as report]))

;; =============================================================================
;; Known valid providers
;; =============================================================================

(def known-providers
  {:wagoe/logging          #{:no-op :stdout :slf4j :file}
   :wagoe/metrics          #{:no-op :prometheus :datadog-statsd}
   :wagoe/error-reporting  #{:no-op :sentry}
   :wagoe/payment-provider #{:mock :mollie :stripe}
   ;; Mirrors wagoe.ai.shell.module-wiring/build-provider. doctor is Babashka
   ;; and cannot load the Clojure lib to read it, so this is a copy — and a copy
   ;; drifts: adding :replicate there left doctor rejecting a provider that
   ;; worked. A test pins the two together (BOU-281).
   :wagoe/ai-service       #{:ollama :anthropic :openai :replicate :no-op}
   :wagoe/cache            #{:redis :in-memory}
   ;; Mirrors the case in wagoe.events.shell.module-wiring/init-key. Same
   ;; copy-drift hazard as :wagoe/ai-service above, and the same reason: doctor
   ;; runs on Babashka and cannot load the lib to ask it.
   :wagoe/events           #{:redis-streams :in-memory}})

;; =============================================================================
;; Pure check functions
;; =============================================================================

(defn extract-env-refs
  "Extract all #env VAR references from raw config text.
   Handles both unquoted (#env FOO) and quoted (#env \"FOO\") forms.
   Returns a set of env var name strings."
  [config-text]
  (->> (re-seq #"#env\s+\"?([A-Z_][A-Z0-9_]*)\"?" config-text)
       (map second)
       set))

(defn extract-or-defaults
  "Extract env var names that are wrapped in #or (have a default).
   Matches patterns like: #or [#env VAR default] and #or [#env \"VAR\" default]"
  [config-text]
  (->> (re-seq #"#or\s+\[#env\s+\"?([A-Z_][A-Z0-9_]*)\"?\s+[^\]]" config-text)
       (map second)
       set))

(defn extract-fallback-env-refs
  "Extract env var names that appear inside :fallback blocks in config text.
   These are optional — the fallback provider only runs when the primary fails."
  [config-text]
  (let [;; Find all :fallback { ... } blocks using brace-balanced extraction
        matches (re-seq #":fallback\s*\{" config-text)]
    (if-not (seq matches)
      #{}
      ;; For each :fallback occurrence, extract the balanced block and find #env refs inside
      (loop [text config-text
             refs #{}]
        (let [idx (str/index-of text ":fallback")]
          (if-not idx
            refs
            (let [open-idx (str/index-of text "{" idx)]
              (if-not open-idx
                refs
                (let [block (loop [i (inc open-idx) depth 1 acc ""]
                              (cond
                                (>= i (count text)) acc
                                (zero? depth) acc
                                :else (let [c (nth text i)]
                                        (recur (inc i)
                                               (case c \{ (inc depth) \} (dec depth) depth)
                                               (str acc c)))))]
                  (recur (subs text (inc open-idx))
                         (into refs (map second (re-seq #"#env\s+\"?([A-Z_][A-Z0-9_]*)\"?" block)))))))))))))

(defn check-env-refs
  "Check that #env references without #or defaults are set in the environment.
   Env vars inside :fallback blocks are treated as warnings (optional), not errors.
   Returns a seq of check result maps."
  [config-text env-map]
  (let [all-refs      (extract-env-refs config-text)
        with-default  (extract-or-defaults config-text)
        fallback-refs (extract-fallback-env-refs config-text)
        unprotected   (set/difference all-refs with-default)
        missing       (remove #(get env-map %) unprotected)
        ;; Split into required (error) and optional/fallback (warn)
        required      (remove fallback-refs missing)
        optional      (filter fallback-refs missing)]
    (concat
     (when (seq required)
       [{:id    :env-refs
         :level :error
         :msg   (str "#env references without defaults are unset: " (str/join ", " (sort required)))
         :fix   (str "Export the missing variables:\n"
                     (str/join "\n" (map #(str "  export " % "=\"...\"") (sort required))))}])
     (when (seq optional)
       [{:id    :env-refs
         :level :warn
         :msg   (str "Optional fallback env vars not set: " (str/join ", " (sort optional)))
         :fix   (str "These are in :fallback blocks and only needed if the primary provider fails.\n"
                     "  To enable: " (str/join ", " (map #(str "export " % "=\"...\"") (sort optional))))}])
     (when (and (empty? required) (empty? optional))
       [{:id    :env-refs
         :level :pass
         :msg   "All #env references resolved or have defaults"}]))))

(defn check-providers
  "Check that :provider values in the config are known/valid.
   Accepts parsed EDN config (the :active map)."
  [active-config]
  (let [errors (for [[config-key valid-set] known-providers
                     :let [provider-val (get-in active-config [config-key :provider])]
                     :when (and provider-val (not (contains? valid-set provider-val)))]
                 {:config-key config-key
                  :value      provider-val
                  :valid      valid-set})]
    (if (seq errors)
      (mapv (fn [{:keys [config-key value valid]}]
              {:id    :providers
               :level :error
               :msg   (str config-key " has unknown provider " value)
               :fix   (str "Valid providers: " (str/join ", " (sort (map name valid))))})
            errors)
      [{:id    :providers
        :level :pass
        :msg   "All provider values are known"}])))

(def jwt-secret-min-length
  "Minimum JWT_SECRET length, mirroring the runtime guard in
   `wagoe.user.shell.auth/validate-jwt-secret*` (HMAC key strength)."
  32)

(def ^:private jwt-secret-fix
  ;; Long enough to be valid. The previous suggestion, "your-32-char-secret",
  ;; is 19 characters — the gate was handing out a value the runtime rejects.
  "export JWT_SECRET=\"$(openssl rand -base64 32)\"")

(defn check-jwt-secret
  "Check that JWT_SECRET is set and long enough when the user module is active.

   Length matters: the runtime refuses to boot on a secret under
   `jwt-secret-min-length`, so a presence-only check passes configurations that
   fail at startup — a shorter secret got a green `bb doctor` and then died
   with `JWT_SECRET must be at least 32 characters` (BOU-250)."
  [active-config env-map]
  (let [user-active? (some (fn [k]
                             (and (keyword? k)
                                  (str/starts-with? (name k) "user")
                                  (= (namespace k) "wagoe")))
                           (keys active-config))
        secret       (get env-map "JWT_SECRET")]
    (cond
      (not user-active?)
      [{:id :jwt-secret :level :pass :msg "User module not active, JWT_SECRET not required"}]

      (str/blank? secret)
      [{:id    :jwt-secret
        :level :error
        :msg   "JWT_SECRET not set (required by user module)"
        :fix   jwt-secret-fix}]

      (< (count secret) jwt-secret-min-length)
      [{:id    :jwt-secret
        :level :error
        :msg   (str "JWT_SECRET is " (count secret) " characters; the user module "
                    "requires at least " jwt-secret-min-length
                    " and will refuse to start")
        :fix   jwt-secret-fix}]

      :else
      [{:id :jwt-secret :level :pass :msg "JWT_SECRET is set"}])))

(defn check-admin-parity
  "Check that admin entity config files exist in both dev and test."
  [dev-files test-files]
  (let [dev-names  (set (map #(.getName %) dev-files))
        test-names (set (map #(.getName %) test-files))
        dev-only   (set/difference dev-names test-names)
        test-only  (set/difference test-names dev-names)]
    (cond
      (and (empty? dev-only) (empty? test-only))
      [{:id :admin-parity :level :pass :msg "Admin entity files match between dev and test"}]

      :else
      (concat
       (when (seq dev-only)
         [{:id    :admin-parity
           :level :warn
           :msg   (str "Admin files in dev but not test: " (str/join ", " (sort dev-only)))
           :fix   "Copy missing admin entity EDN files to resources/conf/test/admin/"}])
       (when (seq test-only)
         [{:id    :admin-parity
           :level :warn
           :msg   (str "Admin files in test but not dev: " (str/join ", " (sort test-only)))
           :fix   "Copy missing admin entity EDN files to resources/conf/dev/admin/"}])))))

(defn check-prod-placeholders
  "Check for placeholder values in config text (only for prod/acc environments)."
  [config-text env-name]
  (if-not (contains? #{"prod" "acc"} env-name)
    [{:id :prod-placeholders :level :pass :msg "Placeholder check skipped (non-production env)"}]
    (let [patterns [#"company\.com" #"example\.com" #"TODO" #"CHANGEME" #"xxx" #"your-.*-here"]
          matches  (for [pat patterns
                         :let [found (re-find pat config-text)]
                         :when found]
                     (str found))]
      (if (seq matches)
        [{:id    :prod-placeholders
          :level :error
          :msg   (str "Placeholder values found in " env-name " config: " (str/join ", " matches))
          :fix   "Replace all placeholder values with real configuration"}]
        [{:id :prod-placeholders :level :pass :msg "No placeholder values found"}]))))

(def reset-endpoint-allowed-profiles
  "Profiles where :test/reset-endpoint-enabled? is allowed to be true.
   This flag exposes a DB-truncating HTTP endpoint and must never ship in prod/acc."
  #{"test" "dev"})

(defn check-reset-endpoint-flag
  "Check that :test/reset-endpoint-enabled? is not true outside of :test or :dev profiles.
   This flag exposes a destructive `/test/reset` endpoint and must never be enabled in
   prod/acc. Backs the runtime assertion in
   libs/platform/src/wagoe/platform/shell/system/wiring.clj/build-test-reset-routes.

   `parsed-config` is the full EDN config map (not just the :active section), since
   the flag lives at the top level of config.edn. `env-name` is the profile name
   string (e.g. \"prod\", \"acc\", \"test\", \"dev\")."
  [parsed-config env-name]
  (let [flag-value (get parsed-config :test/reset-endpoint-enabled?)
        allowed?   (contains? reset-endpoint-allowed-profiles env-name)]
    (cond
      (and (true? flag-value) (not allowed?))
      [{:id    :reset-endpoint-flag
        :level :error
        :msg   (str "[flag=:test/reset-endpoint-enabled?] must not be true in "
                    env-name " profile — this flag exposes a DB-truncating endpoint. "
                    "Allowed profiles: "
                    (str/join ", " (sort (map #(str ":" %) reset-endpoint-allowed-profiles))))
        :fix   (str "Remove :test/reset-endpoint-enabled? from resources/conf/"
                    env-name "/config.edn (or set it to false)")}]

      (true? flag-value)
      [{:id    :reset-endpoint-flag
        :level :pass
        :msg   (str ":test/reset-endpoint-enabled? enabled (allowed in " env-name " profile)")}]

      :else
      [{:id    :reset-endpoint-flag
        :level :pass
        :msg   ":test/reset-endpoint-enabled? not enabled"}])))

(def ^:private relocated-namespaces
  "Namespaces that moved during the Phase-2 refactors (framework upgrade aid).
   Requiring the old name fails at compile; doctor points at the new home."
  {"wagoe.platform.shell.interfaces.http.tenant-middleware"
   "wagoe.tenant.shell.tenant-middleware"
   "wagoe.platform.shell.interfaces.http.membership-middleware"
   "wagoe.tenant.shell.membership-middleware"})

(defn check-upgrade-wiring
  "Framework-upgrade check over the app's own source text (BOU-204).

   An app that requires one of the namespaces BOU-198 moved out of platform
   fails at compile, and the compiler error names the namespace without saying
   where it went. This says where it went.

   It used to also warn when an app wired the tenant module without referencing
   `:wagoe/tenant-http-middleware`, which mounted tenant resolution silently
   nowhere. That hazard is gone: the tenant library emits the middleware from
   its own `ig-config` and contributes it to the HTTP handler, so an app cannot
   have one without the other (BOU-326).

   Nor does it check any more that the app requires a module-wiring namespace
   per active module. That was BOU-171/192/198's contract and BOU-326 replaced
   it: `system-config` loads a module's wiring when the config names the module,
   and an app that requires one statically is the thing
   `wagoe.main-test/the-app-config-requires-no-module-wiring` now forbids. A
   module whose library is genuinely absent throws
   `:wagoe/module-library-missing` at boot, naming the library and both ways
   out — a better answer than a warning, and one that cannot go stale."
  [src-text]
  (let [stale (filter #(str/includes? src-text %) (keys relocated-namespaces))]
    (if (seq stale)
      (mapv (fn [old-ns]
              {:id    :upgrade-wiring
               :level :error
               :msg   (str "Require of relocated namespace " old-ns " (moved in BOU-198)")
               :fix   (str "Require " (get relocated-namespaces old-ns) " instead")})
            stale)
      [{:id :upgrade-wiring :level :pass :msg "No stale framework wiring detected"}])))

;; =============================================================================
;; Shell: file loading
;; =============================================================================

(defn- root-dir [] (System/getProperty "user.dir"))

(defn- config-path [env]
  (str (root-dir) "/resources/conf/" env "/config.edn"))

(defn- load-raw-config
  "Slurp the raw config text for an environment."
  [env]
  (let [path (config-path env)
        f    (io/file path)]
    (when (.exists f)
      (slurp f))))

(def ^:private last-parse-error
  "Message from the most recent failed config parse, for check-config-loadable."
  (atom nil))

(defn- parse-config-minimal
  "Parse config.edn with a minimal reader that replaces Aero tags with placeholders.
   Returns the parsed EDN map."
  [config-text]
  (let [;; Custom readers that handle Aero tags by returning placeholder values
        readers {'env      (fn [v] (str "ENV:" v))
                 'or       (fn [v] (if (vector? v) (last v) v))
                 'long     (fn [v] (if (number? v) v 0))
                 'merge    (fn [v] (if (vector? v) (apply merge v) v))
                 'include  (fn [_v] {})
                 'str      (fn [v] (str v))
                 'join     (fn [v] (if (vector? v) (str/join v) (str v)))
                 'keyword  (fn [v] (keyword v))
                 'ref      (fn [v] v)
                 'ig/ref   (fn [v] v)
                 'profile  (fn [v] v)}]
    (try
      (edn/read-string {:readers readers} config-text)
      ;; Returns nil on failure; `check-config-loadable` turns that into a
      ;; reported error. It used to println a warning here and return nil,
      ;; which never reached the summary — so an unparseable config produced
      ;; "8 passed, 0 warnings, 0 errors" and exit 0, including under --ci.
      (catch Exception e
        (reset! last-parse-error (.getMessage e))
        nil))))

(defn- list-admin-files [env]
  (let [dir (io/file (root-dir) "resources" "conf" env "admin")]
    (when (.exists dir)
      (->> (.listFiles dir)
           (filter #(str/ends-with? (.getName %) ".edn"))
           vec))))

(defn- load-app-source-text
  "Concatenated .clj source of the app's src/ and dev/ trees, plus platform's
   system wiring.clj when present (monorepo). Post BOU-171/192/198 the app owns
   its module-wiring requires, so wiring/upgrade checks must scan the app source
   — in a downstream project the monorepo wiring.clj path does not even exist."
  []
  (let [platform-wiring (io/file (root-dir) "libs/platform/src/wagoe/platform/shell/system/wiring.clj")
        clj-files (fn [dir]
                    (let [d (io/file (root-dir) dir)]
                      (when (.exists d)
                        (->> (file-seq d)
                             (filter #(and (.isFile %) (str/ends-with? (.getName %) ".clj")))))))]
    (->> (concat (when (.exists platform-wiring) [platform-wiring])
                 (clj-files "src")
                 (clj-files "dev"))
         (map slurp)
         (str/join "\n"))))

;; =============================================================================
;; Check orchestration
;; =============================================================================

(defn- extract-active-section
  "Extract the raw text of the :active block from config.edn using balanced brace matching.
   This avoids false positives from #env vars in :inactive blocks.
   Skips occurrences of :active inside ;; comments."
  [config-text]
  (let [lines (str/split-lines config-text)
        ;; Find the line with :active that is not inside a comment
        active-line-idx (first (keep-indexed
                                (fn [i line]
                                  (let [trimmed (str/trim line)]
                                    (when (and (not (str/starts-with? trimmed ";"))
                                               (str/includes? line ":active"))
                                      i)))
                                lines))]
    (if-not active-line-idx
      config-text
      ;; Walk forward from :active, counting braces to find the balanced {} block
      (let [text-from-active (str/join "\n" (subvec (vec lines) active-line-idx))
            open-idx         (str/index-of text-from-active "{")]
        (if-not open-idx
          config-text
          (loop [i     (inc open-idx)
                 depth 1]
            (cond
              (>= i (count text-from-active)) config-text ; unbalanced, fallback
              (zero? depth) (subs text-from-active 0 i)
              :else (let [c (nth text-from-active i)]
                      (recur (inc i)
                             (case c \{ (inc depth) \} (dec depth) depth))))))))))

(defn- check-config-loadable
  "The config must parse and contain a non-empty :active section.

   Without this, both failures were invisible: `parse-config-minimal` returns
   nil on a parse error and the caller does `(or (:active parsed) {})`, so a
   broken file and a misspelled `:active` key both collapse to an empty map.
   Every downstream check then finds nothing to complain about and reports a
   pass, while the application cannot boot at all — `bb doctor --ci`, a CI
   gate, exited 0 on a config that fails with `No active database configured`."
  [parsed]
  (cond
    (nil? parsed)
    [{:id :config-loadable :level :error
      :msg (str "config.edn could not be parsed"
                (when-let [e @last-parse-error] (str ": " e)))
      :fix "Fix the syntax — `clj-paren-repair resources/conf/<env>/config.edn` reports the position."}]

    (empty? (:active parsed))
    [{:id :config-loadable :level :error
      :msg "config.edn has no :active section, or it is empty"
      :fix "Every module the app needs is keyed under :active — check for a typo in the key itself."}]

    :else
    [{:id :config-loadable :level :pass
      :msg (str "config.edn parses, " (count (:active parsed)) " active key(s)")}]))

(defn run-checks
  "Run all doctor checks for a given environment.
   Returns a seq of check result maps."
  [env _opts]
  (let [config-text (load-raw-config env)
        env-map     (into {} (System/getenv))]
    (if-not config-text
      ;; A result rather than a println and an exit: `--edn` writes to a reader
      ;; that calls read-string, and a bare line of red text on stdout is not
      ;; something it can parse. The caller decides what a missing config is
      ;; worth — `bb doctor --ci` still exits 1 on it, because it is an error.
      [{:id    :config-loadable
        :level :error
        :msg   (str "Config file not found: " (config-path env))
        :fix   (str "Create " (config-path env) ", or pass --env <profile> for one that exists.")}]
      (let [parsed       (parse-config-minimal config-text)
          active       (or (:active parsed) {})
          active-text  (extract-active-section config-text)
          src-text     (load-app-source-text)
          dev-admin    (or (list-admin-files "dev") [])
          test-admin   (or (list-admin-files "test") [])]
      (concat
       (check-config-loadable parsed)
       (check-env-refs active-text env-map)
       (check-providers active)
       (check-jwt-secret active env-map)
       (check-admin-parity dev-admin test-admin)
       (check-prod-placeholders config-text env)
       (check-reset-endpoint-flag (or parsed {}) env)
       (check-upgrade-wiring src-text))))))

;; =============================================================================
;; Output formatting
;; =============================================================================

;; =============================================================================
;; Argument parsing
;; =============================================================================

(defn- parse-args [args]
  (loop [[flag & more :as remaining] args
         opts {:env "dev" :ci false :all false}]
    (cond
      (empty? remaining) opts
      (or (= flag "--help") (= flag "-h")) (assoc opts :help true)
      (= flag "--ci")  (recur more (assoc opts :ci true))
      (= flag "--edn") (recur more (assoc opts :edn true))
      (= flag "--all") (recur more (assoc opts :all true))
      (= flag "--env") (recur (rest more) (assoc opts :env (first more)))
      :else (recur more opts))))

(defn- print-help []
  (println (bold "bb doctor") " — Validate Wagoe config for common mistakes")
  (println)
  (println "Usage:")
  (println "  bb doctor                  Check dev config")
  (println "  bb doctor --env prod       Check specific environment config")
  (println "  bb doctor --env all        Check all environment configs")
  (println "  bb doctor --all            Run both config + environment checks")
  (println "  bb doctor --ci             Exit non-zero on any error (CI mode)")
  (println)
  (println "Checks:")
  (println "  env-refs            #env vars without #or defaults are set")
  (println "  providers           Provider values are recognized")
  (println "  jwt-secret          JWT_SECRET set when user module active")
  (println "  admin-parity        Admin entity files exist in both dev and test")
  (println "  prod-placeholders   No placeholder values in prod config")
  (println "  reset-endpoint-flag :test/reset-endpoint-enabled? only true in test/dev")
  (println "  wiring-requires     App source requires wiring for all active modules")
  (println "  upgrade-wiring      No stale pre-Phase-2 framework wiring (BOU-198/200)"))

;; =============================================================================
;; Main entry point
;; =============================================================================

(defn -main [& args]
  (let [opts (parse-args args)]
    (when (:help opts)
      (print-help)
      (System/exit 0))

    ;; When --all, run environment checks first (delegates to doctor:env)
    (when (:all opts)
      (println)
      (println (bold "Running environment checks..."))
      (let [cmd    (cond-> ["bb" "doctor:env"]
                     (:ci opts) (conj "--ci"))
            result (process/shell {:continue true} (str/join " " cmd))]
        (when (and (:ci opts) (not (zero? (:exit result))))
          (System/exit 1))
        (println)))

    (let [envs    (if (= (:env opts) "all")
                    (let [conf-dir (io/file (root-dir) "resources" "conf")]
                      (->> (.listFiles conf-dir)
                           (filter #(.isDirectory %))
                           (map #(.getName %))
                           sort))
                    [(:env opts)])
          all-summaries (mapv (fn [env]
                                (let [results (run-checks env opts)]
                                  (if (:edn opts)
                                    (do (report/print-edn :config results)
                                        (report/tally results))
                                    (report/print-results (str "Wagoe Config Doctor — " env)
                                                          results))))
                              envs)
          total-errors (reduce + (map :errors all-summaries))]
      (when (and (:ci opts) (pos? total-errors))
        (System/exit 1)))))

;; Run when executed directly (not via bb.edn task)
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
