(ns wagoe.main-test
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [wagoe.main :as main]))

(deftest ^:unit worker-ig-config-drops-http-surface
  (let [full {:boundary/http-server  1
              :boundary/http-handler  2
              :boundary/dashboard     3
              :boundary/db-context    4
              :boundary/user-service  5}
        worker (main/worker-ig-config full)]
    (testing "the HTTP surface keys are removed so the worker binds no port"
      (is (empty? (set/intersection (set (keys worker))
                                    (set main/http-surface-keys)))))
    (testing "background/service components are kept"
      (is (= {:boundary/db-context   4
              :boundary/user-service 5}
             worker)))))
