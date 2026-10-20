(ns wagoe.platform.shell.authorization-interceptor-test
  "The authorization interceptor (ADR-041 §5) in each mode, through the real
   `:wagoe/http-handler`: module routes tagged, the user module's rights
   behind `IRights`."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging]
            [integrant.core :as ig]
            [wagoe.platform.ports.authorization :as ports]
            [wagoe.platform.shell.authorization]
            [wagoe.platform.shell.system.authorization-report :as authz-report]
            [wagoe.platform.shell.system.wiring]))

(def ^:private users
  {"reader" {:rights [{:permission :orders/read}]}
   "editor" {:rights [{:permission :orders/read} {:permission :orders/write}]}
   "refund" {:rights [{:permission :orders/refund}]}
   "nobody" {:rights []}
   "scoped" {:rights [{:permission :orders/read :scope {:domain-id 42}}]}
   "admin"  {:role :admin :rights []}
   "boom"   {:boom? true}})

(def ^:private reads (atom 0))

(def ^:private rights
  (reify ports/IRights
    (rights [_ user]
      (swap! reads inc)
      (when (:boom? user) (throw (ex-info "rights store down" {:type :internal-error})))
      (:rights user))))

(defn- ok [_] {:status 200 :body {:ok true}})

(def ^:private orders-routes
  {:api [["/orders" {:skip-interceptors? true :get {:handler ok} :post {:handler ok}}]
         ["/orders/:id/refund" {:skip-interceptors? true :permission :orders/refund :post {:handler ok}}]
         ["/domains/:domain-id/orders" {:skip-interceptors? true :scope {:domain-id :path/domain-id}
                                        :get {:handler ok}}]
         ["/me" {:skip-interceptors? true :authorization :signed-in :get {:handler ok}}]
         ["/own/:id" {:skip-interceptors? true :authorization :self :get {:handler ok}}]
         ["/ping" {:skip-interceptors? true :public true :get {:handler ok}}]]
   :web [["/orders" {:skip-interceptors? true :get {:handler ok}}]]})

(def ^:private unowned-routes
  {:api [["/loose" {:skip-interceptors? true :get {:handler ok}}]]})

(defn- authenticate [handler]
  (fn [request]
    (handler (if-let [who (get-in request [:headers "x-user"])]
               (assoc request :user (assoc (users who) :id who))
               request))))

(defn- handler [mode]
  (let [registry (ig/init-key :wagoe/permission-registry
                              {:declarations {:wagoe/orders {:permissions {:orders/refund {:label "Refund"}}
                                                             :scopes {:domain-id {:label "Domain"}}}}})]
    (ig/init-key :wagoe/http-handler
                 {:module-routes       [{:wagoe/module :wagoe/orders :contribution orders-routes}
                                        unowned-routes]
                  :permission-registry registry
                  :rights              rights
                  :auth-middleware     [authenticate]
                  :router              {}
                  :config              {:active (cond-> {:wagoe/settings {:name "t"}}
                                                  mode (assoc :wagoe/authorization {:mode mode}))}})))

(defn- call
  ([h method uri user] (call h method uri user "application/json"))
  ([h method uri user accept]
   (let [r (h {:request-method method :uri uri :scheme :http :server-name "localhost" :server-port 80
               :headers (cond-> {"accept" accept} user (assoc "x-user" user))})
         b (:body r)]
     {:status       (:status r)
      :content-type (get-in r [:headers "Content-Type"])
      :body         (cond (string? b) b (nil? b) nil :else (slurp b))})))

(defn- status [h method uri user] (:status (call h method uri user)))

(deftest ^:integration enforce-applies-each-rule-in-order
  (let [h (handler :enforce)]
    (testing ":public: nothing checked, not even a user"
      (is (= 200 (status h :get "/api/v1/ping" nil))))
    (testing "no user is still a 401"
      (is (= 401 (status h :get "/api/v1/orders" nil))))
    (testing ":authorization :signed-in: any signed-in user"
      (is (= 200 (status h :get "/api/v1/me" "nobody"))))
    (testing ":authorization :self: the handler decides"
      (is (= 200 (status h :get "/api/v1/own/1" "nobody"))))
    (testing ":permission: that one, not the module's"
      (is (= 200 (status h :post "/api/v1/orders/1/refund" "refund")))
      (is (= 403 (status h :post "/api/v1/orders/1/refund" "editor"))))
    (testing ":wagoe/module: read for GET and HEAD, write otherwise"
      (is (= 200 (status h :get "/api/v1/orders" "reader")))
      (is (= 403 (status h :post "/api/v1/orders" "reader")))
      (is (= 200 (status h :post "/api/v1/orders" "editor")))
      (is (= 403 (status h :get "/api/v1/orders" "nobody"))))
    (testing "no module and no marker: refused"
      (is (= 403 (status h :get "/api/v1/loose" "editor"))))
    (testing "a platform admin passes everything"
      (is (= 200 (status h :post "/api/v1/orders" "admin")))
      (is (= 200 (status h :get "/api/v1/loose" "admin"))))
    (testing "then the scope: outside it a 404, unchecked a 403"
      (is (= 200 (status h :get "/api/v1/domains/42/orders" "scoped")))
      (is (= 404 (status h :get "/api/v1/domains/43/orders" "scoped")))
      (is (= 403 (status h :get "/api/v1/orders" "scoped"))))))

