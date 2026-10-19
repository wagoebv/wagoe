(ns wagoe.storage.core.uploads-test
  (:require [clojure.test :refer [deftest is]]
            [wagoe.storage.core.uploads :as uploads])
  (:import [java.time Instant]))

(deftest ^:unit an-upload-record-names-its-owner-as-strings
  (let [now     (Instant/now)
        user-id (random-uuid)]
    (is (= {:key "ab/x.pdf" :user-id (str user-id) :tenant-id nil :size 3
            :content-type "application/pdf" :stored-at now}
           (uploads/upload-record {:key "ab/x.pdf" :size 3 :content-type "application/pdf" :url "u"}
                                  {:user-id user-id}
                                  now)))))

(deftest ^:unit users-and-tenants-own-uploads
  (is (= {:user-id "u1"} (uploads/subject-owner {:type :user :id "u1"})))
  (is (= {:tenant-id "t1"} (uploads/subject-owner {:type :tenant :id "t1"})))
  (is (nil? (uploads/subject-owner {:type :order :id "o1"}))))

(deftest ^:unit the-export-shows-no-owner-columns
  (is (= {:uploads [{:key "k" :size 1 :content-type "text/plain" :stored-at :t}]}
         (uploads/uploads-export [{:key "k" :size 1 :content-type "text/plain" :stored-at :t
                                   :user-id "u" :tenant-id "t"}]))))
