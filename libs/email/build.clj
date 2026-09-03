(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'com.wagoe/wagoe-email)
(def version "1.0.0-beta-6")
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
                :pom-data [[:description "Email sending library for Wagoe framework: SMTP support, composition, validation"]
                           [:url "https://github.com/wagoebv/wagoe"]
                           [:licenses
                            [:license
                             [:name "Eclipse Public License 2.0"]
                             [:url "https://www.eclipse.org/legal/epl-2.0/"]]]]})
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
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
