(ns wagoe.user-audit-joins-test
  "As the app wires them, the user library's audit repository joins the group
   and user repositories' transactions, so a rights change rolls back with its
   audit entries (BOU-958, BOU-967)."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.system-config :as app]
            [wagoe.test :as t]
            [wagoe.user.ports :as ports]))

(deftest ^:integration the-wired-audit-repository-joins-the-change-transaction
  (t/with-test-system [sys app/ig-config]
    (let [audit   (:wagoe/audit-repository sys)
          entries #(:total-count (ports/find-audit-logs audit {:limit 1}))
          entry   {:action :create :actor-id (random-uuid) :actor-email "system"
                   :target-user-id (random-uuid) :target-user-email "ada@example.com"
                   :changes {:created true} :result :success}
          in-tx   {"group repository" #(ports/in-transaction (:wagoe/user-group-repository sys) (fn [_] (%)))
                   "user repository"  #(ports/with-admins-locked (:wagoe/user-repository sys) %)}]
      (doseq [[what run] in-tx]
        (testing what
          (let [before (entries)]
            (is (thrown-with-msg? Exception #"after the entry"
                                  (run #(do (ports/create-audit-log audit entry)
                                            (throw (ex-info "after the entry" {:type :internal-error}))))))
            (is (= before (entries)) "the entry rolled back with the transaction")
            (run #(ports/create-audit-log audit entry))
            (is (= (inc before) (entries)) "committed with it")))))))
