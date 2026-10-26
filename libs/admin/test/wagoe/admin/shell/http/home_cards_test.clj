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

(defn- home
  "The dashboard's body for `user`. `opts`: :count-of (fn [entity] n), :configs
   {entity config}, :overview (fn [entity] overview), :user-service, :admin-config."
  [config user & [entities {:keys [count-of configs overview user-service admin-config]}]]
  (let [schema  (reify admin-ports/ISchemaProvider
                  (fetch-table-metadata [_ _] nil)
                  (get-entity-config [_ e] (get configs e {:label (name e)}))
                  (list-available-entities [_] (vec entities))
                  (get-entity-label [_ _] nil)
                  (validate-entity-exists [_ _] false))
        ;; Asserted, not thrown: the dashboard swallows a failing count
        counted (fn [e]
                  (is (= :admin (:role user :admin)) (str "counted " e " for a non-admin"))
                  (if count-of (count-of e) 7))
        service #_{:clj-kondo/ignore [:missing-protocol-method]}
                (reify
                  admin-ports/IAdminService
                  (count-entities [_ e _] (counted e))
                  admin-ports/IListOverview
                  (list-overview [_ e _ _] (when overview (overview e))))
        handler (list-handlers/admin-home-handler service schema (or admin-config {}) user-service)]
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

(deftest ^:unit a-failing-count-costs-only-its-tile
  (let [body (home nil nil [:orders :invoices]
                   {:count-of #(if (= :orders %) (throw (ex-info "db down" {})) 12)})]
    (is (str/includes? body "href=\"/web/admin/invoices\""))
    ;; Without a locale bound the markers render as their keys
    (is (= 1 (count (re-seq #"class=\"entity-card-count\">entity-card-count<" body))) "the other count")
    (is (= 1 (count (re-seq #"title=\"dashboard-count-failed\">—<" body))) "the failed one")))

(deftest ^:unit deprecated-extra-cards-follow-the-sections
  (let [body (home nil nil [:orders] {:admin-config {:dashboard-extra-cards [[:div.my-card "Reports"]]}})]
    (is (re-find #"(?s)/web/admin/orders.*<div class=\"my-card\">Reports</div>" body))
    (is (re-find #"aria-labelledby=\"admin-home-extra\"" body) "a section with a heading, like the others")
    (is (re-find #"<h2[^>]*id=\"admin-home-extra\"" body)))
  (is (not (str/includes? (home nil nil [:orders]) "admin-home-extra")) "none configured, no section"))

(deftest ^:unit a-tile-shows-the-entity-summary
  (let [body (home nil nil [:orders]
                   {:configs  {:orders {:label "Orders" :summary [{:label "Open" :agg :count}]}}
                    :overview (fn [_] {:summary [{:label "Open" :value 3 :role :number}]})})]
    (is (re-find #"<dt>Open</dt><dd[^>]*>3</dd>" body))))

(deftest ^:unit recent-activity-is-the-platform-admins
  (let [usvc #_{:clj-kondo/ignore [:missing-protocol-method]}
             (reify user-ports/IUserService
               (list-audit-logs [_ opts]
                 (is (= 10 (:limit opts)))
                 {:audit-logs [{:action :login :result :success :actor-email "ann@example.com"
                                :target-user-email "bob@example.com"
                                :created-at (java.time.Instant/now)}
                               {:action :login :result :failure :actor-email "eve@example.com"
                                :created-at (java.time.Instant/now)}]}))]
    (testing "a platform admin: the last entries, and a link to the trail"
      (let [body (home nil nil [] {:user-service usvc})]
        (is (str/includes? body "dashboard-activity"))
        (is (str/includes? body "ann@example.com"))
        (is (= 1 (count (re-seq #"audit-result-badge" body))) "only the failed entry is marked")
        (is (str/includes? body "href=\"/web/audit\""))))
    (testing "a tenant admin and a plain user: no panel"
      (is (not (str/includes? (home nil {:tenant-id (random-uuid)} [] {:user-service usvc}) "dashboard-activity")))
      (is (not (str/includes? (home nil {:role :user} [] {:user-service usvc}) "dashboard-activity"))))
    (testing "the audit trail switched off: no panel"
      (is (not (str/includes? (home off nil [] {:user-service usvc}) "dashboard-activity"))))
    (testing "a failing audit query: the panel says so, the page renders"
      (let [failing #_{:clj-kondo/ignore [:missing-protocol-method]}
                    (reify user-ports/IUserService
                      (list-audit-logs [_ _] (throw (ex-info "db down" {}))))
            body    (home nil nil [:orders] {:user-service failing})]
        (is (str/includes? body "dashboard-activity-failed"))
        (is (str/includes? body "class=\"entity-card-count\">entity-card-count<"))))))
