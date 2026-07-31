(ns wagoe.cli.add-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [wagoe.cli.add :as add]))

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
