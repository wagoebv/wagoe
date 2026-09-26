(ns wagoe.admin.shell.has-many-test
  "Has-many panels: adding a child from the parent's page (BOU-491), and
   has-many detected from a foreign key (BOU-481)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [wagoe.admin.ports :as ports]
            [wagoe.admin.shell.http.handlers.crud :as crud]
            [wagoe.admin.shell.http.handlers.detail :as detail]
            [wagoe.admin.shell.http.handlers.list :as list]
            [wagoe.admin.shell.schema-repository :as schema-repo]
            [wagoe.admin.shell.service :as service]
            [wagoe.observability.errors.shell.adapters.no-op :as error-reporting-no-op]
            [wagoe.observability.logging.shell.adapters.no-op :as logging-no-op]
            [wagoe.platform.database :as db]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [ring.util.codec :as codec]))

^{:kaocha.testable/meta {:contract true :admin true}}

(def ^:private config
  {:enabled?         true
   :base-path        "/web/admin"
   :require-role     :admin
   :entity-discovery {:mode :allowlist :allowlist #{:hm-orders :hm-items}}
   :entities         {:hm-orders {:label    "Orders"
                                  :has-many [{:entity      :hm-items
                                              :table       :hm_items
                                              :foreign-key :hm-order-id
                                              :label       "Items"
                                              :fields      [:sku]
                                              :editable    true}]}
                      :hm-items  {:label "Items"}}
   :pagination       {:default-page-size 20 :max-page-size 200}})

(def ^:private admin {:id #uuid "00000000-0000-0000-0000-000000000001" :role :admin :active true})

(def ^:private sys (atom nil))

(use-fixtures :once
  (fn [f]
    (let [db-ctx (db-factory/db-context {:adapter       :h2
                                         :database-path "mem:admin_has_many_create;DB_CLOSE_DELAY=-1"
                                         :pool          {:minimum-idle 1 :maximum-pool-size 2}})]
      (db/execute-update! db-ctx {:raw "CREATE TABLE hm_orders (id UUID PRIMARY KEY, name VARCHAR(100) NOT NULL)"})
      (db/execute-update! db-ctx {:raw "CREATE TABLE hm_items (id UUID PRIMARY KEY, hm_order_id UUID NOT NULL, sku VARCHAR(100) NOT NULL)"})
      ;; A child whose list page joins a second table, like :users and auth_users.
      (db/execute-update! db-ctx {:raw "CREATE TABLE hm_members (id UUID PRIMARY KEY, hm_order_id UUID NOT NULL)"})
      (db/execute-update! db-ctx {:raw "CREATE TABLE hm_profiles (id UUID PRIMARY KEY, nickname VARCHAR(100) NOT NULL, hm_order_id UUID)"})
      (let [sp  (schema-repo/create-schema-repository db-ctx config)
            svc (service/create-admin-service db-ctx sp
                                              (logging-no-op/create-logging-component {})
                                              (error-reporting-no-op/create-error-reporting-component {})
                                              config)]
        (reset! sys {:db db-ctx :sp sp :svc svc})
        (try (f)
             (finally
               (db/execute-update! db-ctx {:raw "DROP TABLE hm_items"})
               (db/execute-update! db-ctx {:raw "DROP TABLE hm_members"})
               (db/execute-update! db-ctx {:raw "DROP TABLE hm_profiles"})
               (db/execute-update! db-ctx {:raw "DROP TABLE hm_orders"})
               (db-factory/close-db-context! db-ctx)))))))

(defn- request [method entity & {:keys [id query form]}]
  (cond-> {:request-method method
           :uri            (str "/web/admin/" entity)
           :headers        {}
           :user           admin
           :session        {:user admin}
           :path-params    {:entity entity}
           :query-params   (or query {})
           :form-params    (or form {})}
    id (assoc-in [:path-params :id] (str id))))

(defn- handler [make]
  (make (:svc @sys) (:sp @sys) config))

(defn- create-order! []
  (let [id (random-uuid)]
    (db/execute-update! (:db @sys) {:raw (str "INSERT INTO hm_orders (id, name) VALUES ('" id "', 'Order')")})
    id))

(deftest ^:contract parent-detail-page-offers-a-new-child-link
  (let [order-id (create-order!)
        body     (:body ((handler detail/entity-detail-handler)
                         (request :get "hm-orders" :id order-id)))]
    (is (str/includes? body (str "/web/admin/hm-items/new?hm-order-id=" order-id
                                 "&amp;return_to=%2Fweb%2Fadmin%2Fhm-orders%2F" order-id)))))

(deftest ^:contract new-child-form-prefills-the-foreign-key
  (let [order-id (str (create-order!))
        parent   (str "/web/admin/hm-orders/" order-id)
        new-page #(:body ((handler detail/new-entity-handler) (request :get "hm-items" :query %)))]
    (testing "a UUID in the FK param fills the field, and return_to reaches the form action"
      (let [body (new-page {"hm-order-id" order-id "return_to" parent})]
        (is (str/includes? body (str "value=\"" order-id "\"")))
        (is (str/includes? body (str "/web/admin/hm-items?return_to=%2Fweb%2Fadmin%2Fhm-orders%2F" order-id)))))

    (testing "a value that is not a UUID is not written into the form"
      (let [body (new-page {"hm-order-id" "\"><script>alert(1)</script>"})]
        (is (not (str/includes? body "alert(1)")))))

    (testing "only a has-many foreign key is prefilled, not any field"
      (let [body (new-page {"sku" "INJECTED"})]
        (is (not (str/includes? body "INJECTED")))))

    (testing "a return_to outside the admin is ignored"
      (let [body (new-page {"return_to" "https://evil.example"})]
        (is (not (str/includes? body "evil.example")))))))

(deftest ^:contract creating-a-child-returns-to-the-parent
  (let [order-id (str (create-order!))
        parent   (str "/web/admin/hm-orders/" order-id)
        create   (handler crud/create-entity-handler)]
    (testing "a valid create goes back to return_to"
      (let [resp (create (request :post "hm-items"
                                  :query {"return_to" parent}
                                  :form {"hm-order-id" order-id "sku" "A-1"}))]
        (is (= parent (get-in resp [:headers "HX-Redirect"])))
        (is (= 1 (count (ports/list-related-entities
                         (:svc @sys) :hm-orders order-id
                         (first (get-in config [:entities :hm-orders :has-many])))))))

      (testing "an off-site return_to is not followed"
        (let [resp (create (request :post "hm-items"
                                    :query {"return_to" "https://evil.example"}
                                    :form {"hm-order-id" order-id "sku" "A-2"}))]
          (is (nil? (get-in resp [:headers "HX-Redirect"]))))))))

(deftest ^:contract detected-has-many-reaches-the-parent-config
  ;; BOU-481: a parent discovered by introspection alone showed no children.
  (testing "without explicit config, the FK on hm_items registers a has-many on hm-orders"
    (let [sp (schema-repo/create-schema-repository
              (:db @sys) (update-in config [:entities :hm-orders] dissoc :has-many))]
      (is (= [{:entity :hm-items :table :hm_items :foreign-key :hm-order-id
               :label "Items" :fields [:sku] :editable false}]
             (:has-many (ports/get-entity-config sp :hm-orders))))))

  (testing "the explicit entry wins over the detected one"
    (is (= (get-in config [:entities :hm-orders :has-many])
           (:has-many (ports/get-entity-config (:sp @sys) :hm-orders))))))

(def ^:private joined-config
  (-> config
      (update-in [:entity-discovery :allowlist] conj :hm-members)
      (assoc-in [:entities :hm-members]
                {:label           "Members"
                 :query-overrides {:from          [[:hm_members :m]]
                                   :join          [[:hm_profiles :p] [:= :m.id :p.id]]
                                   :select        [:m.id :m.hm_order_id :p.nickname]
                                   ;; No alias for the FK, and both tables carry the
                                   ;; column: the shape of the shipped :users entity.
                                   :field-aliases {:id       :m.id
                                                   :nickname :p.nickname}}})
      (update-in [:entities :hm-orders :has-many] conj
                 {:entity :hm-members :table :hm_members :foreign-key :hm-order-id
                  :label "Members" :fields [:nickname]})))

(deftest ^:contract a-panel-reads-children-through-their-join
  ;; A child with :query-overrides was read from its primary table alone, so
  ;; the panel showed blanks for every joined column (PR #567 review).
  (let [db        (:db @sys)
        sp        (schema-repo/create-schema-repository db joined-config)
        svc       (service/create-admin-service db sp
                                                (logging-no-op/create-logging-component {})
                                                (error-reporting-no-op/create-error-reporting-component {})
                                                joined-config)
        order-id  (create-order!)
        member-id (random-uuid)]
    (db/execute-update! db {:raw (str "INSERT INTO hm_members (id, hm_order_id) VALUES ('" member-id "', '" order-id "')")})
    (db/execute-update! db {:raw (str "INSERT INTO hm_profiles (id, nickname) VALUES ('" member-id "', 'Ada-the-member')")})
    (let [body (:body ((detail/entity-detail-handler svc sp joined-config)
                       (request :get "hm-orders" :id order-id)))]
      (is (str/includes? body "Ada-the-member")))))

(deftest ^:contract a-failed-child-create-keeps-the-parent
  ;; Both error branches re-rendered the form without return_to, so the next
  ;; submit and Cancel lost the parent (PR #567 review).
  (let [order-id (str (create-order!))
        parent   (str "/web/admin/hm-orders/" order-id)
        create   (handler crud/create-entity-handler)
        check    (fn [resp]
                   (let [body (:body resp)]
                     (is (str/includes? body (str "hx-post=\"/web/admin/hm-items?return_to=%2Fweb%2Fadmin%2Fhm-orders%2F" order-id "\""))
                         "the form posts return_to again")
                     (is (str/includes? body (str "href=\"" parent "\"")) "Cancel goes to the parent")
                     (is (str/includes? body (str "value=\"" order-id "\"")) "the FK the user sent is kept")
                     (is (not (str/includes? body "hx-put")) "still a create form (BOU-533)")))]
    (testing "validation failure"
      (check (create (request :post "hm-items"
                              :query {"return_to" parent}
                              :form {"hm-order-id" order-id})))) ; sku missing

    (testing "persistence failure"
      (check (create (request :post "hm-items"
                              :query {"return_to" parent}
                              ;; Longer than VARCHAR(100): passes validation, fails the insert.
                              :form {"hm-order-id" order-id "sku" (apply str (repeat 150 "x"))}))))))

(deftest ^:contract a-panel-shows-one-page-and-links-to-the-rest
  ;; Detection turned a panel on for every FK child, and the panel had no
  ;; LIMIT: a tenant page rendered every user (PR #567 review).
  (let [small    (assoc-in config [:pagination :default-page-size] 2)
        svc      (service/create-admin-service (:db @sys) (:sp @sys)
                                               (logging-no-op/create-logging-component {})
                                               (error-reporting-no-op/create-error-reporting-component {})
                                               small)
        order-id (create-order!)
        other-id (create-order!)
        add-item (fn [order sku]
                   (db/execute-update! (:db @sys) {:raw (str "INSERT INTO hm_items (id, hm_order_id, sku) VALUES ('"
                                                             (random-uuid) "', '" order "', '" sku "')")}))
        detail   #(:body ((detail/entity-detail-handler svc (:sp @sys) small)
                          (request :get "hm-orders" :id %)))]
    (doseq [n (range 3)] (add-item order-id (str "LIM-" n)))
    (add-item other-id "OTHER-ORDER")
    (let [body     (detail order-id)
          view-all (some->> body (re-find #"href=\"(/web/admin/hm-items\?[^\"]+)\"") second
                            (#(str/replace % "&amp;" "&")))]
      (testing "the panel shows one page of children"
        (is (= 2 (count (re-seq #"LIM-\d" body)))))

      (testing "and links to the child list filtered by the FK"
        (is (some? view-all))
        (let [list-body (:body ((list/entity-list-handler svc (:sp @sys) small)
                                (request :get "hm-items"
                                         :query (codec/form-decode (second (str/split view-all #"\?" 2))))))]
          (is (str/includes? list-body "LIM-"))
          (is (not (str/includes? list-body "OTHER-ORDER")))))

      (testing "no link when every child fits"
        (is (not (re-find #"href=\"/web/admin/hm-items\?filters" (detail other-id))))))))

(deftest ^:contract ^:security detail-page-ignores-an-off-site-return-to
  ;; return_to became the breadcrumb, "Back to list", create and delete links
  ;; unchecked, so `javascript:` rendered as an href (BOU-553).
  (let [order-id (create-order!)
        body     (fn [return-to]
                   (:body ((handler detail/entity-detail-handler)
                           (request :get "hm-orders" :id order-id
                                    :query {"return_to" return-to}))))]
    (doseq [evil ["javascript:alert(document.domain)" "https://evil.com"]]
      (let [page (body evil)]
        (is (not (str/includes? page "javascript:alert")) evil)
        (is (not (str/includes? page "evil.com")) evil)
        (is (str/includes? page "href=\"/web/admin/hm-orders\"") evil)))
    (testing "a path inside the admin is kept"
      (is (str/includes? (body "/web/admin/hm-orders?page=2")
                         "href=\"/web/admin/hm-orders?page=2\"")))))
