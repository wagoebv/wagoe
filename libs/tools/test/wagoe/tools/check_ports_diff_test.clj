(ns wagoe.tools.check-ports-diff-test
  "Unit tests for the ports-diff gate.

   The protocols here are cut down from real ones: `IUserRepository` gained
   `claim-login-attempts` between 1.0.1 and the 1.1 branch with no breaking
   entry, and `IWorkflowEngine` gained a `tx` arity on `transition!`."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.tools.check-ports-diff :as sut]))

(def user-ports-1-0-1
  "(ns wagoe.user.ports)
   (defprotocol IUserRepository
     \"Persistence for users.\"
     (find-user-by-id [this user-id] \"One user.\")
     (logout-user-everywhere [this user-id]))
   (comment (defprotocol Scratch (x [this])))")

(def user-ports-now
  "(ns wagoe.user.ports)
   (defprotocol IUserRepository
     \"Persistence for users.\"
     (find-user-by-id [this user-id] \"One user.\")
     (logout-user-everywhere [this user-id] [this user-id keep-session-id])
     (claim-login-attempts [this user-id n lockout]))
   (defprotocol ILoginLayout (login-layout [this]))")

(defn- read-one [path content] (sut/protocols-in path content))

;; =============================================================================
;; What counts as a port file
;; =============================================================================

(deftest ^:unit port-file-test
  (testing "the places the stability page calls supported surface"
    (is (sut/port-file? "libs/user/src/wagoe/user/ports.clj"))
    (is (sut/port-file? "libs/platform/src/wagoe/platform/ports/database.clj"))
    (is (sut/port-file? "libs/observability/src/wagoe/observability/metrics/ports.clj"))
    (is (sut/port-file? "libs/platform/src/wagoe/platform/shell/adapters/database/protocols.clj")))

  (testing "and what is not"
    (is (not (sut/port-file? "libs/user/src/wagoe/user/shell/persistence.clj")))
    (is (not (sut/port-file? "libs/user/test/wagoe/user/ports.clj")))
    (is (not (sut/port-file? "libs/user/src/wagoe/user/core/reports.clj")))
    (is (not (sut/port-file? "libs/user/src/wagoe/user/import_ports_helper.clj")))))

;; =============================================================================
;; Reading protocols
;; =============================================================================

(deftest ^:unit protocols-in-test
  (let [ps (read-one "libs/user/src/wagoe/user/ports.clj" user-ports-now)]
    (testing "keyed by namespace and name, with the library"
      (is (= #{"wagoe.user.ports/IUserRepository" "wagoe.user.ports/ILoginLayout"} (set (keys ps))))
      (is (= "user" (:lib (get ps "wagoe.user.ports/IUserRepository")))))

    (testing "arity counts `this`, docstrings are not methods"
      (is (= {"find-user-by-id"        #{2}
              "logout-user-everywhere" #{2 3}
              "claim-login-attempts"   #{4}}
             (:methods (get ps "wagoe.user.ports/IUserRepository"))))))

  (testing "a protocol in a comment form is not surface"
    (is (= ["wagoe.user.ports/IUserRepository"]
           (keys (read-one "libs/user/src/wagoe/user/ports.clj" user-ports-1-0-1))))))

;; =============================================================================
;; Comparing
;; =============================================================================

(deftest ^:unit changes-test
  (let [old (read-one "libs/user/src/wagoe/user/ports.clj" user-ports-1-0-1)
        new (read-one "libs/user/src/wagoe/user/ports.clj" user-ports-now)
        cs  (sut/changes old new)]
    (testing "an added method and an added arity break implementers"
      (is (= #{[:added-method "claim-login-attempts"] [:added-arity "logout-user-everywhere"]}
             (set (map (juxt :kind :method) cs)))))

    (testing "a new protocol is not a change"
      (is (not-any? #(= "wagoe.user.ports/ILoginLayout" (:protocol %)) cs)))

    (testing "going the other way, the same edits break callers, and the new protocol is removed"
      (is (= #{[:removed-method "claim-login-attempts"] [:removed-arity "logout-user-everywhere"]
               [:removed-protocol nil]}
             (set (map (juxt :kind :method) (sut/changes new old))))))))

(deftest ^:unit moved-and-removed-test
  (let [base {"wagoe.platform.shell.adapters.database.protocols/DBAdapter"
              {:lib "platform" :name "DBAdapter" :methods {"dialect" #{1}}}
              "wagoe.platform.ports/IGone"
              {:lib "platform" :name "IGone" :methods {"x" #{1}}}}
        head {"wagoe.platform.ports.database/DBAdapter"
              {:lib "platform" :name "DBAdapter" :methods {"dialect" #{1} "engine" #{1}}}}
        cs   (sut/changes base head)]
    (testing "a protocol whose namespace changed is moved, and still compared"
      (is (some #(= [:moved "wagoe.platform.ports.database/DBAdapter"] ((juxt :kind :to) %)) cs))
      (is (some #(= [:added-method "engine"] ((juxt :kind :method) %)) cs)))
    (testing "a protocol that went nowhere is removed"
      (is (some #(= [:removed-protocol "wagoe.platform.ports/IGone"] ((juxt :kind :protocol) %)) cs)))))

;; =============================================================================
;; The verdict
;; =============================================================================

(def tiers (sut/tiers {:modules [{:name "user" :tier :stable} {:name "workflow" :tier :incubating}]}))

(def user-change {:kind :added-method :lib "user" :protocol "wagoe.user.ports/IUserRepository"
                  :method "claim-login-attempts" :arities [4]})
(def workflow-change {:kind :added-arity :lib "workflow" :protocol "wagoe.workflow.ports/IWorkflowEngine"
                      :method "transition!" :arities [3]})

(deftest ^:unit verdict-test
  (testing "a stable change nobody named fails; an incubating one is only reported"
    (let [{:keys [failing reported]} (sut/verdict [user-change workflow-change] tiers "" "1.0.1" "1.1.0")]
      (is (= [user-change] failing))
      (is (= [workflow-change] reported))))

  (testing "naming it under ### Breaking, in backticks, satisfies the gate"
    (is (empty? (:failing (sut/verdict [user-change] tiers
                                       "- **`IUserRepository/claim-login-attempts`** is new (BOU-785)."
                                       "1.0.1" "1.1.0")))))

  (testing "a library the gate cannot place counts as stable"
    (is (= :stable (sut/tier-of tiers "something-new")))
    (is (= :tooling (sut/tier-of tiers "wagoe-cli"))))

  (testing "a major version is allowed to break"
    (is (empty? (:failing (sut/verdict [user-change] tiers "" "1.4.2" "2.0.0-rc-1"))))))

(deftest ^:unit named-test
  (testing "the name in backticks, bare or qualified"
    (is (sut/named? "`like` takes a mode" "like"))
    (is (sut/named? "`DBAdapter/like` takes a mode" "like")))
  (testing "not the word in prose, nor a longer name"
    (is (not (sut/named? "works like before" "like")))
    (is (not (sut/named? "`like-pattern` is new" "like")))))

(deftest ^:unit unreleased-breaking-test
  (let [changelog (str "# Changelog\n\n## [Unreleased]\n\n### Added\n\n- `engine` is new\n\n"
                       "### Breaking\n\n- `claim-login-attempts` on `IUserRepository`\n\n"
                       "### Fixed\n\n- nothing\n\n## [1.0.1] — 2026-10-07\n\n### Breaking\n\n- `old-thing`\n")
        breaking  (sut/unreleased-breaking changelog)]
    (testing "only [Unreleased] ### Breaking counts"
      (is (sut/named? breaking "claim-login-attempts"))
      (is (not (sut/named? breaking "engine")))
      (is (not (sut/named? breaking "old-thing"))))))
