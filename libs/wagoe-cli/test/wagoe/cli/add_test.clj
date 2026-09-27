(ns wagoe.cli.add-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [wagoe.cli.add :as add]
            [wagoe.cli.catalogue :as cat]))

(defn- make-wagoe-project! [dir]
  (io/make-parents (io/file dir "resources/conf/dev/config.edn"))
  (io/make-parents (io/file dir "resources/conf/test/config.edn"))
  (spit (io/file dir "deps.edn")
        "{:deps {com.wagoe/wagoe-core {:mvn/version \"1.0.0\"}}}")
  (spit (io/file dir "resources/conf/dev/config.edn")
        "{\n :active\n {\n }\n\n :inactive\n {}\n}")
  (spit (io/file dir "resources/conf/test/config.edn")
        "{\n :active\n {\n }\n\n :inactive\n {}\n}")
  (spit (io/file dir "AGENTS.md")
        "# Test\n<!-- wagoe:available-modules -->\n| payments | desc | wagoe add payments |\n<!-- /wagoe:available-modules -->\n<!-- wagoe:installed-modules -->\n- core\n<!-- /wagoe:installed-modules -->\n"))

(deftest ^:integration wagoe-project-detection-test
  (let [tmp (str (System/getProperty "java.io.tmpdir") "/wagoe-add-detect-" (System/currentTimeMillis))]
    (try
      (testing "detects a wagoe project by deps.edn content"
        (make-wagoe-project! tmp)
        (is (add/wagoe-project? tmp)))

      (testing "returns false for non-wagoe project"
        (let [other (str tmp "-other")]
          (io/make-parents (io/file other "deps.edn"))
          (spit (io/file other "deps.edn") "{:deps {}}")
          (is (not (add/wagoe-project? other)))
          (doseq [f (reverse (file-seq (io/file other)))] (.delete f))))
      (finally
        (doseq [f (reverse (file-seq (io/file tmp)))] (.delete f))))))

