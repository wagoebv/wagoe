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
