(ns wagoe.user.core.authorization-upgrade-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.user.core.authorization-upgrade :as sut]))

(def ^:private report
  [{:path "/orders" :method :get :rule :module :permissions [:orders/read]}
   {:path "/orders" :method :post :rule :module :permissions [:orders/write]}
   {:path "/orders/:id" :method :get :rule :module :permissions [:orders/read] :scope {:domain-id :path/id}}
   {:path "/refund" :method :post :rule :permission :permissions [:orders/refund]}
   {:path "/custom" :method :get :rule :none :permissions []}
   {:path "/me" :method :get :rule :signed-in :permissions []}])

(defn- user [email & {:as m}]
  (merge {:id (random-uuid) :email email :role :user :rights []} m))

(defn- outcomes [findings]
  (set (map (juxt (comp :email :user) (comp :path :endpoint) :permission :access) findings)))

(deftest ^:unit route-findings-name-what-enforce-refuses
  (let [editor (user "ed@x" :rights [{:permission :orders/read :scope {}} {:permission :orders/write :scope {}}])
        scoped (user "sc@x" :rights [{:permission :orders/read :scope {:domain-id #{1}}}])
        root   (user "root@x" :role :admin :platform-admin? true)]
    (is (= #{["ed@x" "/refund" :orders/refund :refused]
             ["ed@x" "/custom" nil :refused]
             ["sc@x" "/orders" :orders/read :refused]
             ["sc@x" "/orders" :orders/write :refused]
             ["sc@x" "/orders/:id" :orders/read :narrowed]
             ["sc@x" "/refund" :orders/refund :refused]
             ["sc@x" "/custom" nil :refused]}
           (outcomes (sut/route-findings report [editor scoped root]))))
    (is (sut/blocking? (sut/route-findings report [editor])))
    (is (not (sut/blocking? (sut/route-findings report [root]))) "a platform admin passes everything")))

(deftest ^:unit admin-findings-compare-with-role-admin
  (let [perms  [:admin.users/read]
        tenant (user "t@x" :role :admin :tenant-id (random-uuid))
        holder (user "h@x" :rights [{:permission :admin.users/read :scope {}}])
        plain  (user "p@x")
        root   (user "root@x" :role :admin :platform-admin? true)]
    (is (= #{["t@x" nil :admin.users/read :refused]
             ["h@x" nil :admin.users/read :gained]}
           (outcomes (sut/admin-findings perms [tenant holder plain root]))))
    (is (not (sut/blocking? (sut/admin-findings perms [holder]))) "a gain does not block")))

(deftest ^:unit text-groups-by-permission
  (let [users (for [i (range 7)] (user (str "u" i "@x")))
        text  (str/join "\n" (sut/format-findings (sut/route-findings (take 4 report) users)))]
    (is (str/includes? text "orders/refund — refused to 7 user(s): u0@x, u1@x, u2@x, u3@x, u4@x and 2 more"))
    (is (str/includes? text "      POST /refund")))
  (is (= ["  None: every user keeps the access they had under :off."] (sut/format-findings []))))

(deftest ^:unit rights-are-grouped-by-what-users-hold
  (let [text (str/join "\n" (sut/format-rights [(user "a@x" :rights [{:permission :orders/read :scope {}}])
                                                (user "b@x" :rights [{:permission :orders/read :scope {}}])
                                                (user "c@x")
                                                (user "r@x" :platform-admin? true)]))]
    (testing "the platform admins first"
      (is (str/starts-with? text "  1 user(s): r@x\n      platform admin")))
    (is (str/includes? text "  2 user(s): a@x, b@x\n      orders/read"))
    (is (str/includes? text "  1 user(s): c@x\n      nothing beyond signed-in routes"))))

(deftest ^:unit the-plan-says-what-the-first-start-moves
  (let [g (random-uuid)
        text (str/join "\n" (sut/format-plan {:everyone-roles [:orders/editor]
                                              :viewers [(random-uuid)]
                                              :readers-roles [:orders/viewer]
                                              :grants [{:group-id g :permission :invoice/approve :scope {:domain-id 1}}]}
                                             {g "Finance"}))]
    (is (str/includes? text "Everyone is created and given orders/editor"))
    (is (str/includes? text "1 user(s) with role :viewer join Readers, with orders/viewer"))
    (is (str/includes? text "Finance: invoice/approve {:domain-id 1}")))
  (is (= ["  Everyone gets no roles: it exists already, or every user is an admin." "  Nothing left to move."]
         (sut/format-plan {:viewers [] :grants []} {}))))

(deftest ^:unit legacy-roles-are-counted
  (is (= ["  admin (in a tenant): 1" "  admin (platform): 1" "  user: 2" "  viewer: 1"]
         (let [vic (user "e" :role nil)]
           (sut/legacy-roles [(user "a" :role :admin :platform-admin? true)
                              (user "b" :role :admin :tenant-id (random-uuid))
                              (user "c") (user "d" :role nil) vic]
                             [(:id vic)])))))
