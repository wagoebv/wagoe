(ns wagoe.tenant-isolation-registry-test
  "A library that carries a tenant id says how it keeps tenants apart and names
   its :security tests or the ticket for the gap (BOU-900). The model:
   dev-docs/threat-model-tenancy.adoc."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def registry
  "lib -> {:scoped-by how, :tests {file [deftest]}, :gaps [ticket]} or {:carries why}.
   :carries is for code that passes a tenant id along but stores no tenant data."
  {"tenant"        {:scoped-by "explicit tenant_id; tenant-admin guards on membership routes"
                    :tests     {"libs/tenant/test/wagoe/tenant/shell/membership_http_test.clj"
                                ["a-tenant-admin-cannot-reach-another-tenants-memberships" "changing-memberships-takes-a-tenant-admin"]}}
   "workflow"      {:scoped-by "tables in each tenant schema; tenant work refuses the shared tables"
                    :tests     {"libs/workflow/test/wagoe/workflow/shell/tenant_isolation_test.clj"
                                ["a-tenant-sees-only-its-own-instances" "tenant-work-never-falls-back-to-public"]
                                "libs/workflow/test/wagoe/workflow/shell/http_test.clj"
                                ["every-api-route-refuses-a-caller-outside-the-tenant"]}}
   "jobs"          {:scoped-by "a tenant job runs in its tenant's schema (:wagoe/in-tenant)"
                    :tests     {"test/wagoe/tenant_jobs_test.clj"
                                ["a-tenant-job-reads-its-tenants-schema"]}}
   "webhooks"      {:scoped-by "explicit tenant_id on subscriptions and deliveries"
                    :tests     {"libs/webhooks/test/wagoe/webhooks/core/delivery_test.clj"
                                ["per-tenant-filtering"]
                                "libs/webhooks/test/wagoe/webhooks/shell/service_test.clj"
                                ["only-the-events-tenant-is-delivered"]}}
   "cache"         {:scoped-by "tenant:<id>: key prefix, when the app uses create-tenant-cache"
                    :tests     {"libs/cache/test/wagoe/cache/shell/tenant_cache_test.clj"
                                ["tenant-cache-key-test"]}}
   "search"        {:scoped-by "tenant_id filter from the request, for indexes that declare it"
                    :tests     {"libs/search/test/wagoe/search/shell/tenant_scope_test.clj"
                                ["a-member-reads-only-their-own-tenant"]}
                    :gaps      ["BOU-948"]}
   "user"          {:scoped-by "users.tenant_id; tenant-member guards on routes"
                    :tests     {"libs/user/test/wagoe/user/shell/authz_negative_security_test.clj"
                                ["require-tenant-member-denies-non-member"]}
                    :gaps      ["BOU-948"]}
   "audience"      {:scoped-by "may filter on :tenant-id, not forced to" :gaps ["BOU-948"]}
   "storage"       {:scoped-by "tenant_id recorded on upload, not read on the way out" :gaps ["BOU-945"]}
   "push"          {:scoped-by "tenant_id columns, never written or read" :gaps ["BOU-946"]}
   "events"        {:scoped-by "events carry :tenant-id; subscribers do not filter" :gaps ["BOU-947"]}
   "realtime"      {:scoped-by "no tenant concept; topics need the app's :authorize-topic" :gaps ["BOU-947"]}
   "config"        {:carries "reads a default tenant id from config"}
   "core"          {:scoped-by "in-tenant-scope? and tenant-admin?, the predicates route guards call"
                    :tests     {"libs/core/test/wagoe/core/http/access_test.clj"
                                ["in-tenant-scope" "tenant-admin"]}}
   "observability" {:carries "puts the tenant id on logs, metrics and error context"}
   "platform"      {:carries "binds the request's tenant for the layers below"}
   "platform-sql"  {:carries "holds the bound tenant id and schema for a transaction"}})

(defn- files-under [dir pred]
  (filter #(and (.isFile ^java.io.File %) (pred (.getName ^java.io.File %)))
          (file-seq (io/file dir))))

(defn- libs-with-tenant-data
  "Libraries whose source names a tenant id, or whose SQL migrations add a tenant_id column."
  []
  (set (for [lib   (map #(.getName ^java.io.File %) (.listFiles (io/file "libs")))
             :when (some #(re-find #"tenant[_-]id" (slurp %))
                         (concat (files-under (str "libs/" lib "/src") #(re-find #"\.clj[cs]?$" %))
                                 (files-under (str "libs/" lib "/resources") #(str/ends-with? % ".sql"))))]
         lib)))

(defn- security-deftest? [source test-name]
  (boolean (re-find (re-pattern (str "\\(deftest\\s+[^(\\n]*\\^:security[^(\\n]*\\s"
                                     (java.util.regex.Pattern/quote test-name) "\\s"))
                    source)))

(deftest ^:unit ^:security every-library-with-tenant-data-is-registered
  (let [found (libs-with-tenant-data)]
    (is (contains? found "tenant") "the scan reads libs/ from the repo root")
    (doseq [lib (sort found)]
      (is (contains? registry lib)
          (str "libs/" lib " carries a tenant id but is not in " `registry
               ": say how it keeps tenants apart and which :security test holds it")))))

(deftest ^:unit ^:security every-entry-names-a-test-or-a-ticket
  (doseq [[lib {:keys [carries tests gaps]}] registry]
    (testing lib
      (is (or carries (seq tests) (seq gaps)) "no test, no ticket, no reason")
      (doseq [[file names] tests
              :let [source (let [f (io/file file)] (when (.exists f) (slurp f)))]
              test-name names]
        (is (and source (security-deftest? source test-name))
            (str file " has no deftest " test-name " tagged :security"))))))
