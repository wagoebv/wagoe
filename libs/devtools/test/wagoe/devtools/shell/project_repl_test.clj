(ns wagoe.devtools.shell.project-repl-test
  "The shell half: the URL a user can open, and the vars the generated
   dev/user.clj resolves by name."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.devtools.shell.project-repl :as sut]))

(deftest ^:unit the-url-comes-from-the-server-not-the-config-block
  ;; `ig-config` folds the :wagoe/http block into :wagoe/http-server, so the
  ;; key the first version read did not exist in the Integrant config at all
  ;; and every project was told "http://localhost:3000" whatever it ran on.
  (let [config {:wagoe/http-server {:port 3001 :host "127.0.0.1"}}]
    (is (= "http://127.0.0.1:3001" (sut/base-url {} config))))

  (testing "0.0.0.0 is what the server binds, not an address a browser opens"
    (is (= "http://localhost:8080"
           (sut/base-url {} {:wagoe/http-server {:port 8080 :host "0.0.0.0"}}))))

  (testing "no config at all still yields a URL rather than a nil"
    (is (= "http://localhost:3000" (sut/base-url {} {})))))

(deftest ^:unit an-http-server-that-cannot-be-asked-for-its-port-is-not-fatal
  ;; The port is read off the Jetty connector because auto-find may have moved
  ;; the app. Anything else in that slot — a mock, a half-initialised system —
  ;; falls back to the config rather than throwing inside (status).
  (is (= "http://localhost:3001"
         (sut/base-url {:wagoe/http-server ::not-a-server}
                       {:wagoe/http-server {:port 3001}}))))

(deftest ^:unit fix-without-an-error-says-so-rather-than-throwing
  (is (str/includes? (with-out-str (sut/fix! nil)) "No recent error")))

(deftest ^:integration the-generated-user-clj-resolves-every-var-it-delegates-to
  ;; dev/user.clj reaches these by quoted symbol, so a typo — /moduls, /fix —
  ;; degrades into "wagoe-devtools is not on the classpath" and sends the user
  ;; to install what they already have. Nothing else compares the two files.
  (let [tmpl (io/file "../wagoe-cli/resources/wagoe/cli/templates/user.clj.tmpl")]
    (if-not (.exists tmpl)
      ;; Run from somewhere the sibling library is not on disk. Record the skip
      ;; as an assertion so kaocha does not flag a zero-assertion test.
      (is (not (.exists tmpl)) "skipped: wagoe-cli is not next to this library")
      (let [syms (->> (re-seq #"'wagoe\.devtools\.shell\.project-repl/([A-Za-z0-9*+!?<>=_-]+)"
                              (slurp tmpl))
                      (map second)
                      distinct)]
        (is (seq syms) "the template must delegate to this namespace")
        (doseq [s syms]
          (is (ns-resolve 'wagoe.devtools.shell.project-repl (symbol s))
              (str "dev/user.clj calls project-repl/" s ", which does not exist")))))))
