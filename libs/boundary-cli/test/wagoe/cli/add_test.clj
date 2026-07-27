(ns wagoe.cli.add-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [wagoe.cli.add :as add]))

(defn- make-boundary-project! [dir]
  (io/make-parents (io/file dir "resources/conf/dev/config.edn"))
  (io/make-parents (io/file dir "resources/conf/test/config.edn"))
  (spit (io/file dir "deps.edn")
        "{:deps {org.boundary-app/boundary-core {:mvn/version \"1.0.0\"}}}")
  (spit (io/file dir "resources/conf/dev/config.edn")
        "{\n :active\n {\n }\n\n :inactive\n {}\n}")
  (spit (io/file dir "resources/conf/test/config.edn")
        "{\n :active\n {\n }\n\n :inactive\n {}\n}")
  (spit (io/file dir "AGENTS.md")
        "# Test\n<!-- boundary:available-modules -->\n| payments | desc | boundary add payments |\n<!-- /boundary:available-modules -->\n<!-- boundary:installed-modules -->\n- core\n<!-- /boundary:installed-modules -->\n"))

(deftest ^:integration boundary-project-detection-test
  (let [tmp (str (System/getProperty "java.io.tmpdir") "/boundary-add-detect-" (System/currentTimeMillis))]
    (try
      (testing "detects a boundary project by deps.edn content"
        (make-boundary-project! tmp)
        (is (add/boundary-project? tmp)))

      (testing "returns false for non-boundary project"
        (let [other (str tmp "-other")]
          (io/make-parents (io/file other "deps.edn"))
          (spit (io/file other "deps.edn") "{:deps {}}")
          (is (not (add/boundary-project? other)))
          (doseq [f (reverse (file-seq (io/file other)))] (.delete f))))
      (finally
        (doseq [f (reverse (file-seq (io/file tmp)))] (.delete f))))))

(deftest ^:integration patch-deps-test
  (let [tmp (str (System/getProperty "java.io.tmpdir") "/boundary-add-deps-" (System/currentTimeMillis))]
    (try
      (make-boundary-project! tmp)
      (testing "adds module coordinate to deps.edn"
        (add/patch-deps! tmp {:clojars 'org.boundary-app/boundary-payments :version "1.0.0"})
        (let [content (slurp (io/file tmp "deps.edn"))]
          (is (str/includes? content "boundary-payments"))
          (is (map? (clojure.edn/read-string (slurp (io/file tmp "deps.edn")))) "deps.edn must remain valid EDN after patching")))

      (testing "is idempotent — does not duplicate if already present"
        (add/patch-deps! tmp {:clojars 'org.boundary-app/boundary-payments :version "1.0.0"})
        (let [content (slurp (io/file tmp "deps.edn"))]
          (is (= 1 (count (re-seq #"boundary-payments" content))))))
      (finally
        (doseq [f (reverse (file-seq (io/file tmp)))] (.delete f))))))

(deftest ^:integration patch-config-test
  (let [tmp (str (System/getProperty "java.io.tmpdir") "/boundary-add-cfg-" (System/currentTimeMillis))]
    (try
      (make-boundary-project! tmp)
      (testing "injects config-snippet into dev config"
        (add/patch-config! tmp "resources/conf/dev/config.edn"
                           "  :boundary/payment-provider\n  {:provider :mock}\n")
        (let [content (slurp (io/file tmp "resources/conf/dev/config.edn"))]
          (is (str/includes? content ":boundary/payment-provider"))))

      (testing "does not inject if key already present"
        (let [before (slurp (io/file tmp "resources/conf/dev/config.edn"))]
          (add/patch-config! tmp "resources/conf/dev/config.edn"
                             "  :boundary/payment-provider\n  {:provider :mock}\n")
          (let [after (slurp (io/file tmp "resources/conf/dev/config.edn"))]
            (is (= (count (re-seq #":boundary/payment-provider" before))
                   (count (re-seq #":boundary/payment-provider" after)))))))
      (finally
        (doseq [f (reverse (file-seq (io/file tmp)))] (.delete f))))))

(deftest ^:integration email-writes-smtp-to-test-config-test
  (let [tmp (str (System/getProperty "java.io.tmpdir") "/boundary-add-email-" (System/currentTimeMillis))]
    (try
      (make-boundary-project! tmp)
      (testing "boundary add email patches test/config.edn with :wagoe.external/smtp"
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
  (let [tmp (str (System/getProperty "java.io.tmpdir") "/boundary-add-agents-" (System/currentTimeMillis))]
    (try
      (make-boundary-project! tmp)
      (testing "removes module from available block"
        (add/patch-agents-md! tmp {:name "payments" :docs-url "http://example.com"})
        (let [content (slurp (io/file tmp "AGENTS.md"))]
          (is (not (str/includes? content "boundary add payments")))))

      (testing "adds module to installed block"
        (let [content (slurp (io/file tmp "AGENTS.md"))]
          (is (str/includes? content "payments"))))
      (finally
        (doseq [f (reverse (file-seq (io/file tmp)))] (.delete f))))))
