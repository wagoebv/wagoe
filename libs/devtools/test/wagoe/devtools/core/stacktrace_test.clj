(ns wagoe.devtools.core.stacktrace-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [wagoe.devtools.core.stacktrace :as st]))

(deftest ^:unit classify-frame-test
  (testing "user code — the application's own namespaces"
    ;; What `wagoe new` produces: examples/shop, this repository's committed
    ;; generated application, is `shop.*` and nothing under `wagoe.*`.
    (is (= :user (st/classify-frame "shop.product.core.validation")))
    (is (= :user (st/classify-frame "myapp.orders.shell.persistence")))
    (is (= :user (st/classify-frame "acme.billing.core"))))

  (testing "framework — Wagoe's own libraries, whichever they are"
    (is (= :framework (st/classify-frame "wagoe.platform.shell.interceptors")))
    (is (= :framework (st/classify-frame "wagoe.observability.errors.core")))
    (is (= :framework (st/classify-frame "wagoe.devtools.core.guidance")))
    (is (= :framework (st/classify-frame "wagoe.core.validation.messages")))
    ;; These four were :user until BOU-395 — the list of framework prefixes
    ;; named four libraries and the framework has rather more than four.
    (is (= :framework (st/classify-frame "wagoe.user.shell.web-handlers")))
    (is (= :framework (st/classify-frame "wagoe.admin.shell.http")))
    (is (= :framework (st/classify-frame "wagoe.search.shell.http")))
    (is (= :framework (st/classify-frame "wagoe.tenant.shell.module-wiring"))))

  (testing "framework — third-party libraries"
    (is (= :framework (st/classify-frame "ring.middleware.params")))
    (is (= :framework (st/classify-frame "reitit.ring")))
    (is (= :framework (st/classify-frame "integrant.core")))
    (is (= :framework (st/classify-frame "malli.core"))))

  (testing "jvm — Java and Clojure internals"
    (is (= :jvm (st/classify-frame "java.lang.Thread")))
    (is (= :jvm (st/classify-frame "javax.servlet.http.HttpServlet")))
    (is (= :jvm (st/classify-frame "clojure.lang.AFn")))
    (is (= :jvm (st/classify-frame "clojure.core$map")))
    ;; The whole of clojure., not two of its namespaces: with :user as the
    ;; fallthrough these would otherwise be listed as the developer's code,
    ;; and clojure.main is in the trace of anything run from a REPL.
    (is (= :jvm (st/classify-frame "clojure.main$eval_opt")))
    (is (= :jvm (st/classify-frame "clojure.string$join")))))

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
             {:ns "shop.product.core.validation" :fn "validate" :file "validation.clj" :line 15}
             {:ns "shop.product.shell.persistence" :fn "save!" :file "persistence.clj" :line 30}
             {:ns "java.lang.Thread" :fn "run" :file "Thread.java" :line 829}])
        result (st/filter-stacktrace ex)]

    (testing "user frames extracted and ordered"
      (is (= 2 (count (:user-frames result))))
      (is (= "shop.product.core.validation" (:ns (first (:user-frames result))))))

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
