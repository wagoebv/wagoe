(ns wagoe.tools.project-test
  "Which namespace a project's own code lives under.

   `bb scaffold generate` wrote every module into `wagoe.*` — the framework's
   namespace root — because `--base-ns` defaulted to \"wagoe\" and nothing ever
   passed it anything else. A project called shop had a `wagoe.product` module
   (BOU-360)."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [wagoe.tools.project :as project])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- tmp-root [] (.toFile (Files/createTempDirectory "project-test" (make-array FileAttribute 0))))

(defn- touch! [root & segs]
  (let [f (apply io/file root segs)]
    (io/make-parents f)
    (spit f "")
    f))

(deftest ^:unit the-project-namespace-is-where-main-lives
  ;; `wagoe new shop` writes src/shop/main.clj, and has since long before this
  ;; mattered — so the layout answers the question and there is no new file to
  ;; forget to update.
  (let [root (tmp-root)]
    (touch! root "src" "shop" "main.clj")
    (is (= "shop" (project/base-ns root)))))

(deftest ^:unit the-frameworks-own-repository-still-answers-wagoe
  ;; This repository's application really is wagoe.main, so nothing about the
  ;; monorepo changes.
  (let [root (tmp-root)]
    (touch! root "src" "wagoe" "main.clj")
    (is (= "wagoe" (project/base-ns root)))))

(deftest ^:unit a-project-with-no-main-falls-back-rather-than-guessing
  (testing "no src/ at all"
    (is (= "wagoe" (project/base-ns (tmp-root)))))

  (testing "src/ exists but holds no main.clj"
    (let [root (tmp-root)]
      (touch! root "src" "shop" "system.clj")
      (is (= "wagoe" (project/base-ns root)))))

  (testing "a file called src/main.clj is not a project namespace"
    ;; Only a directory containing main.clj counts. Without the directory
    ;; check this returned the file name.
    (let [root (tmp-root)]
      (touch! root "src" "main.clj")
      (is (= "wagoe" (project/base-ns root))))))

(deftest ^:unit an-underscored-directory-is-returned-as-written
  ;; `wagoe new my-app` writes src/my_app/, and the generated namespaces are
  ;; my_app.*. Converting back to dashes here would name a namespace that does
  ;; not exist.
  (let [root (tmp-root)]
    (touch! root "src" "my_app" "main.clj")
    (is (= "my_app" (project/base-ns root)))))
