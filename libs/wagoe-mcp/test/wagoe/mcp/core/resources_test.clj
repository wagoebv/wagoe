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

(deftest ^:unit only-resources-that-deliver-are-advertised
  ;; resources/list advertised all seven everywhere, and four of them answered
  ;; with an :unavailable note in any project — the agent spent a round trip to
  ;; be told the resource does not exist here (BOU-320).
  (testing "a project that can serve two advertises two"
    (let [snap {:conventions  {:fc-is {}}
                :module-graph {:source :project :libraries ["wagoe-core"]}}]
      (is (= ["wagoe://conventions" "wagoe://module-graph"]
             (mapv :uri (res/available-catalog snap))))))

  (testing "a view present but :unavailable is not advertised"
    (is (empty? (res/available-catalog {:conventions {:status :unavailable :note "x"}}))))

  (testing "the lib template is advertised only when libs are reflected"
    (is (empty? (res/available-catalog {:libs {}})))
    (is (= [(str res/lib-uri-prefix "{name}")]
           (mapv :uri (res/available-catalog {:libs {"user" {:namespaces []}}})))))

  (testing "a snapshot with everything still advertises everything"
    (is (= (count res/catalog)
           (count (res/available-catalog (assoc full-snapshot
                                                :schema-registry {:x 1}
                                                :routes          {:x 1}
                                                :workflows       {:x 1})))))))

(deftest ^:unit a-view-that-throws-does-not-take-the-listing-with-it
  ;; Filtering forces every view — that is what it is for. The registry is
  ;; seeded from this at startup, so one unreadable file used to mean the
  ;; server did not start at all.
  (let [snap {:conventions  (delay {:fc-is {}})
              :module-graph (delay (throw (java.io.FileNotFoundException. "permission denied")))
              :kondo-rules  (delay nil)}]
    (is (= ["wagoe://conventions"] (mapv :uri (res/available-catalog snap))))))
