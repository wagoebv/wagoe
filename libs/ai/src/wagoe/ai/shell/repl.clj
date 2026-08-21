(ns wagoe.ai.shell.repl
  "REPL helper functions for the AI module.

   Provides convenient wrappers for use during interactive development:

     (require '[wagoe.ai.shell.repl :as ai])
     (ai/explain *e)
     (ai/sql \"find active users with orders in the last 7 days\")

   The service is resolved from a dynamic var that you can bind
   in your dev/user.clj before using these helpers."
  (:require [wagoe.ai.shell.service :as svc]
            [clojure.string :as str]))

;; =============================================================================
;; Service binding
;; =============================================================================

(def ^:dynamic *ai-service*
  "Dynamic var holding the AI service map.
   Bind this in your REPL session or dev/user.clj:

     (alter-var-root #'wagoe.ai.shell.repl/*ai-service*
                     (constantly (integrant.repl.state/system :wagoe/ai-service)))"
  nil)

(defn set-service!
  "Set the global REPL AI service (convenience for REPL sessions).

   Args:
     service - AIService map from Integrant system

   Returns:
     The service map."
  [service]
  (alter-var-root #'*ai-service* (constantly service))
  service)

(defn- require-service []
  (or *ai-service*
      (throw (ex-info "No AI service bound. Call (ai/set-service! system-service) first."
                      {:type :configuration-error
                       :hint "Try: (ai/set-service! (integrant.repl.state/system :wagoe/ai-service))"}))))

;; =============================================================================
;; Feature 2: Error Explainer REPL wrapper
;; =============================================================================

(defn explain
  "Explain a Clojure exception or stack trace string.

   Usage:
     (ai/explain *e)
     (ai/explain \"clojure.lang.ExceptionInfo: ...\\n\\tat ...\")

   Args:
     exception-or-string - Exception or stack trace string

   Returns:
     Prints explanation to stdout, returns the raw text."
  ([exception-or-string]
   (explain exception-or-string "."))
  ([exception-or-string project-root]
   (let [service    (require-service)
         stacktrace (if (instance? Throwable exception-or-string)
                      (let [sw (java.io.StringWriter.)
                            pw (java.io.PrintWriter. sw)]
                        (.printStackTrace exception-or-string pw)
                        (.toString sw))
                      (str exception-or-string))
         result     (svc/explain-error service stacktrace project-root)]
     (if (:error result)
       (println "\033[31mAI Error:\033[0m" (:error result))
       (println "\n\033[1m=== AI Error Explanation ===\033[0m\n"
                (:text result)
                "\n\n\033[2m[" (:provider result) "/" (:model result)
                " — " (:tokens result) " tokens]\033[0m"))
     (:text result))))

;; =============================================================================
;; Feature 4: SQL Copilot REPL wrapper
;; =============================================================================

(defn sql
  "Generate a HoneySQL query from a natural language description.

   Usage:
     (ai/sql \"find active users with orders in the last 7 days\")

   Args:
     description  - NL description string
     project-root - optional project root path (default \".\")

   Returns:
     Prints HoneySQL + explanation, returns the result map."
  ([description]
   (sql description "."))
  ([description project-root]
   (let [service (require-service)
         result  (svc/sql-from-description service description project-root)]
     (if (:error result)
       (println "\033[31mAI Error:\033[0m" (:error result))
       (do
         (println "\n\033[1m=== HoneySQL ===\033[0m")
         (println (:honeysql result))
         (println "\n\033[1m=== Explanation ===\033[0m")
         (println (:explanation result))
         (println "\n\033[1m=== Raw SQL ===\033[0m")
         (println (:raw-sql result))))
     result)))

;; =============================================================================
;; Feature 3: Test Generator REPL wrapper
;; =============================================================================

(defn gen-tests
  "Generate tests for a source file and print to stdout.

   Usage:
     (ai/gen-tests \"libs/user/src/wagoe/user/core/validation.clj\")

   Args:
     source-path - path to the source file

   Returns:
     Generated test source string."
  [source-path]
  (let [service (require-service)
        result  (svc/generate-tests service source-path)]
    (if (:error result)
      (println "\033[31mAI Error:\033[0m" (:error result))
      (do
        (println "\n\033[1m=== Generated Tests ===\033[0m\n")
        (println (:text result))
        (println "\n\033[2m[" (:provider result) "/" (:model result)
                 " — " (:tokens result) " tokens]\033[0m")))
    (:text result)))

;; =============================================================================
;; Feature 8: Code Review REPL wrapper
;; =============================================================================

(defn review
  "AI code review of a source string or file path.
   (ai/review \"(ns my.ns)\\n(defn foo [] ...)\")
   (ai/review \"libs/user/src/wagoe/user/core/validation.clj\")"
  [source-or-path]
  (let [service (require-service)
        source  (if (and (string? source-or-path)
                         (.exists (java.io.File. source-or-path)))
                  (slurp source-or-path)
                  (str source-or-path))
        ns-name (or (second (re-find #"\(ns\s+([^\s\)]+)" source))
                    "unknown")
        result  (svc/review-code service ns-name source)]
    (if (:error result)
      (println "\033[31mAI Error:\033[0m" (:error result))
      (println "\n\033[1m=== AI Code Review ===\033[0m\n"
               (:text result)
               "\n\n\033[2m[" (:provider result) "/" (:model result)
               " \u2014 " (:tokens result) " tokens]\033[0m"))
    (:text result)))

;; =============================================================================
;; Feature 9: Test Ideas REPL wrapper
;; =============================================================================

(defn test-ideas
  "Suggest missing test cases for source code or file path.
   (ai/test-ideas \"(ns my.ns)\\n(defn foo [] ...)\")
   (ai/test-ideas \"libs/user/src/wagoe/user/core/validation.clj\")"
  [source-or-path]
  (let [service (require-service)
        source  (if (and (string? source-or-path)
                         (.exists (java.io.File. source-or-path)))
                  (slurp source-or-path)
                  (str source-or-path))
        ns-name (or (second (re-find #"\(ns\s+([^\s\)]+)" source))
                    "unknown")
        test-source (when-let [match (re-find #"\(ns\s+(\S+)" source)]
                      (let [test-ns  (str (second match) "-test")
                            test-rel (-> test-ns
                                         (str/replace "." "/")
                                         (str/replace "-" "_")
                                         (str ".clj"))
                            ;; Extract lib name from ns (e.g. "user" from "wagoe.user.core.validation")
                            lib-name (second (str/split (second match) #"\."))]
                        ;; Try: libs/<lib>/test/<path>, test/<path>, <path>
                        (some #(try (slurp %) (catch Exception _ nil))
                              [(str "libs/" lib-name "/test/" test-rel)
                               (str "test/" test-rel)
                               test-rel])))
        result  (svc/suggest-tests service ns-name source test-source)]
    (if (:error result)
      (println "\033[31mAI Error:\033[0m" (:error result))
      (println "\n\033[1m=== Missing Test Ideas ===\033[0m\n"
               (:text result)
               "\n\n\033[2m[" (:provider result) "/" (:model result)
               " \u2014 " (:tokens result) " tokens]\033[0m"))
    (:text result)))

;; =============================================================================
;; Feature 10: FC/IS Refactoring REPL wrapper
;; =============================================================================

(defn refactor-fcis
  "AI-guided FC/IS refactoring for a namespace.
   (ai/refactor-fcis 'wagoe.product.core.validation)"
  [ns-sym]
  (let [service  (require-service)
        ns-str   (str ns-sym)
        file-path (-> ns-str
                      (str/replace "." "/")
                      (str/replace "-" "_")
                      (str ".clj"))
        source   (some #(try (slurp (str % "/" file-path)) (catch Exception _ nil))
                       ["src" "libs"])
        source   (or source
                     (let [parts (str/split ns-str #"\.")
                           lib-name (second parts)]
                       (try (slurp (str "libs/" lib-name "/src/" file-path))
                            (catch Exception _ nil))))
        _        (when-not source
                   (throw (ex-info (str "Cannot find source for " ns-sym)
                                   {:type :not-found :ns ns-sym :tried file-path})))
        violations (let [requires (re-seq #"\[(\S+\.shell\.\S+)" (or source ""))]
                     (mapv (fn [[_ dep]] {:from ns-str :to dep}) requires))]
    (if (empty? violations)
      (do (println (str "\033[32m\u2713 No FC/IS violations found in " ns-str "\033[0m"))
          nil)
      (let [result (svc/refactor-fcis service ns-str source violations)]
        (if (:error result)
          (println "\033[31mAI Error:\033[0m" (:error result))
          (println "\n\033[1m=== FC/IS Refactoring Guide ===\033[0m\n"
                   (:text result)
                   "\n\n\033[2m[" (:provider result) "/" (:model result)
                   " \u2014 " (:tokens result) " tokens]\033[0m"))
        (:text result)))))

;; =============================================================================
;; Help
;; =============================================================================

(defn help []
  (println
   "\033[1mWagoe AI REPL Helpers\033[0m

First, bind the service:
  (require '[wagoe.ai.shell.repl :as ai])
  (ai/set-service! (integrant.repl.state/system :wagoe/ai-service))

Commands:
  (ai/explain *e)                    \u2014 explain last exception
  (ai/sql \"description\")           \u2014 generate HoneySQL
  (ai/gen-tests \"path/to/file.clj\") \u2014 generate test namespace
  (ai/review \"path/to/file.clj\")   \u2014 AI code review
  (ai/test-ideas \"path/to/file.clj\") \u2014 suggest missing tests
  (ai/refactor-fcis 'ns.symbol)    \u2014 FC/IS refactoring guide"))
