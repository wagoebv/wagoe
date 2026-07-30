(ns wagoe.realtime.core.message-test
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.realtime.core.message :as msg]
            [wagoe.realtime.core.connection :as conn]))

(def test-user-id #uuid "550e8400-e29b-41d4-a716-446655440000")
(def test-payload {:text "Hello, world!"})
(def test-now (java.time.Instant/parse "2026-02-04T20:00:00Z"))

(defn create-test-connection
  [user-id roles]
  (conn/create-connection user-id roles {} (java.util.UUID/randomUUID) test-now))

(defn create-test-message
  [input]
  (msg/create-message input test-now))

(defn create-broadcast-message
  [payload]
  (msg/broadcast-message payload test-now))

(defn create-user-message
  [user-id payload]
  (msg/user-message user-id payload test-now))

(defn create-role-message
  [role payload]
  (msg/role-message role payload test-now))

(defn create-connection-message
  [connection-id payload]
  (msg/connection-message connection-id payload test-now))

(deftest ^:unit create-message-test
  (testing "creating message with all fields"
    (let [message (create-test-message {:type :user
                                       :payload test-payload
                                       :target test-user-id})]
      (is (= :user (:type message)))
      (is (= test-payload (:payload message)))
      (is (= test-user-id (:target message)))
      (is (inst? (:timestamp message)))))
  
  (testing "creating message without target"
    (let [message (create-test-message {:type :broadcast
                                       :payload test-payload})]
      (is (= :broadcast (:type message)))
      (is (nil? (:target message))))))

(deftest ^:unit broadcast-message-test
  (testing "creating broadcast message"
    (let [message (create-broadcast-message test-payload)]
      (is (= :broadcast (:type message)))
      (is (= test-payload (:payload message)))
      (is (nil? (:target message))))))

(deftest ^:unit user-message-test
  (testing "creating user-targeted message"
    (let [message (create-user-message test-user-id test-payload)]
      (is (= :user (:type message)))
      (is (= test-user-id (:target message)))
      (is (= test-payload (:payload message))))))

(deftest ^:unit role-message-test
  (testing "creating role-targeted message"
    (let [message (create-role-message :admin test-payload)]
      (is (= :role (:type message)))
      (is (= :admin (:target message)))
      (is (= test-payload (:payload message))))))

(deftest ^:unit connection-message-test
  (testing "creating connection-specific message"
    (let [conn-id (java.util.UUID/randomUUID)
          message (create-connection-message conn-id test-payload)]
      (is (= :connection (:type message)))
      (is (= conn-id (:target message)))
      (is (= test-payload (:payload message))))))

(deftest ^:unit valid-message?-test
  (testing "valid message passes validation"
    (let [message (create-broadcast-message {:foo "bar"})]
      (is (msg/valid-message? message))))
  
  (testing "invalid message fails validation"
    (let [invalid {:type :invalid-type
                   :payload {}}]
      (is (not (msg/valid-message? invalid))))))

(deftest ^:unit route-message-test
  (let [user1 #uuid "550e8400-e29b-41d4-a716-446655440001"
        user2 #uuid "550e8400-e29b-41d4-a716-446655440002"
        conn1 (create-test-connection user1 #{:user})
        conn2 (create-test-connection user2 #{:user})
        conn3 (create-test-connection user1 #{:admin})
        connections [conn1 conn2 conn3]]
    
    (testing "broadcast message routes to all connections"
      (let [message (create-broadcast-message {:text "Hello all"})
            targets (msg/route-message message connections)]
        (is (= 3 (count targets)))
        (is (= (set (map :id connections)) (set targets)))))
    
    (testing "user message routes to user's connections only"
      (let [message (create-user-message user1 {:text "Hello user1"})
            targets (msg/route-message message connections)]
        (is (= 2 (count targets)))
        (is (= #{(:id conn1) (:id conn3)} (set targets)))))
    
    (testing "role message routes to connections with role"
      (let [message (create-role-message :admin {:text "Hello admins"})
            targets (msg/route-message message connections)]
        (is (= 1 (count targets)))
        (is (= [(:id conn3)] targets))))
    
    (testing "connection message routes to specific connection"
      (let [message (create-connection-message (:id conn2) {:text "Hello conn2"})
            targets (msg/route-message message connections)]
        (is (= 1 (count targets)))
        (is (= [(:id conn2)] targets))))
    
    (testing "connection message with non-existent ID routes nowhere"
      (let [fake-id (java.util.UUID/randomUUID)
            message (create-connection-message fake-id {:text "Hello?"})
            targets (msg/route-message message connections)]
        (is (= 0 (count targets)))))))

(deftest ^:unit route-to-connections-test
  (let [user1 #uuid "550e8400-e29b-41d4-a716-446655440001"
        user2 #uuid "550e8400-e29b-41d4-a716-446655440002"
        conn1 (create-test-connection user1 #{:user})
        conn2 (create-test-connection user2 #{:user})
        connections [conn1 conn2]]
    
    (testing "returns full connection records, not just IDs"
      (let [message (create-user-message user1 {:text "Test"})
            targets (msg/route-to-connections message connections)]
        (is (= 1 (count targets)))
        (is (= conn1 (first targets)))))))

(deftest ^:unit filter-by-type-test
  (let [msg1 (create-broadcast-message {:n 1})
        msg2 (create-user-message test-user-id {:n 2})
        msg3 (create-broadcast-message {:n 3})
        messages [msg1 msg2 msg3]]
    
    (testing "filter messages by type"
      (let [broadcasts (msg/filter-by-type messages :broadcast)]
        (is (= 2 (count broadcasts)))
        (is (every? #(= :broadcast (:type %)) broadcasts))))))

(deftest ^:unit filter-by-target-test
  (let [user1 #uuid "550e8400-e29b-41d4-a716-446655440001"
        user2 #uuid "550e8400-e29b-41d4-a716-446655440002"
        msg1 (create-user-message user1 {:n 1})
        msg2 (create-user-message user2 {:n 2})
        msg3 (create-user-message user1 {:n 3})
        messages [msg1 msg2 msg3]]
    
    (testing "filter messages by target"
      (let [user1-msgs (msg/filter-by-target messages user1)]
        (is (= 2 (count user1-msgs)))
        (is (every? #(= user1 (:target %)) user1-msgs))))))

(deftest ^:unit filter-recent-test
  (let [t1 (java.time.Instant/parse "2026-02-04T20:00:00Z")
        t2 (java.time.Instant/parse "2026-02-04T20:05:00Z")
        t3 (java.time.Instant/parse "2026-02-04T20:10:00Z")
        msg1 (assoc (create-broadcast-message {:n 1}) :timestamp t1)
        msg2 (assoc (create-broadcast-message {:n 2}) :timestamp t2)
        msg3 (assoc (create-broadcast-message {:n 3}) :timestamp t3)
        messages [msg1 msg2 msg3]
        now (java.time.Instant/parse "2026-02-04T20:12:00Z")
        duration (java.time.Duration/ofMinutes 10)]
    
    (testing "filter messages within time window"
      (let [recent (msg/filter-recent messages duration now)]
        (is (= 2 (count recent)))
        (is (= [msg2 msg3] recent))))))

(deftest ^:unit message-count-by-type-test
  (let [msg1 (create-broadcast-message {:n 1})
        msg2 (create-user-message test-user-id {:n 2})
        msg3 (create-broadcast-message {:n 3})
        msg4 (create-role-message :admin {:n 4})
        messages [msg1 msg2 msg3 msg4]]
    
    (testing "count messages by type"
      (let [counts (msg/message-count-by-type messages)]
        (is (= 2 (:broadcast counts)))
        (is (= 1 (:user counts)))
        (is (= 1 (:role counts)))))))

(deftest ^:unit messages-for-connection-test
  (let [user1 #uuid "550e8400-e29b-41d4-a716-446655440001"
        user2 #uuid "550e8400-e29b-41d4-a716-446655440002"
        conn1 (create-test-connection user1 #{:user})
        conn2 (create-test-connection user2 #{:admin})
        connections [conn1 conn2]
        msg1 (create-broadcast-message {:n 1})
        msg2 (create-user-message user1 {:n 2})
        msg3 (create-role-message :admin {:n 3})
        messages [msg1 msg2 msg3]]
    
    (testing "messages for user connection"
      (let [conn1-msgs (msg/messages-for-connection messages conn1 connections)]
        (is (= 2 (count conn1-msgs))) ;; broadcast + user message
        (is (contains? (set conn1-msgs) msg1))
        (is (contains? (set conn1-msgs) msg2))))
    
    (testing "messages for admin connection"
      (let [conn2-msgs (msg/messages-for-connection messages conn2 connections)]
        (is (= 2 (count conn2-msgs))) ;; broadcast + role message
        (is (contains? (set conn2-msgs) msg1))
        (is (contains? (set conn2-msgs) msg3))))))
