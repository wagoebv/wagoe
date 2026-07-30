(ns wagoe.realtime.core.connection-test
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.realtime.core.connection :as conn]))

(def test-user-id #uuid "550e8400-e29b-41d4-a716-446655440000")
(def test-roles #{:user :admin})
(def test-metadata {:session-id "abc123"})
(def test-now (java.time.Instant/parse "2026-02-04T20:00:00Z"))

(defn create-test-connection
  ([user-id roles]
   (create-test-connection user-id roles {}))
  ([user-id roles metadata]
   (conn/create-connection user-id roles metadata (java.util.UUID/randomUUID) test-now)))

(deftest ^:unit create-connection-test
  (testing "creating connection with all fields"
    (let [connection (create-test-connection test-user-id test-roles test-metadata)]
      (is (uuid? (:id connection)))
      (is (= test-user-id (:user-id connection)))
      (is (= test-roles (:roles connection)))
      (is (= test-metadata (:metadata connection)))
      (is (inst? (:created-at connection)))))
  
  (testing "creating connection with default metadata"
    (let [connection (create-test-connection test-user-id test-roles)]
      (is (= {} (:metadata connection)))))
  
  (testing "creating connection generates unique IDs"
    (let [conn1 (create-test-connection test-user-id test-roles)
          conn2 (create-test-connection test-user-id test-roles)]
      (is (not= (:id conn1) (:id conn2))))))

(deftest ^:unit connection-age-test
  (testing "calculating connection age"
    (let [created (java.time.Instant/parse "2026-02-04T20:00:00Z")
          now (java.time.Instant/parse "2026-02-04T20:05:30Z")
          connection (conn/map->Connection {:id (java.util.UUID/randomUUID)
                                            :user-id test-user-id
                                            :roles test-roles
                                            :metadata {}
                                            :created-at created})
          age (conn/connection-age connection now)]
      (is (= 330.0 age))))) ;; 5 minutes 30 seconds

(deftest ^:unit authorize-connection?-test
  (testing "connection with required role"
    (let [connection (create-test-connection test-user-id #{:admin :user})]
      (is (conn/authorize-connection? connection :admin))
      (is (conn/authorize-connection? connection :user))))
  
  (testing "connection without required role"
    (let [connection (create-test-connection test-user-id #{:user})]
      (is (not (conn/authorize-connection? connection :admin))))))

(deftest ^:unit authorize-connection-any?-test
  (testing "connection with at least one required role"
    (let [connection (create-test-connection test-user-id #{:user})]
      (is (conn/authorize-connection-any? connection #{:admin :user}))
      (is (conn/authorize-connection-any? connection #{:user}))))
  
  (testing "connection with no required roles"
    (let [connection (create-test-connection test-user-id #{:guest})]
      (is (not (conn/authorize-connection-any? connection #{:admin :user}))))))

(deftest ^:unit authorize-connection-all?-test
  (testing "connection with all required roles"
    (let [connection (create-test-connection test-user-id #{:admin :user :moderator})]
      (is (conn/authorize-connection-all? connection #{:admin :user}))))
  
  (testing "connection missing some required roles"
    (let [connection (create-test-connection test-user-id #{:user})]
      (is (not (conn/authorize-connection-all? connection #{:admin :user}))))))

(deftest ^:unit filter-by-user-test
  (let [user1 #uuid "550e8400-e29b-41d4-a716-446655440001"
        user2 #uuid "550e8400-e29b-41d4-a716-446655440002"
        conn1 (create-test-connection user1 #{:user})
        conn2 (create-test-connection user2 #{:user})
        conn3 (create-test-connection user1 #{:admin})
        connections [conn1 conn2 conn3]]
    
    (testing "filter connections by user ID"
      (let [result (conn/filter-by-user connections user1)]
        (is (= 2 (count result)))
        (is (every? #(= user1 (:user-id %)) result))))
    
    (testing "filter with no matching connections"
      (let [user3 #uuid "550e8400-e29b-41d4-a716-446655440003"
            result (conn/filter-by-user connections user3)]
        (is (= 0 (count result)))))))

(deftest ^:unit filter-by-role-test
  (let [conn1 (create-test-connection test-user-id #{:user})
        conn2 (create-test-connection test-user-id #{:admin})
        conn3 (create-test-connection test-user-id #{:user :admin})
        connections [conn1 conn2 conn3]]
    
    (testing "filter connections by role"
      (let [admins (conn/filter-by-role connections :admin)]
        (is (= 2 (count admins)))))
    
    (testing "filter with no matching connections"
      (let [result (conn/filter-by-role connections :superuser)]
        (is (= 0 (count result)))))))

(deftest ^:unit filter-by-metadata-test
  (let [conn1 (create-test-connection test-user-id #{:user} {:region "us-east"})
        conn2 (create-test-connection test-user-id #{:user} {:region "eu-west"})
        conn3 (create-test-connection test-user-id #{:user} {:region "us-east"})
        connections [conn1 conn2 conn3]]
    
    (testing "filter connections by metadata predicate"
      (let [us-east (conn/filter-by-metadata connections
                                             (fn [m] (= "us-east" (:region m))))]
        (is (= 2 (count us-east)))))))

(deftest ^:unit update-metadata-test
  (testing "update connection metadata"
    (let [connection (create-test-connection test-user-id #{:user} {:count 0})
          updated (conn/update-metadata connection #(update % :count inc))]
      (is (= 1 (get-in updated [:metadata :count])))
      ;; Original unchanged (immutable)
      (is (= 0 (get-in connection [:metadata :count]))))))

(deftest ^:unit assoc-metadata-test
  (testing "associate key-value in metadata"
    (let [connection (create-test-connection test-user-id #{:user} {})
          updated (conn/assoc-metadata connection :foo "bar")]
      (is (= "bar" (get-in updated [:metadata :foo])))
      (is (nil? (get-in connection [:metadata :foo]))))))

(deftest ^:unit dissoc-metadata-test
  (testing "dissociate key from metadata"
    (let [connection (create-test-connection test-user-id #{:user} {:foo "bar" :baz "qux"})
          updated (conn/dissoc-metadata connection :foo)]
      (is (nil? (get-in updated [:metadata :foo])))
      (is (= "qux" (get-in updated [:metadata :baz])))
      (is (= "bar" (get-in connection [:metadata :foo]))))))

(deftest ^:unit valid-connection?-test
  (testing "valid connection passes validation"
    (let [connection (create-test-connection test-user-id #{:user})]
      (is (conn/valid-connection? connection))))
  
  (testing "invalid connection fails validation"
    (let [invalid {:id "not-a-uuid"
                   :user-id test-user-id
                   :roles #{:user}
                   :metadata {}
                   :created-at (java.time.Instant/now)}]
      (is (not (conn/valid-connection? invalid))))))
