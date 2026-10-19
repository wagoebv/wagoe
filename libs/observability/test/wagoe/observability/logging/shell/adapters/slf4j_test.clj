(ns wagoe.observability.logging.shell.adapters.slf4j-test
  "The SLF4J adapter, read back through a Logback appender that keeps its events."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.observability.logging.ports :as ports]
            [wagoe.observability.logging.shell.adapters.slf4j :as slf4j])
  (:import [ch.qos.logback.classic Level Logger]
           [ch.qos.logback.classic.spi ILoggingEvent]
           [ch.qos.logback.core AppenderBase]
           [org.slf4j LoggerFactory MDC]))

(defn- capture
  "Run (f logger) against an adapter on a fresh Logback logger at TRACE, and
   return the events it produced."
  ([f] (capture {} f))
  ([config f]
   (let [logger-name (str "wagoe.test.slf4j." (random-uuid))
         ^Logger lb  (LoggerFactory/getLogger logger-name)
         events      (atom [])
         ;; MDC is read lazily; capture it while the call is still in progress.
         appender    (doto (proxy [AppenderBase] []
                             (append [^ILoggingEvent e]
                               (.prepareForDeferredProcessing e)
                               (swap! events conj e)))
                       (.start))]
     (.setLevel lb Level/TRACE)
     (.setAdditive lb false)
     (.addAppender lb appender)
     (try
       (f (slf4j/create-slf4j-logger (merge {:level :trace :logger-name logger-name} config)))
       @events
       (finally
         (.detachAppender lb appender))))))

(defn- summary [^ILoggingEvent e]
  [(str (.getLevel e)) (.getFormattedMessage e)])

(deftest ^:unit levels-map-onto-slf4j
  (let [events (capture (fn [l]
                          (ports/trace l "t")
                          (ports/debug l "d")
                          (ports/info l "i")
                          (ports/warn l "w")
                          (ports/error l "e")
                          (ports/fatal l "f")))]
    (is (= [["TRACE" "t"] ["DEBUG" "d"] ["INFO" "i"] ["WARN" "w"] ["ERROR" "e"] ["ERROR" "[FATAL] f"]]
           (mapv summary events)))))

(deftest ^:unit the-minimum-level-filters
  (testing "below the adapter's level nothing is written"
    (is (= [["WARN" "w"]]
           (mapv summary (capture {:level :warn} (fn [l] (ports/info l "i") (ports/warn l "w")))))))

  (testing "set-level! changes it at runtime and returns the old one"
    (let [events (capture {:level :info}
                          (fn [l]
                            (is (= :info (ports/set-level! l :error)))
                            (is (= :error (ports/get-level l)))
                            (ports/warn l "dropped")
                            (ports/error l "kept")))]
      (is (= [["ERROR" "kept"]] (mapv summary events))))))

(deftest ^:unit context-reaches-the-mdc
  (let [[^ILoggingEvent e] (capture {:default-tags {:tenant-id "t1"}}
                                    (fn [l]
                                      (ports/with-context l {:request-id "r1"}
                                        #(ports/info l "hello" {:user-id 7 :tags {:region "eu"}}))))
        mdc (into {} (.getMDCPropertyMap e))]
    (is (= {"tenantId" "t1" "requestId" "r1" "userId" "7" "tag.region" "eu"}
           (select-keys mdc ["tenantId" "requestId" "userId" "tag.region"])))))

(deftest ^:unit with-context-restores-the-previous-context
  (capture (fn [l]
             (ports/with-context l {:request-id "outer"}
               (fn []
                 (ports/with-context l {:request-id "inner"}
                   #(is (= {:request-id "inner"} (ports/current-context l))))
                 (is (= {:request-id "outer"} (ports/current-context l)))))
             (is (= {} (ports/current-context l))))))

(deftest ^:unit the-callers-mdc-survives-a-log-call
  ;; The adapter cleared the whole MDC after each call, so a value the
  ;; application had put there vanished from every later log line.
  (MDC/put "appKey" "kept")
  (try
    (capture (fn [l] (ports/info l "hello" {:request-id "r1"})))
    (is (= "kept" (MDC/get "appKey")))
    (is (nil? (MDC/get "requestId")) "the adapter's own keys do not leak")
    (finally
      (MDC/remove "appKey"))))

(deftest ^:unit exceptions-are-attached-redacted
  (let [driver (java.sql.SQLException. "Duplicate entry 'alice@example.com' for key 'users.email'" "23000" 1062)
        [^ILoggingEvent e] (capture (fn [l] (ports/error l "save failed" {} driver)))
        proxy  (.getThrowableProxy e)]
    (is (some? proxy) "the exception is attached")
    (is (not (.contains (str (.getMessage proxy)) "alice@example.com")))))

(deftest ^:unit audit-and-security-events
  (testing "an audit event logs at info, flagged in the MDC"
    (let [[^ILoggingEvent e] (capture (fn [l] (ports/audit-event l :user-action "u1" "order/7" :delete :success {})))]
      (is (= ["INFO" "Audit: u1 performed delete on order/7 -> success"] (summary e)))
      (is (= "true" (get (.getMDCPropertyMap e) "audit")))))

  (testing "security severity picks the level"
    (let [events (capture (fn [l]
                            (doseq [s [:critical :high :medium :low]]
                              (ports/security-event l :login-failed s {} {}))))]
      (is (= ["ERROR" "ERROR" "WARN" "INFO"] (mapv (comp first summary) events)))
      (is (= "[FATAL] Security [critical]: login-failed" (second (summary (first events)))))
      (is (every? #(= "true" (get (.getMDCPropertyMap ^ILoggingEvent %) "security")) events)))))

(deftest ^:unit set-config-merges-and-returns-the-old-config
  (capture (fn [l]
             (let [before (ports/get-config l)]
               (is (= before (ports/set-config! l {:extra 1})))
               (is (= 1 (:extra (ports/get-config l))))))))

(deftest ^:unit apply-level-sets-wagoe-and-root-and-restores-them
  (let [^Logger root  (LoggerFactory/getLogger org.slf4j.Logger/ROOT_LOGGER_NAME)
        ^Logger wagoe (LoggerFactory/getLogger "wagoe")
        before        [(.getLevel root) (.getLevel wagoe)]
        previous      (slf4j/apply-level! {:level :debug})]
    (try
      (testing ":debug shows Wagoe's detail but keeps the root at INFO"
        (is (= [Level/INFO Level/DEBUG] [(.getLevel root) (.getLevel wagoe)])))
      (testing ":root-level overrides the root"
        (slf4j/apply-level! {:level :warn :root-level :error})
        (is (= [Level/ERROR Level/WARN] [(.getLevel root) (.getLevel wagoe)])))
      (finally
        (slf4j/restore-levels! previous)))
    (is (= before [(.getLevel root) (.getLevel wagoe)]) "restore-levels! puts back what was there")))
