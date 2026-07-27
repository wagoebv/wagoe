(ns wagoe.devtools.core.documentation-test
  (:require [clojure.string]
            [clojure.test :refer [deftest is testing]]
            [wagoe.devtools.core.documentation :as docs]))

(deftest ^:unit lookup-topic-test
  (testing "all known topics return non-nil entries with title and body"
    (doseq [topic [:scaffold :interceptors :fcis :testing :config :routes]]
      (let [entry (docs/lookup topic)]
        (is (some? entry) (str "Expected entry for " topic))
        (is (string? (:title entry)) (str "Expected string :title for " topic))
        (is (string? (:body entry)) (str "Expected string :body for " topic)))))

  (testing "unknown topic returns nil"
    (is (nil? (docs/lookup :nonexistent)))))

(deftest ^:unit format-doc-test
  (testing "formats entry with title and see-also"
    (let [entry  {:title    "Scaffolding"
                  :body     "Use bb scaffold..."
                  :see-also [:fcis :testing]}
          result (docs/format-doc entry)]
      (is (clojure.string/includes? result "Scaffolding"))
      (is (clojure.string/includes? result "See also")))))

(deftest ^:unit list-topics-test
  (testing "returns a non-empty sorted sequence of keywords"
    (let [topics (docs/list-topics)]
      (is (seq topics))
      (is (every? keyword? topics))
      (is (= topics (sort topics))))))
