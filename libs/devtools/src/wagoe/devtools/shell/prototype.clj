(ns wagoe.devtools.shell.prototype
  "Orchestrates module generation: scaffold -> migrate -> reset -> summary."
  (:require [wagoe.devtools.core.prototype :as core]
            [wagoe.scaffolder.core.generators :as gen]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]))

(defn- write-file! [path content]
  (let [f (io/file path)]
    (io/make-parents f)
    (spit f content)
    path))

(defn- generate-lib-deps
  "Generate a monorepo library deps.edn (local/root references to sibling libs).
   Mirrors the format used by existing libs/*/deps.edn files."
  [_module-name]
  "{:paths [\"src\" \"resources\"]

 :deps {org.clojure/clojure {:mvn/version \"1.12.4\"}
        wagoe/platform {:local/root \"../platform\"}
        metosin/malli {:mvn/version \"0.20.1\"}}

 :aliases
 {:test {:extra-paths [\"test\"]
         :extra-deps {lambdaisland/kaocha {:mvn/version \"1.91.1392\"}
                      com.h2database/h2 {:mvn/version \"2.4.240\"}}
         :main-opts [\"-m\" \"kaocha.runner\"]}

  :clj-kondo {:replace-deps {clj-kondo/clj-kondo {:mvn/version \"2026.01.19\"}}
              :main-opts [\"-m\" \"clj-kondo.main\"]}

  :build {:replace-deps {io.github.clojure/tools.build {:git/tag \"v0.10.11\" :git/sha \"c6c670a4\"}
                        slipset/deps-deploy {:mvn/version \"0.2.2\"}}
          :ns-default build}}}
")

(defn- generate-module-files!
  "Generate all files for a module using scaffolder generators."
  [module-name ctx generators]
  (let [gen-set  (set generators)
        src-dir  (str "libs/" module-name "/src/wagoe/" module-name)
        files    (atom [])]
    (when (contains? gen-set :schema)
      (swap! files conj (write-file! (str src-dir "/schema.clj")
                                     (gen/generate-schema-file ctx))))
    (when (contains? gen-set :ports)
      (swap! files conj (write-file! (str src-dir "/ports.clj")
                                     (gen/generate-ports-file ctx))))
    (when (contains? gen-set :core)
      (let [entity-kebab (get-in ctx [:entities 0 :entity-kebab] module-name)]
        (swap! files conj (write-file! (str src-dir "/core/" entity-kebab ".clj")
                                       (gen/generate-core-file ctx)))))
    (when (contains? gen-set :service)
      (swap! files conj (write-file! (str src-dir "/shell/service.clj")
                                     (gen/generate-service-file ctx))))
    (when (contains? gen-set :persistence)
      (swap! files conj (write-file! (str src-dir "/shell/persistence.clj")
                                     (gen/generate-persistence-file ctx))))
    (when (contains? gen-set :http)
      (swap! files conj (write-file! (str src-dir "/shell/http.clj")
                                     (gen/generate-http-file ctx))))
    (swap! files conj (write-file! (str "libs/" module-name "/deps.edn")
                                   (generate-lib-deps module-name)))
    @files))

(defn scaffold!
  "Generate module files from a name and field spec.
   Does NOT integrate, migrate, or reset.

   opts keys:
     :fields    - vector of [field-name malli-spec] pairs
     :endpoints - vector of endpoint keywords (default [:crud])"
  [module-name opts]
  (let [spec       {:fields    (:fields opts)
                    :endpoints (or (:endpoints opts) [:crud])}
        ctx        (core/build-scaffold-context module-name spec)
        generators (core/endpoints-to-generators (:endpoints spec))
        files      (generate-module-files! module-name ctx generators)]
    (println (format "\n=> Module '%s' generated at libs/%s/" module-name module-name))
    (println "\nGenerated files:")
    (doseq [f files] (println (str "  " f)))
    (println "\nNext steps:")
    (println (format "  1. Review schema:  libs/%s/src/wagoe/%s/schema.clj" module-name module-name))
    (println (format "  2. Wire module:    bb scaffold integrate %s" module-name))
    (println (format "  3. Add migration:  bb migrate create add-%s-table" module-name))
    (println (format "  4. Run tests:      clojure -M:test:db/h2 :%s" module-name))
    files))

(defn prototype!
  "Generate a complete working module: scaffold files + run migration.
   After this, you need to integrate (bb scaffold integrate) and restart REPL.

   spec keys:
     :fields    - vector of [field-name malli-spec] pairs
     :endpoints - vector of endpoint keywords (default [:crud])"
  [module-name spec]
  (let [generators (core/endpoints-to-generators (or (:endpoints spec) [:crud]))
        ctx        (core/build-scaffold-context module-name spec)
        files      (generate-module-files! module-name ctx generators)
        now               (java.time.LocalDateTime/now)
        migration-ts      (.format now (java.time.format.DateTimeFormatter/ofPattern "yyyyMMddHHmmss"))
        migration-content (gen/generate-migration-file ctx migration-ts)
        up-path           (format "resources/migrations/%s-add-%s-table.up.sql"
                                  migration-ts module-name)
        down-path         (format "resources/migrations/%s-add-%s-table.down.sql"
                                  migration-ts module-name)
        entity-table      (get-in ctx [:entities 0 :entity-table] module-name)]
    (write-file! up-path migration-content)
    (write-file! down-path (format "DROP TABLE IF EXISTS %s;\n" entity-table))
    (println (format "\n=> Module '%s' prototyped:" module-name))
    (println "\nGenerated files:")
    (doseq [f (concat files [up-path down-path])] (println (str "  " f)))
    (println "\nRunning migration...")
    (let [result (shell/sh "bb" "migrate" "up")]
      (if (zero? (:exit result))
        (println "  => Migration applied")
        (println (str "  Warning: Migration failed: " (:err result)))))
    (println (format "\n=> Module '%s' files generated. Next steps:" module-name))
    (println (format "  1. Integrate into app:  bb scaffold integrate %s" module-name))
    (println "  2. Restart REPL to pick up new classpath")
    (println "  3. (reset) to load the module")
    (println (format "  4. Try: (simulate :get \"/api/%s\")" module-name))))
