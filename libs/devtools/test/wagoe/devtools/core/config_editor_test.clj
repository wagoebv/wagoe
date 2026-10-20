(ns wagoe.devtools.core.config-editor-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.edn :as edn]
            [clojure.string]
            [wagoe.devtools.core.config-editor :as cfg-edit]
            [integrant.core :as ig]))

(deftest ^:unit config-diff-detects-changes
  (testing "detects added, removed, and changed keys"
    (let [old {:wagoe/http {:port 3000} :wagoe/db {:host "localhost"}}
          new {:wagoe/http {:port 3001} :wagoe/cache {:ttl 300}}
          diff (cfg-edit/config-diff old new)]
      (is (= {:port 3001} (get-in diff [:changed :wagoe/http :new])))
      (is (contains? (:removed diff) :wagoe/db))
      (is (contains? (:added diff) :wagoe/cache)))))

(deftest ^:unit config-diff-empty-when-identical
  (testing "identical configs produce empty diff"
    (let [cfg {:wagoe/http {:port 3000}}
          diff (cfg-edit/config-diff cfg cfg)]
      (is (empty? (:changed diff)))
      (is (empty? (:added diff)))
      (is (empty? (:removed diff))))))

(deftest ^:unit affected-components-from-diff
  (testing "returns component keys that would need restart"
    (let [diff {:changed {:wagoe/http {:old {:port 3000} :new {:port 3001}}}
                :added {:wagoe/cache {:ttl 300}}
                :removed {:wagoe/db {:host "localhost"}}}]
      (is (= #{:wagoe/http :wagoe/cache :wagoe/db}
             (cfg-edit/affected-components diff))))))

(deftest ^:unit redact-secrets-masks-sensitive-values
  (testing "masks values for keys containing password, secret, api-key, token"
    (let [cfg {:wagoe/db {:host "localhost" :password "secret123"}
               :wagoe/ai {:api-key "sk-abc123"}}
          redacted (cfg-edit/redact-secrets cfg)]
      (is (= "********" (get-in redacted [:wagoe/db :password])))
      (is (= "********" (get-in redacted [:wagoe/ai :api-key])))
      (is (= "localhost" (get-in redacted [:wagoe/db :host]))))))

(deftest ^:unit strip-and-restore-refs-round-trips
  (testing "ig/ref values are stripped and restored correctly"
    (let [original {:db-ctx (ig/ref :wagoe/db-context)
                    :port   3000}
          stripped (cfg-edit/strip-refs original)
          restored (cfg-edit/restore-refs stripped)]
      (is (not (ig/ref? (:db-ctx stripped))))
      (is (keyword? (:db-ctx stripped)))
      (is (ig/ref? (:db-ctx restored)))
      (is (= (ig/ref-key (ig/ref :wagoe/db-context))
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
    (is (cfg-edit/contains-refs? {:a (ig/ref :wagoe/db)}))
    (is (cfg-edit/contains-refs? {:a {:b (ig/ref :wagoe/db)}}))
    (is (not (cfg-edit/contains-refs? {:a 1 :b "hello"})))))

(deftest ^:unit format-config-tree-produces-lines
  (testing "formats config as indented text tree"
    (let [cfg {:wagoe/http {:port 3000 :host "localhost"}}
          tree (cfg-edit/format-config-tree cfg)]
      (is (string? tree))
      (is (clojure.string/includes? tree ":wagoe/http"))
      (is (clojure.string/includes? tree "3000")))))

(defrecord Opts [retries])

(deftest ^:unit format-edn-pretty-prints-edn-that-reads-back
  (let [cfg {:url     "jdbc:h2:mem:app"
             :pool    {:min 1 :max 10 :timeout-ms 30000 :idle-ms 600000
                       :name "a-pool-name-long-enough-to-push-the-map-past-the-right-margin"}
             :at      #inst "2026-01-01T00:00:00.000-00:00"
             :id      #uuid "00000000-0000-0000-0000-000000000001"
             :aliases ["a" "b"]}
        s   (cfg-edit/format-edn cfg)]
    (testing "one key per line, sorted, no commas"
      (is (clojure.string/starts-with? s "{:aliases [\"a\" \"b\"]\n :at #inst"))
      (is (not (clojure.string/includes? s ","))))
    (testing "nothing runs past the right margin"
      (is (every? #(<= (count %) 100) (clojure.string/split-lines s))))
    (testing "reader tags print as tags, so the text reads back to the same value"
      (is (= cfg (edn/read-string s))))
    (testing "a record and a tagged literal keep their tags"
      (is (= "#wagoe.devtools.core.config_editor_test.Opts{:retries 3}"
             (cfg-edit/format-edn (->Opts 3))))
      (is (= "#env \"PORT\"" (cfg-edit/format-edn (tagged-literal 'env "PORT")))))))

(deftest ^:unit strip-unreadable-hides-what-edn-cannot-represent
  (let [f (fn [] {})]
    (testing "a function under a key is hidden, its path reported"
      (is (= {:value {:port 1 :nested {:a 1}} :hidden [[:f] [:nested :g]]}
             (cfg-edit/strip-unreadable {:port 1 :f f :nested {:a 1 :g f}}))))
    (testing "a vector holding a function is hidden whole"
      (is (= {:value {} :hidden [[:xs]]} (cfg-edit/strip-unreadable {:xs [1 f]}))))
    (testing "EDN values pass through untouched"
      (let [v {:at #inst "2026-01-01" :id #uuid "00000000-0000-0000-0000-000000000000"
               :s #{1 "a"} :l '(1 2)}]
        (is (= {:value v :hidden []} (cfg-edit/strip-unreadable v)))
        (is (= v (edn/read-string (cfg-edit/format-edn v))))))
    (testing "a value that is itself unreadable"
      (is (= {:value nil :hidden [[]]} (cfg-edit/strip-unreadable f))))))

(deftest ^:unit restore-unreadable-puts-hidden-values-back
  (let [f        (fn [] {})
        original {:port 1 :f f :db (ig/ref :wagoe/db) :nested {:g f}}]
    (is (= {:port 2 :f f :db (ig/ref :wagoe/other) :nested {:g f}}
           (cfg-edit/restore-unreadable original {:port 2 :db (ig/ref :wagoe/other) :nested {}}))
        "an ig/ref is not hidden, so an edited one is not overwritten")
    (is (= {:port 2 :f f} (cfg-edit/restore-unreadable original {:port 2}))
        "a map the edit removed is not recreated")))
