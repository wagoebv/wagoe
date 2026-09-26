(ns wagoe.mcp.shell.test-runner-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [wagoe.mcp.core.verify :as verify]
            [wagoe.mcp.shell.test-runner :as runner]))

(defn- project!
  "A temp project with `tests-edn`, empty test files at `paths` under test/,
   and a src/<module-dir>/ports.clj for each of `modules`."
  ([tests-edn paths] (project! tests-edn paths []))
  ([tests-edn paths modules]
   (let [dir (io/file (System/getProperty "java.io.tmpdir") (str "runner-" (System/nanoTime)))]
     (.mkdirs dir)
     (spit (io/file dir "tests.edn") tests-edn)
     (doseq [p paths]
       (let [f (io/file dir "test" p)] (.mkdirs (.getParentFile f)) (spit f "")))
     (doseq [m modules]
       (let [f (io/file dir "src" m "ports.clj")] (.mkdirs (.getParentFile f)) (spit f "")))
     dir)))

(def ^:private generated-tests-edn
  "#kaocha/v1 {:tests [{:id :unit :test-paths [\"test\"]} {:id :integration :test-paths [\"test\"]}]}")

(deftest ^:unit a-generated-project-focuses-the-modules-test-namespaces
  ;; Generated projects have only :unit and :integration, so `-M:test :<module>`
  ;; failed with "No such suite", exit 254, even for an integrated module (BOU-520).
  (let [dir (project! generated-tests-edn ["shop/order_line/core/order_line_test.clj"
                                           "shop/order_line/shell/service_test.clj"
                                           "shop/customer/core/customer_test.clj"]
                      ["shop/order_line" "shop/customer"])]
    (is (= {:argv ["clojure" "-M:test"
                   "--focus" "shop.order-line.core.order-line-test"
                   "--focus" "shop.order-line.shell.service-test"]}
           (runner/test-command dir "order-line")))))

(deftest ^:unit a-suite-named-after-the-module-runs-by-id
  (let [dir (project! "#kaocha/v1 {:tests [{:id :admin}]}" ["wagoe/admin/core/x_test.clj"])]
    (is (= {:argv ["clojure" "-M:test" ":admin"]} (runner/test-command dir "admin")))))

(deftest ^:unit a-module-without-tests-is-no-tests-not-an-error
  (let [dir (project! generated-tests-edn ["shop/customer/core/customer_test.clj"] ["shop/customer"])]
    (is (= {:no-tests true} (runner/test-command dir "order")))
    (is (= :no-tests (:status (runner/default-test-runner "order" {:root (str dir)}))))
    (testing "and the report says incomplete without inventing an error"
      (let [r (verify/build-report {:tests {:status :no-tests}})]
        (is (false? (:complete? r)))
        (is (empty? (:issues r)))))))

(deftest ^:unit a-module-named-like-the-base-namespace-gets-only-its-own-tests
  ;; The module was matched against any directory in the path, so module
  ;; "shop" in base namespace "shop" picked up shop.customer's tests.
  (testing "no shop module: nothing, rather than the customer module's tests"
    (let [dir (project! generated-tests-edn ["shop/customer/core/customer_test.clj"] ["shop/customer"])]
      (is (= {:no-tests true} (runner/test-command dir "shop")))))
  (testing "a root module keeps its own tests but not those of a module inside it"
    (let [dir (project! generated-tests-edn ["shop/core/shop_test.clj"
                                             "shop/customer/core/customer_test.clj"]
                        ["shop" "shop/customer"])]
      (is (= {:argv ["clojure" "-M:test" "--focus" "shop.core.shop-test"]}
             (runner/test-command dir "shop"))))))
