(ns wagoe.admin.shell.http.home-cards-test
  "The dashboard links only to pages that are mounted: with the user module's
   web UI switched off, the Groups and Audit cards went to a 404 (BOU-713)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reitit.core :as r]
            [wagoe.admin.ports :as admin-ports]
            [wagoe.admin.shell.http.handlers.list :as list-handlers]
            [wagoe.admin.shell.http.support :as support]
            [wagoe.platform.shell.system.wiring :as wiring]
            [wagoe.user.ports :as user-ports]
            [wagoe.user.shell.http :as user-http]))

(def ^:private off {:active {:wagoe/settings {:features {:user-web-ui {:enabled? false}}}}})

(defn- router
  "The user module's contributions as the platform mounts them, beside the
   admin's catch-all entity route."
  [config]
  (let [usvc  (reify user-ports/IUserService)
        admin {:web-prefix "/web/admin" :web [["/:entity" {:get identity}]]}]
    (r/router (:web (wiring/module-route-contributions
                     [admin
                      (user-http/user-routes usvc nil config)
                      (user-http/group-routes usvc nil config)]))
              {:conflicts nil})))

(defn- home [config]
  (let [schema  (reify admin-ports/ISchemaProvider
                  (fetch-table-metadata [_ _] nil)
                  (get-entity-config [_ _] nil)
                  (list-available-entities [_] [])
                  (get-entity-label [_ _] nil)
                  (validate-entity-exists [_ _] false))
        handler (list-handlers/admin-home-handler (reify admin-ports/IAdminService) schema {})]
    (str (:body (handler {:request-method :get :uri "/web/admin/"
                          :user {:id (random-uuid) :role :admin :active true}
                          ::r/router (router config)})))))

(deftest ^:unit mounted-reads-the-route-table
  (let [on-req  {::r/router (router nil)}
        off-req {::r/router (router off)}]
    (testing "a page of its own"
      (is (support/mounted? on-req "/web/admin/groups"))
      (is (support/mounted? on-req "/web/audit")))
    (testing "not mounted, though /web/admin/:entity matches the path"
      (is (not (support/mounted? off-req "/web/admin/groups")))
      (is (not (support/mounted? off-req "/web/audit"))))))

(deftest ^:unit dashboard-cards-follow-the-user-web-ui-switch
  (testing "on: both cards"
    (let [body (home nil)]
      (is (str/includes? body "href=\"/web/admin/groups\""))
      (is (str/includes? body "href=\"/web/audit\""))))
  (testing "off: neither"
    (let [body (home off)]
      (is (not (str/includes? body "/web/admin/groups")))
      (is (not (str/includes? body "/web/audit"))))))
