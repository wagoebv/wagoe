(ns wagoe.devtools.shell.dashboard.pages.jobs-test
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.devtools.shell.dashboard.pages.jobs :as jobs]
            [clojure.string :as str]))

(deftest ^:unit renders-empty-state-without-job-service
  (testing "renders page without job service (nil context)"
    (let [html (jobs/render {})]
      (is (string? html))
      (is (str/includes? html "Jobs"))
      (is (str/includes? html "No job service")))))

(deftest ^:unit renders-stats-from-job-data-vector-format
  (testing "renders stat cards from actual adapter format (vector of maps)"
    (let [html (jobs/render {:job-stats {:total-processed 42
                                         :total-failed 3
                                         :total-succeeded 39
                                         :queues [{:queue-name :default :size 5 :processed-total 30 :failed-total 2}
                                                  {:queue-name :critical :size 1 :processed-total 12 :failed-total 1}]}
                             :failed-jobs []})]
      (is (str/includes? html "42"))
      (is (str/includes? html "default"))
      (is (str/includes? html "critical")))))

(deftest ^:unit renders-stats-from-job-data-map-format
  (testing "renders stat cards from map format (alternative)"
    (let [html (jobs/render {:job-stats {:total-processed 10
                                         :total-failed 1
                                         :total-succeeded 9
                                         :queues {:default {:size 5 :processed 8 :failed 1}}}
                             :failed-jobs []})]
      (is (str/includes? html "10"))
      (is (str/includes? html "default")))))

(deftest ^:unit renders-failed-jobs-list
  (testing "renders failed jobs with error info"
    (let [html (jobs/render {:job-stats {:total-processed 10
                                         :total-failed 1
                                         :total-succeeded 9
                                         :queues {}}
                             :failed-jobs [{:id "job-1"
                                            :job-type :send-email
                                            :queue :default
                                            :error "Connection refused"
                                            :retry-count 3
                                            :created-at "2026-04-19T10:00:00Z"}]})]
      (is (str/includes? html "send-email"))
      (is (str/includes? html "Connection refused")))))

(deftest ^:unit render-fragment-returns-string
  (testing "fragment rendering returns HTML string"
    (let [html (jobs/render-fragment {:job-stats {:total-processed 0
                                                  :total-failed 0
                                                  :total-succeeded 0
                                                  :queues {}}
                                      :failed-jobs []})]
      (is (string? html)))))
