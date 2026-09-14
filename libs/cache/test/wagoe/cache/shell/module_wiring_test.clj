(ns wagoe.cache.shell.module-wiring-test
  "One provider vocabulary, and no silent fallback.

   `:in-memory` here, `:memory` in jobs, `:redis-streams` in events: the same
   choice spelled three ways. They are one now, with the old spellings accepted
   (BOU-436).

   The second half matters more. An unrecognised provider used to produce an
   in-process cache and a warning, so a production node configured for Redis ran
   on a per-node atom — correct-looking, and wrong the moment there were two
   replicas."
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [wagoe.cache.ports :as ports]
            [wagoe.cache.shell.module-wiring :as wiring]))

(deftest ^:unit the-provider-vocabulary-is-shared
  (testing "the canonical spellings pass through untouched"
    (is (= :memory (wiring/normalize-provider :memory)))
    (is (= :redis (wiring/normalize-provider :redis))))

  (testing "the pre-1.0 spelling still means what it meant"
    (is (= :memory (wiring/normalize-provider :in-memory))))

  (testing "omitting it keeps the documented default"
    (is (= :memory (wiring/normalize-provider nil))))

  (testing "anything else is passed on unchanged, for init-key to reject"
    (is (= :rediss (wiring/normalize-provider :rediss)))))

(deftest ^:unit an-unknown-provider-fails-instead-of-quietly-serving-memory
  (testing "a typo throws rather than building an in-process cache"
    (let [e (try (ig/init-key :wagoe/cache {:provider :rediss})
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo e)
          "a misspelled provider used to return a working in-memory cache")
      (is (= :unknown-provider (:type (ex-data e))))
      (is (= [:memory :redis] (:known (ex-data e))))))

  (testing "and both spellings of in-process still build one"
    (doseq [provider [:memory :in-memory nil]]
      (let [cache (ig/init-key :wagoe/cache {:provider provider :default-ttl 60})]
        (try
          (is (some? cache) (str provider " built no cache"))
          (ports/set-value! cache "k" "v" 60)
          (is (= "v" (ports/get-value cache "k"))
              (str provider " built something that does not cache"))
          (finally (ig/halt-key! :wagoe/cache cache)))))))
