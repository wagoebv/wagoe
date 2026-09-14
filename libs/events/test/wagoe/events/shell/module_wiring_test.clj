(ns wagoe.events.shell.module-wiring-test
  "One provider vocabulary: `:memory` | `:redis`, as in cache, realtime and jobs.

   This module spelled them `:in-memory` and `:redis-streams`, and the second
   one was the odd member of the set — documentation had to explain that the
   event bus is selected with a different word than the cache it sits beside
   (BOU-436). Both old spellings still work.

   What must not change: there is still no default. Choosing one silently would
   decide whether events survive a restart."
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [wagoe.events.shell.module-wiring :as wiring]))

(deftest ^:unit the-provider-vocabulary-is-shared
  (testing "the canonical spellings pass through untouched"
    (is (= :memory (wiring/normalize-provider :memory)))
    (is (= :redis (wiring/normalize-provider :redis))))

  (testing "both pre-1.0 spellings still mean what they meant"
    (is (= :memory (wiring/normalize-provider :in-memory)))
    (is (= :redis (wiring/normalize-provider :redis-streams))))

  (testing "nil is left alone, so init-key still refuses to pick for you"
    (is (nil? (wiring/normalize-provider nil)))))

(deftest ^:unit a-missing-or-unknown-provider-still-throws
  (doseq [provider [nil :rediss :streams]]
    (let [e (try (ig/init-key :wagoe/events {:provider provider})
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo e)
          (str (pr-str provider) " did not throw"))
      (is (= :unknown-provider (:type (ex-data e))))
      (is (= [:memory :redis] (:known (ex-data e)))
          "the error names the vocabulary, so it is the fix as well as the complaint"))))

(deftest ^:unit the-old-spelling-builds-the-same-bus-and-halts-cleanly
  ;; The halt path dispatches on the provider the component stored. Storing the
  ;; configured spelling rather than the normalised one would skip the stop!
  ;; call for anyone still writing :in-memory.
  (doseq [provider [:memory :in-memory]]
    (let [bus (ig/init-key :wagoe/events {:provider provider})]
      (is (= :memory (:wagoe.events/provider bus))
          (str provider " stored a provider the halt path does not handle"))
      (ig/halt-key! :wagoe/events bus))))
