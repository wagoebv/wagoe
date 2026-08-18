(ns wagoe.devtools.shell.project-repl-test
  "The helpers a generated project's dev/user.clj delegates to.

   They are tested here rather than in the template because a template is not
   compiled by anything until someone generates a project and boots it — which
   is how `bb quickstart` came to close by telling users to run two commands
   that did not exist (BOU-319)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.devtools.shell.project-repl :as sut]))

(deftest ^:unit modules-are-the-application-keys-not-the-plumbing
  ;; `(status)` and `(modules)` exist to answer "is my module running". Listing
  ;; :wagoe/http-server and :wagoe/logging alongside it buries the answer in
  ;; fifteen names the user did not write.
  (let [system {:wagoe/settings     {}
                :wagoe/db-context   {}
                :wagoe/http-server  {}
                :wagoe/http-handler {}
                :wagoe/logging      {}
                :wagoe/tasks        {}
                :wagoe/invoices     {}
                :some.other/thing   {}}]
    (is (= ["invoices" "tasks"] (sut/module-names system)))))

(deftest ^:unit a-system-that-is-not-running-has-no-report
  ;; nil, not an empty dashboard: the caller prints "start it with (go)".
  (is (nil? (sut/status-report nil nil))))

(deftest ^:unit the-report-names-the-url-you-can-actually-open
  (let [config {:wagoe/http {:host "0.0.0.0" :port 3001}}
        report (sut/status-report {:wagoe/tasks {}} config)]
    (testing "0.0.0.0 is what the server binds, not an address you can open"
      (is (str/includes? report "http://localhost:3001"))
      (is (not (str/includes? report "http://0.0.0.0"))))

    (testing "and the module is named"
      (is (str/includes? report "tasks")))

    (testing "no admin key, no admin URL"
      (is (not (str/includes? report "Admin:"))))))

(deftest ^:unit the-admin-url-follows-the-configured-base-path
  (let [report (sut/status-report {:wagoe/admin {}}
                                  {:wagoe/http  {:port 3000}
                                   :wagoe/admin {:base-path "/backoffice"}})]
    (is (str/includes? report "/backoffice"))))

(deftest ^:unit the-palette-lists-what-a-generated-project-has
  ;; The monorepo palette lists (lint), (check-all), (scaffold!) and the ai/*
  ;; helpers. None of them exist in a generated dev/user.clj, and a palette
  ;; naming commands the project does not have is the same defect as no
  ;; palette.
  (let [names (->> (vals sut/command-groups) (apply concat) (map :name) set)]
    (testing "the commands quickstart tells the user to run"
      (is (contains? names "(status)"))
      (is (contains? names "(commands)")))

    (testing "and nothing that only exists in the framework repo"
      (doseq [absent ["(lint)" "(check-all)" "(scaffold!)" "(test-module :mod)"]]
        (is (not (contains? names absent))
            (str absent " is not in a generated project"))))))

(deftest ^:unit fix-without-an-error-says-so-rather-than-throwing
  (let [out (with-out-str (sut/fix! nil))]
    (is (str/includes? out "No recent error"))))
