(ns wagoe.platform.shell.privacy-test
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [wagoe.platform.ports.privacy :as ports]
            [wagoe.platform.shell.privacy :as sut]
            [wagoe.platform.shell.system.wiring])
  (:import [java.time Duration Instant Period]))

(defn- exporter [f]
  (reify ports/IDataExporter
    (export-data [_ subject] (f subject))))

(defn- policy [calls n]
  (reify ports/IRetentionPolicy
    (purge-before! [_ cutoff] (swap! calls conj cutoff) n)))

(deftest ^:unit an-export-asks-every-exporter-about-the-subject
  (let [svc (sut/create-data-export
             {:user   (exporter (fn [{:keys [type id]}] (when (= :user type) {:id id})))
              :tenant (exporter (fn [{:keys [type]}] (when (= :tenant type) {:t 1})))})
        doc (ports/export-subject svc {:type :user :id 7})]
    (is (= {:user {:id 7}} (:sections doc)))
    (is (= {:type :user :id 7} (:subject doc)))
    (is (inst? (:exported-at doc))))

  (testing "a malformed subject is refused"
    (is (= :validation-error
           (:type (ex-data (is (thrown? clojure.lang.ExceptionInfo
                                        (ports/export-subject (sut/create-data-export {}) {:type :user}))))))))

  (testing "a failing exporter fails the export rather than leaving its section out"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"db down"
                          (ports/export-subject
                           (sut/create-data-export {:user (exporter (fn [_] (throw (ex-info "db down" {:type :internal-error}))))})
                           {:type :user :id 1})))))

(deftest ^:unit retention-runs-each-policy-at-its-own-cutoff
  (let [now     (Instant/parse "2026-03-31T12:00:00Z")
        a-calls (atom [])
        b-calls (atom [])
        result  (sut/run-retention! {:a (Period/ofYears 1) :b (Duration/ofDays 1)}
                                    {:a (policy a-calls 3) :b (policy b-calls 0)}
                                    now)]
    (is (= {:deleted {:a 3 :b 0} :failed {}} result))
    (is (= [(Instant/parse "2025-03-31T12:00:00Z")] @a-calls))
    (is (= [(Instant/parse "2026-03-30T12:00:00Z")] @b-calls))))

(deftest ^:unit a-failing-policy-fails-the-job-but-not-the-others
  (let [calls   (atom [])
        broken  (reify ports/IRetentionPolicy
                  (purge-before! [_ _] (throw (ex-info "locked" {:type :internal-error}))))
        handler (sut/retention-job-handler {:a (Period/ofDays 1) :b (Period/ofDays 1)}
                                           {:a broken :b (policy calls 2)})
        result  (handler {})]
    (is (false? (:success? result)))
    (is (= :retention-failed (get-in result [:error :type])))
    (is (= {:deleted {:b 2} :failed {:a "locked"}} (:result result)))
    (is (= 1 (count @calls)))))

(deftest ^:unit the-retention-component-is-a-job-handler-map
  (let [calls (atom [])
        handlers (ig/init-key :wagoe/retention {:policies    {:p {:retain "P30D"}}
                                                :contributed {:p (policy calls 5) :unused (policy calls 9)}})]
    (is (= [sut/retention-job-type] (keys handlers)))
    (is (= {:deleted {:p 5} :failed {}} (:result ((get handlers sut/retention-job-type) {}))))
    (is (= 1 (count @calls)) "a contributed policy nobody configured does not run"))

  (testing "a configured policy this service does not run is skipped"
    (let [handlers (ig/init-key :wagoe/retention {:policies {:p {:retain "P30D"}} :contributed {}})]
      (is (= {:deleted {} :failed {}} (:result ((get handlers sut/retention-job-type) {})))))))
