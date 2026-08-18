(ns wagoe.platform.shell.modules-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [wagoe.platform.shell.modules :as sut]))

;; =============================================================================
;; Scaffolded module discovery (BOU-311)
;; =============================================================================

(deftest ^:unit discovery-wires-a-module-the-fixed-list-never-heard-of
  ;; `bb scaffold generate tasks` then adding :wagoe/tasks to config produced
  ;; nothing: ig-config built from a fixed list of known keys, so the key was
  ;; skipped without an init-key, a route, or a word about it. Quickstart
  ;; reported 8/8 and left dead code behind.
  (let [entries (sut/discover-module-config
                 {:wagoe/tasks {:enabled? true}}
                 #{}
                 "acme"
                 (constantly true))]

    (testing "the four keys the scaffolder generates are emitted"
      (is (= #{:wagoe/tasks-repository :wagoe/tasks-service
               :wagoe/tasks-routes :wagoe/tasks}
             (set (keys entries)))))

    (testing "and they are wired to each other, not left dangling"
      (is (= (ig/ref :wagoe/db-context) (:ctx (:wagoe/tasks-repository entries))))
      (is (= (ig/ref :wagoe/tasks-repository) (:repository (:wagoe/tasks-service entries))))
      (is (= (ig/ref :wagoe/tasks-service) (:service (:wagoe/tasks-routes entries))))
      (is (= (ig/ref :wagoe/tasks-routes) (:routes (:wagoe/tasks entries)))))))

(deftest ^:unit discovery-leaves-the-frameworks-own-keys-alone
  ;; The framework's modules have bespoke wiring and names that do not follow
  ;; the convention — :wagoe/payment-provider, :wagoe.external/smtp. Discovery
  ;; must not try to wire them a second time.
  (testing "a key the fixed list already handles is skipped"
    (is (empty? (sut/discover-module-config
                 {:wagoe/cache {:provider :in-memory}}
                 #{:wagoe/cache}
                 "acme"
                 (constantly true)))))

  (testing "a namespaced key that is not :wagoe/<module> is not a module"
    (is (empty? (sut/discover-module-config
                 {:wagoe.external/smtp {:host "x"}}
                 #{}
                 "acme"
                 (constantly true)))))

  (testing "settings and profile are configuration, not modules"
    (is (empty? (sut/discover-module-config
                 {:wagoe/settings {:name "app"} :wagoe/profile :dev}
                 #{}
                 "acme"
                 (constantly true))))))

(deftest ^:unit an-active-key-with-no-wiring-fails-loudly
  ;; The silent skip is the defect. A typo in a key name has to be visible —
  ;; :active/:inactive is easy to get wrong and produced no signal at all.
  (let [e (try (sut/discover-module-config
                {:wagoe/tsaks {:enabled? true}}
                #{}
                "acme"
                (constantly false))
               nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (some? e) "an unresolvable module key must throw")
    (testing "the message names the key, the namespace it looked for, and the fix"
      (is (str/includes? (ex-message e) ":wagoe/tsaks"))
      (is (str/includes? (ex-message e) "acme.tsaks.shell.module-wiring")))
    (testing "and carries a BND code so the error catalogue can explain it"
      (is (= :wagoe/module-wiring-not-found (:type (ex-data e)))))))

(deftest ^:unit a-disabled-module-is-not-wired
  ;; :enabled? false is how you turn a module off without deleting its config.
  (is (empty? (sut/discover-module-config
               {:wagoe/tasks {:enabled? false}}
               #{}
               "acme"
               (constantly true)))))

(deftest ^:unit discovered-routes-reach-the-handler
  ;; Emitting the keys is half the job. :wagoe/http-handler destructures a fixed
  ;; list of *-routes keys — it cannot name a generated module — so a discovered
  ;; module initialised and served nothing until its routes arrived as a
  ;; collection on :module-routes.
  (testing "a ref per discovered module"
    (is (= [(ig/ref :wagoe/tasks-routes)]
           (sut/discovered-route-refs {:wagoe/tasks {:enabled? true}} #{}))))

  (testing "framework modules are not double-wired"
    (is (empty? (sut/discovered-route-refs {:wagoe/admin {:enabled? true}} #{:wagoe/admin}))))

  (testing "and a disabled module contributes none"
    (is (empty? (sut/discovered-route-refs {:wagoe/tasks {:enabled? false}} #{})))))
