(ns wagoe.push.shell.module-wiring-test
  "The FCM settings shape must survive the whole chain: what the catalogue
   writes, ig-config passes, and the init-key destructures (BOU-346 — three
   disagreeing shapes meant configured FCM threw at boot, always)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging.test :as log-test]
            [integrant.core :as ig]
            [wagoe.push.shell.adapters.mock :as mock]
            [wagoe.push.shell.module-wiring :as wiring]))

(deftest ^:unit fcm-settings-reach-the-init-key-in-its-own-shape
  (testing "the catalogue's map lands as :provider/:project-id/:credentials-path"
    (let [fcm (get-in (wiring/ig-config
                       {:fcm-credentials {:project-id "p" :credentials-path "/c.json"}}
                       {})
                      [:components :wagoe.push/fcm-provider])]
      (is (= :fcm (:provider fcm)))
      (is (= "p" (:project-id fcm)))
      (is (= "/c.json" (:credentials-path fcm)))))

  (testing "an absent or wholly unset map falls back to :mock — unset #env
            values must not select a provider that cannot construct"
    (doseq [settings [{} {:fcm-credentials nil}
                      {:fcm-credentials {:project-id nil :credentials-path nil}}]]
      (is (= :mock (get-in (wiring/ig-config settings {})
                           [:components :wagoe.push/fcm-provider :provider]))
          (pr-str settings))))

  (testing "a partial map is refused rather than mocked"
    ;; This case used to expect :mock, which is what the assertion above said
    ;; when it was written for BOU-346. Silently mocking a half-configured
    ;; provider boots a process that accepts every push and delivers none, and
    ;; that is the outcome hardest to notice (BOU-427 review).
    (is (thrown? clojure.lang.ExceptionInfo
                 (wiring/ig-config {:fcm-credentials {:project-id "p"}} {})))))

(deftest ^:unit push-credentials-are-all-or-nothing
  ;; Both adapters, every state, one table. FCM got this guard in BOU-346 and
  ;; APNs did not, and the two sat three lines apart for a release (BOU-427).
  ;;
  ;; The middle row is the one the review asked for: a half-configured provider
  ;; used to fall back to the mock, which accepts every push and delivers none.
  (let [full {:fcm  {:project-id "p" :credentials-path "/creds.json"}
              :apns {:team-id "T" :key-id "K" :key-path "/k.p8" :bundle-id "com.x"}}]
    (doseq [[adapter settings-key component required]
            [[:fcm  :fcm-credentials  :wagoe.push/fcm-provider  [:project-id :credentials-path]]
             [:apns :apns-credentials :wagoe.push/apns-provider [:team-id :key-id :key-path :bundle-id]]]]

      (testing (str (name adapter) ": nothing configured falls back to the mock")
        (let [cfg (wiring/ig-config {} {})]
          (is (= :mock (:provider (get (:components cfg) component))))))

      (testing (str (name adapter) ": an unset #env block reads as nothing configured")
        ;; What aero leaves behind when the variables are not set.
        (let [blank (zipmap required (repeat nil))
              cfg   (wiring/ig-config {settings-key blank} {})]
          (is (= :mock (:provider (get (:components cfg) component))))))

      (testing (str (name adapter) ": every field present selects the real provider")
        (let [cfg (wiring/ig-config {settings-key (get full adapter)} {})]
          (is (= adapter (:provider (get (:components cfg) component))))))

      (doseq [omitted required]
        (testing (str (name adapter) ": missing " omitted " is refused, not mocked")
          (let [partial* (dissoc (get full adapter) omitted)
                ex       (is (thrown? clojure.lang.ExceptionInfo
                                      (wiring/ig-config {settings-key partial*} {})))]
            (is (= [omitted] (:missing (ex-data ex)))
                (str "the refusal does not name " omitted))))))))

