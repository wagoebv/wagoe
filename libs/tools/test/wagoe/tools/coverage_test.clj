(ns wagoe.tools.coverage-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.tools.coverage :as sut]))

(deftest ^:unit measures-what-tests-edn-names
  (let [tests {:kaocha/source-paths ["src" "libs/core/src"]
               :tests [{:id :app :test-paths ["test"]}
                       {:id :core :test-paths ["libs/core/test"]}
                       {:id :geo :test-paths ["libs/geo/test" "libs/gone/test"]}]}
        dirs  #{"libs/core/src" "libs/geo/src"}]
    (testing "the source paths, then each test path's src that exists"
      (is (= ["src" "libs/core/src" "libs/geo/src"] (sut/src-paths tests dirs))))))

(deftest ^:unit the-command-passes-each-path
  (is (= ["clojure" "-M:test:test/all:coverage" "--plugin" "cloverage"
          "--no-cov-html" "--cov-text" "--cov-src-ns-path" "src" "--cov-src-ns-path" "libs/core/src"]
         (sut/command ["src" "libs/core/src"]))))
