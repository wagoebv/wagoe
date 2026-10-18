(ns wagoe.admin.shell.sibling-nav-test
  "Prev/next between the children of one parent, on a child page opened from
   the parent's panel. It read every sibling; now at most a bounded window."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [wagoe.admin.shell.http.handlers.detail :as detail]
            [wagoe.admin.shell.http.support :as support]
            [wagoe.admin.shell.schema-repository :as schema-repo]
            [wagoe.admin.shell.service :as service]
            [wagoe.observability.errors.shell.adapters.no-op :as error-reporting-no-op]
            [wagoe.observability.logging.shell.adapters.no-op :as logging-no-op]
            [wagoe.platform.database :as db]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]))

(def ^:private config
  {:enabled?         true
   :base-path        "/web/admin"
   :require-role     :admin
   :entity-discovery {:mode :allowlist :allowlist #{:sn-orders :sn-items}}
   :entities         {:sn-orders {:has-many [{:entity :sn-items :table :sn_items
                                              :foreign-key :sn-order-id
                                              :label "Items" :fields [:sku]}]}
                      :sn-items  {:parent-context {:label "Order" :fields [:name]}}}
   :pagination       {:default-page-size 20 :max-page-size 200}})

(def ^:private admin {:id #uuid "00000000-0000-0000-0000-000000000001" :role :admin :active true})

(def ^:private order-id "00000000-0000-0000-0000-0000000000a0")

(defn- item-id [n] (format "00000000-0000-0000-0000-%012d" n))

(def ^:private sys (atom nil))

(use-fixtures :once
  (fn [f]
    (let [db-ctx (db-factory/db-context {:adapter :h2 :database-path "mem:admin_sibling_nav;DB_CLOSE_DELAY=-1"})]
      (db/execute-update! db-ctx {:raw "CREATE TABLE sn_orders (id UUID PRIMARY KEY, name VARCHAR(100) NOT NULL)"})
      (db/execute-update! db-ctx {:raw "CREATE TABLE sn_items (id UUID PRIMARY KEY, sn_order_id UUID NOT NULL, sku VARCHAR(100) NOT NULL)"})
      (db/execute-update! db-ctx {:raw (str "INSERT INTO sn_orders VALUES ('" order-id "', 'Order')")})
      (doseq [n (range 1 4)]
        (db/execute-update! db-ctx {:raw (str "INSERT INTO sn_items VALUES ('" (item-id n) "', '" order-id "', 'SKU-" n "')")}))
      (let [sp  (schema-repo/create-schema-repository db-ctx config)
            svc (service/create-admin-service db-ctx sp
                                              (logging-no-op/create-logging-component {})
                                              (error-reporting-no-op/create-error-reporting-component {})
                                              config)]
        (reset! sys {:sp sp :svc svc})
        (try (f)
             (finally
               (db/execute-update! db-ctx {:raw "DROP ALL OBJECTS"})
               (db-factory/close-db-context! db-ctx)))))))

(defn- page [n]
  (str (:body ((detail/entity-detail-handler (:svc @sys) (:sp @sys) config)
               {:request-method :get
                :uri            (str "/web/admin/sn-items/" (item-id n))
                :headers        {}
                :user           admin
                :session        {:user admin}
                :path-params    {:entity "sn-items" :id (item-id n)}
                :query-params   {"return_to" (str "/web/admin/sn-orders/" order-id)}}))))

(defn- count-text [body]
  (some-> (re-find #"sibling-nav-count\">([^<]*)<" body) second str/trim))

(deftest ^:contract sibling-nav-walks-the-parents-children
  (is (= "2 / 3" (count-text (page 2))))
  (is (str/includes? (page 2) (str "/web/admin/sn-items/" (item-id 1))))
  (is (str/includes? (page 2) (str "/web/admin/sn-items/" (item-id 3)))))

(deftest ^:contract sibling-nav-reads-a-bounded-window
  (with-redefs [support/sibling-cap 2]
    (testing "within the window: the count says there are more"
      (is (= "1 / 2+" (count-text (page 1)))))
    (testing "the last in the window links on past it"
      (is (str/includes? (page 2) (str "/web/admin/sn-items/" (item-id 3)))))
    (testing "past it: no sibling navigation"
      (is (nil? (count-text (page 3)))))))
