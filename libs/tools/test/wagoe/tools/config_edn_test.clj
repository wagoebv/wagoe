(ns wagoe.tools.config-edn-test
  "Editing config.edn as text.

   It cannot be read as EDN: Aero tags (`#env`, `#profile`, `#or`) are reader
   macros no plain reader knows, so a read-modify-write round trip fails on the
   first `#env`. Brace-balanced text insertion is what quickstart already did;
   this is that code, in one place, so `integrate` does not grow a second copy
   that drifts (BOU-310)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.tools.config-edn :as sut]))

(def ^:private sample
  ";; A config\n{:profile #profile {:default :dev}\n\n :active\n {:wagoe/settings\n  {:name \"app\"}\n\n  :wagoe/h2\n  {:db \"mem:app\"}}\n\n :inactive\n {:wagoe/cache\n  {:provider :redis}}}\n")

(deftest ^:unit finds-the-closing-brace-of-active-not-inactive
  (testing "the index is the brace that closes :active"
    (let [idx (sut/active-closing-brace sample)]
      (is (some? idx))
      (is (= \} (nth sample idx)))
      ;; Everything after it must still contain :inactive — otherwise we found
      ;; the wrong brace and would inject into the wrong section.
      (is (str/includes? (subs sample idx) ":inactive"))))

  (testing ":inactive is not mistaken for :active"
    ;; `(str/index-of text ":active")` matches inside ":inactive"; a module
    ;; injected there is configured and switched off, which reads as a scaffolder
    ;; bug rather than a config one.
    (let [inactive-first ";; x\n{:inactive\n {:a 1}\n\n :active\n {:b 2}}\n"
          idx (sut/active-closing-brace inactive-first)]
      (is (some? idx))
      (is (str/includes? (subs inactive-first 0 idx) ":b"))))

  (testing "a commented-out :active is not the section"
    (let [commented ";; :active is where modules go\n{:active\n {:a 1}}\n"]
      (is (some? (sut/active-closing-brace commented)))))

  (testing "no :active section at all is nil, not a guess"
    (is (nil? (sut/active-closing-brace "{:profile :dev}\n")))))

(deftest ^:unit injecting-a-key-puts-it-inside-active
  (let [out (sut/insert-before-active-close sample "\n  :wagoe/tasks\n  {:enabled? true}\n")]
    (testing "the key lands inside the :active map"
      (let [active-part (subs out 0 (str/index-of out ":inactive"))]
        (is (str/includes? active-part ":wagoe/tasks"))))

    (testing "and the braces still balance"
      (is (= (count (filter #{\{} out)) (count (filter #{\}} out)))))

    (testing "the rest of the file is untouched"
      (is (str/includes? out ":wagoe/h2"))
      (is (str/includes? out ":inactive")))))

(deftest ^:unit a-key-that-is-already-there-is-not-added-twice
  ;; Running integrate twice must be a no-op, not a duplicate key — EDN keeps
  ;; the last one, so a second run would silently discard hand edits to the
  ;; first.
  (let [once  (sut/insert-before-active-close sample "\n  :wagoe/tasks\n  {:enabled? true}\n")]
    (is (= :already-present (sut/key-status once ":wagoe/tasks")))
    (is (= :absent (sut/key-status sample ":wagoe/tasks")))))
