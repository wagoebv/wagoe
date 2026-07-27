(ns wagoe.mcp.core.resources-test
  (:require [wagoe.mcp.core.resources :as res]
            [clojure.test :refer [deftest is testing]]))

(def ^:private full-snapshot
  {:conventions  {:fc-is {:rules ["core pure"]} :naming [{:case :kebab}]}
   :module-graph {:modules [{:name "user" :deps [] :has-ports? true}]
                  :edges   []}
   :kondo-rules  {:config {:linters {}}}
   :libs         {"user" {:namespaces ["wagoe.user.ports"]}}})

(deftest ^:unit catalog-advertises-seven-resources
  (is (= 7 (count res/catalog)))
  (is (every? #(and (:uri %) (:name %) (:description %) (:mimeType %)) res/catalog))
  (is (every? #(= :read (:capability %)) res/catalog)))

(deftest ^:unit concrete-resources-read-from-snapshot
  (testing "conventions / module-graph / kondo-rules come straight from the snapshot"
    (is (= (:conventions full-snapshot)  (res/read-resource full-snapshot "wagoe://conventions")))
    (is (= (:module-graph full-snapshot) (res/read-resource full-snapshot "wagoe://module-graph")))
    (is (= (:kondo-rules full-snapshot)  (res/read-resource full-snapshot "wagoe://kondo-rules")))))

(deftest ^:unit lib-resource-is-templated-by-name
  (is (= {:namespaces ["wagoe.user.ports"]}
         (res/read-resource full-snapshot "wagoe://lib/user")))
  (testing "unknown lib name → unavailable, not nil"
    (is (= :unavailable (:status (res/read-resource full-snapshot "wagoe://lib/nope"))))))

(deftest ^:unit live-resources-unavailable-without-snapshot-data
  (testing "an empty snapshot yields :unavailable for live-state resources"
    (doseq [uri ["wagoe://schema-registry" "wagoe://routes" "wagoe://workflows"]]
      (is (= :unavailable (:status (res/read-resource {} uri)))
          uri))))

(deftest ^:unit unknown-uri-is-nil
  (is (nil? (res/read-resource full-snapshot "wagoe://does-not-exist")))
  (is (not (res/known-resource? full-snapshot "wagoe://does-not-exist")))
  (testing "wagoe://lib/ with no name is unknown, not :unavailable"
    (is (nil? (res/read-resource full-snapshot "wagoe://lib/")))))

(deftest ^:unit reads-only-force-the-requested-view
  (testing "delayed views are forced lazily — reading one never builds the others"
    (let [forced (atom #{})
          snap   {:conventions  (delay (swap! forced conj :conventions) {:ok 1})
                  :module-graph (delay (swap! forced conj :module-graph) {:m 1})}]
      (is (= {:ok 1} (res/read-resource snap "wagoe://conventions")))
      (is (= #{:conventions} @forced)))))
