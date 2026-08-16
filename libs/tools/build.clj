(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'com.wagoe/wagoe-tools)
(def version "1.0.0-beta-5")
(def class-dir "target/classes")
(load-file "../build_shared.clj")
(def basis (build-shared/pom-basis version))
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis basis
                :src-dirs ["src"]
                :scm {:url "https://github.com/wagoebv/wagoe"
                      :connection "scm:git:git://github.com/wagoebv/wagoe.git"
                      :developerConnection "scm:git:ssh://git@github.com/wagoebv/wagoe.git"
                      :tag version}
                :pom-data [[:description "Developer tooling for the Wagoe framework: scaffolding, AI assistance, i18n management, deployment and development utilities"]
                           [:url "https://github.com/wagoebv/wagoe"]
                           [:licenses
                            [:license
                             [:name "Eclipse Public License 2.0"]
                             [:url "https://www.eclipse.org/legal/epl-2.0/"]]]]})
  (b/copy-dir {:src-dirs ["src"]
               :target-dir class-dir})
  ;; BOU-76: ship the shared error catalogue inside the wagoe-tools jar so
  ;; consumer projects that depend on wagoe-tools alone (without
  ;; wagoe-devtools) can run `bb guide error BND-xxx` and don't crash on
  ;; namespace-load of wagoe.tools.help. Source of truth lives in
  ;; libs/devtools/resources; copied here at build time.
  (b/copy-file {:src    "../devtools/resources/wagoe/devtools/error_catalog.edn"
                :target (str class-dir "/wagoe/devtools/error_catalog.edn")})
  (spit (str class-dir "/cljdoc.edn")
        (pr-str {:cljdoc/root "libs/tools"}))
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))

(defn install [_]
  (jar nil)
  (b/install {:basis basis
              :lib lib
              :version version
              :jar-file jar-file
              :class-dir class-dir}))

(defn deploy [_]
  (jar nil)
  ((requiring-resolve 'deps-deploy.deps-deploy/deploy)
   {:installer :remote
    :artifact jar-file
    :pom-file (b/pom-path {:lib lib :class-dir class-dir})}))
