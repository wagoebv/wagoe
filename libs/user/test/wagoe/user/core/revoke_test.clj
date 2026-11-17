(ns wagoe.user.core.revoke-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.user.core.revoke :as revoke]
            [wagoe.user.core.revoke-ui :as revoke-ui]))

(defn- preview [removed affected]
  {:user {:removed (mapv (fn [p] {:permission p :scope {}}) removed)} :affected-count affected})

(defn- path [group-id & {:keys [role scope] :or {role :x/editor scope {}}}]
  {:group-id group-id :role role :stored-scope scope :membership :member})

(deftest ^:unit options-are-ranked-by-what-else-they-take
  (let [leave-a  {:kind :leave :group {:id 1 :name "A"} :preview (preview [:x/read :x/write] 0)}
        leave-b  {:kind :leave :group {:id 2 :name "B"} :preview (preview [:x/read] 0)}
        end-b    {:kind :end-assignment :group {:id 2 :name "B"} :assignment {:role :x/editor :scope {}}
                  :preview (preview [:x/read] 3)}
        refused  {:kind :end-assignment :group {:id 4 :name "D"} :refused "no"}]
    (testing "held through one group: every option there removes it"
      (let [ranked (revoke/rank [refused end-b leave-b] :x/read [(path 2)])]
        (is (= [:leave :end-assignment :end-assignment] (map :kind ranked)))
        (is (= [true true nil] (map :removes? ranked)))
        (is (= [0 3] (keep :radius ranked)))))
    (testing "held through two groups: no option alone removes it, and what else each takes"
      (let [ranked (revoke/rank [leave-a leave-b] :x/read [(path 1) (path 2)])]
        (is (= ["B" "A"] (map (comp :name :group) ranked)))
        (is (= [false false] (map :removes? ranked)))
        (is (= [:x/write] (map :permission (:also-loses (second ranked)))))))
    (testing "the same group in two scopes: ending one assignment leaves the other"
      (let [o {:kind :end-assignment :group {:id 2 :name "B"} :assignment {:role :x/editor :scope {:d 1}}
               :preview (preview [:x/read] 0)}]
        (is (false? (:removes? (first (revoke/rank [o] :x/read [(path 2 :scope {:d 1}) (path 2 :scope {:d 2})])))))))))

(deftest ^:unit ties-go-to-a-leave-then-by-group-name
  (let [o      (fn [kind n] {:kind kind :group {:id n :name n} :assignment {:role :x/editor :scope {}}
                             :preview (preview [:x/read] 0)})
        ranked (revoke/rank [(o :end-assignment "A") (o :leave "B") (o :leave "A")] :x/read [])]
    (is (= [[:leave "A"] [:leave "B"] [:end-assignment "A"]] (map (juxt :kind (comp :name :group)) ranked)))))

(deftest ^:unit a-refused-option-says-why-and-offers-nothing
  (let [hiccup (pr-str (#'revoke-ui/option 1 {:kind :leave :group {:id 3 :name "Desk"} :refused "nope"}
                                           7 :x/read {} true))]
    (is (str/includes? hiccup "revoke-refused"))
    (is (str/includes? hiccup "nope"))
    (is (not (str/includes? hiccup "revoke-apply")))))
