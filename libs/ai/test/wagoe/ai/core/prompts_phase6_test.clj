(ns wagoe.ai.core.prompts-phase6-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string]
            [wagoe.ai.core.prompts :as prompts]))

(deftest ^:unit review-messages-structure
  (testing "builds review messages with system and user roles"
    (let [msgs (prompts/review-messages "wagoe.user.core.validation"
                                        "(ns wagoe.user.core.validation)\n(defn validate [x] x)")]
      (is (= 2 (count msgs)))
      (is (= :system (:role (first msgs))))
      (is (= :user (:role (second msgs))))
      (is (clojure.string/includes? (:content (second msgs)) "wagoe.user.core.validation")))))

(deftest ^:unit test-ideas-messages-structure
  (testing "builds test-ideas messages"
    (let [msgs (prompts/test-ideas-messages "wagoe.user.core.validation"
                                            "(ns wagoe.user.core.validation)\n(defn validate [x] x)"
                                            nil)]
      (is (= 2 (count msgs)))
      (is (clojure.string/includes? (:content (second msgs)) "test")))))

(deftest ^:unit refactor-fcis-messages-structure
  (testing "builds refactor-fcis messages with violation info"
    (let [msgs (prompts/refactor-fcis-messages
                "wagoe.product.core.validation"
                "(ns wagoe.product.core.validation\n  (:require [wagoe.product.shell.persistence :as p]))"
                [{:from "wagoe.product.core.validation"
                  :to "wagoe.product.shell.persistence"}])]
      (is (= 2 (count msgs)))
      (is (clojure.string/includes? (:content (second msgs)) "FC/IS")))))
