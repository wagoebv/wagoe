(ns wagoe.platform.shell.http.tracing-interceptor-test
  "Unit tests for the http-request-tracing interceptor: a span is started on
   enter, the status is recorded and the span ended on leave, exceptions are
   recorded on error, and everything is a no-op when no tracer is wired."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.platform.shell.http.interceptors :as interceptors]
            [wagoe.observability.tracing.ports :as tracing-ports]))

(defn- recording-tracer [log]
  (reify tracing-ports/ITracer
    (start-span! [_ n] (swap! log conj [:start n {}]) {:span n})
    (start-span! [_ n a] (swap! log conj [:start n a]) {:span n})
    (end-span! [_ s] (swap! log conj [:end s]) nil)
    (add-event! [_ _ _] nil)
    (add-event! [_ _ _ _] nil)
    (set-attributes! [_ s a] (swap! log conj [:attrs s a]) nil)
    (record-exception! [_ s t] (swap! log conj [:exc s (.getMessage ^Throwable t)]) nil)
    (span-context [_ _] {:trace-id nil :span-id nil})
    (with-span* [_ _ _ f] (f {:span :x}))))

(def ^:private interceptor interceptors/http-request-tracing)

(deftest ^:unit tracing-interceptor-spans-a-successful-request
  (let [log (atom [])
        t   (recording-tracer log)
        ctx {:request {:request-method :get :uri "/widgets"}
             :system {:tracer t}
             :correlation-id "corr-1"}
        after-enter ((:enter interceptor) ctx)
        _           ((:leave interceptor) (assoc after-enter :response {:status 200}))
        events @log]
    (testing "enter starts a named span and stashes it on the context"
      (is (some? (get-in after-enter [:tracing :span])))
      (is (= [:start "HTTP GET /widgets"
              {:http.request.method "get" :url.path "/widgets" :correlation-id "corr-1"}]
             (first events))))
    (testing "leave records status then ends the span"
      (is (some (fn [e] (and (= :attrs (first e))
                             (= {:http.response.status_code 200} (last e)))) events))
      (is (some #(= :end (first %)) events)))))

(deftest ^:unit tracing-interceptor-records-exception-on-error
  (let [log (atom [])
        t   (recording-tracer log)
        ctx ((:enter interceptor) {:request {:request-method :post :uri "/orders"}
                                   :system {:tracer t}
                                   :correlation-id "corr-2"})
        _   ((:error interceptor) (assoc ctx :exception (ex-info "kaboom" {})))
        events @log]
    (is (some (fn [e] (and (= :exc (first e)) (= "kaboom" (last e)))) events)
        "the exception was recorded on the span")
    (is (some #(= :end (first %)) events) "the span was ended")))

(deftest ^:unit tracing-interceptor-is-a-no-op-without-a-tracer
  (let [ctx {:request {:request-method :get :uri "/x"} :system {} :correlation-id "c"}
        after-enter ((:enter interceptor) ctx)]
    (is (= ctx after-enter) "enter leaves the context untouched with no tracer")
    (is (nil? (get-in after-enter [:tracing :span])))
    (testing "leave and error do not throw"
      (is (map? ((:leave interceptor) (assoc after-enter :response {:status 200}))))
      (is (map? ((:error interceptor) (assoc after-enter :exception (ex-info "e" {}))))))))
