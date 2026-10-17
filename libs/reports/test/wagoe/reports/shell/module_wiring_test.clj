(ns wagoe.reports.shell.module-wiring-test
  (:require [wagoe.reports.ports :as ports]
            [wagoe.reports.shell.jobs-integration :as jobs-integration]
            [wagoe.reports.shell.module-wiring :as sut]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]))

(deftest ^:unit the-component-is-a-report-service
  (let [svc (ig/init-key :wagoe/reports {:enabled? true :from "r@example.com" :queue :reports})]
    (is (satisfies? ports/ReportServiceProtocol svc))
    (is (= :reports (:queue-name svc)))
    (is (= "r@example.com" (:from svc)))))

(deftest ^:unit refs-only-the-modules-that-are-on
  (testing "alone"
    (let [{:keys [components job-handlers]} (sut/ig-config {:enabled? true} {:enabled #{:wagoe/reports}})]
      (is (= {:enabled? true} (:wagoe/reports components)))
      (is (not (contains? components :wagoe.reports/job-handlers)))
      (is (empty? job-handlers))))
  (testing "with jobs, storage and email"
    (let [{:keys [components job-handlers]}
          (sut/ig-config {} {:enabled #{:wagoe/reports :wagoe/jobs :wagoe/storage :wagoe/email}})]
      (is (= {:job-queue (ig/ref :wagoe/job-queue)
              :storage   (ig/ref :wagoe/storage)
              :email     (ig/ref :wagoe/email)}
             (:wagoe/reports components)))
      (is (= [(ig/ref :wagoe.reports/job-handlers)] job-handlers)))))

(deftest ^:unit the-job-handler-is-contributed-under-its-type
  (let [handlers (ig/init-key :wagoe.reports/job-handlers {:service (ig/init-key :wagoe/reports {})})]
    (is (fn? (get handlers jobs-integration/job-type)))))
