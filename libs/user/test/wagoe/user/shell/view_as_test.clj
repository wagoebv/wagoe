(ns wagoe.user.shell.view-as-test
  "An administrator views the app as another user, read-only (BOU-868), on H2."
  (:require [clojure.string :as str]
            [reitit.ring :as ring]
            [clojure.test :refer [deftest is testing]]
            [support.test-db :as test-db]
            [wagoe.shared.ui.core.layout :as layout]
            [wagoe.shared.ui.core.view-as :as view-as-ui]
            [wagoe.user.core.view-as :as view-as]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.middleware :as middleware]
            [wagoe.user.shell.persistence :as persistence]
            [wagoe.user.shell.service :as user-service]
            [wagoe.core.http.access]
            [wagoe.tenant.ports]
            [wagoe.user.shell.http]
            [wagoe.user.shell.web-handlers :as web-handlers])
  (:import [java.time Instant]
           [java.util UUID]))

(defn- with-app [f]
  (test-db/with-db "user_view_as"
    (fn [ctx]
      (persistence/initialize-user-schema! ctx)
      (let [users    (persistence/create-user-repository ctx)
            sessions (persistence/create-session-repository ctx)
            audit    (persistence/create-audit-repository ctx {:default-limit 50 :max-limit 100})
            svc      (user-service/create-user-service users sessions audit {} nil)
            user!    (fn [email m] (ports/create-user users (merge {:email email :name email :role :user :active true
                                                                    :password-hash "x"} m)))]
        (f {:svc svc :sessions sessions :audit audit :user! user!})))))

(defn- sign-in!
  "A session for `user`: [token session]."
  [sessions user]
  (let [token (str (UUID/randomUUID))]
    (ports/create-session sessions {:user-id (:id user) :session-token token :created-at (Instant/now)
                                    :expires-at (.plusSeconds (Instant/now) 3600)})
    [token (ports/find-session-by-token sessions token)]))

