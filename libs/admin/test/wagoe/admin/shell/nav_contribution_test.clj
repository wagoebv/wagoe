(ns wagoe.admin.shell.nav-contribution-test
  "The admin's sidebar items come from each entity's resolved config, asked
   per page: a join table is hidden once its table exists, whenever that is."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.admin.ports :as ports]
            [wagoe.admin.shell.http :as http]))

(defn- provider [resolved]
  (reify ports/ISchemaProvider
    (fetch-table-metadata [_ _] nil)
    (get-entity-config [_ e] (or (get @resolved e) (throw (ex-info "no table" {:type :not-found}))))
    (list-available-entities [_] [:tickets :ticket-labels])
    (get-entity-label [_ _] nil)
    (validate-entity-exists [_ _] true)))

(defn- hrefs [routes]
  (->> ((:nav routes)) :admin :items (mapv :href)))

(deftest ^:unit join-tables-leave-the-sidebar-once-resolved
  (let [resolved (atom {})
        routes   (http/admin-routes nil (provider resolved)
                                    {:entities {:tickets {:label "Tickets" :module :support}}} nil)]
    (testing "before the tables exist: the entities' own config"
      (is (= ["/web/admin/tickets" "/web/admin/ticket-labels"] (hrefs routes)))
      (is (= :support (-> ((:nav routes)) :admin :items first :module))))
    (testing "after: the resolved config, which hides the join table"
      (reset! resolved {:tickets       {:label "Tickets" :module :support}
                        :ticket-labels {:label "Ticket labels" :sidebar-hidden true}})
      (is (= ["/web/admin/tickets"] (hrefs routes))))))
