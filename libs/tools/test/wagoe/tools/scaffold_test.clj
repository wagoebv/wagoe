(ns wagoe.tools.scaffold-test
  "BOU-259: `bb scaffold new` used to generate a project through a second,
   independent implementation (scaffolder's generate-project-* generators)
   that had drifted until it no longer produced a Wagoe project at all — no
   com.wagoe deps, no main.clj/system.clj, no build.clj, no tests.edn, no .env.
   `wagoe new` (libs/wagoe-cli templates) is the only project generator.

   These tests hold that removal in place: the subcommand must redirect, must
   not write anything, and must fail so automation cannot read the redirect as
   a generated project."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [babashka.fs :as fs]
            [wagoe.tools.scaffold :as scaffold]))

(defn- run
  "Runs `-main` capturing stdout and the requested exit code, without letting
   the exit terminate the test JVM. Returns {:out ... :exit ...}."
  [& args]
  (let [exit (atom nil)
        out  (with-out-str
               (binding [scaffold/*exit!* #(reset! exit %)]
                 (apply scaffold/-main args)))]
    {:out out :exit @exit}))

(deftest ^:unit scaffold-new-redirects-to-wagoe-cli
  (testing "`bb scaffold new` names the replacement command"
    (let [{:keys [out]} (run "new")]
      (is (str/includes? out "wagoe new")
          "must name `wagoe new` — a bare removal notice is a dead end (BOU-261/262)")
      (is (str/includes? out "install.sh")
          "must tell a user without the CLI how to get it")))

  (testing "passthrough args redirect too, rather than generating"
    (let [{:keys [out]} (run "new" "--name" "my-app")]
      (is (str/includes? out "wagoe new"))))

  (testing "the removed command fails, so a script cannot read it as success"
    ;; `bb scaffold new --name x` was a non-interactive passthrough that
    ;; generated a project; existing automation may still call it.
    (is (= 1 (:exit (run "new"))))
    (is (= 1 (:exit (run "new" "--name" "my-app")))
        "exit code must not depend on whether args were passed")
    ;; The scaffolder CLI matched global --help before the removed-command
    ;; branch, so `scaffolder new --help` exited 0 with root help while every
    ;; other form exited 1. This side reads only the subcommand and was always
    ;; correct — asserted so it stays that way.
    (is (= 1 (:exit (run "new" "--help")))
        "--help must not make a removed command look available")
    (let [{:keys [out]} (run "new" "--help")]
      (is (str/includes? out "wagoe new"))))

  (testing "redirect writes no files"
    (let [tmp (fs/create-temp-dir {:prefix "bou259-"})]
      (try
        ;; --output-dir is where the old route wrote the project; nothing may
        ;; land there now.
        (run "new" "--name" "my-app" "--output-dir" (str tmp))
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

(deftest ^:unit scaffolder-deps-carries-the-source-rewriter
  ;; The scaffolder edits schema.clj with rewrite-clj. Injecting the scaffolder
  ;; alone would fail with `Could not locate rewrite_clj/zip` the moment the
  ;; next release lands, because the POM this pins predates the dependency —
  ;; the same shape as BOU-272, where tools.cli was missing from wagoe-ai's POM
  ;; and every `bb ai` subcommand died in a generated project.
  (testing "rewrite-clj travels with the injected dependency"
    ;; Explicit nil and an explicit root, never the zero-arity: that reads
    ;; WAGOE_SCAFFOLDER_ROOT, so the test would assert whatever the developer
    ;; happens to have exported.
    (doseq [[label deps] [["published pin" (#'scaffold/scaffolder-deps nil)]
                          ["local root"    (#'scaffold/scaffolder-deps "/tmp/scaffolder")]]]
      (is (str/includes? deps "rewrite-clj/rewrite-clj")
          (str label ": the schema editor cannot load without it"))))

  (testing "and the library itself declares what it requires"
    (let [declared (slurp "libs/scaffolder/deps.edn")]
      (is (str/includes? declared "rewrite-clj/rewrite-clj")
          "libs/scaffolder requires rewrite-clj, so it must declare it")))

  (testing "the injected argument is readable EDN"
    (let [parsed (read-string (#'scaffold/scaffolder-deps nil))]
      (is (contains? (:deps parsed) 'com.wagoe/wagoe-scaffolder))
      (is (contains? (:deps parsed) 'rewrite-clj/rewrite-clj))))

  (testing "the pin matches what the library declares"
    (let [declared (:mvn/version (get (:deps (read-string (slurp "libs/scaffolder/deps.edn")))
                                      'rewrite-clj/rewrite-clj))
          injected (:mvn/version (get (:deps (read-string (#'scaffold/scaffolder-deps nil)))
                                      'rewrite-clj/rewrite-clj))]
      (is (= declared injected) "a drifted pin resolves two versions"))))

;; =============================================================================
;; BOU-360: the scaffolder is told which namespace this project's code is in
;; =============================================================================

(deftest ^:unit base-ns-is-passed-without-the-user-having-to-know-about-it
  ;; --base-ns existed before this and defaulted to "wagoe", so every generated
  ;; project put its modules in the framework's namespace. Nobody passed the
  ;; flag because nobody knew they had to.
  (testing "a generate call gains the flag"
    (let [args (scaffold/with-base-ns ["generate" "--module-name" "product"])]
      (is (some #{"--base-ns"} args))))

  (testing "an explicit --base-ns is left alone"
    ;; Including the value: appending a second one would win over the user's.
    (let [args (scaffold/with-base-ns ["generate" "--module-name" "p" "--base-ns" "acme"])]
      (is (= 1 (count (filter #{"--base-ns"} args))))
      (is (= "acme" (second (drop-while #(not= "--base-ns" %) args)))))))

;; =============================================================================
;; BOU-364: --output-dir names the project the namespace is read from
;; =============================================================================

(deftest ^:unit base-ns-is-read-from-the-project-being-edited
  ;; `module-base-ns` looks for the module's directory to decide which
  ;; namespace it lives under, and looked in the working directory regardless of
  ;; --output-dir. Editing another project therefore derived the *caller's*
  ;; namespace: `bb scaffold endpoint --output-dir /path/to/shop` from this repo
  ;; sent `--base-ns wagoe`, and the guard added in BOU-364 then refused a module
  ;; that is really there, under `shop`.
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "wagoe-scaffold-basens"
                       (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      ;; A project whose own namespace is `shop`, with a `product` module in it.
      (.mkdirs (io/file root "src/shop/product/shell"))
      (spit (io/file root "src/shop/main.clj") "(ns shop.main)")

      (testing "the module's own project decides, not the caller's"
        (let [args (scaffold/with-base-ns
                     ["endpoint" "--module-name" "product"
                      "--output-dir" (.getPath root)])]
          (is (= "shop" (second (drop-while #(not= "--base-ns" %) args)))
              "the module is under shop/ in the directory being edited")))

      (testing "an explicit --base-ns still wins"
        (let [args (scaffold/with-base-ns
                     ["endpoint" "--module-name" "product"
                      "--output-dir" (.getPath root) "--base-ns" "acme"])]
          (is (= "acme" (second (drop-while #(not= "--base-ns" %) args))))))

      (finally (doseq [f (reverse (file-seq root))] (.delete f))))))

(deftest ^:unit long-options-are-read-in-both-their-forms
  ;; tools.cli accepts `--opt value` and `--opt=value`, and with-base-ns scanned
  ;; for the bare token only. `--output-dir=/path/to/shop` therefore fell back to
  ;; the working directory, derived the caller's namespace and sent the guards
  ;; looking in the wrong project. The same scan reads --module-name and
  ;; --base-ns, so all three are checked here.
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "wagoe-scaffold-eq"
                       (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (.mkdirs (io/file root "src/shop/product/shell"))
      (spit (io/file root "src/shop/main.clj") "(ns shop.main)")

      (testing "--output-dir=DIR names the project to read the namespace from"
        (is (= "shop" (second (drop-while
                               #(not= "--base-ns" %)
                               (scaffold/with-base-ns
                                 ["endpoint" "--module-name" "product"
                                  (str "--output-dir=" (.getPath root))]))))))

      (testing "--module-name=NAME still finds the module"
        (is (= "shop" (second (drop-while
                               #(not= "--base-ns" %)
                               (scaffold/with-base-ns
                                 ["endpoint" "--module-name=product"
                                  (str "--output-dir=" (.getPath root))]))))))

      (testing "--base-ns=NS is left alone rather than joined by a second one"
        ;; Appending would put two --base-ns on the command line; the scaffolder
        ;; takes the first, so the user's would win by luck rather than by rule.
        (let [args (scaffold/with-base-ns
                     ["endpoint" "--module-name=product" "--base-ns=acme"
                      (str "--output-dir=" (.getPath root))])]
          (is (= 1 (count (filter #(str/starts-with? % "--base-ns") args))))
          (is (some #{"--base-ns=acme"} args))))

      (finally (doseq [f (reverse (file-seq root))] (.delete f))))))
