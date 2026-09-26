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

(deftest ^:unit the-display-zone-is-the-browsers-then-the-configured-then-amsterdam
  ;; BOU-523 + BOU-519 decision: storage and the JVM stay UTC (BOU-431); the
  ;; zone people see is the browser's, else :time-zone, else Europe/Amsterdam.
  (let [zone   (fn [config headers] (str (:zone-id (support/display-options config {:headers headers}))))
        cookie {"cookie" "a=b; wagoe_tz=America%2FNew_York"}]
    (testing "the browser's zone wins"
      (is (= "America/New_York" (zone {:time-zone "Asia/Tokyo"} cookie))))
    (testing "then the configured zone"
      (is (= "Asia/Tokyo" (zone {:time-zone "Asia/Tokyo"} {}))))
    (testing "then Amsterdam — not the server's zone, which is UTC by design"
      (is (= "Europe/Amsterdam" (zone {} {}))))
    (testing "an unknown zone in the cookie is ignored, not trusted"
      (is (= "Europe/Amsterdam" (zone {} {"cookie" "wagoe_tz=Mars%2FOlympus"})))))
  (testing "the server zone is always the JVM's, whatever is displayed"
    (is (= (java.time.ZoneId/systemDefault)
           (:server-zone-id (support/display-options {:time-zone "Asia/Tokyo"} {:headers {}}))))))

(deftest ^:unit ^:security return-to-stays-inside-the-admin
  ;; BOU-553: the prefix check alone let control characters and backslashes
  ;; through to the redirect.
  (doseq [[return-to expected]
          [["/web/admin/\\evil.com"     nil]
           ["/web/admin/%5Cevil.com"   nil]
           ["/web/admin/x\ny"         nil]
           ["/web/admin/x\ty"         nil]
           ["//evil.com/web/admin/"    nil]
           ["https://evil.com/web/admin/" nil]
           [""                         nil]
           ["/web/admin/users?page=2"  "/web/admin/users?page=2"]
           ["/web/admin/users#top"     "/web/admin/users#top"]]]
    (is (= expected (support/safe-return-to {:query-params {"return_to" return-to}}))
        (pr-str return-to))))
