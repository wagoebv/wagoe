(ns wagoe.tools.migration-manifests-test
  "A library that ships migrations must publish the manifest that reveals them.

   `wagoe.platform.shell.database.migrations` discovers library migrations from
   a `wagoe/migration-paths/<lib>.edn` resource enumerated off the classpath. A
   library with migration files and no manifest contributes nothing: the runner
   never looks in its directory, `bb migrate up` reports nothing pending, and
   the first query fails on a missing table.

   `libs/push` shipped three migrations and no manifest, so `wagoe add push`
   gave you a module whose tables were never created (BOU-423). Two libraries
   ship migrations today, which makes this the cheapest moment to notice the
   third."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]))

(defn- repo-root []
  (let [cwd (fs/cwd)]
    (if (fs/exists? (fs/path cwd "libs"))
      cwd
      (fs/path cwd ".." ".."))))

(def ^:private migration-extensions
  "What Migratus counts as a migration.

   Both, because `migrations.clj` says why in the file this guard protects:
   \"Hardcoding '.sql' missed EDN migrations, which migratus reads just as
   happily — `get-all-supported-extensions` returns [\"sql\" \"edn\"]\". The first
   version of this test hardcoded `.sql` and repeated that exact mistake, so an
   EDN migration in an undeclared directory passed it (BOU-423 review).

   Listed rather than read from `migratus.migrations`, which is a Maven
   dependency this Babashka surface does not have."
  ["sql" "edn"])

(defn- libs-with-migrations
  "{lib-name #{migration-dir-relative-to-resources}} for every library with a
   migration file under `resources/`, in any format Migratus reads."
  []
  (into {}
        (for [lib  (fs/list-dir (fs/path (repo-root) "libs"))
              :let [resources (fs/path lib "resources")]
              :when (fs/directory? resources)
              :let  [ups (mapcat #(fs/glob resources (str "**/*.up." %))
                                 migration-extensions)]
              :when (seq ups)]
          [(fs/file-name lib)
           (into #{} (map #(str (fs/relativize resources (fs/parent %)) "/")) ups)])))

(defn- declared-paths
  "The migration directories `lib` publishes, or nil when it publishes none."
  [lib]
  (let [manifest (fs/path (repo-root) "libs" lib "resources" "wagoe" "migration-paths" (str lib ".edn"))]
    (when (fs/exists? manifest)
      (let [data (edn/read-string (slurp (fs/file manifest)))]
        (set (if (vector? data) data (:paths data)))))))

(deftest ^:unit every-library-with-migrations-publishes-a-manifest
  (let [with-migrations (libs-with-migrations)]

    (testing "the libraries were read — otherwise this passes vacuously"
      (is (seq with-migrations)
          "found no library shipping migrations; the scan is looking in the wrong place"))

    (testing "each one publishes wagoe/migration-paths/<lib>.edn"
      (doseq [[lib dirs] with-migrations]
        (let [declared (declared-paths lib)]
          (is (some? declared)
              (str lib " ships migrations in " (pr-str (sort dirs))
                   " and publishes no wagoe/migration-paths/" lib ".edn, so the runner"
                   " never reads them. Add {:paths " (pr-str (vec (sort dirs))) "}"))

          (testing (str lib " declares the directories it actually has")
            (when declared
              (is (empty? (remove declared dirs))
                  (str lib " has migrations in " (pr-str (sort (remove declared dirs)))
                       " which its manifest does not declare")))))))))

(deftest ^:unit no-library-ships-the-shared-manifest-name
  ;; An uberjar keeps one copy of a shared name, so every library but one lost
  ;; its migrations in `java -jar app.jar migrate` (BOU-543).
  (doseq [lib (fs/list-dir (fs/path (repo-root) "libs"))]
    (is (not (fs/exists? (fs/path lib "resources" "wagoe" "migration-paths.edn")))
        (str (fs/file-name lib) " ships wagoe/migration-paths.edn; move it to"
             " wagoe/migration-paths/" (fs/file-name lib) ".edn"))))
