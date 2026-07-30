(ns wagoe.devtools.core.guidance-test
  (:require [clojure.string]
            [clojure.test :refer [deftest is testing]]
            [wagoe.devtools.core.guidance :as guidance]))

(deftest ^:unit valid-level-test
  (testing "valid levels are recognized"
    (is (true? (guidance/valid-level? :full)))
    (is (true? (guidance/valid-level? :minimal)))
    (is (true? (guidance/valid-level? :off))))
  (testing "invalid levels are rejected"
    (is (false? (guidance/valid-level? :verbose)))
    (is (false? (guidance/valid-level? nil)))))

(deftest ^:unit format-startup-dashboard-test
  (testing "renders dashboard with all fields"
    (let [result (guidance/format-startup-dashboard
                  {:components     12
                   :errors         0
                   :database       "PostgreSQL @ localhost:5432/wagoe_dev"
                   :web-url        "http://localhost:3000"
                   :admin-url      "http://localhost:3000/admin"
                   :nrepl-port     7888
                   :modules        ["user" "admin" "payments"]
                   :guidance-level :full})]
      (is (string? result))
      (is (clojure.string/includes? result "Wagoe Dev"))
      (is (clojure.string/includes? result "12 components"))
      (is (clojure.string/includes? result "PostgreSQL"))
      (is (clojure.string/includes? result "user, admin, payments"))
      (is (clojure.string/includes? result "full"))))

  (testing "renders dashboard with minimal fields"
    (let [result (guidance/format-startup-dashboard
                  {:components     3
                   :errors         1
                   :modules        []
                   :guidance-level :minimal})]
      (is (string? result))
      (is (clojure.string/includes? result "3 components"))
      (is (clojure.string/includes? result "none")))))

(deftest ^:unit format-post-scaffold-guidance-test
  (testing "renders scaffold guidance with module name"
    (let [result (guidance/format-post-scaffold-guidance "invoice")]
      (is (clojure.string/includes? result "invoice"))
      (is (clojure.string/includes? result "bb scaffold integrate invoice"))
      (is (clojure.string/includes? result "libs/invoice/src/wagoe/invoice/schema.clj")))))

(deftest ^:unit pick-tip-test
  (testing "picks a tip for a known context"
    (let [[tip shown] (guidance/pick-tip :start #{})]
      (is (string? tip))
      (is (contains? shown tip))))

  (testing "returns nil when all tips shown"
    (let [all-tips (set (get guidance/tips :start))]
      (is (nil? (guidance/pick-tip :start all-tips)))))

  (testing "returns nil for unknown context"
    (is (nil? (guidance/pick-tip :nonexistent #{})))))

(deftest ^:unit format-tip-test
  (testing "formats tip at :full level"
    (let [result (guidance/format-tip "Try (routes)" :full)]
      (is (string? result))
      (is (clojure.string/includes? result "Tip"))))

  (testing "returns nil at non-full levels"
    (is (nil? (guidance/format-tip "Try (routes)" :minimal)))
    (is (nil? (guidance/format-tip "Try (routes)" :off)))))

(deftest ^:unit format-commands-test
  (testing "renders all command groups"
    (let [result (guidance/format-commands)]
      (is (clojure.string/includes? result "SYSTEM"))
      (is (clojure.string/includes? result "DATA"))
      (is (clojure.string/includes? result "DEBUG"))
      (is (clojure.string/includes? result "(go)")))))
