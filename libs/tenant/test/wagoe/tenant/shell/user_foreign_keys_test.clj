(ns wagoe.tenant.shell.user-foreign-keys-test
  (:require [clojure.test :refer [deftest is]]
            [wagoe.platform.database :as db]
            [wagoe.tenant.shell.user-foreign-keys :as sut]))

(deftest ^:unit a-lost-connection-ends-adding-keys-at-boot
  ;; BOU-970: each key after it would wait out the connection timeout
  (doseq [[what error lost?] [["pool timeout" #(java.sql.SQLTransientConnectionException. "Connection is not available") true]
                              ["one key" #(java.sql.SQLException. "lock timeout" "55P03") false]]]
    (let [added (atom [])
          refs  (mapv #(hash-map :table % :column "user_id") ["a" "b" "c"])
          dirty {:table "a" :column "user_id" :orphans ["x"]}]
      (with-redefs [sut/missing-keys (constantly refs)
                    sut/orphans      (fn [_ table _] (if (= "a" table) ["x"] []))
                    sut/add-key!     (fn [_ {:keys [table]}]
                                       (swap! added conj table)
                                       (when (= "b" table) (throw (error))))]
        (if lost?
          (let [e (try (sut/add-clean-foreign-keys! {}) nil (catch Exception e e))]
            (is (db/connection-lost? e) what)
            (is (= [dirty] (:left-off (ex-data e))) what)
            (is (= ["b"] @added) what))
          (do (is (= [dirty {:table "b" :column "user_id" :error "lock timeout"}]
                     (sut/add-clean-foreign-keys! {}))
                  what)
              (is (= ["b" "c"] @added) what)))))))
