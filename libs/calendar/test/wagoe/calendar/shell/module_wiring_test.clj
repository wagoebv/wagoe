(ns wagoe.calendar.shell.module-wiring-test
  (:require [wagoe.calendar.ports :as ports]
            [wagoe.calendar.shell.module-wiring]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [integrant.core :as ig])
  (:import [java.time Instant]))

(deftest ^:unit the-component-is-a-calendar-adapter
  (let [cal (ig/init-key :wagoe/calendar {:enabled? true})]
    (is (satisfies? ports/CalendarAdapterProtocol cal))
    (is (true? (:enabled? cal)) "the settings stay readable")
    (is (str/includes? (ports/export-ical cal [{:id       (random-uuid)
                                                :title    "Standup"
                                                :start    (Instant/parse "2026-03-10T09:00:00Z")
                                                :end      (Instant/parse "2026-03-10T09:30:00Z")
                                                :timezone "UTC"}]
                                          {})
                       "SUMMARY:Standup"))))
