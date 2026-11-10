(ns wagoe.platform.shell.adapters.database.session-statements-test
  (:require [clojure.test :refer [deftest is]]
            [next.jdbc :as jdbc]
            [wagoe.platform.database :as db]
            [wagoe.platform.shell.adapters.database.common.adapter :as adapter]))

(deftest ^:unit a-lost-connection-ends-the-session-statements
  ;; BOU-970: no statement after it can run either
  (doseq [[what error lost?] [["connection closed" #(java.sql.SQLException. "I/O error" "08006") true]
                              ["one statement" #(java.sql.SQLException. "unknown variable" "HY000") false]]]
    (let [ran (atom [])]
      (with-redefs [jdbc/execute! (fn [_ [sql]]
                                    (swap! ran conj sql)
                                    (when (= "a" sql) (throw (error))))]
        (let [e (try (adapter/run-session-statements! "Test" ::ds [["a"] ["b"] ["c"]]) nil
                     (catch Exception e e))]
          (is (= lost? (db/connection-lost? e)) what)
          (is (= (if lost? ["a"] ["a" "b" "c"]) @ran) what))))))
