(ns wagoe.observability.tracing.tracer-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.observability.tracing.ports :as ports]
            [wagoe.observability.tracing.core :refer [with-span]]
            [wagoe.observability.tracing.shell.adapters.no-op :as no-op]
            [wagoe.observability.tracing.shell.adapters.logging :as logging]))

(deftest ^:unit no-op-tracer-is-inert-but-conformant
  (let [t (no-op/create-tracing-component)]
    (is (satisfies? ports/ITracer t))
    (testing "with-span runs the body and returns its value"
      (is (= 42 (with-span t [_sp "work"] 42)))
      (is (= 7 (with-span t [_sp "work" {:k "v"}] 7))))
    (testing "an exception still propagates"
      (is (thrown? clojure.lang.ExceptionInfo
                   (with-span t [_sp "work"] (throw (ex-info "boom" {}))))))
    (testing "span-context has nil ids"
      (is (= {:trace-id nil :span-id nil} (ports/span-context t (ports/start-span! t "x")))))))

(deftest ^:unit logging-tracer-spans-carry-ids-and-run-the-body
  (let [t (logging/create-tracing-component)]
    (is (satisfies? ports/ITracer t))
    (testing "with-span binds the span, runs the body, returns its value"
      (let [ctx (with-span t [sp "handle" {:route "/x"}]
                  (ports/add-event! t sp "checkpoint" {:n 1})
                  (ports/set-attributes! t sp {:user "u1"})
                  (ports/span-context t sp))]
        (is (re-matches #"[0-9a-f]{32}" (:trace-id ctx)))
        (is (re-matches #"[0-9a-f]{16}" (:span-id ctx)))))
    (testing "an exception is recorded and rethrown (span still ended in finally)"
      (is (thrown? clojure.lang.ExceptionInfo
                   (with-span t [_sp "work"] (throw (ex-info "boom" {}))))))
    (testing "start/end/attrs/event never throw on their own"
      (let [sp (ports/start-span! t "manual" {:a 1})]
        (is (some? sp))
        (is (nil? (ports/add-event! t sp "ev")))
        (is (nil? (ports/set-attributes! t sp {:b 2})))
        (is (nil? (ports/end-span! t sp)))
        (is (nil? (ports/end-span! t nil)) "ending nil is a no-op")))
    (testing "set-attributes! accumulates onto the span so span.end sees them"
      (let [sp (ports/start-span! t "manual" {:a 1})]
        (ports/set-attributes! t sp {:b 2})
        (ports/set-attributes! t sp {:a 9 :c 3})
        (is (= {:a 9 :b 2 :c 3} @(:attrs sp))
            "later set-attributes! merge over initial attrs")))))

(deftest ^:unit with-span-macro-supports-both-arities
  (let [t (no-op/create-tracing-component)]
    ;; no attributes
    (is (= :ok (with-span t [_sp "a"] :ok)))
    ;; with attributes
    (is (= :ok (with-span t [_sp "a" {:x 1}] :ok)))))
