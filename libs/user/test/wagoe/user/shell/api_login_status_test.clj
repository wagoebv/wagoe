(ns wagoe.user.shell.api-login-status-test
  "POST /auth/login and POST /sessions answered 200 {authenticated false} to a
   failed login, so a client checking the status took it for success (BOU-691)."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.auth :as auth]
            [wagoe.user.shell.http :as user-http]
            [wagoe.user.shell.interceptors :as user-interceptors]
            [wagoe.user.shell.in-memory-repository-test :as mem]
            [wagoe.user.shell.service :as service])
  (:import [java.time Duration Instant]))

(def ^:private password "Correct-Horse-9")

(defn- fixture
  "A real user service over in-memory repositories, with one user, Bob."
  []
  (let [users (mem/->MemoryUserRepository (atom {}))
        sess  (mem/->MemorySessionRepository (atom {}))
        svc   (service/create-user-service users sess (mem/->MemoryAuditRepository (atom []))
                                           {} (auth/create-authentication-service users sess nil {}))
        bob   (ports/register-user svc {:email "bob@x.org" :name "Bob" :password password :role :user})]
    {:svc svc :users users :bob bob}))

(defn- login-routes [svc]
  {"/auth/login" (user-http/login-handler svc)
   "/sessions"   (user-http/create-session-handler svc)})

(defn- post [handler body]
  (handler {:parameters {:body body}}))

(defn- update-bob! [{:keys [users bob]} changes]
  (.update-user ^wagoe.user.ports.IUserRepository users
                (merge (.find-user-by-id ^wagoe.user.ports.IUserRepository users (:id bob)) changes)))

(deftest ^:contract ^:security a-wrong-password-answers-401
  (let [{:keys [svc]} (fixture)]
    (doseq [[path handler] (login-routes svc)]
      (testing path
        (let [resp (post handler {:email "bob@x.org" :password "Wrong-Pass-1"})
              err  (get-in resp [:body :error])]
          (is (= 401 (:status resp)))
          (is (= "unauthorized" (:type err)))
          (is (string? (:message err)))
          (is (string? (:correlation-id err)))
          (is (nil? (get-in resp [:body :sessionToken]))))))))

(deftest ^:contract ^:security an-unknown-email-gets-the-same-body
  (let [{:keys [svc]} (fixture)
        refusal      (fn [handler body]
                       (let [resp (post handler body)]
                         [(:status resp) (dissoc (get-in resp [:body :error]) :correlation-id)]))]
    (doseq [[path handler] (login-routes svc)]
      (testing path
        (is (= (refusal handler {:email "bob@x.org" :password "Wrong-Pass-1"})
               (refusal handler {:email "nobody@x.org" :password "Wrong-Pass-1"})))))))

(deftest ^:contract ^:security an-inactive-account-gets-the-same-body
  (let [{:keys [svc] :as f} (fixture)]
    (update-bob! f {:active false})
    (let [resp (post (user-http/login-handler svc) {:email "bob@x.org" :password password})]
      (is (= 401 (:status resp)))
      (is (= "Invalid email or password" (get-in resp [:body :error :message]))))))

(deftest ^:contract ^:security a-locked-account-answers-429-with-retry-after
  (let [{:keys [svc] :as f} (fixture)]
    (update-bob! f {:lockout-until (.plus (Instant/now) (Duration/ofMinutes 10))})
    (doseq [[path handler] (login-routes svc)]
      (testing path
        (let [resp (post handler {:email "bob@x.org" :password password})
              secs (parse-long (get-in resp [:headers "Retry-After"]))]
          (is (= 429 (:status resp)))
          (is (= "account-locked" (get-in resp [:body :error :type])))
          (is (< 500 secs 601))
          (is (= secs (get-in resp [:body :error :details :retry-after-seconds]))))))))

(deftest ^:contract mfa-required-is-still-a-200
  (let [{:keys [svc] :as f} (fixture)]
    (update-bob! f {:mfa-enabled true})
    (doseq [[path handler] (login-routes svc)]
      (testing path
        (let [resp (post handler {:email "bob@x.org" :password password})]
          (is (= 200 (:status resp)))
          (is (true? (get-in resp [:body :requiresMfa])))
          (is (nil? (get-in resp [:body :sessionToken]))))))))

(deftest ^:contract the-right-password-still-signs-in
  (let [{:keys [svc]} (fixture)]
    (doseq [[path handler] (login-routes svc)]
      (testing path
        (let [resp (post handler {:email "bob@x.org" :password password})]
          (is (= 200 (:status resp)))
          (is (string? (get-in resp [:body :sessionToken]))))))))

(deftest ^:contract nested-maps-carry-both-key-styles
  ;; The kebab-case keys are deprecated; until 2.0 both are sent (BOU-737).
  (let [{:keys [svc]} (fixture)]
    (doseq [[path handler] (login-routes svc)]
      (testing path
        (let [body (:body (post handler {:email "bob@x.org" :password password}))]
          (is (= (:sessionToken body) (get-in body [:session :sessionToken])))
          (is (= (:jwtToken body) (get-in body [:session :jwtToken])))
          (doseq [[m k camel] [[:session :session-token :sessionToken] [:session :jwt-token :jwtToken]
                               [:session :user-id :userId] [:session :expires-at :expiresAt]
                               [:session :user-agent :userAgent] [:user :created-at :createdAt]]]
            (is (some? (get-in body [m k])) (str m " " k))
            (is (= (get-in body [m k]) (get-in body [m camel])) (str m " " k))))))))

(deftest ^:unit a-lockout-auth-shell-reports-is-still-a-429
  ;; A lockout set by a concurrent request reaches us as :authentication-failed.
  (let [enter (:enter user-interceptors/format-session-creation-response)
        resp  (:response (enter {:correlation-id "c"
                                 :session {:authenticated false :reason :authentication-failed
                                           :retry-after (.plus (Instant/now) (Duration/ofMinutes 5))}}))]
    (is (= 429 (:status resp)))
    (is (some? (get-in resp [:headers "Retry-After"])))))

