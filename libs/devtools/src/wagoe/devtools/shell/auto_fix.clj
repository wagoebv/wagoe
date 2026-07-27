(ns wagoe.devtools.shell.auto-fix
  "Execute fix descriptors — side-effecting operations.
   This is a shell namespace: it runs migrations, sets env vars, etc.
   The safety gate (safe? false → always confirm) is never overridden by guidance level."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(defmulti run-action!
  "Execute a specific fix action. Dispatches on :action keyword."
  (fn [action _params] action))

(defmethod run-action! :migrate-up
  [_ _params]
  (println "Running: bb migrate up")
  (let [{:keys [exit out err]} (shell/sh "bb" "migrate" "up")]
    (when (seq out) (println out))
    (when (and (seq err) (not (zero? exit))) (println err))
    (zero? exit)))

(defn- write-env-var!
  "Write var=value to .env in the project root.
   Creates the file if it doesn't exist; appends if it does,
   replacing any existing line for the same variable.
   Returns true on success."
  [var-name value]
  (try
    (let [env-file (io/file ".env")
          existing (when (.exists env-file) (slurp env-file))
          prefix   (str var-name "=")
          ;; Also match quoted form: VAR="value"
          q-prefix (str var-name "=\"")
          filtered (when existing
                     (->> (str/split-lines existing)
                          (remove #(or (str/starts-with? % prefix)
                                       (str/starts-with? % q-prefix)))
                          (str/join "\n")))
          content  (str (when (and filtered (not (str/blank? filtered)))
                          (str filtered "\n"))
                        var-name "=\"" value "\"\n")]
      (spit env-file content)
      (println (str "Written to .env: " var-name "=..."))
      (println "Restart the REPL to pick it up:")
      (println (str "  source .env && clojure -M:repl-clj"))
      true)
    (catch Exception e
      (println (str "Failed to write .env: " (.getMessage e)))
      false)))

(defmethod run-action! :set-env
  [_ {:keys [var-name value required-env-var]}]
  (let [env-name (or var-name required-env-var)]
    (if-not env-name
      (do (println "No environment variable name found in error data.")
          false)
      (if value
        (write-env-var! env-name value)
        (do (print (str "Value for " env-name ": "))
            (flush)
            (if-let [input (not-empty (read-line))]
              (write-env-var! env-name input)
              (do (println "No value provided. Aborted.")
                  false)))))))

(defmethod run-action! :set-jwt
  [_ _params]
  (let [secret (str "dev-secret-" (System/currentTimeMillis) "-boundary")]
    (write-env-var! "JWT_SECRET" secret)))

(defmethod run-action! :integrate-module
  [_ {:keys [module-name]}]
  (when module-name
    (println (str "Running: bb scaffold integrate " module-name))
    (let [{:keys [exit out err]} (shell/sh "bb" "scaffold" "integrate" (name module-name))]
      (when (seq out) (println out))
      (when (and (seq err) (not (zero? exit))) (println err))
      (zero? exit))))

(defmethod run-action! :add-dependency
  [_ {:keys [lib version]}]
  (println "Suggested addition to deps.edn:")
  (println (str "  " lib " {:mvn/version \"" (or version "LATEST") "\"}"))
  (println "Please add this manually to the appropriate deps.edn file.")
  true)

(defmethod run-action! :show-refactoring
  [_ {:keys [source-ns requires-ns]}]
  (println "FC/IS Refactoring Steps:")
  (println "  1. Create a protocol in ports.clj for the data you need")
  (println "  2. Move the shell dependency behind the protocol")
  (println "  3. Have the shell namespace implement the protocol")
  (when source-ns
    (println (str "  Source: " source-ns))
    (println (str "  Remove require: " requires-ns)))
  (println (str "\n  Or try: (ai/refactor-fcis '" source-ns ")"))
  true)

(defmethod run-action! :default
  [action _params]
  (println (str "Unknown fix action: " action))
  false)

(defn execute-fix!
  "Execute a fix descriptor with safety/confirmation logic.
   opts:
     :guidance-level — :full, :minimal, or :off
     :confirm-fn     — (fn [prompt] => boolean), for risky fixes"
  [{:keys [label safe? action params]} {:keys [guidance-level confirm-fn]}]
  (let [should-confirm? (not safe?)
        should-print?   (not= guidance-level :minimal)]
    (if should-confirm?
      (if (and confirm-fn (confirm-fn (str "Apply fix: " label "?")))
        (let [_ (when should-print? (println (str "Applying: " label)))
              result (run-action! action params)]
          (when (and should-print? (not result))
            (println "Fix could not be applied automatically."))
          result)
        (println "Aborted."))
      (let [_ (when should-print? (println (str "Applying: " label)))
            result (run-action! action params)]
        (when (and should-print? (not result))
          (println "Fix could not be applied automatically."))
        result))))
