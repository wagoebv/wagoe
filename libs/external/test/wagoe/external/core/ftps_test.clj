(ns wagoe.external.core.ftps-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.external.core.ftps :as ftps]))

(def ^:private files
  [{:name "b.csv" :path "/in/b.csv" :size 2}
   {:name "a.csv" :path "/in/a.csv" :size 1}
   {:name "readme.txt" :path "/in/readme.txt" :size 3}])

(deftest ^:unit select-files-test
  (testing "everything, sorted by name, without a matcher"
    (is (= ["a.csv" "b.csv" "readme.txt"] (map :name (ftps/select-files files {})))))

  (testing "a regex matches the whole name"
    (is (= ["a.csv" "b.csv"] (map :name (ftps/select-files files {:match #".*\.csv"}))))
    (is (= [] (ftps/select-files files {:match #"csv"}))))

  (testing "a string is an exact name, a collection a list of names"
    (is (= ["readme.txt"] (map :name (ftps/select-files files {:match "readme.txt"}))))
    (is (= ["a.csv" "readme.txt"] (map :name (ftps/select-files files {:match ["readme.txt" "a.csv"]})))))

  (testing "skipped names are left out"
    (is (= ["b.csv"] (map :name (ftps/select-files files {:match #".*\.csv" :skip #{"a.csv"}}))))))

(deftest ^:unit remote-path-test
  (is (= "/in/a.csv" (ftps/remote-path "/in" "a.csv")))
  (is (= "/in/a.csv" (ftps/remote-path "/in/" "a.csv")))
  (is (= "a.csv" (ftps/remote-path "" "a.csv"))))

(deftest ^:unit retryable-test
  (testing "transient: no reply at all, or a 4xx"
    (is (ftps/retryable? {}))
    (is (ftps/retryable? {:reply-code 421})))

  (testing "permanent: a 5xx, or a certificate the JVM does not trust"
    (is (not (ftps/retryable? {:reply-code 530})))
    (is (not (ftps/retryable? {:reply-code 550})))
    (is (not (ftps/retryable? {:certificate? true})))))

(deftest ^:unit backoff-ms-test
  (is (= [100 200 400] (map #(ftps/backoff-ms 100 %) [1 2 3])))
  (is (= 30000 (ftps/backoff-ms 10000 5))))

(deftest ^:unit ftps-error-test
  (let [err (ftps/ftps-error {:host "ftp.example.org" :operation :list :attempts 3
                              :cause "java.net.UnknownHostException: ftp.example.org"})]
    (is (= :external-error (:type err)))
    (is (= 3 (:attempts err)))
    (is (str/includes? (:message err) "ftp.example.org"))
    (is (str/includes? (:message err) "3 attempts"))
    (is (not (contains? err :reply-code))))
  (is (= 530 (:reply-code (ftps/ftps-error {:host "h" :operation :fetch :attempts 1
                                            :cause "Login refused" :reply-code 530})))))