(deftest ^:integration a-refusal-is-a-403-forbidden
  (let [h (handler :enforce)]
    (testing "an API caller gets the error body"
      (let [{:keys [status body]} (call h :post "/api/v1/orders" "reader")]
        (is (= 403 status))
        (is (= "forbidden" (get-in (json/parse-string body true) [:error :type])))))
    (testing "a signed-in web user gets a 403 page, not the login redirect"
      (let [{:keys [status content-type body]} (call h :get "/web/orders" "nobody" "text/html")]
        (is (= 403 status))
        (is (str/starts-with? content-type "text/html"))
        (is (str/includes? body "403"))))))

(deftest ^:integration rights-are-read-once-per-request-and-only-when-needed
  (let [h (handler :enforce)]
    (reset! reads 0)
    (call h :get "/api/v1/orders" "reader")
    (is (= 1 @reads))
    (call h :get "/api/v1/me" "reader")
    (call h :get "/api/v1/ping" nil)
    (is (= 1 @reads) "a marker or a public route reads none")))

(defn- logged
  "The refusals `f` logs, as the entries handed to the logger."
  [f]
  (let [entries (atom [])]
    (with-redefs [wagoe.platform.shell.authorization/log-refusal! #(swap! entries conj %)]
      (f))
    @entries))

(deftest ^:integration report-logs-what-enforce-would-refuse-and-refuses-nothing
  (let [h       (handler :report)
        entries (logged (fn []
                          (is (= 200 (status h :post "/api/v1/orders" "reader")))
                          (is (= 200 (status h :get "/api/v1/loose" "reader")))
                          (is (= 200 (status h :get "/api/v1/orders" "reader")))
                          (dotimes [_ 3] (status h :post "/api/v1/orders/1/refund" "nobody"))))]
    (is (every? #(= :report (:mode %)) entries))
    (is (= 3 (count entries)) "one each, a repeat not logged again")
    (is (some #{{:mode :report :user-id "reader" :route "/api/v1/orders" :method :post
                 :permission :orders/write :reason :forbidden}}
              entries)
        "names the user, the route and the permission")
    (is (some #(= "/api/v1/loose" (:route %)) entries))))

(deftest ^:integration report-logs-a-failing-check-once-and-lets-it-through
  (let [h      (handler :report)
        errors (atom 0)]
    (with-redefs [clojure.tools.logging/log* (fn [_ level _ message]
                                                (when (and (= :error level) (re-find #"report failed" (str message)))
                                                  (swap! errors inc)))]
      (dotimes [_ 3] (is (= 200 (status h :get "/api/v1/orders" "boom")))))
    (is (= 1 @errors) "a repeat within the minute is not logged again"))
  (testing ":enforce lets it fail"
    (is (= 500 (status (handler :enforce) :get "/api/v1/orders" "boom")))))

(deftest ^:integration the-report-lists-each-endpoint-and-the-unruled-first
  (let [text (authz-report/report
              {:wagoe/permission-registry {:declarations {:wagoe/orders {:permissions {:orders/refund {:label "Refund"}}
                                                                         :scopes {:domain-id {:label "Domain"}}}}}
               :wagoe/http-handler        {:module-routes       [{:wagoe/module :wagoe/orders :contribution orders-routes}
                                                                 unowned-routes]
                                           :permission-registry (ig/ref :wagoe/permission-registry)
                                           :router              {}
                                           :config              {:active {:wagoe/settings {:name "t"}}}}})
        [unruled all] (str/split text #"All \d+ endpoint")]
    (is (re-find #":mode :off" text) "built and reported under :off too")
    (is (re-find #"1 endpoint\(s\) no rule covers" unruled))
    (is (re-find #"GET\s+/api/v1/loose\s+none" unruled))
    (is (not (re-find #"/api/v1/orders" unruled)))
    (testing "each endpoint with its rule and permission"
      (is (re-find #"POST\s+/api/v1/orders\s+module\s+orders/write" all))
      (is (re-find #"GET\s+/api/v1/orders\s+module\s+orders/read" all))
      (is (re-find #"POST\s+/api/v1/orders/:id/refund\s+permission\s+orders/refund" all))
      (is (re-find #"GET\s+/api/v1/me\s+signed-in" all))
      (is (re-find #"GET\s+/api/v1/domains/:domain-id/orders\s+module\s+orders/read  scope \{:domain-id :path/domain-id\}" all)))))

(deftest ^:integration off-checks-nothing
  (doseq [mode [nil :off]]
    (let [h (handler mode)]
      (reset! reads 0)
      (is (= 200 (status h :post "/api/v1/orders" "nobody")))
      (is (= 200 (status h :get "/api/v1/loose" "nobody")))
      (is (= 401 (status h :get "/api/v1/orders" nil)) "authentication still holds")
      (is (zero? @reads) "and no rights are read"))))

(deftest ^:unit an-unknown-mode-fails-boot
  (let [e (is (thrown? clojure.lang.ExceptionInfo (handler :enforced)))]
    (is (= :configuration-error (:type (ex-data e))))))

(deftest ^:unit an-unknown-marker-fails-boot
  (let [registry (ig/init-key :wagoe/permission-registry {:declarations {}})
        e        (is (thrown? clojure.lang.ExceptionInfo
                              (ig/init-key :wagoe/http-handler
                                           {:module-routes       [{:api [["/x" {:authorization :signedin
                                                                                :get {:handler ok}}]]}]
                                            :permission-registry registry
                                            :router              {}
                                            :config              {:active {:wagoe/settings {:name "t"}}}})))]
    (is (= :configuration-error (:type (ex-data e))))
    (is (re-find #":signedin" (ex-message e)))))
