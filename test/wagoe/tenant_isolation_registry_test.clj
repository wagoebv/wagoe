(ns wagoe.tenant-isolation-registry-test
  "A library that carries a tenant id says how it keeps tenants apart and names
   its :security tests or the ticket for the gap (BOU-900)."
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
   "search"        {:scoped-by "tenant_id filter from the request; an index without it is refused to a tenant's caller unless :global?"
                    :tests     {"libs/search/test/wagoe/search/shell/tenant_scope_test.clj"
                                ["a-member-reads-only-their-own-tenant"
                                 "an-index-without-a-tenant-filter-is-refused-to-a-caller-in-a-tenant"
                                 "only-a-platform-admin-writes-documents"]}}
   "user"          {:scoped-by "users.tenant_id; listings and routes by user id forced to the caller's tenant"
                    :tests     {"libs/user/test/wagoe/user/shell/authz_negative_security_test.clj"
                                ["require-tenant-member-denies-non-member"]
                                "libs/user/test/wagoe/user/shell/tenant_scope_test.clj"
                                ["a-listing-holds-only-the-callers-tenant"
                                 "a-route-naming-another-tenants-user-answers-404"
                                 "a-bulk-action-skips-another-tenants-users"
                                 "a-tenants-admin-creates-users-in-their-tenant"
                                 "another-tenants-users-rights-are-not-found"]}}
   "audience"      {:scoped-by "segments span every tenant, so only a platform admin reaches them"
                    :tests     {"libs/audience/test/wagoe/audience/shell/http_test.clj"
                                ["a-tenants-admin-gets-no-audience"]}}
   "admin"         {:scoped-by "an entity mapping :tenant-id under :scope shows a tenant's caller only their tenant's records"
                    :tests     {"libs/admin/test/wagoe/admin/shell/tenant_scope_test.clj"
                                ["a-tenants-admin-reaches-only-their-tenants-records"
                                 "a-platform-admin-reaches-every-tenant-or-the-resolved-one"]}}
   "storage"       {:scoped-by "download, delete and URL check the uploader or tenant in the upload index"
                    :tests     {"libs/storage/test/wagoe/storage/shell/http_handlers_test.clj"
                                ["a-file-is-reached-only-by-its-owner-its-tenant-or-an-admin"
                                 "an-upload-cannot-replace-another-users-file"]}}
   "push"          {:scoped-by "tenant_id columns, never written or read" :gaps ["BOU-946"]}
   "events"        {:scoped-by "an event takes the bound tenant; a subscriber in a tenant gets only its tenant's and system events"
                    :tests     {"libs/events/test/wagoe/events/shell/tenant_scope_test.clj"
                                ["a-subscriber-in-a-tenant-sees-only-that-tenants-events"
                                 "an-event-built-in-a-tenant-carries-it"]}}
   "realtime"      {:scoped-by "role and broadcast messages reach only the sender's tenant; topics need the app's :authorize-topic"
                    :tests     {"libs/realtime/test/wagoe/realtime/shell/tenant_scope_test.clj"
                                ["role-and-broadcast-stay-in-the-senders-tenant"]}}
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
