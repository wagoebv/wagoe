(ns build
  "Build tooling for Wagoe monorepo.
   
   Usage:
     clojure -T:build test-all          ; Run all tests
     clojure -T:build test-lib :lib core ; Test specific library
     clojure -T:build clean             ; Clean build artifacts
     clojure -T:build jar :lib core     ; Build jar for specific library
     clojure -T:build release-all       ; Release all libraries to Clojars"
  (:require [clojure.tools.build.api :as b]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def version "0.1.0-SNAPSHOT")
(def group-id "boundary")
(def libraries 
  ["core" "observability" "platform" "user" "admin" "storage" "external" "scaffolder"])

(defn lib-dir [lib]
  (str "libs/" lib))

(defn lib-deps-file [lib]
  (str (lib-dir lib) "/deps.edn"))

(defn lib-src-dir [lib]
  (str (lib-dir lib) "/src"))

(defn lib-target-dir [lib]
  (str (lib-dir lib) "/target"))

(defn lib-jar-file [lib]
  (str (lib-target-dir lib) "/" lib "-" version ".jar"))

;; =============================================================================
;; Testing
;; =============================================================================

(defn test-all
  "Run tests for all libraries."
  [_]
  (println "Running tests for all libraries...")
  (doseq [lib libraries]
    (println (str "\n=== Testing " lib " ==="))
    (let [result (b/process {:command-args ["clojure" "-M:dev:test" 
                                             "-m" "kaocha.runner"
                                             "--focus-meta" (str ":lib-" lib)]})]
      (when (not= 0 (:exit result))
        (println (str "FAILED: Tests failed for " lib))
        (System/exit 1))))
  (println "\n✓ All tests passed"))

(defn test-lib
  "Run tests for a specific library.
   
   Usage: clojure -T:build test-lib :lib core"
  [{:keys [lib]}]
  (when-not lib
    (println "ERROR: :lib parameter required")
    (System/exit 1))
  (println (str "Running tests for " lib "..."))
  (b/process {:command-args ["clojure" "-M:dev:test" 
                              "-m" "kaocha.runner"
                              "--focus-meta" (str ":lib-" lib)]}))

;; =============================================================================
;; Cleaning
;; =============================================================================

(defn clean
  "Clean build artifacts for all libraries."
  [_]
  (println "Cleaning build artifacts...")
  (doseq [lib libraries]
    (let [target-dir (lib-target-dir lib)]
      (when (.exists (io/file target-dir))
        (println (str "  Removing " target-dir))
        (b/delete {:path target-dir}))))
  (println "✓ Clean complete"))

(defn clean-lib
  "Clean build artifacts for a specific library.
   
   Usage: clojure -T:build clean-lib :lib core"
  [{:keys [lib]}]
  (when-not lib
    (println "ERROR: :lib parameter required")
    (System/exit 1))
  (let [target-dir (lib-target-dir lib)]
    (when (.exists (io/file target-dir))
      (println (str "Removing " target-dir))
      (b/delete {:path target-dir}))))

;; =============================================================================
;; JAR Building
;; =============================================================================

(defn jar
  "Build jar for a specific library.
   
   Usage: clojure -T:build jar :lib core"
  [{:keys [lib]}]
  (when-not lib
    (println "ERROR: :lib parameter required")
    (System/exit 1))
  
  (let [artifact-id lib
        jar-file (lib-jar-file lib)
        class-dir (str (lib-target-dir lib) "/classes")
        basis (b/create-basis {:project (lib-deps-file lib)})
        src-dirs [(lib-src-dir lib)]]
    
    (println (str "Building jar for " lib "..."))
    (clean-lib {:lib lib})
    
    (b/write-pom {:class-dir class-dir
                  :lib (symbol group-id artifact-id)
                  :version version
                  :basis basis
                  :src-dirs src-dirs})
    
    (b/copy-dir {:src-dirs src-dirs
                 :target-dir class-dir})
    
    (b/jar {:class-dir class-dir
            :jar-file jar-file})
    
    (println (str "✓ Created " jar-file))))

(defn jar-all
  "Build jars for all libraries."
  [_]
  (println "Building jars for all libraries...")
  (doseq [lib libraries]
    (jar {:lib lib}))
  (println "\n✓ All jars built"))

;; =============================================================================
;; Publishing (placeholder for now)
;; =============================================================================

(defn deploy
  "Deploy a library to Clojars.
   
   Usage: clojure -T:build deploy :lib core
   
   Note: Requires CLOJARS_USERNAME and CLOJARS_PASSWORD environment variables."
  [{:keys [lib]}]
  (when-not lib
    (println "ERROR: :lib parameter required")
    (System/exit 1))
  
  (println "WARN: Clojars deployment not yet implemented")
  (println (str "To deploy " lib ", run:"))
  (println (str "  cd " (lib-dir lib)))
  (println "  clojure -T:build jar")
  (println "  mvn deploy:deploy-file ..."))

(defn release-all
  "Release all libraries to Clojars.
   
   Steps:
   1. Run all tests
   2. Build all jars
   3. Deploy to Clojars
   
   Note: Requires CLOJARS_USERNAME and CLOJARS_PASSWORD environment variables."
  [_]
  (println "=== Wagoe Release Process ===\n")
  
  ;; Step 1: Test
  (println "Step 1: Running all tests...")
  (test-all {})
  
  ;; Step 2: Build
  (println "\nStep 2: Building all jars...")
  (jar-all {})
  
  ;; Step 3: Deploy
  (println "\nStep 3: Deploying to Clojars...")
  (println "WARN: Automated deployment not yet implemented")
  (println "Please deploy manually for now.")
  
  (println "\n✓ Release preparation complete"))

;; =============================================================================
;; Utilities
;; =============================================================================

(defn list-libraries
  "List all libraries in the monorepo."
  [_]
  (println "Wagoe Libraries:")
  (doseq [lib libraries]
    (println (str "  - " lib " (" group-id "/" lib ":" version ")"))))

(defn status
  "Show status of all libraries."
  [_]
  (println "=== Wagoe Monorepo Status ===\n")
  (println (str "Version: " version))
  (println (str "Group ID: " group-id))
  (println (str "Libraries: " (count libraries)))
  (println)
  (list-libraries {}))
