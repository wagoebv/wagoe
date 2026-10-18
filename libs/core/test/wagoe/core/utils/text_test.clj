(ns wagoe.core.utils.text-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.core.utils.case-conversion :as cc]
            [wagoe.core.utils.text :as text])
  (:import (java.util Locale)))

(defn- in-turkish [f]
  (let [default (Locale/getDefault)]
    (Locale/setDefault (Locale/forLanguageTag "tr-TR"))
    (try (f) (finally (Locale/setDefault default)))))

(deftest ^:unit locale-independent-case-test
  (in-turkish
   (fn []
     (testing "the JVM default really is Turkish here"
       (is (= "ıd" (.toLowerCase "ID"))))
     (testing "text/ ignores it"
       (is (= "id" (text/lower-case "ID")))
       (is (= "ID" (text/upper-case "id")))
       (is (= "Id" (text/capitalize "iD"))))
     (testing "case conversion keeps API and DB keys intact"
       (is (= {:user-id 1} (cc/camel-case->kebab-case-map {:userId 1})))
       (is (= {:userId 1} (cc/kebab-case->camel-case-map {:user-id 1})))
       (is (= "user-id" (cc/camel-case->kebab-case-string "userId")))))))

(deftest ^:unit accepts-char-sequences-test
  (is (= "ab" (text/lower-case (StringBuilder. "AB")))))
