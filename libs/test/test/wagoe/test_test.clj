(ns wagoe.test-test
  "The kit, run against this repository's own application the way a consumer
   runs it against theirs."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.events.ports :as event-ports]
            [wagoe.events.shell.publisher :as publisher]
            [wagoe.jobs.core.job :as job]
            [wagoe.jobs.ports :as job-ports]
            [wagoe.realtime.ports :as realtime-ports]
            [wagoe.system-config :as app]
            [wagoe.tenant.ports :as tenant-ports]
            [wagoe.test :as t]
            [wagoe.user.ports :as user-ports]))

(defn- with-modules
  "A :config tweak switching `modules` on, as the app's config.edn would."
  [modules]
  (fn [config] (update config :active merge modules)))

(deftest ^:integration boots-the-app-without-a-port-on-its-own-database
  (t/with-test-system [a app/ig-config]
    (t/with-test-system [b app/ig-config]
      (is (nil? (:wagoe/http-server a)) "a test system binds no port")
      (is (= 200 (:status (t/request (t/client a) :get "/health"))))
      (user-ports/register-user (:wagoe/user-service a)
                                {:email "only-in-a@example.com" :name "Ann" :role :user
                                 :password "Kit-Passw0rd-469!"})
      (is (nil? (user-ports/get-user-by-email (:wagoe/user-service b) "only-in-a@example.com"))
          "two test systems share a database"))))

(deftest ^:integration signs-in-and-passes-csrf
  (t/with-test-system [sys {:ig-config app/ig-config
                            :config #(assoc-in % [:active :wagoe/http :security :csrf :enabled?] true)}]
    (user-ports/register-user (:wagoe/user-service sys)
                              {:email "signer@example.com" :name "Kit" :role :user
                               :password "Kit-Passw0rd-469!"})
    (testing "CSRF is on: a request without a token is refused"
      (let [raw ((:wagoe/http-handler sys) {:request-method :post :uri "/web/login"
                                            :scheme :http :server-name "localhost" :server-port 80
                                            :headers {"content-type" "application/x-www-form-urlencoded"}
                                            :body (java.io.ByteArrayInputStream. (.getBytes "email=x"))})]
        (is (= 403 (:status raw)))))
    (let [c (t/client sys)]
      (is (= 302 (:status (t/sign-in! c "signer@example.com" "Kit-Passw0rd-469!"))))
      (is (= 200 (:status (t/request c :get "/web/dashboard"))) "the session was not kept")
      (testing "a signed-in, state-changing request carries a valid token"
        (is (= 302 (:status (t/request c :post "/web/logout"))))
        (is (not= 200 (:status (t/request c :get "/web/dashboard"))) "logout did not end the session")))
    (testing "a wrong password throws rather than leaving the test signed out"
      (is (thrown? clojure.lang.ExceptionInfo
                   (t/sign-in! (t/client sys) "signer@example.com" "wrong"))))))

(deftest ^:integration runs-jobs-only-when-asked
  (t/with-test-system [sys {:ig-config app/ig-config
                            :config (with-modules {:wagoe/jobs {:provider :memory :workers {:count 2}}})}]
    (let [ran (atom [])]
      (is (empty? (:wagoe/job-workers sys)) "a worker thread would race the test")
      (job-ports/register-handler! (:wagoe/job-registry sys) :kit/probe
                                   (fn [args] (swap! ran conj args) {:success? true}))
      (job-ports/enqueue-job! (:wagoe/job-queue sys) :default
                              (job/create-job {:job-type :kit/probe :args {:n 1} :queue :default}
                                              (random-uuid) (java.time.Instant/now)))
      (is (= [] @ran))
      (is (= 1 (t/run-pending-jobs! sys)))
      (is (= [{:n 1}] @ran)))))

(deftest ^:integration drains-events-and-realtime
  (t/with-test-system [sys {:ig-config app/ig-config
                            :config (with-modules {:wagoe/realtime {:provider :memory}})}]
    (let [handled (atom [])]
      (event-ports/subscribe! (:wagoe/events sys) :orders #(swap! handled conj (:type %)))
      (publisher/emit! (:wagoe/events sys) :orders :order/placed :kit {:id 1})
      (is (= [:order/placed] (map :type (t/drain-events! sys :orders))))
      (is (= [:order/placed] @handled) "drained before the subscriber ran"))
    (let [user-id (random-uuid)]
      (realtime-ports/send-to-user (get-in sys [:wagoe/realtime :service]) user-id {:type "ping"})
      (is (= [[:user user-id "ping"]]
             (map (juxt :route :target (comp :type :message)) (t/drain-realtime! sys))))
      (is (= [] (t/drain-realtime! sys))))))

(deftest ^:integration a-test-tenant-is-gone-afterwards
  (t/with-test-system [sys app/ig-config]
    (let [repo (:wagoe/tenant-repository sys)
          svc  (:wagoe/tenant-service sys)
          id   (t/with-test-tenant [tenant sys]
                 (is (some? (tenant-ports/get-tenant svc (:id tenant))))
                 (:id tenant))
          acme (t/call-with-tenant sys {:name "Acme"}
                                   (fn [tenant]
                                     (is (= "Acme" (:name (tenant-ports/get-tenant svc (:id tenant)))))
                                     (:id tenant)))]
      (is (nil? (tenant-ports/find-tenant-by-id repo id)))
      (is (nil? (tenant-ports/find-tenant-by-id repo acme))))))
