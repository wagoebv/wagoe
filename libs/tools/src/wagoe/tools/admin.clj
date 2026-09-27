#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/admin.clj
;;
;; Interactive wizard to create the first admin user for a new Wagoe project.
;;
;; Usage (via bb.edn task):
;;   bb create-admin                                         -- interactive wizard
;;   bb create-admin --env prod                              -- use production config
;;   bb create-admin --email a@b.com --name "Admin"         -- skip email/name prompts
;;   bb create-admin --dir examples/ecommerce-api           -- target a sub-project

(ns wagoe.tools.admin
  (:require [wagoe.tools.ansi :refer [bold green red cyan dim]]
            [clojure.string :as str]
            [babashka.process :as p]))

;; =============================================================================
;; Input helpers
;; =============================================================================

(def ^:private max-attempts 3)

(defn- fail!
  "End the wizard with `msg`. -main prints it and exits 1."
  [msg]
  (throw (ex-info msg {:type :validation-error})))

(defn- prompt
  "Read one trimmed line, or nil at end of input."
  [label]
  (print (str (cyan "? ") (bold label) ": "))
  (flush)
  (some-> (read-line) str/trim))

(defn- ask
  "Return `given` if set, else prompt for `label` up to max-attempts times.
   `problem` returns an error message for a bad value, or nil. A bad `given`
   fails at once: re-checking a flag's value cannot change it (BOU-565)."
  [given label problem]
  (if given
    (if-let [msg (problem given)] (fail! msg) given)
    (loop [attempt 1]
      (let [input (prompt label)]
        (when (nil? input)
          (fail! (str "stdin closed before " label " was entered.")))
        (let [msg (problem input)]
          (cond
            (nil? msg)                input
            (>= attempt max-attempts) (fail! msg)
            :else (do (println (red (str "  " msg))) (recur (inc attempt)))))))))

(defn- read-password-once
  "Read one password, or nil at end of input. Echoes nothing when given a
   console; falls back to read-line when `console` is nil.

   That fallback is load-bearing, not incidental: `bb create-admin` is driven
   non-interactively by the wagoe-setup skill, which pipes the password on
   stdin (BOU-236). Pinned by admin_test.clj — do not make a console mandatory.

   The console is a parameter rather than a `System/console` lookup so the
   no-console branch is reachable in a test regardless of whether the test JVM
   happens to be attached to a terminal. Looking it up inline made the test
   pass under CI and block on a real console locally."
  [label console]
  (if console
    (some-> (.readPassword console (str label ": ") (into-array Object [])) String.)
    (do (print (str label ": ")) (flush) (some-> (read-line) str/trim))))

(defn- password-problem [p confirm]
  (cond
    (str/blank? p)   "Password cannot be empty."
    (not= p confirm) "Passwords do not match."
    (< (count p) 8)  "Password must be at least 8 characters."))

(def ^:private eof-message
  "No password on stdin. Pipe one line: printf '%s\\n' \"$PW\" | bb create-admin ...")

(defn- read-admin-password
  "Piped stdin (`read-secret` nil): read one line and accept or reject it.
   Interactive: `read-secret` is label -> string, or nil at EOF; ask twice and
   re-prompt at most max-attempts times."
  [read-secret]
  (if-not read-secret
    (let [p (read-password-once "Password" nil)]
      (when (nil? p) (fail! eof-message))
      (if-let [msg (password-problem p p)] (fail! msg) p))
    (loop [attempt 1]
      (let [p       (read-secret "Password")
            confirm (when p (read-secret "Confirm password"))]
        (when (nil? confirm) (fail! "Password entry ended before it was confirmed."))
        (let [msg (password-problem p confirm)]
          (cond
            (nil? msg)                p
            (>= attempt max-attempts) (fail! (str msg " Giving up after " max-attempts " attempts."))
            :else (do (println (red (str "  " msg))) (recur (inc attempt)))))))))

