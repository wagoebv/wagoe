(ns wagoe.scaffolder.cli-test
  "BOU-259 removed the `new` verb — project generation lives in libs/wagoe-cli.
   The verb is kept only to redirect, and it must fail so automation that still
   calls it cannot read the redirect as a generated project.

   `--help` was the hole: the global help branch ran before the removed-command
   branch, so `scaffolder new --help` printed root help and exited 0 while every
   other form of `new` exited 1. Anything probing help for availability saw a
   working command."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.tools.cli]
            [wagoe.scaffolder.ports]
            [wagoe.scaffolder.cli :as cli]))

(defn- run
  "Run the CLI with no service — the removed-command and help branches are
   reached before any service call. Returns {:out ... :status ...}."
  [& args]
  (let [status (atom nil)
        out    (with-out-str (reset! status (cli/run-cli! nil (vec args))))]
    {:out out :status @status}))

(deftest ^:unit removed-new-verb-always-redirects-and-fails
  (testing "bare `new` redirects and exits non-zero"
    (let [{:keys [out status]} (run "new")]
      (is (= 1 status))
      (is (str/includes? out "wagoe new"))))

  (testing "`new` with arguments redirects and exits non-zero"
    (let [{:keys [out status]} (run "new" "--name" "my-app")]
      (is (= 1 status))
      (is (str/includes? out "wagoe new"))))

  (testing "`new --help` redirects too, rather than printing root help"
    ;; The regression: global help matched first, so this exited 0 with root
    ;; help and looked like a supported command.
    (let [{:keys [out status]} (run "new" "--help")]
      (is (= 1 status)
          "a removed command must not succeed just because --help was passed")
      (is (str/includes? out "wagoe new")
          "must name the replacement, not print root help")
      (is (str/includes? out "has been removed"))))

  (testing "`-h` behaves the same as `--help`"
    (is (= 1 (:status (run "new" "-h"))))))

(deftest ^:unit help-still-works-for-live-commands
  (testing "global help with no verb still succeeds"
    (let [{:keys [out status]} (run "--help")]
      (is (= 0 status))
      (is (str/includes? out "Usage:"))))

  (testing "no arguments at all still shows root help"
    (is (= 0 (:status (run)))))

  (testing "root help does not advertise the removed command"
    (let [{:keys [out]} (run "--help")]
      (is (not (re-find #"(?m)^\s+new\s" out))
          "root help must not list `new` as a command")
      (is (str/includes? out "wagoe new")
          "it should still point at the CLI that does create projects"))))

;; =============================================================================
;; BOU-275: the field command reaches the service's output-dir support
;; =============================================================================

(deftest ^:unit field-accepts-and-forwards-output-dir
  ;; `generate` has always taken --output-dir; `field` did not, so
  ;;   bb scaffold field --output-dir /tmp/app …
  ;; failed with `Unknown option: "--output-dir"` and the service's support for
  ;; it was unreachable from the command users run. Both write migrations, so
  ;; there is no reason for them to differ.
  (testing "the option parses"
    (let [{:keys [errors options]}
          (clojure.tools.cli/parse-opts
           ["--module-name" "box" "--entity" "Box" "--name" "h" "--type" "string"
            "--output-dir" "/tmp/app"]
           cli/field-options)]
      (is (nil? errors) "the flag must exist, not be rejected as unknown")
      (is (= "/tmp/app" (:output-dir options)))))

  (testing "and reaches the request"
    ;; A parsed option that is never put in the request is the same as no
    ;; option at all — which is how --output-dir behaved for `generate` before
    ;; this ticket.
    (let [seen (atom nil)
          ;; The whole protocol, not just add-field: a partial reify throws
          ;; AbstractMethodError if anything reaches another method, which
          ;; would surface as an unrelated failure rather than a clear one.
          svc  (reify wagoe.scaffolder.ports/IScaffolderService
                 (generate-module [_ _] (throw (ex-info "not under test" {})))
                 (add-endpoint [_ _] (throw (ex-info "not under test" {})))
                 (add-adapter [_ _] (throw (ex-info "not under test" {})))
                 (add-field [_ request]
                   (reset! seen request)
                   {:success true :module-name "box" :files []}))]
      (cli/execute-field svc {:module-name "box" :entity "Box" :name "h"
                              :type "string" :output-dir "/tmp/app"})
      (is (= "/tmp/app" (:output-dir @seen)))))

  (testing "the default is the working directory"
    (let [{:keys [options]}
          (clojure.tools.cli/parse-opts
           ["--module-name" "box" "--entity" "Box" "--name" "h" "--type" "string"]
           cli/field-options)]
      (is (= "." (:output-dir options))))))
