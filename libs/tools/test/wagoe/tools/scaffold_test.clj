(ns wagoe.tools.scaffold-test
  "BOU-259: `bb scaffold new` used to generate a project through a second,
   independent implementation (scaffolder's generate-project-* generators)
   that had drifted until it no longer produced a Wagoe project at all — no
   com.wagoe deps, no main.clj/system.clj, no build.clj, no tests.edn, no .env.
   `wagoe new` (libs/wagoe-cli templates) is the only project generator.

   These tests hold that removal in place: the subcommand must redirect and
   must not write anything."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [wagoe.tools.scaffold :as scaffold]))

(deftest ^:unit scaffold-new-redirects-to-wagoe-cli
  (testing "`bb scaffold new` names the replacement command"
    (let [out (with-out-str (scaffold/-main "new"))]
      (is (str/includes? out "wagoe new")
          "must name `wagoe new` — a bare removal notice is a dead end (BOU-261/262)")
      (is (str/includes? out "install.sh")
          "must tell a user without the CLI how to get it")))

  (testing "passthrough args redirect too, rather than generating"
    (let [out (with-out-str (scaffold/-main "new" "--name" "my-app"))]
      (is (str/includes? out "wagoe new"))))

  (testing "redirect writes no files"
    (let [tmp (fs/create-temp-dir {:prefix "bou259-"})]
      (try
        ;; --output-dir is where the old route wrote the project; nothing may
        ;; land there now.
        (with-out-str
          (scaffold/-main "new" "--name" "my-app" "--output-dir" (str tmp)))
        (is (empty? (fs/list-dir tmp))
            "the removed route must not create a project directory")
        (finally (fs/delete-tree tmp))))))

(deftest ^:unit scaffold-help-does-not-advertise-project-creation
  (testing "help text points project creation at the CLI, not at bb scaffold"
    (is (not (re-find #"bb scaffold new\s+Interactive wizard for new project"
                      scaffold/help-text))
        "help must no longer offer `bb scaffold new` as a project bootstrapper")
    (is (str/includes? scaffold/help-text "wagoe new")
        "help must name the real project generator")))
