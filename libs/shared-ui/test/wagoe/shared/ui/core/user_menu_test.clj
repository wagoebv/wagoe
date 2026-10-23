(ns wagoe.shared.ui.core.user-menu-test
  (:require [wagoe.platform.core.http.pages :as pages]
            [wagoe.shared.ui.core.user-menu :as user-menu]
            [clojure.test :refer [deftest testing is]]))

(defn- tagged [tree tag]
  (filter #(and (vector? %) (= tag (first %))) (tree-seq coll? seq tree)))

(deftest ^:unit initials-test
  (is (= "AJ" (user-menu/initials {:display-name "Ann de Jong"})))
  (is (= "AN" (user-menu/initials {:display-name "ann"})))
  (is (= "BO" (user-menu/initials {:email "bob@example.nl"})))
  (is (= "?" (user-menu/initials {}))))

(deftest ^:unit avatar-test
  (testing "the picture when the user has one"
    (is (= [:img {:class "user-menu-avatar" :src "https://cdn.example/a.png" :alt ""}]
           (user-menu/avatar {:avatar-url "https://cdn.example/a.png" :email "a@b.c"})))
    (is (= "/uploads/a.png" (:src (second (user-menu/avatar {:avatar-url "/uploads/a.png"}))))))
  (testing "else the initials, also for a URL that is no picture's"
    (is (= "AB" (user-menu/avatar {:email "ab@c.d"})))
    (is (= "AB" (user-menu/avatar {:email "ab@c.d" :avatar-url "javascript:alert(1)"})))
    (is (= "AB" (user-menu/avatar {:email "ab@c.d" :avatar-url "//evil.example/a.png"})))))

(deftest ^:unit user-menu-test
  (let [menu  (user-menu/user-menu {:display-name "Ann de Jong" :email "ann@example.nl"}
                                   {:items [{:href "/web/admin/" :label "Beheer" :icon :settings}]})
        hrefs (map (comp :href second) (tagged menu :a))]
    (testing "who it is, the extra items before the profile, and signing out"
      (is (= "Ann de Jong" (:title (second (first (tagged menu :summary))))))
      (is (some #{"ann@example.nl"} (tree-seq coll? seq menu)))
      (is (= ["/web/admin/" "/web/profile"] hrefs))
      (is (seq (tagged menu :form))))
    (testing "no profile or sign-out link to a page that is not there"
      (binding [pages/*mounted?* (constantly false)]
        (let [menu (user-menu/user-menu {:email "a@b.c"})]
          (is (empty? (tagged menu :a)))
          (is (empty? (tagged menu :form))))))))
