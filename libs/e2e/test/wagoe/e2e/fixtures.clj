(ns wagoe.e2e.fixtures
  "Kaocha-compatible fixtures for the end-to-end test suite.

   Every e2e test starts from a known-clean DB state by calling
   `wagoe.e2e.helpers.reset/reset-db!`, which POSTs to /test/reset on
   the running test-profile server (port 3100). The returned SeedResult
   (tenant/admin/user with plain-text passwords) is bound to `*seed*` so
   individual tests can read credentials without re-seeding."
  (:require [wagoe.e2e.helpers.reset :as reset]))

(def ^:dynamic *seed*
  "Per-test seed result. Bound by `with-fresh-seed` to the parsed body
   of POST /test/reset. nil outside a fixture scope."
  nil)

(defn with-fresh-seed
  "Fixture function (clojure.test :each compatible). Resets the DB to the
   baseline seed before every test and binds the result to `*seed*` for
   the duration of the test body."
  [f]
  (let [seed (reset/reset-db!)]
    (binding [*seed* seed]
      (f))))
