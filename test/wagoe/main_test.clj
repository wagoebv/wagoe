(ns wagoe.main-test
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [wagoe.main :as main]))

(deftest ^:unit worker-ig-config-drops-http-surface
  (let [full {:wagoe/http-server  1
              :wagoe/http-handler  2
              :wagoe/dashboard     3
              :wagoe/db-context    4
              :wagoe/user-service  5}
        worker (main/worker-ig-config full)]
    (testing "the HTTP surface keys are removed so the worker binds no port"
      (is (empty? (set/intersection (set (keys worker))
                                    (set main/http-surface-keys)))))
    (testing "background/service components are kept"
      (is (= {:wagoe/db-context   4
              :wagoe/user-service 5}
             worker)))))
