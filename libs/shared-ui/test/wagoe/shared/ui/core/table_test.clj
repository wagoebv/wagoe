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

(deftest ^:unit table-controls-hx-swap-option-test
  ;; For a handler that returns only the target's contents.
  (let [th    (table-ui/sortable-th {:label "Email" :field :email :base-url "/t" :page-size 20
                                     :hx-target "#t" :hx-swap "innerHTML"})
        pager (table-ui/pagination {:table-query {:page 2 :page-size 10} :total-count 50
                                    :base-url "/t" :hx-target "#t" :hx-swap "innerHTML"})
        requesting (filter #(and (vector? %) (map? (second %)) (:hx-get (second %)))
                           (tree-seq #(or (vector? %) (seq? %)) seq [th pager]))]
    (is (< 3 (count requesting)))
    (is (every? #(= "innerHTML" (:hx-swap (second %))) requesting))))

(deftest ^:unit filter-specs-survive-sort-and-paging-test
  (let [filters {:status {:op :eq :value "paid"}
                 :kind   {:op :in :values ["a" "b c"]}
                 :role   "admin"}
        th      (table-ui/sortable-th {:label "Email" :field :email :base-url "/t"
                                       :page 1 :page-size 20 :hx-target "#t"
                                       :extra-params filters})
        url     (get-in th [1 :hx-get])]
    (testing "a spec map goes back in the filter bar's format, URL-encoded"
      (is (str/includes? url "filters%5Bstatus%5D%5Bop%5D=eq"))
      (is (str/includes? url "filters%5Bstatus%5D%5Bvalue%5D=paid"))
      (is (str/includes? url "filters%5Bkind%5D%5Bvalues%5D%5B%5D=a&filters%5Bkind%5D%5Bvalues%5D%5B%5D=b+c"))
      (is (str/includes? url "role=admin"))
      (is (not (str/includes? url "%7B")) "no printed map"))
    (testing "search is kept"
      (is (str/includes? (table-ui/encode-query-params
                          (table-ui/table-query->params {:page 2 :page-size 20 :search "a&b"}))
                         "search=a%26b")))))
