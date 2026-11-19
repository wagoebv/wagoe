(ns wagoe.webhooks.shell.module-wiring-test
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [wagoe.webhooks.shell.module-wiring :as wiring]))

(defn- graph [settings enabled profile]
  (wiring/ig-config settings {:enabled enabled :config {:wagoe/profile profile}}))

(deftest ^:unit needs-jobs-and-for-topics-events
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"needs :wagoe/jobs"
                        (graph {} #{} :dev)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":topics needs :wagoe/events"
                        (graph {:topics [:orders]} #{:wagoe/jobs} :dev)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"settings are invalid"
                        (graph {:max-attempts 0} #{:wagoe/jobs} :dev))))

(deftest ^:unit the-graph
  (let [{:keys [components job-handlers]} (graph {:topics [:orders]} #{:wagoe/jobs :wagoe/events} :prod)]
    (is (= [(ig/ref :wagoe.webhooks/job-handlers)] job-handlers))
    (is (= [:orders] (get-in components [:wagoe.webhooks/subscriber :topics])))
    (testing "https is required in prod and not in dev"
      (is (true? (get-in components [:wagoe/webhooks :settings :require-https?])))
      (is (false? (get-in (graph {} #{:wagoe/jobs} :dev)
                          [:components :wagoe/webhooks :settings :require-https?])))
      (is (false? (get-in (graph {:require-https? false} #{:wagoe/jobs} :prod)
                          [:components :wagoe/webhooks :settings :require-https?])))))
  (testing "no topics, no subscriber"
    (is (not (contains? (:components (graph {} #{:wagoe/jobs} :dev)) :wagoe.webhooks/subscriber)))))

(deftest ^:unit ^:security a-short-or-example-encryption-key-is-refused
  ;; BOU-1021: it encrypts the signing secrets at rest
  (let [boot #(try (ig/init-key :wagoe/webhooks {:settings {:encryption-key %1} :dev-like? %2}) nil
                   (catch clojure.lang.ExceptionInfo e (ex-message e)))]
    (is (re-find #"at least 32" (str (boot "short" true))))
    (is (re-find #"example value" (str (boot "your-webhook-encryption-key-of-32-characters" false))))
    (is (false? (get-in (graph {} #{:wagoe/jobs} :prod) [:components :wagoe/webhooks :dev-like?])))
    (is (true? (get-in (graph {} #{:wagoe/jobs} :dev) [:components :wagoe/webhooks :dev-like?])))
    (is (boolean? (get-in (graph {} #{:wagoe/jobs} nil) [:components :wagoe/webhooks :dev-like?]))
        "no profile: the active one's, as elsewhere")))
