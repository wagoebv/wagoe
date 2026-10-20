(ns wagoe.user.core.profile-ui-test
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.user.core.profile-ui :as profile-ui]))

(defn- nodes [tree]
  (filter vector? (tree-seq coll? seq tree)))

(defn- tagged [tree tag]
  (filter #(= tag (first %)) (nodes tree)))

(def ^:private user {:id 7 :name "Ada" :email "ada@example.com" :role :user})

(deftest ^:unit profile-pages-render-in-the-admin-shell
  (doseq [[label page title]
          [["profile"      (profile-ui/profile-page user {:enabled false} {:user user})
            [:t :user/profile-page-title]]
           ["mfa setup"    (profile-ui/mfa-setup-page {:user user})
            [:t :user/mfa-setup-title]]
           ["backup codes" (profile-ui/mfa-backup-codes-display ["a1" "b2"] true {:user user})
            [:t :user/mfa-backup-codes-page-title]]
           ["mfa disable"  (profile-ui/mfa-disable-confirm-page {} {:user user})
            [:t :user/mfa-disable-title]]]]
    (testing label
      (is (seq (tagged page :div.admin-shell)))
      (is (seq (tagged page :aside.admin-sidebar)))
      (testing "the title is the topbar's, the page's only h1"
        (is (= [[:h1 {:class "admin-topbar-title"} title]] (tagged page :h1))))
      (is (= "ada@example.com" (:title (second (first (tagged page :summary)))))
          "the signed-in user is in the user menu"))))

(deftest ^:unit backup-codes-without-full-page-are-a-fragment
  (is (empty? (tagged (profile-ui/mfa-backup-codes-display ["a1"]) :div.admin-shell))))
