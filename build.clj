(ns build
  (:require [clojure.string :as str]
            [clojure.tools.build.api :as b]))

(def lib 'tcbv/boundary)
(def version (format "1.2.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")

;; Include database drivers in the uberjar basis
(def basis (b/create-basis {:project "deps.edn"
                            :aliases [:db]}))

(def uber-file (format "target/%s-%s-standalone.jar" (name lib) version))

;; All source/resource directories, derived from deps.edn :paths so new libs
;; are packaged automatically (test dirs excluded).
(def all-src-dirs
  (->> (:paths basis)
       (remove #(or (= % "test") (str/ends-with? % "/test")))
       vec))

(defn clean
  "Remove compiled artifacts."
  [_]
  (b/delete {:path "target"}))

(defn uber
  "Build uberjar with all dependencies including database drivers."
  [_]
  (clean nil)
  (println "Building uberjar...")
  (println (str "  Library: " lib))
  (println (str "  Version: " version))
  (println (str "  Output:  " uber-file))

  ;; Copy source and resources from all libs
  (b/copy-dir {:src-dirs all-src-dirs
               :target-dir class-dir})

  ;; Compile Clojure namespaces for better startup time.
  ;; Direct linking removes var indirection in compiled code; dynamic vars are
  ;; unaffected, but alter-var-root/with-redefs on non-dynamic vars won't be
  ;; seen by compiled call sites.
  (b/compile-clj {:basis basis
                  :src-dirs all-src-dirs
                  :class-dir class-dir
                  :ns-compile '[wagoe.main]
                  :java-opts ["-Dclojure.compiler.direct-linking=true"]})

  ;; Build uberjar
  ;; Some dependency jars ship LICENSE as a file, others as a directory —
  ;; b/uber cannot merge the two, so keep license/notice files out of the jar.
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis basis
           :main 'wagoe.main
           :exclude ["^LICENSE(/.*)?$" "^NOTICE(/.*)?$"]})

  (println (str "✓ Uberjar built successfully: " uber-file))
  (println)
  (println "Run with:")
  (println (str "  java -jar " uber-file))
  (println (str "  java -jar " uber-file " server"))
  (println (str "  java -jar " uber-file " worker"))
  (println (str "  java -jar " uber-file " cli user list"))
  (println)
  (println "Recommended production JVM flags:")
  (println (str "  java -XX:+UseG1GC -XX:MaxRAMPercentage=75"
                " -Dclojure.compiler.direct-linking=true -jar " uber-file " server")))
