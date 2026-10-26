(ns wagoe.push.core.callback-test
  (:require [clojure.test :refer [deftest is]]
            [wagoe.push.core.callback :as callback]))

(def ^:private fields
  {:callback-id "cb" :notification-id :a/b :device-token "t" :platform :fcm :expires-at 10})

(deftest ^:unit every-field-is-signed
  (doseq [[k v] {:callback-id "cc" :notification-id :a/c :device-token "u" :platform :apns :expires-at 11}]
    (is (not= (callback/signed-message fields) (callback/signed-message (assoc fields k v))) (str k))))

(deftest ^:unit no-field-borrows-from-its-neighbour
  (is (not= (callback/signed-message (assoc fields :callback-id "c" :device-token "t"))
            (callback/signed-message (assoc fields :callback-id "c:" :device-token "t")))))

(deftest ^:unit a-token-reads-back
  (let [mac (apply str (repeat 64 "a"))]
    (is (= {:expires-at 10 :mac mac} (callback/parse-token (callback/token 10 mac))))
    (is (nil? (callback/parse-token mac)) "a legacy token is not a bound one")
    (is (nil? (callback/parse-token nil)))))

(deftest ^:unit shadowed-keys-finds-keywords-and-strings
  (is (= [:notification-id :callback-token]
         (callback/shadowed-keys {:notification-id 1 "callback-token" 2 :other 3})))
  (is (empty? (callback/shadowed-keys nil))))
