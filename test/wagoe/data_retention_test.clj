(ns wagoe.data-retention-test
  "Data export and retention through the assembled system: the user module's
   contributions reach platform's components, and retention is a recurring
   job only when configured."
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [wagoe.config :as config]
            [wagoe.jobs.ports :as job-ports]
            [wagoe.main :as main]
            [wagoe.platform.ports.privacy :as privacy]
            [wagoe.storage.ports :as storage-ports]
            [wagoe.storage.shell.export-jobs :as export-jobs]
            [wagoe.storage.shell.service :as storage]
            [wagoe.system-config :as sys-config]
            [wagoe.user.ports :as user-ports])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.time Duration Instant]
           [java.util.zip ZipInputStream]))

(defn- with-system [privacy-settings f]
  (let [root   (str (Files/createTempDirectory "wagoe-retention" (make-array FileAttribute 0)))
        cfg    (cond-> (-> (config/load-config {:profile :test})
                           (assoc-in [:active :wagoe/jobs] {:provider :memory :workers {:count 0}})
                           (assoc-in [:active :wagoe/storage] {:provider :local :root root}))
                 privacy-settings (assoc-in [:active :wagoe/privacy] privacy-settings))
        system (ig/init (main/worker-ig-config (sys-config/ig-config cfg)))]
    (try (f system) (finally (ig/halt! system)))))

(defn- audit! [audit user created-at]
  (user-ports/create-audit-log audit {:action :update :target-user-id (:id user)
                                      :target-user-email (:email user) :result :success
                                      :created-at created-at}))

(deftest ^:integration retention-runs-the-configured-policies-as-a-recurring-job
  (with-system {:retention {:user/audit-log {:retain "P1Y"}}}
    (fn [system]
      (let [users   (:wagoe/user-repository system)
            audit   (:wagoe/audit-repository system)
            ada     (user-ports/create-user users {:email (str (random-uuid) "@example.com") :name "Ada"
                                                   :role :user :active true :password-hash "x"})
            now     (Instant/now)
            _       (audit! audit ada (.minus now (Duration/ofDays 400)))
            recent  (audit! audit ada (.minus now (Duration/ofDays 1)))
            handler (job-ports/get-handler (:wagoe/job-registry system) :wagoe/retention)]
        (is (contains? @(:schedules (:wagoe/job-scheduler system)) :wagoe/retention))
        (is (:success? (handler {})))
        (is (= [(:id recent)]
               (map :id (user-ports/find-audit-logs-by-user audit (:id ada) {:limit 10}))))

        (testing "and the user module's exporter reaches the export"
          (is (= (:email ada)
                 (get-in (privacy/export-subject (:wagoe/data-export system) {:type :user :id (:id ada)})
                         [:sections :user :profile :email]))))))))

(defn- zip-entries [response]
  (let [out (ByteArrayOutputStream.)]
    ((requiring-resolve 'ring.core.protocols/write-body-to-stream) (:body response) response out)
    (with-open [zip (ZipInputStream. (ByteArrayInputStream. (.toByteArray out)))]
      (loop [acc {}]
        (if-let [entry (.getNextEntry zip)]
          (recur (assoc acc (.getName entry) (String. (.readAllBytes zip))))
          acc)))))

(deftest ^:integration a-users-uploads-are-exported-and-expire
  (with-system {:retention {:storage/uploads {:retain "P30D"}}}
    (fn [system]
      (let [service      (get-in system [:wagoe/storage :service])
            upload-index (:wagoe.storage/upload-index system)
            data-export  (:wagoe/data-export system)
            user-id      (random-uuid)
            upload!      #(-> (storage/upload-file service {:bytes (.getBytes ^String %) :content-type "text/plain"}
                                                   {:filename "note.txt" :owner {:user-id user-id}} {})
                              :data :key)
            old          (upload! "old")
            recent       (upload! "recent")
            subject      {:type :user :id user-id}
            doc          (privacy/export-subject data-export subject)]
        (is (= #{old recent} (set (map :key (get-in doc [:sections :storage :uploads])))))

        (testing "a zip export carries the files"
          (let [entries (zip-entries (privacy/export-response data-export doc {:query-params {"format" "zip"}}))]
            (is (= "old" (get entries (str "storage/" old))))
            (is (contains? entries "data-export.json"))))

        (testing "upload retention runs with the retention job"
          (storage-ports/record-upload! upload-index
                                        {:key old :user-id (str user-id) :size 3 :content-type "text/plain"
                                         :stored-at (.minus (Instant/now) (Duration/ofDays 40))})
          (is (:success? ((job-ports/get-handler (:wagoe/job-registry system) :wagoe/retention) {})))
          (is (= [recent] (map :key (get-in (privacy/export-subject data-export subject)
                                            [:sections :storage :uploads])))))))))

(deftest ^:integration retention-is-off-unless-configured
  (with-system nil
    (fn [system]
      (is (nil? (job-ports/get-handler (:wagoe/job-registry system) :wagoe/retention)))
      (is (not (contains? @(:schedules (:wagoe/job-scheduler system)) :wagoe/retention))))))

(deftest ^:integration a-requested-export-is-produced-by-the-job-and-expires
  (with-system {:retention {:storage/export-archives {:retain "P1D"}}}
    (fn [system]
      (let [{:keys [store job-queue] :as ctx} (:wagoe.storage/export-jobs system)
            registry (:wagoe/job-registry system)
            user-id  (random-uuid)
            {:keys [id]} (export-jobs/request-export! ctx {:type :user :id user-id} user-id)
            job      (job-ports/dequeue-job! job-queue :default "w1")]
        (is (:success? ((job-ports/get-handler registry :storage/data-export) (:args job))))
        (is (= :done (:status (storage-ports/find-export-job store id))))
        (storage-ports/save-export-job! store (assoc (storage-ports/find-export-job store id)
                                                     :created-at (.minus (Instant/now) (Duration/ofDays 2))))
        (is (:success? ((job-ports/get-handler registry :wagoe/retention) {})))
        (is (nil? (storage-ports/find-export-job store id)))))))
