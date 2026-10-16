(ns wagoe.reports.shell.service-test
  (:require [wagoe.reports.ports :as ports]
            [wagoe.reports.shell.jobs-integration :as jobs-integration]
            [wagoe.reports.shell.registry :as registry]
            [wagoe.reports.shell.service :as sut]
            [wagoe.email.ports]
            [wagoe.jobs.ports]
            [wagoe.storage.ports]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.nio.charset StandardCharsets]))

(use-fixtures :each (fn [f] (registry/clear-registry!) (f) (registry/clear-registry!)))

(def ^:private sales
  {:id          :sales
   :type        :csv
   :data-source (fn [_] [{:product "Widget"}])
   :sections    [{:type :table :columns [{:key :product :label "Product"}]}]})

(defn- text [^bytes b] (String. b StandardCharsets/UTF_8))

(defn- fake-queue [enqueued]
  (reify wagoe.jobs.ports/IJobQueue
    (enqueue-job! [_ queue-name job] (swap! enqueued conj [queue-name job]) (:id job))))

(defn- fake-storage [stored url]
  (reify wagoe.storage.ports/IFileStorage
    (store-file [_ file-data metadata]
      (swap! stored conj [file-data metadata])
      {:key (str (:path metadata) "/" (:filename metadata)) :url url})))

(defn- fake-email [sent result]
  (reify wagoe.email.ports/EmailSenderProtocol
    (send-email! [_ email] (swap! sent conj email) result)))

(defn- thrown-type [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

(deftest ^:unit generates-from-a-definition-or-a-registered-id
  (let [svc (sut/create-report-service {})]
    (is (= "Product\r\nWidget\r\n" (text (:bytes (ports/generate svc sales {})))))
    (ports/register-report! svc sales)
    (is (= [:sales] (ports/list-reports svc)))
    (is (= :csv (:type (ports/generate svc :sales {}))))
    (is (= :not-found (thrown-type #(ports/generate svc :nope {}))))))

(deftest ^:unit an-unknown-type-is-a-validation-error
  (is (= :validation-error (thrown-type #(sut/generate (assoc sales :type :odt) {})))))

(deftest ^:unit generate-async-refuses-what-it-cannot-do
  (registry/register-report! sales)
  (testing "without the jobs module"
    (is (= :missing-dependency
           (thrown-type #(ports/generate-async (sut/create-report-service {}) :sales {})))))
  (let [svc (sut/create-report-service {:job-queue (fake-queue (atom []))})]
    (testing ":storage-key without storage"
      (is (= :missing-dependency (thrown-type #(ports/generate-async svc :sales {:storage-key "r"})))))
    (testing ":notify-email without email"
      (is (= :missing-dependency (thrown-type #(ports/generate-async svc :sales {:notify-email "a@b.c"})))))
    (testing "an unregistered definition, whose functions cannot reach a worker"
      (is (= :not-found (thrown-type #(ports/generate-async svc (assoc sales :id :other) {})))))))

(defn- run-job [svc [_ job]]
  (jobs-integration/process-report-job svc (:args job)))

(deftest ^:unit async-output-is-stored-and-the-link-mailed
  (registry/register-report! sales)
  (let [enqueued (atom []) stored (atom []) sent (atom [])
        svc      (sut/create-report-service
                  {:job-queue    (fake-queue enqueued)
                   :file-storage (fake-storage stored "https://files/reports/sales.csv")
                   :email-sender (fake-email sent {:success? true})
                   :from         "reports@example.com"})
        job-id   (ports/generate-async svc :sales {:storage-key "reports" :notify-email "fin@example.com"})
        [queue job :as entry] (first @enqueued)]
    (is (uuid? job-id))
    (is (= :default queue))
    (is (= {:report-id :sales :opts {:storage-key "reports" :notify-email "fin@example.com"}}
           (:args job))
        "only the id travels; the definition holds functions")
    (is (= jobs-integration/job-type (:job-type job)))
    (let [result (run-job svc entry)
          [file-data metadata] (first @stored)
          email (first @sent)]
      (is (:success? result))
      (is (= "reports/sales.csv" (get-in result [:result :storage-key])))
      (is (= "Product\r\nWidget\r\n" (text (:bytes file-data))))
      (is (= {:filename "sales.csv" :path "reports"} metadata))
      (is (= ["fin@example.com"] (:to email)))
      (is (= "reports@example.com" (:from email)))
      (is (re-find #"https://files/reports/sales.csv" (:body email)))
      (is (nil? (:attachments email))))))

(deftest ^:unit without-a-link-the-file-is-attached
  (registry/register-report! sales)
  (let [enqueued (atom []) sent (atom [])
        svc      (sut/create-report-service {:job-queue    (fake-queue enqueued)
                                             :email-sender (fake-email sent {:success? true})})]
    (ports/generate-async svc :sales {:notify-email "fin@example.com"})
    (run-job svc (first @enqueued))
    (let [[attachment] (:attachments (first @sent))]
      (is (= "sales.csv" (:filename attachment)))
      (is (= "Product\r\nWidget\r\n" (text (:content attachment)))))))

(deftest ^:unit a-failed-notification-fails-the-job
  (registry/register-report! sales)
  (let [enqueued (atom [])
        svc      (sut/create-report-service
                  {:job-queue    (fake-queue enqueued)
                   :email-sender (fake-email (atom []) {:success? false :error {:message "smtp down"}})})]
    (ports/generate-async svc :sales {:notify-email "fin@example.com"})
    (is (= :internal-error (thrown-type #(run-job svc (first @enqueued)))))))

(deftest ^:unit a-worker-without-the-definition-says-so
  (is (= :not-found (thrown-type #(jobs-integration/process-report-job
                                   (sut/create-report-service {})
                                   {:report-id :sales :opts {}})))))
