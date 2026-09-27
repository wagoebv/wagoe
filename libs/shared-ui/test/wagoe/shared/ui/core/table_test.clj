(ns wagoe.shared.ui.core.table-test
  (:require [wagoe.shared.ui.core.table :as table-ui]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest ^:unit sortable-th-direction-toggle-test
  (testing "active asc sort toggles to desc and renders ascending icon"
    (let [th (table-ui/sortable-th {:label "Email"
                                    :field :email
                                    :current-sort :email
                                    :current-dir :asc
                                    :base-url "/web/admin/users/table"
                                    :page-size 20
                                    :hx-target "#entity-table-container"
                                    :hx-push-url? true})
          attrs (second th)]
      (is (str/includes? (:hx-get attrs) "sort=email"))
      (is (str/includes? (:hx-get attrs) "dir=desc"))
      (is (str/includes? (:hx-push-url attrs) "dir=desc"))
      (is (= "↑" (last (last th))))))

  (testing "active desc sort toggles to asc and renders descending icon"
    (let [th (table-ui/sortable-th {:label "Email"
                                    :field :email
                                    :current-sort :email
                                    :current-dir :desc
                                    :base-url "/web/admin/users/table"
                                    :page-size 20
                                    :hx-target "#entity-table-container"
                                    :hx-push-url? true})
          attrs (second th)]
      (is (str/includes? (:hx-get attrs) "sort=email"))
      (is (str/includes? (:hx-get attrs) "dir=asc"))
      (is (str/includes? (:hx-push-url attrs) "dir=asc"))
      (is (= "↓" (last (last th)))))))

(deftest ^:unit table-controls-replace-their-target-test
  ;; The handlers behind these return the element carrying the target id, so
  ;; an innerHTML swap nested a duplicate on every click (BOU-386).
  (let [th    (table-ui/sortable-th {:label "Email" :field :email :base-url "/t"
                                     :page-size 20 :hx-target "#entity-table-container"})
        pager (table-ui/pagination {:table-query {:page 2 :page-size 10} :total-count 50
                                    :base-url "/t" :hx-target "#entity-table-container"})
        requesting (filter #(and (vector? %) (map? (second %)) (:hx-get (second %)))
                           (tree-seq #(or (vector? %) (seq? %)) seq [th pager]))]
    (is (< 3 (count requesting)))
    (is (every? #(= "outerHTML" (:hx-swap (second %))) requesting))))
