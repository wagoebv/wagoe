(ns wagoe.user.core.rights-ui-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.user.core.rights-ui :as sut]))

(defn- text
  "The strings and translation keys in `hiccup`, in order."
  [hiccup]
  (->> (tree-seq coll? seq hiccup)
       (keep #(cond (string? %) %
                    (and (vector? %) (= :t (first %))) (second %)))
       vec))

(deftest ^:unit a-scope-reads-as-its-keys-and-values
  (let [info {:labels {:domain-id "Domain"} :sources {:user/department "department"}}]
    (is (= [:user.rights/scope-everywhere] (text (sut/scope-view {} info))))
    (is (= ["Domain" ": " "42"] (text (sut/scope-view {:domain-id 42} info))))
    (testing "a key on an admin entity asks the admin for the record's title"
      (let [[_ attrs v] (last (last (sut/scope-view {:domain-id 42} (assoc info :entities {:domain-id :domains}))))]
        (is (= "/web/admin/domains/lookup?id=42" (:hx-get attrs)))
        (is (:data-quiet-failure attrs) "a failed lookup leaves the id, without an error toast")
        (is (= "42" v) "the id until it answers")))
    (testing "an unlabelled key shows its name"
      (is (= ["region" ": " "north"] (text (sut/scope-view {:region "north"} info)))))
    (testing "a value from the member names its source, and what it resolved to"
      (is (= ["dept" ": " :user.rights/scope-from-source "department"]
             (text (sut/scope-view {:dept :user/department} info))))
      (is (= ["dept" ": " :user.rights/scope-from-source "department" " = " "3, 7"]
             (text (sut/scope-view {:dept :user/department} (assoc info :resolved {[:dept :user/department] #{7 3}})))))
      (is (some #{:user.rights/scope-resolved-none}
                (text (sut/scope-view {:dept :user/department} (assoc info :resolved {[:dept :user/department] #{}})))))
      (is (some #{:user.rights/scope-resolve-failed}
                (text (sut/scope-view {:dept :user/department} (assoc info :resolved {[:dept :user/department] :error}))))))))

(deftest ^:unit permissions-are-grouped-by-module
  (let [registry {:orders/read  {:label "Read orders" :module :wagoe/orders}
                  :orders/write {:label "Change orders" :module :wagoe/orders}
                  :ai/read      {:label "Read ai"}}]
    (is (= [["ai" [[:ai/read "Read ai" false]]]
            ["orders" [[:orders/read "Read orders" true] [:orders/write "Change orders" false]]]]
           (sut/permission-groups registry #{:orders/read})))
    (testing "a held permission the registry lacks comes last"
      (is (= [nil [[:legacy/x nil true]]] (last (sut/permission-groups registry #{:legacy/x})))))))

(deftest ^:unit paths-carry-the-name-as-a-query-parameter
  (is (= "/web/admin/roles/view?name=orders%2Fviewer" (sut/role-path :orders/viewer)))
  (is (= "/web/admin/permissions/view?name=admin.users%2Fread" (sut/permission-path :admin.users/read))))

(deftest ^:unit the-rights-tab-is-active-on-the-rights-page
  (let [page (sut/rights-page {:id 1 :name "Ada"} [] [] {})]
    (is (some #(and (map? %) (= "tab tab-active" (:class %)) (= "/web/users/1/rights" (:href %)))
              (tree-seq coll? seq page)))))
