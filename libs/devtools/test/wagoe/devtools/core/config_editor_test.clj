(ns wagoe.devtools.core.config-editor-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string]
            [wagoe.devtools.core.config-editor :as cfg-edit]
            [integrant.core :as ig]))

(deftest ^:unit config-diff-detects-changes
  (testing "detects added, removed, and changed keys"
    (let [old {:boundary/http {:port 3000} :boundary/db {:host "localhost"}}
          new {:boundary/http {:port 3001} :boundary/cache {:ttl 300}}
          diff (cfg-edit/config-diff old new)]
      (is (= {:port 3001} (get-in diff [:changed :boundary/http :new])))
      (is (contains? (:removed diff) :boundary/db))
      (is (contains? (:added diff) :boundary/cache)))))

(deftest ^:unit config-diff-empty-when-identical
  (testing "identical configs produce empty diff"
    (let [cfg {:boundary/http {:port 3000}}
          diff (cfg-edit/config-diff cfg cfg)]
      (is (empty? (:changed diff)))
      (is (empty? (:added diff)))
      (is (empty? (:removed diff))))))

(deftest ^:unit affected-components-from-diff
  (testing "returns component keys that would need restart"
    (let [diff {:changed {:boundary/http {:old {:port 3000} :new {:port 3001}}}
                :added {:boundary/cache {:ttl 300}}
                :removed {:boundary/db {:host "localhost"}}}]
      (is (= #{:boundary/http :boundary/cache :boundary/db}
             (cfg-edit/affected-components diff))))))

(deftest ^:unit redact-secrets-masks-sensitive-values
  (testing "masks values for keys containing password, secret, api-key, token"
    (let [cfg {:boundary/db {:host "localhost" :password "secret123"}
               :boundary/ai {:api-key "sk-abc123"}}
          redacted (cfg-edit/redact-secrets cfg)]
      (is (= "********" (get-in redacted [:boundary/db :password])))
      (is (= "********" (get-in redacted [:boundary/ai :api-key])))
      (is (= "localhost" (get-in redacted [:boundary/db :host]))))))

(deftest ^:unit strip-and-restore-refs-round-trips
  (testing "ig/ref values are stripped and restored correctly"
    (let [original {:db-ctx (ig/ref :boundary/db-context)
                    :port   3000}
          stripped (cfg-edit/strip-refs original)
          restored (cfg-edit/restore-refs stripped)]
      (is (not (ig/ref? (:db-ctx stripped))))
      (is (keyword? (:db-ctx stripped)))
      (is (ig/ref? (:db-ctx restored)))
      (is (= (ig/ref-key (ig/ref :boundary/db-context))
             (ig/ref-key (:db-ctx restored))))
      (is (= 3000 (:port stripped)))
      (is (= 3000 (:port restored)))))

  (testing "round-trips non-boundary namespaced refs"
    (let [original {:logger (ig/ref :app.logging/stdout)
                    :cache  (ig/ref :vendor/redis)}
          restored (cfg-edit/restore-refs (cfg-edit/strip-refs original))]
      (is (ig/ref? (:logger restored)))
      (is (= :app.logging/stdout (ig/ref-key (:logger restored))))
      (is (ig/ref? (:cache restored)))
      (is (= :vendor/redis (ig/ref-key (:cache restored)))))))

(deftest ^:unit contains-refs-detects-refs
  (testing "detects ig/ref in nested structures"
    (is (cfg-edit/contains-refs? {:a (ig/ref :boundary/db)}))
    (is (cfg-edit/contains-refs? {:a {:b (ig/ref :boundary/db)}}))
    (is (not (cfg-edit/contains-refs? {:a 1 :b "hello"})))))

(deftest ^:unit format-config-tree-produces-lines
  (testing "formats config as indented text tree"
    (let [cfg {:boundary/http {:port 3000 :host "localhost"}}
          tree (cfg-edit/format-config-tree cfg)]
      (is (string? tree))
      (is (clojure.string/includes? tree ":boundary/http"))
      (is (clojure.string/includes? tree "3000")))))
