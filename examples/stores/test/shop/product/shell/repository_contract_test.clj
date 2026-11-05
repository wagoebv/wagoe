(ns shop.product.shell.repository-contract-test
  "What the product repository port promises, run on every adapter: the
   scaffolded SQL one on H2 and SQLite, and the one over an atom. A case
   written for one store runs on all of them, so they cannot drift apart."
  (:require [clojure.test :refer [deftest is testing]]
            [shop.product.ports :as ports]
            [shop.product.shell.memory :as memory]
            [shop.product.shell.persistence :as persistence]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.platform.shell.database.migrations :as migrations])
  (:import [java.io File]
           [java.time Instant]
           [java.util UUID]))

(defn- on-sql [db-config]
  (let [ctx (db-factory/db-context (assoc db-config :pool {:minimum-idle 1 :maximum-pool-size 2}))]
    (migrations/migrate-datasource! (:datasource ctx))
    [(persistence/create-repository ctx) #(db-factory/close-db-context! ctx)]))

(def ^:private adapters
  "[name open], `open` returning [repository close!]."
  [[:h2 #(on-sql {:adapter :h2 :database-path (str "mem:" (UUID/randomUUID))})]
   [:sqlite #(let [f (File/createTempFile "stores-contract" ".db")
                   [repo close!] (on-sql {:adapter :sqlite :database-path (str f)})]
               [repo (fn [] (close!) (.delete f))])]
   [:memory #(vector (memory/create-repository) (constantly nil))]])

(defn- on-each-adapter [f]
  (doseq [[adapter open] adapters]
    (testing (name adapter)
      (let [[repo close!] (open)]
        (try (f repo) (finally (close!)))))))

(def ^:private t0 (Instant/parse "2026-01-01T00:00:00Z"))

(defn- product
  "A product as the service hands it to `create`: id and created-at set."
  ([n] (product n (UUID/randomUUID)))
  ([n id]
   {:id id :name (str "Product " n) :sku (str "SKU-" n) :price 9.99M
    :created-at (.plusSeconds t0 n) :updated-at (.plusSeconds t0 n)}))

(defn- type-of [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

(deftest create-and-find-test
  (on-each-adapter
   (fn [repo]
     (let [p       (product 1)
           created (ports/create repo p)]
       (testing "create returns the product with the schema's types, as find-by-id reads it"
         (is (= p (select-keys created (keys p))))
         (is (= created (ports/find-by-id repo (:id p)))))
       (testing "an unknown id is nil"
         (is (nil? (ports/find-by-id repo (UUID/randomUUID)))))
       (testing "an id that is taken is a :conflict"
         (is (= :conflict (type-of #(ports/create repo (assoc (product 2) :id (:id p)))))))))))

(deftest find-all-test
  (on-each-adapter
   (fn [repo]
     (let [ps (mapv product (range 25))]
       (doseq [p (shuffle ps)] (ports/create repo p))
       (testing "oldest first, 20 by default"
         (is (= (map :id (take 20 ps)) (map :id (ports/find-all repo {})))))
       (testing "limit and offset page through without overlap"
         (is (= (map :id (subvec ps 10 15)) (map :id (ports/find-all repo {:limit 5 :offset 10}))))
         (is (= (map :id (subvec ps 20)) (map :id (ports/find-all repo {:limit 10 :offset 20})))))))))

(deftest find-all-ties-test
  (on-each-adapter
   (fn [repo]
     (testing "the same created-at orders by id, as the database orders a UUID"
       ;; f… is negative to UUID.compareTo, so a signed compare puts it first.
       (let [low  (assoc (product 1 #uuid "00000000-0000-4000-8000-000000000000") :created-at t0)
             high (assoc (product 2 #uuid "f0000000-0000-4000-8000-000000000000") :created-at t0)]
         (ports/create repo high)
         (ports/create repo low)
         (is (= [(:id low) (:id high)] (map :id (ports/find-all repo {})))))))))

(deftest update-entity-test
  (on-each-adapter
   (fn [repo]
     (let [p       (ports/create repo (product 1))
           updated (ports/update-entity repo {:id (:id p) :name "Renamed"})]
       (testing "changes the fields given, keeps the rest, stamps updated-at"
         (is (= "Renamed" (:name updated)))
         (is (= (:sku p) (:sku updated)))
         (is (= (:created-at p) (:created-at updated)))
         (is (pos? (compare (:updated-at updated) (:updated-at p))))
         (is (= updated (ports/find-by-id repo (:id p)))))
       (testing "an unknown id is nil, so the handler answers 404"
         (is (nil? (ports/update-entity repo {:id (UUID/randomUUID) :name "x"}))))
       (testing "nothing to change is a :validation-error"
         (is (= :validation-error (type-of #(ports/update-entity repo {:id (:id p)})))))))))

(deftest delete-test
  (on-each-adapter
   (fn [repo]
     (let [p (ports/create repo (product 1))]
       (ports/delete repo (:id p))
       (is (nil? (ports/find-by-id repo (:id p))))
       (testing "an unknown id is not an error"
         (ports/delete repo (UUID/randomUUID))
         (is (empty? (ports/find-all repo {}))))))))

(deftest transact-test
  (on-each-adapter
   (fn [repo]
     (testing "returns what f returns"
       (is (= :done (ports/transact repo (fn [] (ports/create repo (product 1)) :done)))))
     (testing "a throw undoes every write made in it"
       (is (thrown? clojure.lang.ExceptionInfo
                    (ports/transact repo (fn []
                                           (ports/create repo (product 2))
                                           (throw (ex-info "boom" {:type :internal-error}))))))
       (is (= ["Product 1"] (map :name (ports/find-all repo {}))))))))
