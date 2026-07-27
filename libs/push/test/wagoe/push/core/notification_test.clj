(ns wagoe.push.core.notification-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [wagoe.push.core.notification :as notif]
            [wagoe.push.shell.registry :as registry]))

(use-fixtures :each (fn [f] (registry/clear-registry!) (f)))

(deftest ^:unit render-template-test
  (is (= "Order ORD-123 shipped"
         (notif/render-template "Order {{order-id}} shipped"
                                {:order-id "ORD-123"})))
  (testing "missing placeholder left as-is"
    (is (= "Hello {{name}}"
           (notif/render-template "Hello {{name}}" {})))))

(deftest ^:unit resolve-content-test
  (testing "returns requested locale"
    (is (= "Verzonden"
           (notif/resolve-content {:en "Shipped" :nl "Verzonden"} :nl))))
  (testing "falls back to :en"
    (is (= "Shipped"
           (notif/resolve-content {:en "Shipped" :nl "Verzonden"} :de))))
  (testing "plain string passes through"
    (is (= "Shipped"
           (notif/resolve-content "Shipped" :nl))))
  (testing "falls back to first available when no :en"
    (is (some? (notif/resolve-content {:nl "Verzonden" :de "Versendet"} :fr)))))

(deftest ^:unit defpush-and-registry-test
  (registry/register-push!
   {:id :test-notification
    :title {:en "Test"}
    :body {:en "Body"}
    :channels #{:fcm}})
  (testing "registered push is retrievable"
    (is (= :test-notification (:id (registry/get-push :test-notification)))))
  (testing "list-pushes returns registered ids"
    (is (= [:test-notification] (registry/list-pushes))))
  (testing "clear-registry! removes all"
    (registry/clear-registry!)
    (is (nil? (registry/get-push :test-notification)))))

(deftest ^:unit build-notification-test
  (registry/register-push!
   {:id :order-shipped
    :title {:en "Order {{order-id}} Shipped" :nl "Bestelling {{order-id}} Verzonden"}
    :body {:en "On its way!" :nl "Onderweg!"}
    :channels #{:fcm :apns}
    :priority :high
    :ttl 3600
    :deep-link "/orders/{{order-id}}"
    :silent? false
    :collapse-key :order-status})
  (let [result (notif/build-notification
                (registry/get-push :order-shipped)
                {:order-id "ORD-42"}
                :nl)]
    (is (= "Bestelling ORD-42 Verzonden" (:title result)))
    (is (= "Onderweg!" (:body result)))
    (is (= "/orders/ORD-42" (:deep-link result)))
    (is (= :high (:priority result)))
    (is (= 3600 (:ttl result)))
    (is (= false (:silent? result)))
    (is (= :order-status (:collapse-key result)))))