(deftest ^:unit a-missing-callback-secret-warns-at-boot
  ;; Without one every callback is refused, and nothing said so (BOU-844).
  (doseq [secret [nil "" "s3cret"]]
    (log-test/with-log
      (ig/init-key :wagoe.push/service {:callback-secret secret})
      (is (= (not= "s3cret" secret)
             (log-test/logged? 'wagoe.push.shell.module-wiring :warn #"callback-secret"))
          (pr-str secret))))
  (testing "but not with mock providers, which reach no device (BOU-849)"
    (log-test/with-log
      (ig/init-key :wagoe.push/service
                   (get-in (wiring/ig-config {} {}) [:components :wagoe.push/service]))
      (is (not (log-test/logged? 'wagoe.push.shell.module-wiring :warn #"callback-secret")))))
  (testing "the providers' config decides, not their class"
    (log-test/with-log
      (ig/init-key :wagoe.push/service {:fcm-provider  (mock/->MockFCMProvider)
                                        :apns-provider (mock/->MockAPNsProvider)
                                        :providers     {:fcm :fcm :apns :mock}})
      (is (log-test/logged? 'wagoe.push.shell.module-wiring :warn #"callback-secret")))))

(deftest ^:unit callback-token-settings-reach-the-components
  (let [components (:components (wiring/ig-config {:callback-secret                "s"
                                                   :callback-token-ttl-seconds     86400
                                                   :refuse-legacy-callback-tokens? true}
                                                  {:enabled #{:wagoe/jobs}}))
        service    (ig/init-key :wagoe.push/service
                                (dissoc (:wagoe.push/service components) :job-queue))]
    (is (= 86400 (:callback-token-ttl-seconds service)))
    (is (true? (get-in components [:wagoe.push/route-contribution :refuse-legacy-callback-tokens?])))
    (is (true? (get-in components [:wagoe.push/routes :refuse-legacy-callback-tokens?]))))
  (testing "off and seven days unless set"
    (let [components (:components (wiring/ig-config {:callback-secret "s"} {}))]
      (is (nil? (get-in components [:wagoe.push/route-contribution :refuse-legacy-callback-tokens?])))
      (is (nil? (:callback-token-ttl-seconds (ig/init-key :wagoe.push/service
                                                          (:wagoe.push/service components)))))))
  (testing "an unset #env is the default"
    (is (map? (wiring/ig-config {:callback-token-ttl-seconds     nil
                                 :refuse-legacy-callback-tokens? nil} {}))))
  (doseq [bad [{:callback-token-ttl-seconds 0} {:callback-token-ttl-seconds "7d"}
               {:callback-token-ttl-seconds (* 101 365 24 60 60)}
               {:refuse-legacy-callback-tokens? "yes"}]]
    (let [ex (is (thrown? clojure.lang.ExceptionInfo (wiring/ig-config bad {})) (pr-str bad))]
      (is (= :configuration-error (:type (ex-data ex))))))
  (testing "a hand-wired service is checked too"
    (let [ex (is (thrown? clojure.lang.ExceptionInfo
                          (ig/init-key :wagoe.push/service {:callback-token-ttl-seconds "604800"})))]
      (is (= :configuration-error (:type (ex-data ex)))))))

(deftest ^:unit stores-take-the-db-context-as-db-ctx
  (let [components (:components (wiring/ig-config {} {}))]
    (is (= {:db-ctx (ig/ref :wagoe/db-context)} (:wagoe.push/device-store components)))
    (is (= {:db-ctx (ig/ref :wagoe/db-context)} (:wagoe.push/analytics-store components))))
  (log-test/with-log
    (is (= :ctx (:db (ig/init-key :wagoe.push/device-store {:db-ctx :ctx}))))
    (is (not (log-test/logged? 'wagoe.push.shell.module-wiring :warn #"DEPRECATED"))))
  (log-test/with-log
    (is (= :ctx (:db (ig/init-key :wagoe.push/analytics-store {:db :ctx}))) "the deprecated :db still works")
    (is (log-test/logged? 'wagoe.push.shell.module-wiring :warn #"DEPRECATED :wagoe.push/analytics-store :db.*2\.0"))))
