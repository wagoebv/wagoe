(ns build
  "Uberjar build for shop.

   Usage:
     clojure -T:build clean
     clojure -T:build uber      ; -> target/shop-<version>.jar
     java -jar target/shop-0.1.0.jar

   AOT-compiles shop.main, which is the same entry point
   `clojure -M:run` and the Dockerfile use."
  (:require [clojure.tools.build.api :as b]))

(def lib 'shop/shop)
(def version "0.1.0")
(def class-dir "target/classes")
(def uber-file (format "target/%s-%s.jar" (name lib) version))

(defn- basis []
  (b/create-basis {:project "deps.edn"}))

(defn clean [_]
  (b/delete {:path "target"})
  (println "Cleaned target/"))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs   ["src" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis      (basis)
                  :ns-compile '[shop.main]
                  :class-dir  class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis     (basis)
           :main      'shop.main
           ;; Several transitive dependencies ship signed jars and overlapping
           ;; META-INF entries; without this the uber step fails on duplicate
           ;; or invalid signature files.
           :exclude   ["META-INF/.*\\.SF" "META-INF/.*\\.DSA" "META-INF/.*\\.RSA"]})
  (println "Built" uber-file))
