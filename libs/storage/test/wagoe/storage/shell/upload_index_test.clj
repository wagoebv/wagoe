(ns wagoe.storage.shell.upload-index-test
  "The upload index on the engine WAGOE_TEST_DB names: h2 (default), pg or mysql."
  (:require [clojure.test :refer [deftest is testing]]
            [support.test-db :as test-db]
            [wagoe.platform.database :as db]
            [wagoe.storage.ports :as ports]
            [wagoe.storage.shell.upload-index :as upload-index])
  (:import [java.time Instant]
           [java.time.temporal ChronoUnit]))

(defn- with-index [f]
  (test-db/with-db "uploads"
    (fn [ctx]
      (upload-index/create-tables! ctx)
      (upload-index/create-tables! ctx)          ; the boot runs it every time
      (f (upload-index/create-upload-index ctx)))))

(def ^:private now (.truncatedTo (Instant/now) ChronoUnit/SECONDS))

(defn- upload [key & {:keys [user tenant at] :or {at now}}]
  {:key key :user-id user :tenant-id tenant :size 42 :content-type "image/png" :stored-at at})

(deftest ^:integration an-upload-round-trips-and-a-re-store-replaces-it
  (with-index
    (fn [index]
      (let [u (upload "ab/one.png" :user "u1" :tenant "t1")]
        (ports/record-upload! index u)
        (is (= [u] (ports/find-uploads index {:user-id "u1"} {})))
        (ports/record-upload! index (assoc u :size 7 :user-id "u2"))
        (is (empty? (ports/find-uploads index {:user-id "u1"} {})))
        (is (= [7] (map :size (ports/find-uploads index {:user-id "u2"} {}))))
        (is (true? (ports/remove-upload! index "ab/one.png")))
        (is (false? (ports/remove-upload! index "ab/one.png")))))))

(deftest ^:integration uploads-are-found-by-owner-a-page-at-a-time
  (with-index
    (fn [index]
      (doseq [u [(upload "a" :user "u1") (upload "b" :user "u1" :tenant "t1")
                 (upload "c" :user "u2" :tenant "t1") (upload "d")]]
        (ports/record-upload! index u))
      (is (= ["a" "b"] (map :key (ports/find-uploads index {:user-id "u1"} {}))))
      (is (= ["b" "c"] (map :key (ports/find-uploads index {:tenant-id "t1"} {}))))
      (testing "paging"
        (is (= ["a"] (map :key (ports/find-uploads index {:user-id "u1"} {:limit 1}))))
        (is (= ["b"] (map :key (ports/find-uploads index {:user-id "u1"} {:after-key "a"})))))
      (is (empty? (ports/find-uploads index {} {})) "no owner, no uploads"))))

(deftest ^:integration uploads-stored-before-a-cutoff
  (with-index
    (fn [index]
      (doseq [u [(upload "old-2" :at (.minus now 2 ChronoUnit/DAYS))
                 (upload "old-1" :at (.minus now 1 ChronoUnit/DAYS))
                 (upload "new")]]
        (ports/record-upload! index u))
      (is (= ["old-2" "old-1"]
             (map :key (ports/uploads-stored-before index (.minus now 1 ChronoUnit/HOURS) 10))))
      (is (= ["old-2"]
             (map :key (ports/uploads-stored-before index (.minus now 1 ChronoUnit/HOURS) 1)))))))

(deftest ^:integration keys-differing-only-in-case-are-two-uploads
  (with-index
    (fn [index]
      (ports/record-upload! index (upload "A.png" :user "u1"))
      (ports/record-upload! index (upload "a.png" :user "u2"))
      (is (= ["A.png"] (map :key (ports/find-uploads index {:user-id "u1"} {}))))
      (is (= ["a.png"] (map :key (ports/find-uploads index {:user-id "u2"} {})))))))

(deftest ^:integration two-uploads-racing-to-insert-one-key-both-succeed
  (with-index
    (fn [index]
      (let [update! db/execute-update!
            rival   (promise)]
        ;; A rival records the same key between our update finding no row
        ;; and our insert. On MySQL our update's gap lock makes it wait instead.
        (with-redefs [db/execute-update! (fn [ctx q]
                                           (when (and (:insert-into q) (not (realized? rival)))
                                             (deliver rival (future (ports/record-upload! index (upload "same.png" :user "rival"))))
                                             (Thread/sleep 300))
                                           (update! ctx q))]
          (ports/record-upload! index (upload "same.png" :user "me"))
          (is (some? (deref @rival 10000 nil)) "the rival's upload failed"))
        (is (= 1 (+ (count (ports/find-uploads index {:user-id "me"} {}))
                    (count (ports/find-uploads index {:user-id "rival"} {})))))))))
