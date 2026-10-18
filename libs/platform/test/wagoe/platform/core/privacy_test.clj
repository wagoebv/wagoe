(ns wagoe.platform.core.privacy-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.platform.core.privacy :as sut])
  (:import [java.time Duration Instant Period]))

(def ^:private now (Instant/parse "2026-03-31T12:00:00Z"))

(deftest ^:unit periods-and-durations-parse-and-nothing-else-does
  (is (= (Period/ofYears 2) (sut/parse-retain "P2Y")))
  (is (= (Duration/ofHours 12) (sut/parse-retain "PT12H")))
  (doseq [bad ["2 years" "" nil "P0D" "PT0S" "-P1D" "P1Y-2M"]]
    (testing (pr-str bad)
      (is (= :configuration-error (get-in (sut/parse-retain bad) [:error :type]))))))

(deftest ^:unit a-cutoff-counts-calendar-months
  (is (= (Instant/parse "2026-02-28T12:00:00Z") (sut/cutoff (Period/ofMonths 1) now)))
  (is (= (Instant/parse "2026-03-31T00:00:00Z") (sut/cutoff (Duration/ofHours 12) now))))

(deftest ^:unit the-plan-holds-only-configured-policies
  (is (= {:plan {:user/audit-log (Period/ofYears 2)}}
         (sut/retention-plan {:user/audit-log {:retain "P2Y"}} [:user/audit-log :jobs/dlq])))
  (testing "a policy no module provides is an error, not a no-op"
    (is (re-find #":user/audit-lg"
                 (get-in (sut/retention-plan {:user/audit-lg {:retain "P2Y"}} [:user/audit-log])
                         [:error :message]))))
  (testing "as is a malformed period, named by its policy"
    (is (re-find #"^:user/audit-log: "
                 (get-in (sut/retention-plan {:user/audit-log {:retain "two years"}} [:user/audit-log])
                         [:error :message])))))

(deftest ^:unit the-export-document-leaves-out-exporters-with-nothing
  (is (= {:format      "wagoe.data-export/1"
          :subject     {:type :user :id 1}
          :exported-at now
          :sections    {:user {:profile {}}}}
         (sut/export-document {:type :user :id 1 :extra true} {:user {:profile {}} :tenant nil} now))))

(deftest ^:unit a-subject-needs-a-known-type-and-an-id
  (is (nil? (sut/subject-error {:type :tenant :id "acme"})))
  (is (= :validation-error (get-in (sut/subject-error {:type :group :id 1}) [:error :type])))
  (is (= :validation-error (get-in (sut/subject-error {:type :user}) [:error :type]))))
