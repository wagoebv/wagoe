(ns build
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]))

(def lib 'wagoe/wagoe)

(defn- suite-version
  "The published library-suite version, read from libs/core/build.clj — the same
   value every lib pins.

   Previously this was `(format \"1.2.%s\" (b/git-count-revs nil))`, which was
   wrong twice: the `1.2.N` scheme contradicts the suite's actual `1.0.0-beta-2`,
   and `.git` is in .dockerignore, so inside the Docker builder git-count-revs
   returned nil and the artifact was named `wagoe-1.2.null-standalone.jar`.

   Reading libs/core rather than restating the literal keeps this from becoming
   yet another copy that drifts (BOU-250)."
  []
  (let [f (io/file "libs" "core" "build.clj")]
    (or (when (.exists f)
          (second (re-find #"\(def version\s+\"([^\"]+)\"" (slurp f))))
        (throw (ex-info "cannot determine version: libs/core/build.clj unreadable"
                        {:path (.getPath f)})))))

;; Delayed on purpose: resolving the version reads another file and throws if it
;; is unreadable. Eagerly it took every task down with it — `clojure -T:build
;; clean` would fail purely because libs/core/build.clj was missing, which is
;; nothing to do with deleting target/.
;;
;; Note this only defers the VERSION. `basis` and `all-src-dirs` below are still
;; top-level defs, so every task still pays for `create-basis` (~2s) at load.
;; That is a speed cost, not a correctness one — worth revisiting if more tasks
;; are added that do not need the classpath.
(def ^:private version* (delay (suite-version)))

(defn- uber-file* []
  (format "target/%s-%s-standalone.jar" (name lib) @version*))

(defn print-version
  "Print the artifact version and nothing else, so CI and scripts can read it
   without re-implementing the parser:

     clojure -T:build print-version

   Keeping this the single way to ask makes it impossible for a shell-side
   regex to drift from the one in suite-version (BOU-250)."
  [_]
  (println @version*))

(defn print-uber-file
  "Print the uberjar path `uber` will produce. Same rationale as print-version:
   CI asserts against this instead of rebuilding the name itself."
  [_]
  (println (uber-file*)))

(def class-dir "target/classes")

;; Include database drivers in the uberjar basis
(def basis (b/create-basis {:project "deps.edn"
                            :aliases [:db]}))

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
  (let [version   @version*
        uber-file (uber-file*)]
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

    ;; Build uberjar.
    ;;
    ;; Some dependency jars ship LICENSE/NOTICE as a file, others as a directory,
    ;; and b/uber cannot merge the two — it explodes every jar onto one tree, so
    ;; whichever lands first wins and the other errors with "parent dir is a file
    ;; from another lib".
    ;;
    ;; Both the top-level and META-INF variants must be excluded, and the META-INF
    ;; ones case-insensitively: grpc-netty-shaded ships `META-INF/license/` as a
    ;; DIRECTORY while ~38 other jars (jackson, httpclient, guava, pdfbox, poi,
    ;; batik, postgresql, …) ship `META-INF/LICENSE` as a FILE. On a case-sensitive
    ;; filesystem those are distinct paths and the build succeeds — which is why
    ;; this only failed on macOS while Linux and the Docker build were fine.
    ;; Excluding them makes the build behave identically on both.
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis basis
             :main 'wagoe.main
             :exclude ["^LICENSE(/.*)?$"
                       "^NOTICE(/.*)?$"
                       "(?i)^META-INF/LICENSE(/.*|\\.[^/]*)?$"
                       "(?i)^META-INF/NOTICE(/.*|\\.[^/]*)?$"]})

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
                  " -Dclojure.compiler.direct-linking=true -jar " uber-file " server"))))