(deftest ^:integration patch-deps-test
  (let [tmp (str (System/getProperty "java.io.tmpdir") "/wagoe-add-deps-" (System/currentTimeMillis))]
    (try
      (make-wagoe-project! tmp)
      (testing "adds module coordinate to deps.edn"
        (add/patch-deps! tmp {:clojars 'com.wagoe/wagoe-payments :version "1.0.0"})
        (let [content (slurp (io/file tmp "deps.edn"))]
          (is (str/includes? content "wagoe-payments"))
          (is (map? (clojure.edn/read-string (slurp (io/file tmp "deps.edn")))) "deps.edn must remain valid EDN after patching")))

      (testing "is idempotent — does not duplicate if already present"
        (add/patch-deps! tmp {:clojars 'com.wagoe/wagoe-payments :version "1.0.0"})
        (let [content (slurp (io/file tmp "deps.edn"))]
          (is (= 1 (count (re-seq #"wagoe-payments" content))))))
      (finally
        (doseq [f (reverse (file-seq (io/file tmp)))] (.delete f))))))

(deftest ^:integration a-dev-scoped-module-lands-in-the-repl-alias
  ;; devtools carries a dashboard and a Jetty adapter. In :deps it would be in
  ;; the uberjar of every project that ran `wagoe add devtools` (BOU-318).
  (let [tmp (str (System/getProperty "java.io.tmpdir") "/wagoe-add-dev-" (System/currentTimeMillis))
        dev {:clojars 'com.wagoe/wagoe-devtools :version "1.0.0" :scope :dev}]
    (try
      (make-wagoe-project! tmp)
      (spit (io/file tmp "deps.edn")
            (str "{:deps {com.wagoe/wagoe-core {:mvn/version \"1.0.0\"}}\n"
                 " :aliases\n"
                 " {:repl {:extra-paths [\"dev\"]\n"
                 "         :extra-deps {nrepl/nrepl {:mvn/version \"1.3.0\"}}}}}"))

      (testing "it goes in the :repl alias, not in :deps"
        (is (= :repl-alias (add/patch-deps! tmp dev)))
        (let [parsed (clojure.edn/read-string (slurp (io/file tmp "deps.edn")))]
          (is (nil? (get-in parsed [:deps 'com.wagoe/wagoe-devtools]))
              "a dev-only module in :deps ships in the uberjar")
          (is (= {:mvn/version "1.0.0"}
                 (get-in parsed [:aliases :repl :extra-deps 'com.wagoe/wagoe-devtools])))
          (is (get-in parsed [:aliases :repl :extra-deps 'nrepl/nrepl])
              "the deps already in the alias survive"))
        (is (not (re-find #"\}[^\s\}\)\]]" (slurp (io/file tmp "deps.edn"))))
            "a coordinate must not be glued onto the end of the previous one"))

      (testing "and it is idempotent"
        (is (nil? (add/patch-deps! tmp dev)))
        (is (= 1 (count (re-seq #"wagoe-devtools" (slurp (io/file tmp "deps.edn")))))))

      (testing "a project with no :repl alias is told, not silently skipped"
        (spit (io/file tmp "deps.edn") "{:deps {com.wagoe/wagoe-core {:mvn/version \"1.0.0\"}}}")
        (is (= :no-repl-extra-deps (add/patch-deps! tmp dev)))
        (is (not (str/includes? (slurp (io/file tmp "deps.edn")) "devtools"))
            "nothing may be written when there is nowhere correct to write it"))

      (testing "it never writes into a different alias"
        ;; A regex anchored on :repl takes the next :extra-deps in the FILE,
        ;; which need not be inside :repl. With a :repl alias that has none,
        ;; devtools landed in :test — and the command reported the :repl alias
        ;; while writing to :test.
        (spit (io/file tmp "deps.edn")
              (str "{:deps {com.wagoe/wagoe-core {:mvn/version \"1.0.0\"}}\n"
                   " :aliases\n"
                   " {:repl {:extra-paths [\"dev\"] :main-opts [\"-m\" \"nrepl.cmdline\"]}\n"
                   "  :test {:extra-paths [\"test\"]\n"
                   "         :extra-deps {lambdaisland/kaocha {:mvn/version \"1.0\"}}}}}"))
        (is (= :no-repl-extra-deps (add/patch-deps! tmp dev)))
        (let [parsed (clojure.edn/read-string (slurp (io/file tmp "deps.edn")))]
          (is (nil? (get-in parsed [:aliases :test :extra-deps 'com.wagoe/wagoe-devtools])))))

      (testing "a brace in a comment does not move the insertion"
        (spit (io/file tmp "deps.edn")
              (str "{:deps {com.wagoe/wagoe-core {:mvn/version \"1.0.0\"}}\n"
                   " :aliases\n"
                   " {;; e.g. :extra-deps {foo/bar {:mvn/version \"1\"}\n"
                   "  :repl {:extra-deps {nrepl/nrepl {:mvn/version \"1.3.0\"}}}}}"))
        (is (= :repl-alias (add/patch-deps! tmp dev)))
        (let [parsed (clojure.edn/read-string (slurp (io/file tmp "deps.edn")))]
          (is (get-in parsed [:aliases :repl :extra-deps 'com.wagoe/wagoe-devtools]))))

      (testing "an unreadable deps.edn is left alone"
        ;; Writing into a file we cannot parse risks a second copy of a key the
        ;; file already has, and a deps.edn with a duplicate key does not load.
        (spit (io/file tmp "deps.edn") "{:deps {com.wagoe/wagoe-core {:mvn/version \"1.0.0\"}}")
        (is (= :unreadable (add/patch-deps! tmp dev)))
        (is (not (str/includes? (slurp (io/file tmp "deps.edn")) "devtools"))))
      (finally
        (doseq [f (reverse (file-seq (io/file tmp)))] (.delete f))))))

(deftest ^:integration patch-config-test
  (let [tmp (str (System/getProperty "java.io.tmpdir") "/wagoe-add-cfg-" (System/currentTimeMillis))]
    (try
      (make-wagoe-project! tmp)
      (testing "injects config-snippet into dev config"
        (add/patch-config! tmp "resources/conf/dev/config.edn"
                           "  :wagoe/payment-provider\n  {:provider :mock}\n")
        (let [content (slurp (io/file tmp "resources/conf/dev/config.edn"))]
          (is (str/includes? content ":wagoe/payment-provider"))))

      (testing "does not inject if key already present"
        (let [before (slurp (io/file tmp "resources/conf/dev/config.edn"))]
          (add/patch-config! tmp "resources/conf/dev/config.edn"
                             "  :wagoe/payment-provider\n  {:provider :mock}\n")
          (let [after (slurp (io/file tmp "resources/conf/dev/config.edn"))]
            (is (= (count (re-seq #":wagoe/payment-provider" before))
                   (count (re-seq #":wagoe/payment-provider" after)))))))
      (finally
        (doseq [f (reverse (file-seq (io/file tmp)))] (.delete f))))))

(deftest ^:integration email-writes-smtp-to-test-config-test
  (let [tmp (str (System/getProperty "java.io.tmpdir") "/wagoe-add-email-" (System/currentTimeMillis))]
    (try
      (make-wagoe-project! tmp)
      (testing "wagoe add email patches test/config.edn with :wagoe.external/smtp"
        (add/patch-config! tmp "resources/conf/test/config.edn"
                           "  :wagoe.external/smtp\n  {:host \"localhost\" :port 1025 :tls? false :from \"test@localhost\"}\n")
        (let [content (slurp (io/file tmp "resources/conf/test/config.edn"))]
          (is (str/includes? content ":wagoe.external/smtp"))))

      (testing "does not inject SMTP into test config if already present"
        (let [before (slurp (io/file tmp "resources/conf/test/config.edn"))]
          (add/patch-config! tmp "resources/conf/test/config.edn"
                             "  :wagoe.external/smtp\n  {:host \"localhost\" :port 1025 :tls? false :from \"test@localhost\"}\n")
          (let [after (slurp (io/file tmp "resources/conf/test/config.edn"))]
            (is (= (count (re-seq #":wagoe.external/smtp" before))
                   (count (re-seq #":wagoe.external/smtp" after)))))))
      (finally
        (doseq [f (reverse (file-seq (io/file tmp)))] (.delete f))))))

(deftest ^:integration patch-agents-md-test
  (let [tmp (str (System/getProperty "java.io.tmpdir") "/wagoe-add-agents-" (System/currentTimeMillis))]
    (try
      (make-wagoe-project! tmp)
      (testing "removes module from available block"
        (add/patch-agents-md! tmp {:name "payments" :docs-url "http://example.com"})
        (let [content (slurp (io/file tmp "AGENTS.md"))]
          (is (not (str/includes? content "wagoe add payments")))))

      (testing "adds module to installed block"
        (let [content (slurp (io/file tmp "AGENTS.md"))]
          (is (str/includes? content "payments"))))
      (finally
        (doseq [f (reverse (file-seq (io/file tmp)))] (.delete f))))))

(deftest ^:integration patch-configs-writes-every-existing-profile
  ;; Only dev and test were written, so under WAG_ENV=prod the module was not
  ;; there (BOU-529).
  (let [tmp  (str (System/getProperty "java.io.tmpdir") "/wagoe-add-profiles-" (System/currentTimeMillis))
        conf #(slurp (io/file tmp "resources/conf" % "config.edn"))]
    (try
      (make-wagoe-project! tmp)
      (io/make-parents (io/file tmp "resources/conf/prod/config.edn"))
      (spit (io/file tmp "resources/conf/prod/config.edn") "{\n :active\n {\n }\n}")
      (add/patch-configs! tmp {:config-snippet      "  :wagoe/jobs\n  {:workers {:count 1}}\n"
                               :test-config-snippet "  :wagoe/jobs\n  {:workers {:count 0}}\n"})
      (is (str/includes? (conf "dev") ":count 1"))
      (is (str/includes? (conf "test") ":count 0"))
      (is (str/includes? (conf "prod") ":count 1"))
      (is (not (.exists (io/file tmp "resources/conf/acc"))) "never creates a profile")

      (testing "a dev-scoped module stays in dev"
        (add/patch-configs! tmp {:scope               :dev
                                 :config-snippet      "  :wagoe/dashboard\n  {}\n"
                                 :test-config-snippet "  :wagoe/dashboard\n  {}\n"})
        (is (str/includes? (conf "dev") ":wagoe/dashboard"))
        (is (not (str/includes? (conf "test") ":wagoe/dashboard")))
        (is (not (str/includes? (conf "prod") ":wagoe/dashboard"))))
      (finally
        (doseq [f (reverse (file-seq (io/file tmp)))] (.delete f))))))

;; BOU-564 -----------------------------------------------------------------------

(defn- with-profiles!
  "A wagoe project with dev, test and prod profiles. Returns the dir."
  [label]
  (let [tmp (str (System/getProperty "java.io.tmpdir") "/wagoe-add-" label "-" (System/nanoTime))]
    (make-wagoe-project! tmp)
    (io/make-parents (io/file tmp "resources/conf/prod/config.edn"))
    (spit (io/file tmp "resources/conf/prod/config.edn") "{\n :active\n {\n }\n}")
    tmp))

(defn- active-of [dir env]
  (:active (clojure.edn/read-string {:default (fn [_ v] v)}
                                    (slurp (io/file dir "resources/conf" env "config.edn")))))

(deftest ^:integration events-runs-in-memory-in-dev-and-on-redis-in-prod
  (let [tmp (with-profiles! "events")]
    (try
      (add/patch-configs! tmp (cat/find-module "events"))
      (is (= :memory (get-in (active-of tmp "dev") [:wagoe/events :provider])))
      (is (= :memory (get-in (active-of tmp "test") [:wagoe/events :provider])))
      (is (= :redis (get-in (active-of tmp "prod") [:wagoe/events :provider])))
      (finally
        (doseq [f (reverse (file-seq (io/file tmp)))] (.delete f))))))

(deftest ^:integration a-module-missing-from-prod-is-not-installed
  ;; `wagoe add` read only dev, so a module added before prod existed was
  ;; "already installed" and prod never got it.
  (let [tmp    (with-profiles! "prod-missing")
        module (cat/find-module "jobs")]
    (try
      (doseq [env ["dev" "test"]]
        (add/patch-config! tmp (str "resources/conf/" env "/config.edn") (:config-snippet module)))
      (is (not (add/installed? tmp module true)))
      (is (= [["dev" :present] ["prod" :added] ["test" :present]]
             (add/patch-configs! tmp module)))
      (is (contains? (active-of tmp "prod") :wagoe/jobs))
      (is (add/installed? tmp module true))
      (finally
        (doseq [f (reverse (file-seq (io/file tmp)))] (.delete f))))))

(deftest ^:integration devtools-enables-the-dashboard-in-dev-only
  (let [tmp (with-profiles! "devtools")]
    (try
      (add/patch-configs! tmp (cat/find-module "devtools"))
      (is (contains? (active-of tmp "dev") :wagoe/dashboard))
      (is (not (contains? (active-of tmp "test") :wagoe/dashboard)))
      (is (not (contains? (active-of tmp "prod") :wagoe/dashboard))
          "the platform refuses the dashboard outside :dev")
      (finally
        (doseq [f (reverse (file-seq (io/file tmp)))] (.delete f))))))

(deftest ^:integration events-in-prod-is-named-for-the-project-and-its-env-vars-listed
  ;; The group was "my-app" in every project, and .env.example never named the
  ;; Redis variables prod now needs.
  (let [tmp    (with-profiles! "events-name")
        module (cat/find-module "events")
        env-ex (io/file tmp ".env.example")]
    (try
      (spit (io/file tmp "resources/conf/dev/config.edn")
            "{\n :active\n {:wagoe/settings {:name \"shop-dev\"}\n }\n}")
      (spit env-ex "# mine\nJWT_SECRET=keep-me\nREDIS_PORT=6380\n")
      (add/patch-env-example! tmp module (add/patch-configs! tmp module))
      (is (= "shop" (get-in (active-of tmp "prod") [:wagoe/events :group])))
      (let [text (slurp env-ex)]
        (is (str/starts-with? text "# mine\nJWT_SECRET=keep-me\nREDIS_PORT=6380\n") "only added to")
        (is (str/includes? text "REDIS_HOST="))
        (is (str/includes? text "REDIS_PASSWORD="))
        (is (= 1 (count (re-seq #"(?m)^REDIS_PORT=" text)))))
      (testing "and nothing when no profile runs it on Redis"
        (spit env-ex "X=1\n")
        (add/patch-env-example! tmp module [["dev" :present] ["test" :present]])
        (is (= "X=1\n" (slurp env-ex))))
      (finally
        (doseq [f (reverse (file-seq (io/file tmp)))] (.delete f))))))

(deftest ^:integration payments-never-puts-the-mock-in-prod
  ;; The mock provider accepts any webhook as paid (BOU-564 review).
  (let [tmp (with-profiles! "payments")]
    (try
      (add/patch-configs! tmp (cat/find-module "payments"))
      (is (= :mock (get-in (active-of tmp "dev") [:wagoe/payment-provider :provider])))
      (is (not (contains? (active-of tmp "prod") :wagoe/payment-provider)))
      (finally
        (doseq [f (reverse (file-seq (io/file tmp)))] (.delete f))))))
