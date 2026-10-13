(ns wagoe.tools.coverage-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.tools.coverage :as sut]))

(deftest ^:unit measures-what-tests-edn-names
  (let [tests {:kaocha/source-paths ["src" "libs/core/src"]
               :tests [{:id :unit :test-paths ["test" "libs/core/test" "libs/config/test" "libs/gone/test"]}
                       {:id :core :test-paths ["libs/core/test"]}]}
        dirs  #{"libs/core/src" "libs/config/src"}]
    (testing "the source paths, then each :unit test path's src that exists"
      (is (= ["src" "libs/core/src" "libs/config/src"] (sut/src-paths tests dirs))))))

(deftest ^:unit the-command-passes-each-path
  (is (= ["clojure" "-M:test:db:test/pg:test/otel:test/http:coverage" ":unit" "--plugin" "cloverage"
          "--no-cov-html" "--cov-text" "--cov-src-ns-path" "src" "--cov-src-ns-path" "libs/core/src"]
         (sut/command ["src" "libs/core/src"] false)))
  (is (some #{"-M:test:db:test/pg:test/otel:test/http:test/pg-mac:coverage"} (sut/command [] true))))
