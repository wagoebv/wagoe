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

  (testing "an unknown source is named"
    (is (re-find #":nope" (ex-message (thrown #(read-edn "{:pw #secret [:nope \"x\"]}")))))))

(deftest ^:unit or-supplies-a-default-for-dev
  (is (= "dev-pw" (:pw (read-edn (str "{:pw #or [#secret \"" unset "\" \"dev-pw\"]}")))))
  (is (= "real" (:pw (read-edn "{:pw #or [#secret [:vault \"db\"] \"dev-pw\"]}"
                               {:secret-sources {:vault (map-source {"db" "real"})}}))))
  (testing "only inside the #or: a sibling secret still fails"
    (is (thrown? clojure.lang.ExceptionInfo
                 (read-edn (str "{:a #or [#secret \"" unset "\" \"x\"] :b #secret \"" unset "\"}"))))))

(deftest ^:unit redact-replaces-resolved-values-wherever-they-sit
  (let [cfg (read-edn "{:db {:dsn #secret [:vault \"dsn\"] :host \"db\"}}"
                      {:secret-sources {:vault (map-source {"dsn" "postgres://u:s3cret@db"})}})
        ig  {:wagoe/db {:url (get-in cfg [:db :dsn]) :pool [{:x (get-in cfg [:db :dsn])}]}}]
    (is (= {:wagoe/db {:url "<secret vault:dsn>" :pool [{:x "<secret vault:dsn>"}]}}
           (sut/redact ig))
        "by value, so a key no name pattern catches is still covered")
    (is (= ig (sut/unredact (sut/redact ig))) "and an edited copy restores")))
