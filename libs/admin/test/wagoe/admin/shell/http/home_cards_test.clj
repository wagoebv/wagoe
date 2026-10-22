(ns wagoe.admin.shell.http.home-cards-test
  "The dashboard links only to pages that are mounted and the user may open:
   with the user module's web UI switched off, the Groups and Audit cards went
   to a 404 (BOU-713); a tenant admin's Audit card to a 403 (BOU-724). The
   cards come from the navigation modules contribute."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reitit.core :as r]
            [wagoe.admin.core.ui :as admin-ui]
            [wagoe.admin.ports :as admin-ports]
            [wagoe.admin.shell.http.handlers.list :as list-handlers]
            [wagoe.platform.core.http.nav :as nav]
            [wagoe.platform.shell.http.interceptors :as interceptors]
            [wagoe.platform.shell.http.pages :as pages]
            [wagoe.platform.shell.system.wiring :as wiring]
            [wagoe.user.ports :as user-ports]
            [wagoe.user.shell.http :as user-http]))

(def ^:private off {:active {:wagoe/settings {:features {:user-web-ui {:enabled? false}}}}})

(defn- contributions
  "The user module's contributions, beside the admin's catch-all entity route."
  [config]
  (let [usvc (reify user-ports/IUserService)]
    [{:web-prefix "/web/admin" :web [["/:entity" {:get identity}]]}
     (user-http/user-routes usvc nil config)
     (user-http/group-routes usvc nil config)]))

(defn- router
  "The contributions as the platform mounts them."
  [config]
  (r/router (:web (wiring/module-route-contributions (contributions config)))
            {:conflicts nil}))

(defn- home [config user & [entities]]
  (let [schema  (reify admin-ports/ISchemaProvider
                  (fetch-table-metadata [_ _] nil)
                  (get-entity-config [_ e] {:label (name e)})
                  (list-available-entities [_] (vec entities))
                  (get-entity-label [_ _] nil)
                  (validate-entity-exists [_ _] false))
        service #_{:clj-kondo/ignore [:missing-protocol-method]}
                (reify admin-ports/IAdminService
                  (count-entities [_ e _]
                    (if (= :admin (:role user :admin)) 7 (throw (ex-info "counted for a non-admin" {:entity e})))))
        handler (list-handlers/admin-home-handler service schema {})]
    ;; Through the pipeline every route runs, which binds what is mounted,
    ;; inside the navigation the platform binds.
    (str (:body ((interceptors/wrap-nav
                  #(interceptors/run-http-interceptors handler [] % {})
                  (constantly
                   (nav/collect (conj (vec (keep :nav (contributions config)))
                                      (admin-ui/nav entities {}))
                                nil
                                (partial pages/mounted? {::r/router (router config)}))))
                 {:request-method :get :uri "/web/admin/"
                  :user (merge {:id (random-uuid) :role :admin :active true} user)
                  ::r/router (router config)})))))

(deftest ^:unit mounted-reads-the-route-table
  (let [on-req  {::r/router (router nil)}
        off-req {::r/router (router off)}]
    (testing "a page of its own"
      (is (pages/mounted? on-req "/web/admin/groups"))
      (is (pages/mounted? on-req "/web/audit")))
    (testing "not mounted, though /web/admin/:entity matches the path"
      (is (not (pages/mounted? off-req "/web/admin/groups")))
      (is (not (pages/mounted? off-req "/web/audit"))))))

(deftest ^:unit dashboard-cards-follow-the-user-web-ui-switch
  (testing "on: both cards"
    (let [body (home nil nil)]
      (is (str/includes? body "href=\"/web/admin/groups\""))
      (is (str/includes? body "href=\"/web/audit\""))))
  (testing "off: neither, nor a logout form"
    (let [body (home off nil)]
      (is (not (str/includes? body "/web/admin/groups")))
      (is (not (str/includes? body "/web/audit")))
      (is (not (str/includes? body "/web/logout"))))))

(deftest ^:unit a-tenant-admin-sees-no-audit-card
  ;; /web/audit admits platform admins only.
  (let [body (home nil {:tenant-id (random-uuid)})]
    (is (str/includes? body "href=\"/web/admin/groups\""))
    (is (not (str/includes? body "/web/audit")))))

(deftest ^:unit a-plain-user-sees-only-what-they-may-open
  (testing "an admin: entity tiles with counts"
    (let [body (home nil nil [:orders])]
      (is (str/includes? body "href=\"/web/admin/orders\""))
      (is (str/includes? body "entity-card-count"))))
  (testing "a user: the profile card, no entities, counts or admin pages"
    (let [body (home nil {:role :user} [:orders])]
      (is (str/includes? body "href=\"/web/profile\""))
      (is (not (str/includes? body "/web/admin/orders")))
      (is (not (str/includes? body "entity-card-count")))
      (is (not (str/includes? body "/web/admin/groups")))
      (is (not (str/includes? body "/web/audit"))))))

(deftest ^:unit the-dashboard-groups-cards-as-the-sidebar-does
  ;; The user module claims :users, so its card sits with Groups; :orders,
  ;; claimed by nobody, goes to the last section, as in the sidebar.
  (let [body     (home nil nil [:users :orders])
        sections (re-seq #"(?s)<section[^>]*data-group=\"([^\"]+)\".*?</section>" body)]
    (is (= ["user" "other"] (map second sections)))
    (is (str/includes? (ffirst sections) "/web/admin/users"))
    (is (str/includes? (ffirst sections) "/web/admin/groups"))
    (is (str/includes? (first (second sections)) "/web/admin/orders"))))
