(ns wagoe.platform.core.authorization-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.platform.core.authorization :as sut]))

(deftest ^:unit every-module-has-read-and-write
  (let [{:keys [permissions]} (sut/build-registry {:wagoe/orders nil :wagoe/user {}})]
    (is (= #{:orders/read :orders/write :user/read :user/write} (set (keys permissions))))
    (is (= {:label "Read orders" :source :derived :module :wagoe/orders}
           (:orders/read permissions)))))

(deftest ^:unit declared-permissions-roles-and-scopes-join-the-registry
  (let [registry (sut/build-registry
                  {:wagoe/orders {:permissions {:orders/refund {:label "Refund orders"}}
                                  :roles       {:orders/manager {:description "All of orders"
                                                                 :permissions #{:orders/read :orders/refund}}}
                                  :scopes      {:domain-id {:label "Domain" :entity :domains}}}})]
    (is (= {:label "Refund orders" :source :declared :module :wagoe/orders}
           (get-in registry [:permissions :orders/refund])))
    (is (= :wagoe/orders (get-in registry [:roles :orders/manager :module])))
    (is (= {:label "Domain" :entity :domains :source :declared :module :wagoe/orders}
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
                   [:permissions :orders/refund :module]))))
  (testing "including the user module's own derived ones"
    (is (= :derived
           (get-in (sut/build-registry {:wagoe/user {:permissions {:user/read {:label "user/read"
                                                                               :source :groups-schema}}}})
                   [:permissions :user/read :source])))))

(deftest ^:unit a-role-naming-an-unknown-permission-is-refused
  (let [{:keys [error]} (sut/build-registry
                         {:wagoe/orders {:roles {:orders/viewer {:permissions #{:orders/read :orders/refnud}}}}})]
    (is (= :configuration-error (:type error)))
    (is (= [:orders/refnud] (:permissions error)))))

(deftest ^:unit only-admin-code-roles-hold-admin-permissions
  (let [decls {:wagoe/admin  {:permissions (sut/read-write-permissions "admin.users" "users")}
               :wagoe/orders {:roles {:orders/boss {:permissions #{:admin.users/write}}}}}]
    (is (= :orders/boss (get-in (sut/build-registry decls) [:error :role])))
    (is (nil? (:error (sut/build-registry
                       (assoc decls :wagoe/admin {:permissions (sut/read-write-permissions "admin.users" "users")
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
        routes      [["/orders" {:get {:handler identity :permission :orders/read}}]
                     ["/orders/:id/refund" {:post {:handler identity :permission :orders/refnud}}]
                     ["/reports" {:permission :reports/read :get {:handler identity}}]
                     ["/open" {:get {:handler identity}}]]]
    (is (= [{:path "/orders/:id/refund" :permission :orders/refnud}
            {:path "/reports" :permission :reports/read}]
           (sut/unknown-route-permissions permissions routes)))))

(deftest ^:unit every-module-has-viewer-and-editor-roles
  (let [{:keys [roles]} (sut/build-registry {:wagoe/orders nil})]
    (is (= {:orders/viewer #{:orders/read} :orders/editor #{:orders/read :orders/write}}
           (update-vals roles :permissions)))
    (is (= :derived (get-in roles [:orders/editor :source]))))
  (testing "a declared one stands over it, in either order"
    (doseq [decls [{:wagoe/orders {:roles {:orders/editor {:permissions #{:orders/read}}}}}
                   {:wagoe/orders nil :wagoe/aaa {:roles {:orders/editor {:permissions #{:orders/read}}}}}
                   {:wagoe/orders nil :wagoe/zzz {:roles {:orders/editor {:permissions #{:orders/read}}}}}]]
      (is (= #{:orders/read} (get-in (sut/build-registry decls) [:roles :orders/editor :permissions]))))))
(deftest ^:unit a-scope-key-from-the-groups-schema-yields-to-a-declared-one
  (let [scope (get-in (sut/build-registry {:wagoe/orders {:scopes {:domain-id {:label "Domain"}}}
                                           :wagoe/user   {:scopes {:domain-id {:label "domain-id" :schema :int
                                                                               :source :groups-schema}}}})
                      [:scopes :domain-id])]
    (is (= :wagoe/orders (:module scope)))
    (is (= "Domain" (:label scope)))
    (is (= :int (:schema scope)) "keeping the type the groups schema gave it")))

(deftest ^:unit scope-sources-need-a-resolver
  (let [dept (fn [_] #{7})]
    (testing "a key taken from a source someone resolves boots"
      (is (= :wagoe/app
             (get-in (sut/build-registry {:wagoe/app {:scopes        {:department-id {:label   "Department"
                                                                                      :sources #{:user/department}}}
                                                      :scope-sources {:user/department {:label "Their department" :resolve dept}}}})
                     [:scope-sources :user/department :module]))))
    (testing "one nobody resolves does not"
      (let [{:keys [error]} (sut/build-registry {:wagoe/app {:scopes {:department-id {:label   "Department"
                                                                                      :sources #{:user/department}}}}})]
        (is (= :configuration-error (:type error)))
        (is (= [:user/department] (:sources error)))))
    (testing "a source is a :user/<name> keyword"
      (is (= :wagoe/app (get-in (sut/build-registry {:wagoe/app {:scope-sources {:org/department {:label "x" :resolve dept}}}})
                                [:error :module]))))))

(deftest ^:unit route-scopes-the-platform-cannot-read
  (let [scopes {:domain-id {}}
        routes [["/a" {:get {:handler identity}}]
                ["/b" {:get {:handler identity :scope :handler}}]
                ["/c/:domain-id" {:scope {:domain-id :path/domain-id} :get {:handler identity}}]
                ["/d/:id" {:get {:handler identity :scope {:area-id :path/id}}}]
                ["/e/:id" {:get {:handler identity :scope {:domain-id :id}}}]
                ["/f" {:get {:handler identity :scope :everywhere}}]
                ["/g/:domian-id" {:get {:handler identity :scope {:domain-id :path/domain-id}}}]
                ["/h/{domain-id}" {:get {:handler identity :scope {:domain-id :path/domain-id}}}]]]
    (is (= [{:path "/d/:id" :scope {:area-id :path/id}}
            {:path "/e/:id" :scope {:domain-id :id}}
            {:path "/f" :scope :everywhere}
            {:path "/g/:domian-id" :scope {:domain-id :path/domain-id}}]
           (sut/invalid-route-scopes scopes routes)))))