(defn- valid-email? [s]
  (boolean (re-matches #"^[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}$" s)))

;; =============================================================================
;; Argument parsing
;; =============================================================================

(defn- parse-args [args]
  (loop [[flag & more :as remaining] args
         opts {}]
    (cond
      (empty? remaining) opts
      (= flag "--help")  (recur more (assoc opts :help true))
      (= flag "-h")      (recur more (assoc opts :help true))
      (and (str/starts-with? flag "--") (seq more))
      (recur (rest more) (assoc opts (keyword (subs flag 2)) (first more)))
      :else (recur more opts))))

;; =============================================================================
;; Help
;; =============================================================================

(defn- print-help []
  (println (bold "bb create-admin") "\u2014 Create initial admin user for a Wagoe project")
  (println)
  (println (bold "Usage:"))
  (println "  bb create-admin                            Interactive wizard (root project)")
  (println "  bb create-admin --dir examples/ecommerce-api  Target a sub-project")
  (println "  bb create-admin --env prod                 Use production config")
  (println "  bb create-admin --email EMAIL --name NAME  Skip email/name prompts")
  (println)
  (println (bold "Options:"))
  (println "  --email EMAIL    Admin user email address")
  (println "  --name  NAME     Admin user full name")
  (println "  --env   ENV      Config environment: dev (default), test, acc, prod")
  (println "  --dir   DIR      Run from this directory (for sub-projects)")
  (println "  -h, --help       Show this help")
  (println)
  (println (bold "Notes:"))
  (println "  The password is read from a secure prompt (not echoed) and confirmed.")
  (println "  With stdin piped, it is read once, from the first line:")
  (println "    printf '%s\\n' \"$PW\" | bb create-admin --email EMAIL --name NAME")
  (println "  Run database migrations first: clojure -M:migrate up"))

;; =============================================================================
;; User CLI
;; =============================================================================

(def ^:private quiet-logback
  "Logging config for the user CLI subprocess. Its schema setup logs every DDL
   statement, and a project whose logback.xml writes to stdout printed them all
   over the wizard (BOU-565). Warnings and errors still reach stderr."
  "<configuration>
  <appender name=\"STDERR\" class=\"ch.qos.logback.core.ConsoleAppender\">
    <target>System.err</target>
    <encoder><pattern>%-5level %logger{36} - %msg%n</pattern></encoder>
  </appender>
  <root level=\"WARN\"><appender-ref ref=\"STDERR\"/></root>
</configuration>
")

(defn- user-cli-command [email name logback-path]
  ["clojure"
   (str "-J-Dlogback.configurationFile=" logback-path)
   "-M:user-cli"
   "create"
   "--email" email
   "--name"  name
   "--role"  "admin"
   "--password-prompt"])

(defn- create-user! [{:keys [email name password env dir]}]
  (let [logback (java.io.File/createTempFile "create-admin-logback" ".xml")]
    (try
      (spit logback quiet-logback)
      (apply p/shell
             (cond-> {:continue true
                      :in (str password "\n")
                      :env (assoc (into {} (System/getenv)) "WAG_ENV" env)}
               dir (assoc :dir dir))
             (user-cli-command email name (.getAbsolutePath logback)))
      (finally (.delete logback)))))

;; =============================================================================
;; Main entry point
;; =============================================================================

(defn- create-admin!
  "Run the wizard; returns the exit code."
  [opts]
  (let [email (ask (:email opts) "Admin email address"
                   #(cond (str/blank? %)         "Email is required."
                          (not (valid-email? %)) "Not a valid email address."))
        name  (ask (:name opts) "Full name"
                   #(when (str/blank? %) "Name is required."))
        env   (or (:env opts) "dev")
        dir   (:dir opts)]

    (println)
    (println (bold "Summary"))
    (println (str "  Email  : " (cyan email)))
    (println (str "  Name   : " (cyan name)))
    (println (str "  Role   : " (cyan "admin")))
    (println (str "  Config : " (cyan env)))
    (when dir
      (println (str "  Dir    : " (cyan dir))))
    (println)

    (let [password (read-admin-password
                    (when-let [console (System/console)]
                      #(read-password-once % console)))
          result   (create-user! {:email email :name name :password password
                                  :env env :dir dir})]
      (if (zero? (:exit result))
        (do
          (println)
          (println (green (bold "Admin user created successfully.")))
          (println (dim (str "  You can now log in at your application with: " email)))
          0)
        (do
          (println)
          (println (red (bold "Failed to create admin user.")))
          (println (dim "  See the output above for details."))
          1)))))

(defn -main [& args]
  (let [opts (parse-args args)]

    (when (:help opts)
      (print-help)
      (System/exit 0))

    (println)
    (println (bold (cyan "Wagoe \u2014 Create Admin User")))
    (println (dim "Sets up the first administrator account for your project."))
    (println)

    (let [exit (try
                 (create-admin! opts)
                 (catch clojure.lang.ExceptionInfo e
                   (if (= :validation-error (:type (ex-data e)))
                     (binding [*out* *err*]
                       (println (red (str "Error: " (ex-message e))))
                       1)
                     (throw e))))]
      (when-not (zero? exit)
        (System/exit exit)))))

;; Run when executed directly (not via bb.edn task)
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
