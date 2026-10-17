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
