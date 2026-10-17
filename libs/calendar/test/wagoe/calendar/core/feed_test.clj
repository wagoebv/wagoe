(ns wagoe.calendar.core.feed-test
  (:require [wagoe.calendar.core.feed :as sut]
            [clojure.test :refer [deftest is testing]]))

(deftest ^:unit ical-response-test
  (testing "defaults"
    (is (= {:status  200
            :headers {"Content-Type"        "text/calendar; charset=utf-8"
                      "Content-Disposition" "attachment; filename=\"calendar.ics\""
                      "Cache-Control"       "private, max-age=300"}
            :body    "BEGIN:VCALENDAR"}
           (sut/ical-response "BEGIN:VCALENDAR"))))
  (testing "filename and max-age"
    (let [{:keys [headers]} (sut/ical-response "" {:filename "team.ics" :max-age 60})]
      (is (= "attachment; filename=\"team.ics\"" (get headers "Content-Disposition")))
      (is (= "private, max-age=60" (get headers "Cache-Control")))))
  (testing "a filename cannot break out of its header"
    (is (= "attachment; filename=\"a.icsX-Evil: 1\""
           (get-in (sut/ical-response "" {:filename "a.ics\"\r\nX-Evil: 1"})
                   [:headers "Content-Disposition"])))))
