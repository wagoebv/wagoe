(ns wagoe.workflow.core.ui-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.shared.ui.core.home :as home]
            [wagoe.workflow.core.ui :as ui]))

(defn- nodes [tree]
  (filter vector? (tree-seq coll? seq tree)))

(defn- tag? [node tag]
  (str/starts-with? (name (first node)) (name tag)))

(defn- badge-tones
  "[label tone-class] of each badge under `tree`, in order."
  [tree]
  (for [n (nodes tree)
        :let [cls (:class (second n))]
        :when (and (string? cls) (str/includes? cls "c-badge"))]
    [(last n) (re-find #"tone-\w+" cls)]))

(def ^:private definition
  {:id            :order
   :initial-state :pending
   :states        #{:pending :paid :on-hold :shipped}
   :transitions   [{:from :pending :to :paid}
                   {:from :paid :to :on-hold}
                   {:from :on-hold :to :paid}
                   {:from :paid :to :shipped}]})

(def ^:private instance
  {:id #uuid "00000000-0000-0000-0000-000000000001" :workflow-id :order
   :entity-type :order :entity-id #uuid "00000000-0000-0000-0000-000000000002"
   :current-state :paid})

(def ^:private audit
  [{:transition :pay :from-state :pending :to-state :paid}
   {:transition :hold :from-state :paid :to-state :on-hold}
   {:transition :release :from-state :on-hold :to-state :paid}])

(deftest ^:unit state-badges-use-the-entity-page-tones
  (is (str/includes? (:class (second (ui/state-badge :paid :current))) "c-badge tone-success"))
  (is (str/includes? (:class (second (ui/state-badge :paid :reachable))) "c-badge tone-info"))
  (is (str/includes? (:class (second (ui/state-badge :paid :visited))) "c-badge tone-neutral"))
  (is (= "On hold" (last (ui/state-badge :on-hold)))))

(deftest ^:unit pages-sit-in-the-admin-shell
  (let [page (ui/instances-page [] {} {:entities [:users]
                                       :entity-configs {:users {:label "Users"}}})]
    (is (some #(tag? % :aside.admin-sidebar) (nodes page)))
    (is (some #(= "/web/admin/users" (:href (second %))) (nodes page)))
    (testing "the sidebar's dashboard link is the configured home"
      (binding [home/*path* "/app"]
        (is (some #(= "/app" (:href (second %)))
                  (nodes (ui/instances-page [] {} {}))))))))

(deftest ^:unit the-detail-page-cards-are-styled
  (let [page  (ui/instance-detail-page instance definition audit {})
        cards (filter #(tag? % :article.workflow-card) (nodes page))]
    (is (= 3 (count cards)))
    (is (every? #(some (fn [n] (tag? n :h2.workflow-card-title)) (nodes %)) cards))))

(deftest ^:unit only-the-current-state-reads-as-current-in-the-audit-trail
  ;; Every to-state used to render as current.
  (is (= [["Pending" "tone-neutral"] ["Paid" "tone-neutral"]
          ["Paid" "tone-neutral"] ["On hold" "tone-neutral"]
          ["On hold" "tone-neutral"] ["Paid" "tone-success"]]
         (badge-tones (ui/audit-log-table audit :paid))))
  (testing "a last entry that is not where the instance is now is not current"
    (is (= "tone-neutral" (second (last (badge-tones (ui/audit-log-table audit :shipped))))))))

(deftest ^:unit a-reachable-state-reads-as-reachable-though-visited
  ;; :on-hold was visited and is reachable from :paid again.
  (let [tones (into {} (take 4 (badge-tones (ui/state-machine-vis definition :paid [:paid :on-hold :paid]))))]
    (is (= {"Pending" "tone-neutral" "Paid" "tone-success"
            "On hold" "tone-info" "Shipped" "tone-info"}
           tones))))
