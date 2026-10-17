(ns wagoe.jobs.shell.adapters.codec-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.jobs.shell.adapters.codec :as codec])
  (:import [java.net URI]
           [java.time Duration Instant OffsetTime YearMonth ZoneId]))

(def legacy-json
  "A job as the :db and :redis queues wrote it before BOU-689."
  (str "{\"id\":\"7f8c1f0e-3b5a-4c1e-9a52-1d2f0c9b8e11\",\"job-type\":\"send-email\","
       "\"queue\":\"default\",\"priority\":\"normal\",\"status\":\"failed\","
       "\"args\":{\"to\":\"a@b.c\",\"kind\":\"receipt\"},\"retry-count\":1,"
       "\"created-at\":1760689408536,\"updated-at\":1760689408536,"
       "\"execute-at\":null,\"error\":{\"type\":\"no-handler\",\"message\":\"x\"}}"))

(deftest ^:unit a-job-round-trips-with-its-types
  (let [job {:id         (random-uuid)
             :job-type   :reports/generate
             :queue      :default
             :priority   :high
             :status     :pending
             :args       {:report-id :sales
                          :zone-id   (ZoneId/of "Europe/Amsterdam")
                          :at        (Instant/parse "2026-10-17T08:23:28.536003Z")
                          :user-id   (random-uuid)
                          :offset    (ZoneId/of "+02:00")
                          :every     (Duration/ofMinutes 90)
                          :month     (YearMonth/of 2026 10)
                          :opens     (OffsetTime/parse "09:00+02:00")
                          :callback  (URI/create "https://example.com/done")}
             :created-at (Instant/now)}]
    (is (= job (codec/decode-job (codec/encode-job job))))))

(deftest ^:unit a-job-written-as-json-still-decodes
  (let [job (codec/decode-job legacy-json)]
    (testing "the job's own fields are typed again"
      (is (= #uuid "7f8c1f0e-3b5a-4c1e-9a52-1d2f0c9b8e11" (:id job)))
      (is (= :send-email (:job-type job)))
      (is (= :default (:queue job)))
      (is (= :normal (:priority job)))
      (is (= :failed (:status job)))
      (is (= (Instant/ofEpochMilli 1760689408536) (:created-at job)))
      (is (nil? (:execute-at job)))
      (is (= :no-handler (get-in job [:error :type]))))
    (testing "args come back as JSON left them"
      (is (= {:to "a@b.c" :kind "receipt"} (:args job))))))

(deftest ^:unit nil-decodes-to-nil
  (is (nil? (codec/decode-job nil))))
