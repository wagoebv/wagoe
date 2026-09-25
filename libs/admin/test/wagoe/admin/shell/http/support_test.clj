(ns wagoe.admin.shell.http.support-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.admin.shell.http.support :as support]))

(def ^:private config
  {:fields {:age      {:type :int}
            :birthday {:type :date}
            :name     {:type :string}}})

(deftest ^:unit a-date-is-exactly-yyyy-mm-dd
  ;; BOU-519 decision: a :date is a calendar date, never a time part (BOU-521).
  (is (= {:birthday "1990-05-17"} (support/parse-form-params {"birthday" "1990-05-17"} config)))
  (is (= {:birthday "2024-02-29"} (support/parse-form-params {"birthday" "2024-02-29"} config))
      "a real leap day is a date")
  (doseq [bad ["1990-05-17T10:00" "1990-05-17 10:00:00" "1990-05-17T00:00:00Z" "17-05-1990"
               "2024-02-31" "2023-02-29" "2024-13-01" "2024-00-10"]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid date value"
                          (support/parse-form-params {"birthday" bad} config))
        bad)))

(deftest ^:unit checked-parsing-reports-every-bad-field-instead-of-throwing
  ;; The create and update handlers called parse-form-params outside their
  ;; error handling, so any value it could not read — an integer, a decimal, a
  ;; UUID, now a date — answered 500 instead of re-rendering the form.
  (let [[data errors] (support/parse-form-params-checked
                       {"age" "forty" "birthday" "1990-05-17T10:00" "name" "Ada"} config)]
    (testing "every bad field is reported, in the shape the form renders"
      (is (= #{:age :birthday} (set (keys errors))))
      (is (every? #(and (vector? %) (string? (first %))) (vals errors))))
    (testing "good fields are parsed, bad ones keep what the user typed"
      (is (= "Ada" (:name data)))
      (is (= "forty" (:age data)))
      (is (= "1990-05-17T10:00" (:birthday data)))))

  (testing "clean input gives no errors and the same data as parse-form-params"
    (let [params {"age" "41" "birthday" "1990-05-17"}]
      (is (= [(support/parse-form-params params config) {}]
             (support/parse-form-params-checked params config))))))

(deftest ^:unit an-unreadable-integer-is-an-error-not-a-null
  ;; parse-long answers nil for "forty", so the catch around it never fired
  ;; and the field was written as NULL — silently clearing the stored value.
  (is (= {:age 41} (support/parse-form-params {"age" "41"} config)))
  (doseq [bad ["forty" "12.5" "4 1"]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid integer value"
                          (support/parse-form-params {"age" bad} config))
        bad)))
