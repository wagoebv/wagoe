(ns wagoe.push-hand-mounted-routes-test
  "An app that mounted the deprecated `:wagoe.push/routes` vector by hand, as
   the docs once said to, still boots now that the module mounts push itself,
   and serves both paths."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [support.route-table :as rt]))

(use-fixtures :once (rt/system-fixture "push-hand-mounted" {:mount-by-hand :wagoe.push/routes}))

(deftest ^:integration both-paths-answer-and-neither-is-a-redirect
  (let [user (rt/bearer (get-in (rt/decoded (rt/call :post "/test/reset" "{}")) [:seeded :user]))]
    (doseq [uri ["/api/push/devices" "/api/v1/push/devices"]]
      (is (= 401 (:status (rt/call :get uri))) uri)
      (is (= 200 (:status (rt/call :get uri "" user))) uri))))
