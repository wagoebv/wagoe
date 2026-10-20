(ns wagoe.search.core.ui-test
  (:require [clojure.test :refer [deftest is]]
            [wagoe.search.core.ui :as ui]
            [wagoe.shared.ui.core.home :as home]))

(defn- back-href [page]
  (->> (tree-seq coll? seq page)
       (some #(when (and (vector? %) (= :a.button.secondary (first %)))
                (:href (second %))))))

(deftest ^:unit back-link-uses-the-configured-home
  (is (= "/web/dashboard" (back-href (ui/indices-page [] {}))))
  (binding [home/*path* "/app"]
    (is (= "/app" (back-href (ui/indices-page [] {}))))))
