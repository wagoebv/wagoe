(ns wagoe.devtools.shell.project-repl-test
  "The shell half: the URL a user can open, and the vars the generated
   dev/user.clj resolves by name."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [integrant.repl.state :as state]
            [wagoe.admin.ports :as admin-ports]
            [wagoe.config.secrets :as secrets]
            [wagoe.devtools.shell.project-repl :as sut]))

(deftest ^:unit a-config-section-prints-secret-values-redacted
  ;; Under :url, which no key-name pattern treats as sensitive.
  (let [dsn (secrets/resolve-secret {:secret-sources {:test (reify secrets/ISecretSource
                                                              (fetch-secret [_ _] "postgres://u:repl-pw@db/app"))}}
                                    [:test "dsn"])
        out (with-redefs [state/config {:wagoe/db-context {:url dsn}}]
              (with-out-str (sut/config :database)))]
    (is (str/includes? out "<secret test:dsn>") out)
    (is (not (str/includes? out "repl-pw")))))

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

(deftest ^:unit the-nrepl-port-is-the-one-nrepl-wrote
  ;; The banner said 7888 whatever port the REPL ran on (BOU-564).
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "nrepl-port" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (is (nil? (sut/nrepl-port dir)) "no port file, no port to claim")
      (spit (io/file dir ".nrepl-port") "7899\n")
      (is (= 7899 (sut/nrepl-port dir)))
      (finally
        (io/delete-file (io/file dir ".nrepl-port") true)
        (io/delete-file dir true)))))

(deftest ^:unit fix-without-an-error-says-so-rather-than-throwing
  (is (str/includes? (with-out-str (sut/fix! nil)) "No recent error")))

(deftest ^:unit explain-entity-prints-the-overview-or-says-why-not
  (let [provider (reify admin-ports/ISchemaProvider
                   (fetch-table-metadata [_ _] [])
                   (get-entity-config [_ entity]
                     (case entity
                       :invoices {:fields {:number {:type :string} :total {:type :decimal}}
                                  :list-fields [:number :total]}
                       :typo     (throw (ex-info "Table not found: typo" {:type :schema-fetch-error}))
                       nil))
                   (list-available-entities [_] #{:invoices})
                   (get-entity-label [_ _] "Invoices")
                   (validate-entity-exists [_ _] true))
        run      (fn [system entity]
                   (with-redefs [state/system system]
                     (with-out-str (sut/explain-entity entity))))]
    (is (str/includes? (run {:wagoe/admin-schema-provider provider} :invoices)
                       "Admin overview of invoices"))
    (is (str/includes? (run {:wagoe/admin-schema-provider provider} :nope) "No admin entity :nope"))
    (is (str/includes? (run {:wagoe/admin-schema-provider provider} :typo)
                       "No admin entity :typo: Table not found"))
    (is (str/includes? (run {} :invoices) "needs wagoe-admin"))
    (is (str/includes? (run nil :invoices) "System not running"))))

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
