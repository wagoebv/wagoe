(ns wagoe.e2e.smoke-test
  "Integration milestone for the e2e scaffold: verifies that the
   test-profile server is reachable AND that POST /test/reset produces
   a usable baseline seed. If this test is green, every downstream e2e
   spec can assume the infrastructure (server boot, DB init, reset
   handler, seed fixture) works."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clj-http.client :as http]
            [wagoe.e2e.fixtures :as fx]
            [wagoe.e2e.helpers.reset :as reset]))

(use-fixtures :each fx/with-fresh-seed)

(deftest ^:integration ^:e2e server-reachable-and-seeded
  (is (= "admin@acme.test" (-> fx/*seed* :admin :email))
      "baseline seed should contain the acme admin user")
  (let [resp (http/get (str (reset/default-base-url) "/web/login")
                       {:throw-exceptions false})]
    (is (= 200 (:status resp))
        "GET /web/login on the running e2e server should return 200")))
