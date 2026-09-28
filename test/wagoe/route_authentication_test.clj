(ns wagoe.route-authentication-test
  "Every route requires a signed-in user unless its data says `:public true`.

   Built from the real route table, with every framework module switched on,
   because the hole this closes was in the modules nobody guarded by hand:
   tenants, memberships and search answered anonymous callers (BOU-568). A
   route added without `:public` and without a guard fails here."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [support.route-table :as rt :refer [route-table]]))

(use-fixtures :once (rt/system-fixture "route-authentication"))

(def ^:private bodies rt/bodies)

(defn- anonymous
  "The response to an anonymous `method` on `path`."
  ([method path] (anonymous method path (:empty bodies)))
  ([method path body] (rt/call method (rt/concrete-uri path) body)))

(def ^:private decoded rt/decoded)

(defn- sent-to-login? [{:keys [status headers]}]
  (and (= 302 status)
       (str/starts-with? (str (get headers "Location")) "/web/login?return-to=")))

(defn- refused?
  "Refused the way the framework refuses everywhere: a login redirect for a
   page, and for anything else a 401 whose body actually reached the wire."
  [path response]
  (if (str/starts-with? path "/web")
    (sent-to-login? response)
    (let [{:keys [type correlation-id]} (:error (decoded response))]
      (and (= 401 (:status response))
           (= "unauthorized" type)
           (some? correlation-id)
           (= correlation-id (get-in response [:headers "X-Correlation-ID"]))))))

(deftest ^:integration ^:security every-route-refuses-anonymous-callers-unless-public
  (let [table (route-table)]
    (testing "the table was built with the modules this is about"
      (doseq [p ["/api/v1/tenants" "/api/v1/memberships/:id/accept"
                 "/api/v1/search/:index-id" "/web/admin/search"
                 "/api/v1/storage/upload" "/api/push/devices"]]
        (is (some #(= p (first %)) table) (str p " is not in the route table"))))

    (doseq [[label body] bodies]
      (testing (str "with a " (name label) " body")
        (let [open (for [[path m public?] table
                         :when (not public?)
                         :let  [response (anonymous m path body)]
                         :when (not (refused? path response))]
                     [(str/upper-case (name m)) path (:status response)])]
          (is (zero? (count open))
              (str "Answered an anonymous caller without being :public true:\n"
                   (str/join "\n" (map #(str "  " (str/join " " %)) open)))))))))

(deftest ^:integration ^:security public-routes-answer-anonymous-callers
  (let [public (filter #(nth % 2) (route-table))]
    (is (seq public) "no :public route at all; login could not work")
    (doseq [[path m _] public]
      (let [response (anonymous m path)]
        (is (not (or (= 401 (:status response)) (sent-to-login? response)))
            (str (str/upper-case (name m)) " " path " is :public but refused an anonymous caller"))))))

(def ^:private expected-public
  "Every route an anonymous caller may reach, written out. `:public` inherited
   from a parent route, or added without a reason, changes this set and fails
   the test below. The unversioned `/api/...` redirects are checked by rule."
  #{[:get "/"] [:get "/health"] [:get "/health/live"] [:get "/health/ready"]
    [:get "/metrics"] [:get "/swagger.json"] [:get "/api-docs/*"]
    [:post "/test/reset"]
    [:post "/api/v1/auth/login"] [:post "/api/v1/sessions"]
    [:get "/api/v1/sessions/:token"] [:delete "/api/v1/sessions/:token"]
    [:get "/web"] [:get "/web/login"] [:post "/web/login"]
    [:get "/web/register"] [:post "/web/register"]
    [:post "/api/push/callback"]})

(deftest ^:integration ^:security the-public-set-is-exactly-what-was-decided
  (let [table     (route-table)
        public    (set (for [[p m pub?] table :when pub?] [m p]))
        versioned (set (for [[p] table :when (str/starts-with? p "/api/v1/")]
                         (subs p (count "/api/v1"))))
        redirects (set (for [suffix versioned
                             m      [:get :post :put :patch :delete]]
                         [m (str "/api" suffix)]))]
    (is (= expected-public (set (remove (set redirects) public)))
        "the public routes changed; update expected-public only on purpose")
    (is (every? public redirects)
        "an unversioned /api redirect is not public, so it cannot redirect")))
