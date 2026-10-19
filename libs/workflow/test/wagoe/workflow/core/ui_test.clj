(ns wagoe.workflow.core.ui-test
  (:require [clojure.test :refer [deftest is]]
            [wagoe.shared.ui.core.home :as home]
            [wagoe.workflow.core.ui :as ui]))

(defn- back-href [page]
  (->> (tree-seq coll? seq page)
       (some #(when (and (vector? %) (= :a.button.secondary (first %)))
                (:href (second %))))))

(deftest ^:unit back-link-uses-the-configured-home
  (is (= "/web/dashboard" (back-href (ui/instances-page [] {} {}))))
  (binding [home/*path* "/app"]
    (is (= "/app" (back-href (ui/instances-page [] {} {}))))))

(defn- class-of [hiccup] (:class (second hiccup)))

(deftest ^:unit state-badges-use-the-entity-page-tones
  (is (= "badge ui-badge ui-badge-success c-badge tone-success"
         (class-of (ui/state-badge :paid :current))))
  (is (= "badge ui-badge ui-badge-info c-badge tone-info"
         (class-of (ui/state-badge :paid :reachable))))
  (is (= "badge ui-badge ui-badge-neutral c-badge tone-neutral"
         (class-of (ui/state-badge :paid :visited))))
  (is (= "On hold" (last (ui/state-badge :on-hold)))))

(deftest ^:unit instances-table-renders-inside-the-workflow-page-scope
  ;; app.css's `.workflow-page table.data-table` rule undoes the fixed
  ;; checkbox/actions column widths; outside that scope the headers overlap.
  (let [page  (ui/instances-page [{:id (random-uuid) :workflow-id :invoice-workflow
                                   :entity-type :invoice :entity-id (random-uuid)
                                   :current-state :paid}]
                                 {} {})
        scope (some #(when (and (vector? %) (= :div.workflow-page (first %))) %)
                    (tree-seq coll? seq page))]
    (is (some #(and (vector? %) (= :table (first %))
                    (= "data-table ui-table" (class-of %)))
              (tree-seq coll? seq scope)))))
