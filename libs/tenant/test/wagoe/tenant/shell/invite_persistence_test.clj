(ns wagoe.tenant.shell.invite-persistence-test
  "An invite read back carries Instants on every engine.

   H2 returns a zoned timestamp as OffsetDateTime, which the repository passed
   through and cheshire cannot encode — the membership routes answered 500 for
   the same reason (BOU-576)."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [support.embedded-pg :as epg]
            [wagoe.platform.shell.adapters.database.factory :as factory]
            [wagoe.tenant.ports :as ports]
            [wagoe.tenant.shell.invite-persistence :as sut]
            [wagoe.tenant.shell.persistence :as tenant-persistence])
  (:import [java.time Instant]
           [java.time.temporal ChronoUnit]))

(defn- backends []
  [["h2" (fn []
           (let [ctx (factory/db-context
                      (factory/h2-config (str "mem:invite_ts_" (System/nanoTime) ";DB_CLOSE_DELAY=-1")))]
             [ctx #(factory/close-db-context! ctx)]))]
   ["postgresql" (fn []
                   (let [pg  (epg/start!)
                         ctx (epg/db-context pg)]
                     [ctx (fn []
                            (factory/close-db-context! ctx)
                            (epg/stop! pg))]))]])

(def ^:private timestamps [:expires-at :accepted-at :revoked-at :created-at :updated-at])

(deftest ^:integration invite-timestamps-read-back-as-instants
  (doseq [[label open] (backends)]
    (testing label
      (let [[ctx close!] (open)
            now          (.truncatedTo (Instant/now) ChronoUnit/MILLIS)]
        (try
          (tenant-persistence/initialize-tenant-schema! ctx)
          (let [repo   (sut/create-invite-repository ctx nil nil)
                invite {:id          (random-uuid)
                        :tenant-id   (random-uuid)
                        :email       "a@example.test"
                        :role        :member
                        :status      :pending
                        :token-hash  (str (random-uuid))
                        :expires-at  (.plus now 7 ChronoUnit/DAYS)
                        :accepted-at now
                        :revoked-at  now
                        :created-at  now
                        :updated-at  now}
                _      (ports/create-invite repo invite)
                read   (ports/find-invite-by-id repo (:id invite))]
            (doseq [k timestamps]
              (testing (name k)
                (is (instance? Instant (get read k)) (str (class (get read k))))
                (is (= (get invite k) (get read k)))))
            (testing "so the invite encodes as JSON"
              (is (string? (json/generate-string read)))))
          (finally (close!)))))))
