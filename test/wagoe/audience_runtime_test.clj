(ns wagoe.audience-runtime-test
  "Can an application actually enable audience?

   Until BOU-419 it could not. `:wagoe/audience`'s init-key throws without a
   `:user-data-source`, and `IUserDataSource` had no implementation anywhere —
   the only one in the repository was a `reify` inside audience's own tests. The
   module also shipped no `ig-config`, so the platform emitted its settings map
   and built neither the service nor the routes, and no schema component, so a
   booted app had no `audience_segments` table to write to.

   Each of those is invisible until something boots the module, which nothing
   did."
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [next.jdbc :as jdbc]
            [wagoe.audience.ports :as audience-ports]
            [wagoe.config :as config]
            [wagoe.main :as main]
            [wagoe.system-config :as sys-config]
            [support.handler-test-helpers :as h])
  (:import [java.util UUID]))

(defn- audience-config []
  (-> (config/load-config {:profile :test})
      (assoc-in [:active :wagoe/audience] {:enabled? true})))

(defn- with-system
  "Boots without the HTTP server: it binds a fixed port from the test profile,
   and a port already in use made these tests fail before an assertion ran
   (BOU-419 review). The route tree is data and needs no listener."
  [f]
  (let [system (ig/init (main/worker-ig-config (sys-config/ig-config (audience-config))))]
    (try (f system) (finally (ig/halt! system)))))

(defn- with-http-handler
  "Boots the handler but not the listener, for the requests below."
  [f]
  (let [system (ig/init (dissoc (sys-config/ig-config (audience-config))
                                :wagoe/http-server))]
    (try (f system) (finally (ig/halt! system)))))

(defn- insert-user!
  "A row in the users table the data source reads."
  [ds id name*]
  (jdbc/execute! ds ["INSERT INTO users (id, name, role, created_at)
                        VALUES (?,?,?,CURRENT_TIMESTAMP)"
                     id name* "user"])
  id)

(deftest ^:integration audience-boots-and-mounts-its-routes
  (with-system
    (fn [system]
      (testing "the module assembles a graph rather than a settings map"
        (is (some? (:wagoe/audience-db-schema system))   "no schema component")
        (is (some? (:wagoe/audience-user-source system)) "no user data source")
        (is (some? (:wagoe/audience system))             "no audience service"))

      (testing "the data source is the protocol the service demands"
        (is (satisfies? audience-ports/IUserDataSource
                        (:wagoe/audience-user-source system))))

      (testing "and its routes are mounted, not merely defined"
        (is (seq (:api (:wagoe/audience-routes system))))
        (is (some #{(ig/ref :wagoe/audience-routes)}
                  (get-in (sys-config/ig-config (audience-config))
                          [:wagoe/http-handler :module-routes]))
            "the routes component exists but the handler never receives it")))))

(deftest ^:integration an-audience-segments-the-real-users-table
  ;; The end of the loop: a stored definition, resolved through the service,
  ;; reading the users the application actually has. Empty filters are the
  ;; universe, so this is the whole table — the case the port's docstring warns
  ;; is easy to implement as "nobody".
  (with-system
    (fn [system]
      (let [ds    (get-in system [:wagoe/db-context :datasource])
            alice (insert-user! ds (UUID/randomUUID) "Alice")
            bob   (insert-user! ds (UUID/randomUUID) "Bob")
            store (:store (:wagoe/audience system))]

        (testing "the schema component created the tables the store writes to"
          (is (some? (audience-ports/save-audience
                      store {:id :everyone :label "Everyone" :filters []}))))

        (testing "resolving it returns the users in the database"
          (let [result (audience-ports/resolve-audience
                        (:resolver (:wagoe/audience system))
                        :everyone
                        {:force-refresh? true})
                ;; Every user in the table, ours included. The test profile's
                ;; H2 lives for the JVM, so other tests' users may be there too.
                all    (set (map #(parse-uuid (str (val (first %))))
                                 (jdbc/execute! ds ["SELECT id FROM users"])))]
            (is (every? all [alice bob]) "the users this test inserted are in the table")
            (is (= all (set (:user-ids result)))
                (str "expected every user in the table, got " (pr-str (:user-ids result))))
            (is (= (count all) (:count result)))))))))

(deftest ^:security ^:integration anonymous-callers-cannot-manage-audiences
  ;; The routes carried no middleware of their own, and the global
  ;; authentication only *sets* `:user` when credentials are present — it does
  ;; not demand them. So mounting these published segment management to anyone:
  ;; measured before the guard, `DELETE /api/v1/audiences/:id` answered 204 and
  ;; the segment was gone, and `…/members` answered 200 with the user ids
  ;; (BOU-419 review).
  (with-http-handler
    (fn [system]
      (let [handler (:wagoe/http-handler system)
            store   (:store (:wagoe/audience system))]
        (audience-ports/save-audience store {:id :secret-cohort :label "S" :filters []})

        (doseq [[label request]
                [["list members" (h/make-get "/api/v1/audiences/secret-cohort/members")]
                 ["preview"      (h/make-post "/api/v1/audiences/preview" {})]
                 ["create"       (h/make-post "/api/v1/audiences" {})]
                 ["delete"       (h/make-delete "/api/v1/audiences/secret-cohort")]
                 ["web list"     (h/make-get "/web/audiences")]]]
          (testing (str "anonymous " label)
            (let [{:keys [status headers]} (handler request)]
              ;; A browser page redirects to the login form; an API answers
              ;; 401/403. Both are refusals — what must not happen is the work.
              (is (or (contains? #{401 403} status)
                      (and (= 302 status)
                           (re-find #"(?i)login|sign-?in" (str (get headers "Location")))))
                  (str label " answered " status
                       (when (= 302 status) (str " -> " (get headers "Location")))
                       " to a caller with no credentials")))))

        (testing "and the segment an anonymous caller tried to delete is still there"
          (is (some? (audience-ports/find-audience store :secret-cohort))))))))

(deftest ^:integration audience-as-a-service-keeps-its-guard
  ;; `service audience` drops every user-owned key, and the authorization guard
  ;; is one — so the routes component received no middleware and threw a
  ;; message that named neither the cause nor the remedy (BOU-419 review).
  ;;
  ;; It still refuses: serving segment management unguarded is worse than not
  ;; starting. What changed is that it says which command does work.
  (let [config (audience-config)]
    (testing "audience alone refuses, and says what to run instead"
      (let [[svc-cfg _] (main/service-ig-config config #{:audience})
            ex          (is (thrown? clojure.lang.ExceptionInfo (ig/init svc-cfg)))
            message     (ex-message (ex-cause ex))]
        (is (re-find #"service audience user" message)
            (str "the refusal does not name the command that works: " message))))

    (testing "and with the user module it boots, guard intact"
      (let [[svc-cfg _] (main/service-ig-config config #{:audience :user})
            system      (ig/init svc-cfg)]
        (try
          (is (seq (:api (:wagoe/audience-routes system))))
          (is (seq (get-in svc-cfg [:wagoe/audience-routes :middleware]))
              "the routes were mounted with no guard")
          (finally (ig/halt! system)))))))
