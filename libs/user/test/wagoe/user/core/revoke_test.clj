(ns wagoe.user.core.revoke-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.user.core.revoke :as revoke]))

(defn- preview [removed affected]
  {:user {:removed (mapv (fn [p] {:permission p :scope {}}) removed)} :affected-count affected})

(deftest ^:unit options-are-ranked-by-what-else-they-take
  (let [leave-a  {:kind :leave :group {:name "A"} :preview (preview [:x/read :x/write] 0)}
        leave-b  {:kind :leave :group {:name "B"} :preview (preview [:x/read] 0)}
        end-b    {:kind :end-assignment :group {:name "B"} :preview (preview [:x/read] 3)}
        partial  {:kind :leave :group {:name "C"} :preview (preview [] 0)}
        refused  {:kind :end-assignment :group {:name "D"} :refused "no"}
        ranked   (revoke/rank [refused partial end-b leave-a leave-b] :x/read)]
    (is (= ["B" "A" "B" "C" "D"] (map (comp :name :group) ranked)))
    (is (= [:leave :leave :end-assignment :leave :end-assignment] (map :kind ranked)))
    (testing "what else each takes"
      (is (= [0 1 3 0] (map :radius (butlast ranked))))
      (is (= [:x/write] (map :permission (:also-loses (second ranked))))))
    (testing "one that leaves the permission held is marked"
      (is (= [true true true false] (map :removes? (butlast ranked)))))))
