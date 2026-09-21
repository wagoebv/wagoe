(ns wagoe.platform.shell.database.migrate-on-start-test
  "`:migrate-on-start?` on `:wagoe/db-context`.

   `auto-migrate` existed, documented itself as safe to call on every startup,
   and was called by nothing but its own unit test. So a generated project only
   had tables if someone ran `clojure -M:migrate up` against a persistent
   database first — impossible on the `test` profile, which is in-memory H2 and
   shares nothing with another process. Every route that touched the database
   answered 500 (BOU-485).

   migratus is stubbed here, the way `migrations-test` stubs it: this repo's own
   `resources/migrations` is not a runnable set on a fresh database — it indexes
   `users`, which nothing in it creates — so a test that really migrated would
   be testing that, not this. The end-to-end proof is `scripts/example-smoke.sh`,
   which now asks a booted `examples/shop` for a page that reads its own table."
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [migratus.core :as migratus]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.platform.shell.database.migrations :as migrations]
            [wagoe.platform.shell.system.wiring]))

(defn- init-db-context
  "Init the component with migratus stubbed, returning what it was asked to do."
  [config]
  (let [calls (atom [])
        ctx   (with-redefs [db-factory/db-context (fn [_] {:adapter ::adapter
                                                           :datasource ::the-pool})
                            db-factory/close-db-context! (fn [_] nil)
                            migrations/refuse-shadowed-migration-dirs! (fn [] nil)
                            migrations/discover-migration-dirs (fn [] ["migrations"])
                            migratus/migrate (fn [cfg] (swap! calls conj cfg))]
                (ig/init-key :wagoe/db-context config))]
    {:ctx ctx :calls @calls}))

(deftest ^:unit db-context-migrates-only-when-asked
  (testing "with :migrate-on-start? the schema is applied before anything queries it"
    (let [{:keys [calls]} (init-db-context {:adapter :h2 :migrate-on-start? true})]
      (is (= 1 (count calls)))

      (testing "against the context's own pool, not one built from config.edn"
        ;; `migrate` resolves its database by re-reading config.edn and taking
        ;; the first :active entry. At boot the application already holds the
        ;; pool it is about to query, and an app with more than one active
        ;; database would otherwise migrate whichever came first.
        (is (= ::the-pool (get-in (first calls) [:db :datasource]))))))

  (testing "without it nothing runs, so no existing application changes on upgrade"
    (is (empty? (:calls (init-db-context {:adapter :h2}))))
    (is (empty? (:calls (init-db-context {:adapter :h2 :migrate-on-start? false}))))))

(deftest ^:unit a-failing-migration-fails-the-boot
  ;; `auto-migrate` caught, logged and returned false. An application whose
  ;; schema did not apply then fails on its first query instead, with an error
  ;; far from the cause.
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"Migration failed"
       (with-redefs [db-factory/db-context (fn [_] {:datasource ::pool})
                     migrations/refuse-shadowed-migration-dirs! (fn [] nil)
                     migrations/discover-migration-dirs (fn [] ["migrations"])
                     migratus/migrate (fn [_] (throw (Exception. "syntax error")))]
         (ig/init-key :wagoe/db-context {:adapter :h2 :migrate-on-start? true})))))

(deftest ^:unit migrate-datasource-keeps-the-discovery-rules
  ;; The directory set and the shadowed-directory refusal are what make
  ;; migrations findable and their absence loud; taking a datasource must not
  ;; quietly opt out of either.
  (let [calls (atom [])]
    (with-redefs [migrations/discover-migration-dirs (fn [] ["migrations" "wagoe/geo/migrations/"])
                  migrations/refuse-shadowed-migration-dirs! (fn [] nil)
                  migratus/migrate (fn [cfg] (swap! calls conj cfg))]
      (migrations/migrate-datasource! ::ds))
    (is (= {:store                :database
            :migration-dir        ["migrations" "wagoe/geo/migrations/"]
            :init-script          nil
            :init-in-transaction? false
            :migration-table-name "schema_migrations"
            :db                   {:datasource ::ds}}
           (first @calls))))

  (testing "and migrations in a directory nothing reads are still refused"
    (with-redefs [migrations/refuse-shadowed-migration-dirs!
                  (fn [] (throw (ex-info "never read" {:type :migration-dir-conflict})))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"never read"
                            (migrations/migrate-datasource! ::ds))))))
