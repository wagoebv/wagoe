(ns wagoe.push-hand-mounted-routes-test
  "An app that mounted the deprecated `:wagoe.push/routes` vector by hand, as
   the docs once said to, still boots now that the module mounts push itself,
   and serves both paths."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [support.route-table :as rt]
            [wagoe.user.shell.auth :as auth]))

(use-fixtures :once (rt/system-fixture "push-hand-mounted" {:mount-by-hand :wagoe.push/routes}))

(deftest ^:integration both-paths-answer-and-neither-is-a-redirect
  (let [{:keys [id email role]} (get-in (rt/decoded (rt/call :post "/test/reset" "{}")) [:seeded :user])
        user {"authorization"
              (str "Bearer " (auth/create-jwt-token {:id id :email email :role (keyword role)} 1))}]
    (doseq [uri ["/api/push/devices" "/api/v1/push/devices"]]
      (is (= 401 (:status (rt/call :get uri))) uri)
      (is (= 200 (:status (rt/call :get uri "" user))) uri))))
