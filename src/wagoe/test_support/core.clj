(ns wagoe.test-support.core
  "Pure seed specifications for Playwright e2e tests.

   This namespace is FC-pure: no I/O, no logging, no DB. It only describes
   what the baseline seed should look like. The shell side (reset.clj)
   translates these specs into actual persistence operations via the
   production user and tenant services.")

(def ^:private default-password "Test-Pass-1234!")

(defn baseline-seed-spec
  "Returns a data description of the baseline test fixture: one tenant
   with one admin and one regular user. All passwords are identical and
   intentionally plain text so test helpers can log in with them."
  []
  {:tenant {:slug "acme" :name "Acme Test" :status :active}
   :admin  {:email    "admin@acme.test"
            :name     "Admin User"
            :password default-password
            :role     :admin}
   :user   {:email    "user@acme.test"
            :name     "Regular User"
            :password default-password
            :role     :user}})

(defn empty-seed-spec
  "Returns an empty seed — used by tests that need a pristine DB."
  []
  {})
