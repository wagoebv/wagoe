(ns wagoe.user.core.grant-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.user.core.grant :as sut]))

(deftest ^:unit the-smallest-role-holding-a-permission
  (let [roles {:invoice/editor {:permissions #{:invoice/read :invoice/write}}
               :invoice/viewer {:permissions #{:invoice/read}}
               :audit/reader   {:permissions #{:invoice/read}}}]
    (is (= :audit/reader (sut/smallest-role roles :invoice/read)) "fewest permissions, then by name")
    (is (= :invoice/editor (sut/smallest-role roles :invoice/write)))
    (is (nil? (sut/smallest-role roles :invoice/approve)))))

(defn- preview [added & counts]
  {:user {:added added} :permissions (vec counts)})

(deftest ^:unit an-option-fixes-only-what-it-gives-where-asked
  (let [p (preview [{:permission :invoice/read :scope {:domain-id #{4}}}])]
    (is (sut/fixes? p :invoice/read {:domain-id 4}))
    (is (not (sut/fixes? p :invoice/read {:domain-id 5})))
    (is (not (sut/fixes? p :invoice/read {})) "a scoped right is not everywhere")
    (is (not (sut/fixes? p :invoice/write {:domain-id 4}))))
  (is (sut/fixes? (preview [{:permission :invoice/read :scope {}}]) :invoice/read {:domain-id 4})))

(deftest ^:unit options-rank-by-what-else-they-give
  (let [read   {:permission :invoice/read :scope {}}
        write  {:permission :invoice/write :scope {}}
        join   {:kind :join :group {:name "Finance"}
                :preview (preview [read write] {:permission :invoice/read :added 1} {:permission :invoice/write :added 1})}
        narrow {:kind :narrow :group {:name "Desk"}
                :preview (preview [read] {:permission :invoice/read :added 2})}
        alone  {:kind :narrow :group {:name "Alone"} :preview (preview [read] {:permission :invoice/read :added 1})}
        miss   {:kind :join :group {:name "Elsewhere"} :preview (preview [write] {:permission :invoice/write :added 1})}
        ranked (sut/rank [{:kind :new-group :preview (preview [read] {:permission :invoice/read :added 1})}
                          {:kind :new-role} narrow miss join alone]
                         :invoice/read {})]
    (testing "one that does not give it is dropped; the last resorts come last"
      (is (= [["Alone" :narrow] ["Finance" :join] ["Desk" :narrow] [nil :new-role] [nil :new-group]]
             (map (juxt (comp :name :group) :kind) ranked))))
    (testing "a join before an assignment of the same radius, each saying what else it gives"
      (is (= [0 1 1] (map :radius (take 3 ranked))))
      (is (= [write] (:extra (second ranked)))))
    (is (= 0 (sut/blast-radius (preview []))) "never below nothing")))

(deftest ^:unit giving-it-wider-than-asked-counts-as-more
  (let [everywhere {:kind :join :group {:name "Everywhere"}
                    :preview (preview [{:permission :invoice/read :scope {}}] {:permission :invoice/read :added 1})}
        exact      {:kind :narrow :group {:name "Exact"}
                    :preview (preview [{:permission :invoice/read :scope {:domain-id #{4}}}] {:permission :invoice/read :added 1})}
        [a b]      (sut/rank [everywhere exact] :invoice/read {:domain-id 4})]
    (is (= ["Exact" "Everywhere"] [(get-in a [:group :name]) (get-in b [:group :name])]))
    (is (= [[] [{:permission :invoice/read :scope {}}]] [(:extra a) (:extra b)]))
    (is (= [0 1] [(:radius a) (:radius b)]))))
