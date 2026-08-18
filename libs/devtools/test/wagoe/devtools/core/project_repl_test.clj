(ns wagoe.devtools.core.project-repl-test
  "Module detection and the palette a generated project gets.

   Tested here rather than through the template because a template is not
   compiled by anything until someone generates a project and boots it — which
   is how `bb quickstart` came to close by naming two commands that did not
   exist (BOU-319)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.devtools.core.project-repl :as sut]))

(def ^:private fresh-project
  "Every Integrant key `wagoe new demo` produces, measured — not invented.

   The first version of this test used `{:wagoe/http {...}}` and
   `{:wagoe/admin {...}}`, neither of which `ig-config` emits, so it asserted
   on branches that could never run in a real project."
  [:wagoe/audit-repository :wagoe/auth-service :wagoe/db-context :wagoe/email
   :wagoe/error-reporting :wagoe/http-handler :wagoe/http-server :wagoe/i18n
   :wagoe/i18n-http-middleware :wagoe/logging :wagoe/metrics :wagoe/mfa-service
   :wagoe/router :wagoe/session-repository :wagoe/settings :wagoe/tracing
   :wagoe/user-db-schema :wagoe/user-repository :wagoe/user-routes
   :wagoe/user-service])

(def ^:private scaffolded-module
  "What `bb scaffold generate --module-name tasks` + integrate adds."
  [:wagoe/tasks :wagoe/tasks-repository :wagoe/tasks-routes :wagoe/tasks-service])

(defn- system-of [ks] (zipmap ks (repeat {})))

(deftest ^:unit a-fresh-project-runs-one-module
  ;; Twenty keys, one module. The first version filtered a blocklist of names
  ;; and reported twelve — auth, mfa, session and audit among them, which are
  ;; the user module in pieces, and four things the user never added.
  (is (= ["user"] (sut/module-names (system-of fresh-project)))))

(deftest ^:unit the-scaffolded-module-is-listed-once
  (let [names (sut/module-names (system-of (concat fresh-project scaffolded-module)))]
    (is (= ["tasks" "user"] names))
    (testing "its repository, service and routes are the module, not three more"
      (is (= 1 (count (filter #{"tasks"} names)))))))

(deftest ^:unit a-module-that-namespaces-its-keys-is-still-a-module
  ;; push and external use :wagoe.push/… and :wagoe.external/… . A filter on
  ;; (= "wagoe" (namespace k)) drops them, so `wagoe add push` produced a
  ;; module that never appeared in (status).
  (is (= ["external" "push" "user"]
         (sut/module-names (system-of (concat fresh-project
                                              [:wagoe.push/token-store
                                               :wagoe.push/service
                                               :wagoe.external/smtp]))))))

(deftest ^:unit plumbing-never-counts-as-a-module
  (doseq [k [:wagoe/settings :wagoe/logging :wagoe/metrics :wagoe/tracing
             :wagoe/error-reporting :wagoe/db-context :wagoe/router
             :wagoe/http-server :wagoe/http-handler :wagoe/i18n
             :wagoe/i18n-http-middleware :wagoe/email]]
    (is (= [] (sut/module-names {k {}})) (str k " is plumbing"))))

(deftest ^:unit the-dashboard-shows-the-url-and-the-modules
  (let [text (sut/status-text {:system   (system-of (concat fresh-project scaffolded-module))
                               :base-url "http://localhost:3001"})]
    (is (str/includes? text "http://localhost:3001"))
    (is (str/includes? text "tasks"))

    (testing "and it stays inside its own box"
      ;; The border is drawn at a fixed width, so a long module list used to
      ;; push it off the end — and (go) prints this before anything else.
      (let [lines (->> (str/split-lines text)
                       (filter #(str/starts-with? % "│")))
            widths (set (map count lines))]
        (is (= 1 (count widths))
            (str "box lines have different widths: " (sort widths)))))))

(deftest ^:unit nothing-running-has-no-dashboard
  ;; nil rather than an empty box: the caller prints "start it with (go)".
  (is (nil? (sut/status-text {:system nil :base-url "http://localhost:3000"}))))

(deftest ^:unit the-palette-lists-what-a-generated-project-has
  (let [names (->> (vals sut/command-groups) (apply concat) (map :name) set)]
    (testing "the commands quickstart tells the user to run"
      (is (contains? names "(status)"))
      (is (contains? names "(commands)")))

    (testing "and nothing that only exists in the framework repo"
      (doseq [absent ["(lint)" "(check-all)" "(scaffold!)" "(test-module :mod)"]]
        (is (not (contains? names absent))
            (str absent " is not in a generated project"))))))