(defn- send!
  ([svc token method uri] (send! svc token method uri {}))
  ([svc token method uri headers]
   (((middleware/authenticate-if-present svc)
     (fn [req] {:status 200 :user (:user req) :viewing (:viewing-as req) :bar view-as-ui/*viewing*}))
    {:request-method method :uri uri :headers (merge {"x-session-token" token} headers)})))

(defn- session-of [sessions token] (ports/find-session-by-token sessions token))

(defn- actions [audit user-id]
  (set (map (comp name :action) (ports/find-audit-logs-by-user audit user-id {:limit 20}))))

(deftest ^:integration ^:security an-administrator-views-the-app-as-a-user-read-only
  (with-app
    (fn [{:keys [svc sessions audit user!]}]
      (let [admin (user! "admin@example.com" {:role :admin})
            ada   (user! "ada@example.com" {:name "Ada"})
            [token session] (sign-in! sessions admin)]
        (is (= (:id admin) (:id (:user (send! svc token :get "/web")))) "before: the administrator")
        (let [{:keys [until]} (ports/start-view-as svc admin session (:id ada))
              minutes (.toMinutes (java.time.Duration/between (Instant/now) until))]
          (is (<= 29 minutes 30) "about half an hour"))
        (testing "every request is Ada's, the administrator kept beside it, the bar on the page"
          (let [r (send! svc token :get "/web/orders")]
            (is (= (:id ada) (:id (:user r))))
            (is (= (:id admin) (get-in r [:viewing :admin :id])))
            (is (= {:name "Ada" :stop-path view-as/stop-path} (:bar r)))))
        (testing "nothing changes: every write is refused, but stopping and signing out"
          (doseq [m [:post :put :patch :delete]]
            (let [r (send! svc token m "/web/orders/1" {"hx-request" "true"})]
              (is (= 403 (:status r)) (name m))
              (is (nil? (:user r)) "the handler is not reached")))
          (testing "a browser's form goes back to its page, the bar there, saying why"
            (let [r (send! svc token :post "/web/orders/1" {"referer" "https://evil.example/web/orders?page=2"})]
              (is (= 303 (:status r)))
              (is (= "/web/orders?page=2" (get-in r [:headers "Location"])) "the path only, never another site")
              (is (= {:error :user.view-as/disabled} (:flash r)))))
          (is (= 403 (:status (send! svc token :post "/api/v1/orders"))) "an API call is a 403")
          (let [r (send! svc token :get "/ws" {"upgrade" "websocket" "connection" "Upgrade"})]
            (is (= 403 (:status r)) "no socket opens: its messages could write (BOU-1004)")
            (is (str/includes? (str (:body r)) "Read-only while viewing")))
          (is (= 200 (:status (send! svc token :post view-as/stop-path))))
          (is (= 200 (:status (send! svc token :post "/web/logout"))) "signing out ends it")
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Not while viewing"
                                ((wagoe.user.shell.http/export-own-data-handler nil)
                                 {:user ada :viewing-as {:admin admin}}))
              "their data export is no way to their data"))
        (testing "the administrator is the actor of what the view audits"
          (is (= (:id admin) (:actor-id (user-service/request-audit-context
                                         {:user ada :viewing-as {:admin admin}})))))
        (testing "starting and stopping are audited, the administrator the actor"
          (is (contains? (actions audit (:id ada)) "view-as-start"))
          (is (= (:id ada) (:id (ports/stop-view-as svc admin (session-of sessions token)))) "returns whom it viewed as")
          (is (contains? (actions audit (:id ada)) "view-as-stop"))
          (is (every? #(= (:id admin) (:actor-id %))
                      (filter #(str/starts-with? (name (:action %)) "view-as")
                              (ports/find-audit-logs-by-user audit (:id ada) {:limit 20})))))
        (testing "stopped, the administrator again, and writing again"
          (let [r (send! svc token :post "/web/orders/1")]
            (is (= 200 (:status r)))
            (is (= (:id admin) (:id (:user r))))
            (is (nil? (:bar r)))))))))

(deftest ^:integration ^:security a-view-ends-by-itself-and-follows-the-user
  (with-app
    (fn [{:keys [svc sessions audit user!]}]
      (let [admin (user! "admin@example.com" {:role :admin})
            ada   (user! "ada@example.com" {})
            [token session] (sign-in! sessions admin)]
        (testing "past its time it is the administrator again, the view ended and audited"
          (ports/set-view-as sessions (:id session) (:id ada) (.minusSeconds (Instant/now) 1))
          (is (= (:id admin) (:id (:user (send! svc token :get "/web")))))
          (is (nil? (:view-as-user-id (session-of sessions token))) "so it blocks no new view")
          (is (contains? (actions audit (:id ada)) "view-as-stop")))
        (testing "an access write does not undo a view set after the session was read"
          (let [stale (session-of sessions token)]
            (ports/set-view-as sessions (:id session) (:id ada) (.plusSeconds (Instant/now) 600))
            (ports/update-session sessions (assoc stale :last-accessed-at (Instant/now)))
            (is (= (:id ada) (:view-as-user-id (session-of sessions token))))))
        (testing "a user made an administrator meanwhile is no longer viewed as"
          (ports/set-view-as sessions (:id session) (:id ada) (.plusSeconds (Instant/now) 600))
          (is (= (:id ada) (:id (:user (send! svc token :get "/web")))))
          (ports/update-user-profile svc {:id (:id ada) :role :admin})
          (is (= (:id admin) (:id (:user (send! svc token :get "/web")))))
          (is (= "/web" (get-in ((web-handlers/stop-view-as-handler svc {})
                                 {:request-method :post :uri view-as/stop-path :headers {} :user admin :session session})
                                [:headers "Location"]))
              "not viewing: home"))))))

(deftest ^:integration ^:security who-may-not-be-viewed-as
  (with-app
    (fn [{:keys [svc sessions user!]}]
      (let [admin   (user! "admin@example.com" {:role :admin})
            other   (user! "other@example.com" {:role :admin})
            gone    (user! "gone@example.com" {:active false})
            ada     (user! "ada@example.com" {})
            bob     (user! "bob@example.com" {})
            [_ session] (sign-in! sessions admin)
            reason  (fn [actor s target-id]
                      (try (ports/start-view-as svc actor s target-id) nil
                           (catch clojure.lang.ExceptionInfo e ((juxt :type :reason) (ex-data e)))))]
        (is (= [:forbidden :admin] (reason admin session (:id other))))
        (is (= [:forbidden :self] (reason admin session (:id admin))))
        (is (= [:forbidden :inactive] (reason admin session (:id gone))))
        (is (= [:not-found nil] (reason admin session (random-uuid))))
        (is (= [:forbidden :not-admin] (reason ada (second (sign-in! sessions ada)) (:id bob))))
        (testing "a tenant's administrator, not another tenant's user"
          (let [boss (user! "boss@a.test" {:role :admin :tenant-id (random-uuid)})]
            (is (= [:not-found nil] (reason boss (second (sign-in! sessions boss)) (:id ada))))))
        (testing "a session store that keeps no views refuses, not a 500"
          (let [svc' (user-service/create-user-service (:user-repository svc) (reify ports/IUserSessionRepository)
                                                       (:audit-repository svc) {} nil)]
            (is (= [:forbidden :unsupported]
                   (try (ports/start-view-as svc' admin session (:id ada)) nil
                        (catch clojure.lang.ExceptionInfo e ((juxt :type :reason) (ex-data e))))))))
        (testing "no view without its audit entry"
          (let [failing #_{:clj-kondo/ignore [:missing-protocol-method]}
                        (reify ports/IUserAuditRepository
                          (create-audit-log [_ _] (throw (ex-info "audit down" {}))))
                svc'    (user-service/create-user-service (:user-repository svc) sessions failing {} nil)
                [token' s'] (sign-in! sessions admin)]
            (is (thrown? Exception (ports/start-view-as svc' admin s' (:id ada))))
            (is (nil? (:view-as-user-id (session-of sessions token'))))))
        (testing "not nested"
          (let [[token s] (sign-in! sessions admin)]
            (ports/start-view-as svc admin s (:id ada))
            (is (= [:forbidden :nested] (reason admin (session-of sessions token) (:id bob))))))))))

(deftest ^:integration the-handlers-start-and-stop
  (with-app
    (fn [{:keys [svc sessions user!]}]
      (let [admin  (user! "admin@example.com" {:role :admin})
            ada    (user! "ada@example.com" {})
            other  (user! "other@example.com" {:role :admin})
            [token session] (sign-in! sessions admin)
            start  #((web-handlers/start-view-as-handler svc {})
                     {:request-method :post :uri "/" :user admin :session session :headers {}
                      :path-params {:id (str (:id %))}})]
        (testing "an admin of this tenant by membership is refused before anything is set (BOU-1004)"
          (let [ms #_{:clj-kondo/ignore [:missing-protocol-method]}
                (reify wagoe.tenant.ports/ITenantMembershipService
                  (get-active-membership [_ user-id _] (when (= user-id (:id ada)) {:role :admin})))
                t  (random-uuid)
                r  ((web-handlers/start-view-as-handler svc {:membership-service ms})
                    {:request-method :post :uri "/" :user admin :session session :headers {}
                     :tenant {:id t} :path-params {:id (str (:id ada))}})]
            (is (= :user.view-as/refused-admin (get-in r [:flash :error])))
            (is (nil? (:view-as-user-id (session-of sessions token))))))
        (let [r (start other)]
          (is (= 303 (:status r)))
          (is (= (str "/web/users/" (:id other) "/rights") (get-in r [:headers "Location"])))
          (is (= :user.view-as/refused-admin (get-in r [:flash :error]))))
        (let [r (start ada)]
          (is (= 303 (:status r)))
          (is (= "/web" (get-in r [:headers "Location"]))))
        (let [r ((web-handlers/stop-view-as-handler svc {})
                 {:request-method :post :uri view-as/stop-path :headers {} :user ada
                  :session (session-of sessions token) :viewing-as {:admin admin}})]
          (is (= (str "/web/users/" (:id ada) "/rights") (get-in r [:headers "Location"])))
          (is (= (:id admin) (:id (:user (send! svc token :get "/web"))))))))))

(deftest ^:integration ^:security the-routes-start-and-stop-and-guard
  (with-app
    (fn [{:keys [svc sessions user!]}]
      (let [admin (user! "admin@example.com" {:role :admin})
            ada   (user! "ada@example.com" {})
            bob   (user! "bob@example.com" {})
            app   ((middleware/authenticate-if-present svc)
                   (ring/ring-handler (ring/router ["/web" (:web (wagoe.user.shell.http/user-routes svc nil {}))] {:conflicts nil})))
            post  (fn [token uri] (app {:request-method :post :uri uri :headers {"x-session-token" token "hx-request" "true"}}))
            [token _]   (sign-in! sessions admin)
            [bob-token] (sign-in! sessions bob)]
        (testing "only an administrator starts it"
          (is (= 403 (:status (post bob-token (str "/web/users/" (:id ada) "/view-as")))))
          (is (nil? (:view-as-user-id (session-of sessions bob-token)))))
        (testing "started, every write is refused through the routes too"
          (is (= 303 (:status (post token (str "/web/users/" (:id ada) "/view-as")))))
          (is (= (:id ada) (:view-as-user-id (session-of sessions token))))
          (is (= 403 (:status (post token (str "/web/users/" (:id bob) "/view-as")))) "not nested")
          (is (str/includes? (str (:body (post token "/web/users/x/hard-delete"))) "forbidden")))
        (testing "stopped through its route"
          (let [r (post token view-as/stop-path)]
            (is (= 303 (:status r)))
            (is (= (str "/web/users/" (:id ada) "/rights") (get-in r [:headers "Location"]))))
          (is (nil? (:view-as-user-id (session-of sessions token)))))))))

(deftest ^:unit the-page-shows-the-bar-and-switches-its-forms-off
  (let [page (binding [view-as-ui/*viewing* {:name "Ada" :stop-path view-as/stop-path}]
               (pr-str (layout/page-layout "T" [:form [:button "Save"]])))]
    (is (str/includes? page "view-as-bar"))
    (is (str/includes? page ":data-viewing-as \"true\""))
    (is (str/includes? page "/js/view-as.js"))
    (is (str/includes? page (str ":action \"" view-as/stop-path "\""))))
  (let [page (pr-str (layout/page-layout "T" [:p "x"]))]
    (is (not (str/includes? page "view-as")))))

(deftest ^:integration ^:security a-tenant-refusing-the-view-ends-it
  ;; BOU-1004: the tenant module answers with a marked refusal
  (with-app
    (fn [{:keys [svc sessions audit user!]}]
      (let [admin (user! "admin@example.com" {:role :admin})
            ada   (user! "ada@example.com" {})
            [token session] (sign-in! sessions admin)
            app   ((middleware/authenticate-if-present svc)
                   (fn [_] (wagoe.core.http.access/refused-view {:status 403 :body "no"})))]
        (ports/start-view-as svc admin session (:id ada))
        (let [r (app {:request-method :get :uri "/web/orders" :headers {"x-session-token" token}})]
          (is (= 303 (:status r)))
          (is (= "/web" (get-in r [:headers "Location"])))
          (is (= {:error :user.view-as/ended-refused} (:flash r))))
        (is (nil? (:view-as-user-id (session-of sessions token))))
        (is (contains? (actions audit (:id ada)) "view-as-stop"))
        (testing "not viewing, the refusal is passed on as it is"
          (is (= 403 (:status (app {:request-method :get :uri "/web/orders" :headers {"x-session-token" token}})))))))))
