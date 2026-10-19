(ns wagoe.shared.ui.core.modal-test
  (:require [wagoe.shared.ui.core.modal :as modal]
            [wagoe.shared.ui.core.components :as components]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]))

(defn- nodes
  "Every hiccup element in `tree` whose tag is `tag`."
  [tree tag]
  (filter #(and (vector? %) (= tag (first %))) (tree-seq coll? seq tree)))

(deftest ^:unit modal-test
  (testing "a dialog named by its title, closed by forms the browser handles"
    (let [m     (modal/modal {:id "edit" :title "Edit"} [:p "Body"])
          attrs (second m)]
      (is (= :dialog (first m)))
      (is (= {:id "edit" :class "modal" :aria-labelledby "edit-title"} attrs))
      (is (= "edit-title" (:id (second (first (nodes m :h3))))))
      (is (= ["dialog" "dialog"] (map (comp :method second) (nodes m :form)))
          "the close button and the backdrop")
      (is (= "modal-backdrop" (:class (second (last (nodes m :form))))))
      (is (str/includes? (components/render-html m) "<p>Body</p>"))))

  (testing ":open? marks it to open on arrival"
    (is (true? (:data-modal-show (second (modal/modal {:id "x" :open? true}))))))

  (testing "no close button when not closable, no label without a title"
    (let [m (modal/modal {:id "x" :closable? false})]
      (is (= 1 (count (nodes m :form))))
      (is (not (contains? (second m) :aria-labelledby)))))

  (testing ":class goes on the box, :attrs on the dialog"
    (let [m (modal/modal {:id "x" :class "max-w-3xl" :attrs {:data-test "y"}})]
      (is (= "y" (:data-test (second m))))
      (is (= "modal-box max-w-3xl" (:class (second (first (nodes m :div)))))))))

(deftest ^:unit confirm-modal-test
  (let [m       (modal/confirm-modal {:id "del" :title "Delete?" :message "Gone for good."
                                      :confirm-label "Delete" :danger? true})
        buttons (map second (nodes m :button))]
    (testing "an alertdialog described by its message"
      (is (= "alertdialog" (:role (second m))))
      (is (= "del-message" (:aria-describedby (second m))))
      (is (= "del-message" (:id (second (first (nodes m :p)))))))
    (testing "cancel takes focus; the answer is the dialog's return value"
      (is (= [["cancel" true] ["confirm" nil]]
             (map (juxt :value :autofocus) (remove #(= "-1" (:tabindex %)) buttons))))
      (is (= "btn btn-error" (:class (second (first (filter #(= "confirm" (:value (second %)))
                                                            (nodes m :button)))))))))
  (testing "labels default to catalogue markers"
    (let [m (str (modal/confirm-modal {:id "c" :message "Sure?"}))]
      (is (str/includes? m ":common/button-confirm"))
      (is (str/includes? m ":common/button-cancel")))))

(deftest ^:unit trigger-attrs-test
  (is (= {:type "button" :aria-haspopup "dialog" :aria-controls "edit" :data-modal-open "edit"}
         (modal/open-attrs "edit")))
  (is (= {:data-confirm-modal "del"} (modal/confirm-attrs "del"))))
