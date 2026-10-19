(ns wagoe.audience.core.ui-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hiccup2.core :as h]
            [wagoe.audience.core.ui :as ui]))

(defn- html [hiccup] (str (h/html hiccup)))

(deftest ^:unit filter-badge-test
  (testing "type, field, op and value each appear"
    (let [out (html (ui/filter-badge {:type :role :field :plan :op :eq :value "free"}))]
      (is (every? #(str/includes? out %) [">role<" ">plan<" ">eq<" ">free<"]))))

  (testing "a filter without a field renders without one"
    (let [out (html (ui/filter-badge {:type :last-active :op :within-days :value 30}))]
      (is (= 4 (count (re-seq #"<span" out))) "the badge and three parts")
      (is (str/includes? out ">within-days<")))))

(deftest ^:unit segment-card-test
  (testing "label, count, description, badges and cache time"
    (let [out (html (ui/segment-card {:label        "Free users"
                                      :description  "On the free plan"
                                      :member-count 42
                                      :cached-at    "2026-10-19T10:00:00Z"
                                      :filters      [{:type :role :field :plan :op :eq :value "free"}
                                                     {:type :last-active :op :within-days :value 30}]}))]
      (is (str/includes? out "Free users"))
      (is (str/includes? out "(42 members)"))
      (is (str/includes? out "On the free plan"))
      (is (str/includes? out "Cached: 2026-10-19T10:00:00Z"))
      (is (= 2 (count (re-seq #"rounded-full" out))) "one badge per filter")))

  (testing "a segment never evaluated shows zero members and no cache line"
    (let [out (html (ui/segment-card {:label "New" :filters []}))]
      (is (str/includes? out "(0 members)"))
      (is (not (str/includes? out "Cached:")))
      (is (not (str/includes? out "<p class=\"mt-1"))))))

(deftest ^:unit segment-list-test
  (is (= 2 (count (re-seq #"<h3" (html (ui/segment-list [{:label "A" :filters []}
                                                         {:label "B" :filters []}])))))))

(deftest ^:unit builder-layout-test
  (testing "new mode has empty fields"
    (let [out (html (ui/builder-layout {}))]
      (is (str/includes? out "New Audience"))
      (is (str/includes? out "value=\"\""))
      (is (every? #(str/includes? out %)
                  ["id=\"audience-filter-panel\"" "id=\"audience-composition-builder\""
                   "id=\"audience-preview\"" "id=\"audience-filters-data\""])
          "the widget mount points are there")))

  (testing "edit mode fills in the segment, escaped"
    (let [out (html (ui/builder-layout {:segment {:label "<b>VIP</b>" :description "Top & loyal"} :edit? true}))]
      (is (str/includes? out "Edit Audience"))
      (is (str/includes? out "value=\"&lt;b&gt;VIP&lt;/b&gt;\""))
      (is (str/includes? out "Top &amp; loyal"))
      (is (not (str/includes? out "<b>VIP"))))))
