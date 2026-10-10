(ns wagoe.user.shell.hard-delete-tenant-reference-test
  "A user still referenced by a tenant membership cannot be hard-deleted, on a
   real database: the platform's foreign-key :conflict (BOU-590) becomes
   :hard-deletion-not-allowed."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.persistence :as persistence]
            [wagoe.user.shell.service :as sut])
  (:import [java.io File]))

(defn- user-entity []
  {:email (str "member-" (random-uuid) "@example.com")
   :name "Member" :password-hash "hash" :role :user :active true})

(deftest ^:integration a-member-cannot-be-hard-deleted
  (doseq [[engine make] [[:h2 #(db-factory/db-context {:adapter :h2 :database-path (str "mem:harddel_" (System/nanoTime) ";DB_CLOSE_DELAY=-1")})]
                         [:sqlite #(db-factory/db-context {:adapter :sqlite :database-path (.getPath (doto (File/createTempFile "harddel" ".db") .deleteOnExit))})]]]
    (testing (name engine)
      (let [ctx (make)]
        (try
          (persistence/initialize-user-schema! ctx)
          (jdbc/execute! (:datasource ctx)
                         ["CREATE TABLE tenant_memberships (id VARCHAR(36) PRIMARY KEY,
                             user_id VARCHAR(36) NOT NULL REFERENCES users(id))"])
          (let [repo    (persistence/create-user-repository ctx)
                user    (ports/create-user repo (user-entity))
                service (sut/->UserService repo nil nil {} nil nil)]
            (jdbc/execute! (:datasource ctx) ["INSERT INTO tenant_memberships VALUES ('m1', ?)" (str (:id user))])
            (let [e (try (ports/permanently-delete-user service (:id user)) nil
                         (catch clojure.lang.ExceptionInfo e e))]
              (is (= :hard-deletion-not-allowed (:type (ex-data e)))
                  (str "got " (pr-str (select-keys (ex-data e) [:type :constraint]))))))
          (finally (db-factory/close-db-context! ctx)))))))
