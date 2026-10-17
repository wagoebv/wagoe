(ns wagoe.config.secrets-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.config :as config]
            [wagoe.config.secrets :as sut]))

(defn- temp-dir ^java.io.File []
  (let [d (.toFile (java.nio.file.Files/createTempDirectory "wagoe-secrets" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (.deleteOnExit d)
    d))

(defn- read-edn
  "`text` read as a config file, with `opts` for the secret sources."
  ([text] (read-edn text {}))
  ([text opts]
   (let [f (java.io.File/createTempFile "wagoe-config" ".edn")]
     (.deleteOnExit f)
     (spit f text)
     (config/read-config-resource (.toURL (.toURI f)) :test opts))))

(defn- map-source [m]
  (reify sut/ISecretSource
    (fetch-secret [_ n] (get m n))))

(def ^:private unset "WAGOE_TEST_SECRET_THAT_IS_NEVER_SET")

(deftest ^:unit a-bare-name-reads-the-environment
  (is (= (System/getenv "PATH") (:path (read-edn "{:path #secret \"PATH\"}"))))
  (is (= (System/getenv "PATH") (:path (read-edn "{:path #secret [:env PATH]}")))
      "symbols, as #env takes them"))

(deftest ^:unit the-file-source-reads-a-mounted-secret-trimmed
  (let [dir (temp-dir)]
    (spit (java.io.File. dir "db-password") "hunter2\n")
    (is (= "hunter2"
           (:pw (read-edn "{:pw #secret [:file \"db-password\"]}"
                          {:secret-sources {:file (sut/->FileSource (str dir))}}))))
    (testing "a configured default source serves bare names"
      (is (= "hunter2"
             (:pw (read-edn "{:pw #secret \"db-password\"}"
                            {:secret-sources        {:file (sut/->FileSource (str dir))}
                             :default-secret-source :file})))))))

(deftest ^:unit a-plugged-in-source-resolves
  (is (= "from-vault"
         (:pw (read-edn "{:pw #secret [:vault \"db\"]}"
                        {:secret-sources {:vault (map-source {"db" "from-vault"})}})))))

(defn- thrown [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e e)))

(deftest ^:unit a-missing-secret-fails-naming-the-secret-and-not-a-value
  (let [e (thrown #(read-edn (str "{:pw #secret \"" unset "\"}")))]
    (is (= {:type :configuration-error :source :env :secret unset} (ex-data e)))
    (is (re-find (re-pattern unset) (ex-message e))))

  (testing "a blank value is missing, and is not echoed"
    (let [e (thrown #(read-edn "{:pw #secret [:vault \"db\"]}"
                               {:secret-sources {:vault (map-source {"db" "   "})}}))]
      (is (= :configuration-error (:type (ex-data e))))))

  (testing "a missing file names the directory looked in"
    (let [dir (str (temp-dir))
          e   (thrown #(read-edn "{:pw #secret [:file \"gone\"]}"
                                 {:secret-sources {:file (sut/->FileSource dir)}}))]
      (is (re-find #"gone" (ex-message e)))
      (is (re-find (re-pattern (java.util.regex.Pattern/quote dir)) (ex-message e)))))

  (testing "a malformed reference is refused"
    (doseq [form ["42" "[:file]" "[:file \"a\" \"b\"]" "[\"file\" \"a\"]"
                  "{:default \"x\"}" "{:name \"a\" :dflt \"x\"}" "{:name \"a\" :source \"file\"}"]]
      (is (= :configuration-error (:type (ex-data (thrown #(read-edn (str "{:pw #secret " form "}"))))))
          form)))

  (testing "a value that is not a string is refused"
    (let [e (thrown #(read-edn "{:pw #secret [:vault \"db\"]}"
                               {:secret-sources {:vault (map-source {"db" 5432})}}))]
      (is (= {:type :configuration-error :source :vault :secret "db"} (ex-data e)))))

  (testing "an unknown source is named"
    (is (re-find #":nope" (ex-message (thrown #(read-edn "{:pw #secret [:nope \"x\"]}")))))))

(deftest ^:unit a-default-in-the-form-is-used-for-a-missing-secret
  (is (= "dev-pw" (:pw (read-edn (str "{:pw #secret {:name \"" unset "\" :default \"dev-pw\"}}")))))
  (is (= "real" (:pw (read-edn "{:pw #secret {:name \"db\" :source :vault :default \"dev-pw\"}}"
                               {:secret-sources {:vault (map-source {"db" "real"})}}))))
  (testing "a blank value is missing, so the default applies"
    (is (= "dev-pw" (:pw (read-edn "{:pw #secret {:name \"db\" :source :vault :default \"dev-pw\"}}"
                                   {:secret-sources {:vault (map-source {"db" " "})}})))))
  (testing "an explicit nil default is honoured"
    (is (nil? (:pw (read-edn (str "{:pw #secret {:name \"" unset "\" :default nil}}"))))))
  (testing "the default may itself be a tag"
    (is (= (System/getenv "PATH")
           (:pw (read-edn (str "{:pw #secret {:name \"" unset "\" :default #env PATH}}")))))))

(deftest ^:unit or-does-not-make-a-secret-optional
  (testing "a missing secret inside #or fails, with a message that names the replacement"
    (let [e (thrown #(read-edn (str "{:pw #or [#secret \"" unset "\" \"dev-pw\"]}")))]
      (is (= {:type :configuration-error :source :env :secret unset} (ex-data e)))
      (is (re-find #":default" (ex-message e)))))
  (testing "nor one in a map inside #or, which used to come back nil"
    (is (thrown? clojure.lang.ExceptionInfo
                 (read-edn (str "{:db #or [{:pw #secret \"" unset "\"} {:pw \"dev\"}]}")))))
  (testing "Aero's #or is its own again"
    (is (= "x" (:a (read-edn (str "{:a #or [#env " unset " \"x\"]}")))))))

(def vault-source (map-source {"db" "from-declared-vault"}))
(defn make-vault [] (map-source {"db" "from-constructor"}))
(def not-a-source 42)

(deftest ^:unit sources-declared-in-the-config-resolve-for-every-reader
  (is (= "from-declared-vault"
         (:pw (read-edn "{:secret-sources {:vault wagoe.config.secrets-test/vault-source}
                          :pw #secret [:vault \"db\"]}"))))
  (testing "a no-argument function is a constructor"
    (is (= "from-constructor"
           (:pw (read-edn "{:secret-sources {:vault wagoe.config.secrets-test/make-vault}
                            :pw #secret [:vault \"db\"]}")))))
  (testing "sources passed in opts win"
    (is (= "from-opts"
           (:pw (read-edn "{:secret-sources {:vault wagoe.config.secrets-test/vault-source}
                            :pw #secret [:vault \"db\"]}"
                          {:secret-sources {:vault (map-source {"db" "from-opts"})}}))))
    (is (= "from-opts"
           (:pw (read-edn "{:secret-sources {:vault wagoe.config.secrets-test/not-a-source}
                            :pw #secret [:vault \"db\"]}"
                          {:secret-sources {:vault (map-source {"db" "from-opts"})}})))
        "an overridden declaration is not built"))
  (testing "a bad declaration is a configuration error"
    (doseq [decl ["wagoe.config.secrets-test/not-a-source" "wagoe.config.secrets-test/nope"
                  "no.such.ns/source" "unqualified"]]
      (is (= :configuration-error
             (:type (ex-data (thrown #(read-edn (str "{:secret-sources {:vault " decl "}}"))))))
          decl))
    (is (= :configuration-error
           (:type (ex-data (thrown #(read-edn "{:secret-sources #profile {:default {}}}"))))))))

(deftest ^:unit redact-replaces-resolved-values-wherever-they-sit
  (let [cfg (read-edn "{:db {:dsn #secret [:vault \"dsn\"] :host \"db\"}}"
                      {:secret-sources {:vault (map-source {"dsn" "postgres://u:s3cret@db"})}})
        ig  {:wagoe/db {:url (get-in cfg [:db :dsn]) :pool [{:x (get-in cfg [:db :dsn])}]}}]
    (is (= {:wagoe/db {:url "<secret vault:dsn>" :pool [{:x "<secret vault:dsn>"}]}}
           (sut/redact ig))
        "by value, so a key no name pattern catches is still covered")
    (is (= ig (sut/unredact (sut/redact ig))) "and an edited copy restores")))

(deftest ^:unit redact-covers-a-secret-joined-into-a-longer-string
  (let [cfg (read-edn "{:url #join [\"postgres://u:\" #secret [:vault \"pw\"] \"@db\"]}"
                      {:secret-sources {:vault (map-source {"pw" "j0ined-pw"})}})]
    (is (= {:url "postgres://u:<secret vault:pw>@db"} (sut/redact cfg)))
    (is (= cfg (sut/unredact (sut/redact cfg))))))

(deftest ^:unit optional-reads-make-missing-secrets-and-sources-nil
  (is (= {:a nil :b nil}
         (read-edn (str "{:a #secret \"" unset "\" :b #secret [:nope \"x\"]}")
                   {::sut/optional? true}))))
