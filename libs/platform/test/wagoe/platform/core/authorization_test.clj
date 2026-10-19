(ns wagoe.platform.core.authorization-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.platform.core.authorization :as sut]))

(deftest ^:unit every-module-has-read-and-write
  (let [{:keys [permissions]} (sut/build-registry {:wagoe/orders nil :wagoe/user {}})]
    (is (= #{:orders/read :orders/write :user/read :user/write} (set (keys permissions))))
    (is (= {:label "Read orders" :source :derived :module :wagoe/orders}
           (:orders/read permissions)))))

(deftest ^:unit admin-entities-derive-read-and-write
  (is (= #{:admin.users/read :admin.users/write}
         (set (keys (sut/entity-permissions :users))))))

(deftest ^:unit declared-permissions-roles-and-scopes-join-the-registry
  (let [registry (sut/build-registry
                  {:wagoe/orders {:permissions {:orders/refund {:label "Refund orders"}}
                                  :roles       {:orders/manager {:description "All of orders"
                                                                 :permissions #{:orders/read :orders/refund}}}
                                  :scopes      {:domain-id {:label "Domain" :entity :domains}}}})]
    (is (= {:label "Refund orders" :source :declared :module :wagoe/orders}
           (get-in registry [:permissions :orders/refund])))
    (is (= :wagoe/orders (get-in registry [:roles :orders/manager :module])))
    (is (= {:label "Domain" :entity :domains :module :wagoe/orders}
           (get-in registry [:scopes :domain-id])))))

(deftest ^:unit two-declarations-of-one-permission
  (testing "from two modules clash"
    (is (= :configuration-error
           (get-in (sut/build-registry {:wagoe/a {:permissions {:x/y {:label "a"}}}
                                        :wagoe/b {:permissions {:x/y {:label "b"}}}})
                   [:error :type]))))
  (testing "a declared one stands over a derived one"
    (is (= "Read all orders"
           (get-in (sut/build-registry {:wagoe/orders {:permissions {:orders/read {:label "Read all orders"}}}})
                   [:permissions :orders/read :label]))))
  (testing "and the groups schema's yield to a module's, whichever comes first"
    (is (= :wagoe/orders
           (get-in (sut/build-registry {:wagoe/orders {:permissions {:orders/refund {:label "Refund"}}}
                                        :wagoe/user   {:permissions {:orders/refund {:label "orders/refund"
                                                                                     :source :groups-schema}}}})
                   [:permissions :orders/refund :module])))))

(deftest ^:unit a-role-naming-an-unknown-permission-is-refused
  (let [{:keys [error]} (sut/build-registry
                         {:wagoe/orders {:roles {:orders/viewer {:permissions #{:orders/read :orders/refnud}}}}})]
    (is (= :configuration-error (:type error)))
    (is (= [:orders/refnud] (:permissions error)))))

(deftest ^:unit only-admin-code-roles-hold-admin-permissions
  (let [decls {:wagoe/admin  {:permissions (sut/entity-permissions :users)}
               :wagoe/orders {:roles {:orders/boss {:permissions #{:admin.users/write}}}}}]
    (is (= :orders/boss (get-in (sut/build-registry decls) [:error :role])))
    (is (nil? (:error (sut/build-registry
                       (assoc decls :wagoe/admin {:permissions (sut/entity-permissions :users)
                                                  :roles {:admin/users {:permissions #{:admin.users/write}}}}
                              :wagoe/orders nil)))))))

(deftest ^:unit roles-and-scopes-clash-across-modules
  (is (some? (:error (sut/build-registry {:wagoe/a {:roles {:x/r {:permissions #{:a/read}}}}
                                          :wagoe/b {:roles {:x/r {:permissions #{:b/read}}}}}))))
  (testing "but one scope key declared alike by two modules is one key"
    (is (nil? (:error (sut/build-registry {:wagoe/a {:scopes {:domain-id {:label "Domain"}}}
                                           :wagoe/b {:scopes {:domain-id {:label "Domain"}}}})))))
  (is (some? (:error (sut/build-registry {:wagoe/a {:scopes {:domain-id {:label "Domain"}}}
                                          :wagoe/b {:scopes {:domain-id {:label "Area"}}}})))))

(deftest ^:unit a-malformed-declaration-is-refused
  (is (= :wagoe/orders (get-in (sut/build-registry {:wagoe/orders {:roles {:viewer {:permissions #{}}}}})
                               [:error :module]))))

(deftest ^:unit route-permissions-outside-the-registry
  (let [permissions {:orders/read {} :orders/refund {}}
        routes      [["/orders" {:get {:handler identity :permission :orders/read}}
                      ["/:id/refund" {:post {:handler identity :permission :orders/refnud}}]]
                     ["/reports" {:permission :reports/read :get {:handler identity}}]
                     ["/open" {:get {:handler identity}}]]]
    (is (= [{:path "/orders/:id/refund" :permission :orders/refnud}
            {:path "/reports" :permission :reports/read}]
           (sut/unknown-route-permissions permissions routes)))))
