(ns wagoe.observability.logging.shell.adapters.stdout-test
  (:require [clojure.test :refer [deftest is]]
            [wagoe.observability.logging.shell.adapters.stdout :as stdout]
            [wagoe.observability.logging.ports :as ports]
            [clojure.string :as str]
            [cheshire.core :as json]))

(deftest ^:unit text-format-simple
  (let [logger (stdout/create-stdout-logger {:level :debug
                                             :format :text
                                             :include-timestamp false
                                             :include-level true
                                             :include-thread false
                                             :colors false
                                             :default-tags {:svc "tst"}})
        out (with-out-str
              (println "--capture start--")
              (.info logger "hello world" {:foo "bar"}))
        captured (->> out
                      str/split-lines
                      (remove #(= % "--capture start--")))
        line (first captured)]
    (is (some? line))
    (is (str/includes? line "INFO"))
    (is (str/includes? line "hello world"))
    (is (str/includes? line "foo=\"bar\""))))

(deftest ^:unit json-format-parseable
  (let [logger (stdout/create-stdout-logger {:level :debug
                                             :format :json
                                             :include-timestamp false
                                             :include-level false
                                             :include-thread false
                                             :default-tags {:svc "tst"}})
        out (with-out-str
              (.info logger "json msg" {:x 1 :y 2}))
        line (-> out str/trim)
        parsed (json/parse-string line true)]
    (is (= "json msg" (:message parsed)))
    (is (= "info" (:level parsed)))
    (is (= {:x 1 :y 2 :svc "tst"} (:context parsed)))))

(deftest ^:unit level-filtering
  (let [logger (stdout/create-stdout-logger {:level :info
                                             :format :text
                                             :include-timestamp false
                                             :include-level false
                                             :include-thread false
                                             :colors false})
        out (with-out-str
              (.debug logger "should not appear")
              (.info logger "should appear"))
        lines (->> out str/split-lines (remove str/blank?))]
    (is (= 1 (count lines)))
    (is (some #(str/includes? % "should appear") lines))))

(deftest ^:unit with-context-merges
  (let [logger (stdout/create-stdout-logger {:level :debug
                                             :format :json
                                             :include-timestamp false
                                             :include-level false
                                             :default-tags {:svc "tst"}})
        out (with-out-str
              (.with-context logger {:request-id "r1"}
                             (fn []
                               (.info logger "ctx test" {:user "u1"}))))
        parsed (json/parse-string (str/trim out) true)]
    (is (= "ctx test" (:message parsed)))
    (is (= "r1" (get-in parsed [:context :request-id])))
    (is (= "u1" (get-in parsed [:context :user])))))

(deftest ^:unit exception-logging-includes-stacktrace
  (let [logger (stdout/create-stdout-logger {:level :error
                                             :format :json
                                             :include-timestamp false
                                             :include-level false
                                             :include-thread false
                                             :max-stacktrace-elements 2
                                             :default-tags {:svc "tst"}})
        ex (Exception. "boom")
        out (with-out-str
              (.error logger "exception test" {:op "x"} ex))
        line (-> out str/trim)
        parsed (json/parse-string line true)
        ctx (:context parsed)]
    (is (= "exception test" (:message parsed)))
    (is (= "error" (:level parsed)))
    (is (= "boom" (:exception-message ctx)))
    (is (= "Exception" (:exception-type ctx)))
    (is (<= (count (:stack-trace ctx)) 2))))

(deftest ^:unit sanitizes-sensitive-context-keys
  (let [logger (stdout/create-stdout-logger {:level :info
                                             :format :json
                                             :include-timestamp false
                                             :include-level false
                                             :include-thread false
                                             :default-tags {:svc "tst"}})
        out (with-out-str
              (.info logger "sanitize test"
                     {:user-id "u1"
                      :password "secret"
                      :token "t123"
                      :secret "s456"
                      :api-key "k789"}))
        parsed (json/parse-string (str/trim out) true)
        ctx (:context parsed)]
    (is (= "u1" (:user-id ctx)))
    (doseq [k [:password :token :secret :api-key]]
      (is (= "***REDACTED***" (get ctx k))))))

(deftest ^:unit json-format-via-json-flag
  (let [logger (stdout/create-stdout-logger {:level :debug
                                             :json true
                                             :include-timestamp false
                                             :include-level false
                                             :include-thread false
                                             :default-tags {:svc "tst"}})
        out (with-out-str
              ;; use protocol to ensure ILogger implementation works
              (ports/info logger "json flag msg" {:k 1}))
        parsed (json/parse-string (str/trim out) true)]
    (is (= "json flag msg" (:message parsed)))
    (is (= "info" (:level parsed)))
    (is (= {:k 1 :svc "tst"} (:context parsed)))))

(deftest ^:unit stdout-logger-implements-protocols
  (let [logger (stdout/create-stdout-logger {:level :info})]
    (is (satisfies? ports/ILogger logger))
    (is (satisfies? ports/IAuditLogger logger))
    (is (satisfies? ports/ILoggingContext logger))
    (is (satisfies? ports/ILoggingConfig logger))
    ;; basic audit/security calls should not throw and should return nil
    (is (nil? (ports/audit-event logger :user-action "actor" "resource" :create :success {})))
    (is (nil? (ports/security-event logger :login-attempt :high {} {})))))

(deftest ^:unit stdout-logger-level-and-config-management
  (let [logger (stdout/create-stdout-logger {:level :info
                                             :format :text})]
    ;; level management
    (is (= :info (ports/get-level logger)))
    (let [prev (ports/set-level! logger :debug)]
      (is (= :info prev))
      (is (= :debug (ports/get-level logger))))

    ;; config management
    (let [orig-config (ports/get-config logger)
          prev-config (ports/set-config! logger {:extra "value"})
          new-config  (ports/get-config logger)]
      (is (= orig-config prev-config))
      (is (= "value" (:extra new-config))))))
