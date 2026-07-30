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

(defn- prompt [label]
  (print (str (cyan "? ") (bold label) ": "))
  (flush)
  (str/trim (or (read-line) "")))

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
  (println "  The password is always collected via a secure prompt (not echoed).")
  (println "  Run database migrations first: clojure -M:migrate up"))

;; =============================================================================
;; Main entry point
;; =============================================================================

(defn -main [& args]
  (let [opts (parse-args args)]

    (when (:help opts)
      (print-help)
      (System/exit 0))

    (println)
    (println (bold (cyan "Wagoe \u2014 Create Admin User")))
    (println (dim "Sets up the first administrator account for your project."))
    (println)

    (let [email (loop []
                  (let [input (or (:email opts) (prompt "Admin email address"))]
                    (cond
                      (str/blank? input)    (do (println (red "  Email is required.")) (recur))
                      (not (valid-email? input)) (do (println (red "  Not a valid email address.")) (recur))
                      :else input)))

          name  (loop []
                  (let [input (or (:name opts) (prompt "Full name"))]
                    (if (str/blank? input)
                      (do (println (red "  Name is required.")) (recur))
                      input)))

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

      (let [password (loop []
                       (let [read-pw  (fn [label]
                                        (if-let [c (System/console)]
                                          (String. (.readPassword c (str label ": ") (into-array Object [])))
                                          (do (print (str label ": ")) (flush) (str/trim (or (read-line) "")))))
                             p        (read-pw "Password")
                             confirm  (read-pw "Confirm password")]
                         (cond
                           (str/blank? p)   (do (println (red "  Password cannot be empty.")) (recur))
                           (not= p confirm) (do (println (red "  Passwords do not match.")) (recur))
                           (< (count p) 8)  (do (println (red "  Password must be at least 8 characters.")) (recur))
                           :else p)))

            shell-opts (cond-> {:continue true
                                :in (str password "\n")
                                :env (assoc (into {} (System/getenv)) "WAG_ENV" env)}
                         dir (assoc :dir dir))

            result (p/shell
                    shell-opts
                    "clojure"
                    "-M:user-cli"
                    "create"
                    "--email" email
                    "--name"  name
                    "--role"  "admin"
                    "--password-prompt")]
        (if (zero? (:exit result))
          (do
            (println)
            (println (green (bold "Admin user created successfully.")))
            (println (dim (str "  You can now log in at your application with: " email))))
          (do
            (println)
            (println (red (bold "Failed to create admin user.")))
            (println (dim "  See the output above for details."))
            (System/exit 1)))))))

;; Run when executed directly (not via bb.edn task)
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
