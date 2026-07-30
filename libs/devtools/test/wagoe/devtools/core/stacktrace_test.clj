(ns wagoe.devtools.core.stacktrace-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [wagoe.devtools.core.stacktrace :as st]))

(deftest ^:unit classify-frame-test
  (testing "user code — boundary namespace not in framework list"
    (is (= :user (st/classify-frame "wagoe.product.core.validation")))
    (is (= :user (st/classify-frame "wagoe.invoice.shell.persistence"))))

  (testing "framework — boundary internal libraries"
    (is (= :framework (st/classify-frame "wagoe.platform.shell.interceptors")))
    (is (= :framework (st/classify-frame "wagoe.observability.errors.core")))
    (is (= :framework (st/classify-frame "wagoe.devtools.core.guidance")))
    (is (= :framework (st/classify-frame "wagoe.core.validation.messages"))))

  (testing "framework — third-party libraries"
    (is (= :framework (st/classify-frame "ring.middleware.params")))
    (is (= :framework (st/classify-frame "reitit.ring")))
    (is (= :framework (st/classify-frame "integrant.core")))
    (is (= :framework (st/classify-frame "malli.core"))))

  (testing "jvm — Java and Clojure internals"
    (is (= :jvm (st/classify-frame "java.lang.Thread")))
    (is (= :jvm (st/classify-frame "javax.servlet.http.HttpServlet")))
    (is (= :jvm (st/classify-frame "clojure.lang.AFn")))
    (is (= :jvm (st/classify-frame "clojure.core$map")))))

(defn- make-exception-with-trace
  "Create an exception with a synthetic stack trace for testing."
  [frames]
  (let [ex (Exception. "test error")
        elements (into-array StackTraceElement
                             (map (fn [{:keys [ns fn file line]}]
                                    (StackTraceElement. ns fn file line))
                                  frames))]
    (.setStackTrace ex elements)
    ex))

(deftest ^:unit filter-stacktrace-test
  (let [ex (make-exception-with-trace
            [{:ns "clojure.core$map" :fn "invoke" :file "core.clj" :line 100}
             {:ns "wagoe.platform.shell.interceptors" :fn "execute" :file "interceptors.clj" :line 42}
             {:ns "wagoe.product.core.validation" :fn "validate" :file "validation.clj" :line 15}
             {:ns "wagoe.product.shell.persistence" :fn "save!" :file "persistence.clj" :line 30}
             {:ns "java.lang.Thread" :fn "run" :file "Thread.java" :line 829}])
        result (st/filter-stacktrace ex)]

    (testing "user frames extracted and ordered"
      (is (= 2 (count (:user-frames result))))
      (is (= "wagoe.product.core.validation" (:ns (first (:user-frames result))))))

    (testing "framework and jvm frames counted"
      (is (= 1 (count (:framework-frames result))))
      (is (= 2 (count (:jvm-frames result)))))

    (testing "total-hidden is framework + jvm count"
      (is (= 3 (:total-hidden result))))))

(deftest ^:unit format-stacktrace-test
  (let [filtered {:user-frames [{:ns "wagoe.product.core.validation"
                                 :fn-name "validate"
                                 :file "validation.clj"
                                 :line 15}]
                  :framework-frames [{:ns "ring.middleware.params" :fn-name "wrap" :file "params.clj" :line 10}]
                  :jvm-frames [{:ns "java.lang.Thread" :fn-name "run" :file "Thread.java" :line 829}]
                  :total-hidden 2}
        output (st/format-stacktrace filtered)]

    (testing "output contains user code section"
      (is (str/includes? output "Your code"))
      (is (str/includes? output "wagoe.product.core.validation/validate")))

    (testing "output contains hidden frame count"
      (is (str/includes? output "2 frames")))))
