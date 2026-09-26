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

(deftest ^:unit scaffold-help-names-the-field-modifiers
  ;; default= is new (BOU-494); the passthrough example is where it is found.
  (doseq [modifier ["values=" "required" "unique" "default="]]
    (is (str/includes? scaffold/help-text modifier) modifier)))

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

(deftest ^:unit a-repeated-long-option-resolves-the-way-tools-cli-resolves-it
  ;; tools.cli is last-wins and does not care which form each occurrence used:
  ;;   ["--output-dir=/a" "--output-dir" "/b"] => /b
  ;;   ["--output-dir" "/b" "--output-dir=/a"] => /a
  ;; long-opt preferred the =value form wherever it sat, so an overridden
  ;; default made with-base-ns read the namespace from one project while the
  ;; scaffolder edited another — and the guard then rejected a module that is
  ;; really there.
  (let [a (.toFile (java.nio.file.Files/createTempDirectory
                    "wagoe-scaffold-a" (make-array java.nio.file.attribute.FileAttribute 0)))
        b (.toFile (java.nio.file.Files/createTempDirectory
                    "wagoe-scaffold-b" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      ;; Two projects, distinguishable by the namespace their module sits under.
      (.mkdirs (io/file a "src/alpha/product/shell"))
      (spit (io/file a "src/alpha/main.clj") "(ns alpha.main)")
      (.mkdirs (io/file b "src/bravo/product/shell"))
      (spit (io/file b "src/bravo/main.clj") "(ns bravo.main)")

      (let [base-ns-of (fn [args]
                         (second (drop-while #(not= "--base-ns" %)
                                             (scaffold/with-base-ns args))))]
        (testing "equals-form first, two-token second — the second wins"
          (is (= "bravo" (base-ns-of ["endpoint" "--module-name" "product"
                                      (str "--output-dir=" (.getPath a))
                                      "--output-dir" (.getPath b)]))))

        (testing "two-token first, equals-form second — the second wins"
          (is (= "alpha" (base-ns-of ["endpoint" "--module-name" "product"
                                      "--output-dir" (.getPath b)
                                      (str "--output-dir=" (.getPath a))]))))

        (testing "same form twice — still the last one"
          (is (= "bravo" (base-ns-of ["endpoint" "--module-name" "product"
                                      "--output-dir" (.getPath a)
                                      "--output-dir" (.getPath b)])))))

      (finally
        (doseq [d [a b]] (doseq [f (reverse (file-seq d))] (.delete f)))))))

;; =============================================================================
;; One parser: the spec here must be the scaffolder's spec (BOU-378)
;; =============================================================================

(deftest ^:unit base-ns-option-specs-match-the-scaffolder-cli
  ;; libs/tools cannot require libs/scaffolder, so with-base-ns carries its own
  ;; narrow copy of the three option specs. This reads the scaffolder's cli.clj
  ;; source and fails if any of the three is renamed out from under the copy —
  ;; the drift that two independent parsers turned into three shipped bugs.
  (let [cli-src (or (some #(when (.exists (io/file %)) (slurp %))
                          ["libs/scaffolder/src/wagoe/scaffolder/cli.clj"
                           "../scaffolder/src/wagoe/scaffolder/cli.clj"])
                    (throw (ex-info "scaffolder cli.clj not found — cannot compare" {})))
        names   (map second scaffold/base-ns-option-specs)]
    (is (= 3 (count names)) "the narrow spec covers exactly the options with-base-ns reads")
    (doseq [n names]
      (is (str/includes? cli-src (str "\"" n "\""))
          (str "with-base-ns specs " n " but the scaffolder CLI no longer declares it — "
               "the two parsers are drifting apart again")))))

(deftest ^:unit with-base-ns-takes-the-next-token-as-a-value-like-tools-cli
  ;; The hand parser refused a flag-looking token as a value; tools.cli — the
  ;; parser the scaffolder actually runs — takes the next token unconditionally.
  ;; Agreement is the requirement, so this pins the tools.cli reading: the bogus
  ;; directory means module-base-ns finds nothing there and with-base-ns still
  ;; appends a --base-ns rather than silently reading the caller's project.
  (let [args (scaffold/with-base-ns
               ["endpoint" "--module-name" "product" "--output-dir" "--dry-run"])]
    (is (some #{"--base-ns"} args))))

(deftest ^:unit a-malformed-command-is-passed-through-for-the-scaffolder-to-reject
  ;; A bare trailing --base-ns parses as a missing-argument error. Appending our
  ;; pair after it would hand the scaffolder `--base-ns --base-ns <computed>`,
  ;; which its non-strict parse reads as base-ns = "--base-ns" — scaffolded
  ;; garbage instead of a rejection. Same for --output-dir, whose consumed flag
  ;; becomes a directory name.
  (doseq [args [["endpoint" "--module-name" "product" "--base-ns"]
                ["endpoint" "--module-name" "product" "--output-dir"]]]
    (is (= args (scaffold/with-base-ns args))
        (str "malformed " (last args) " must reach the scaffolder untouched"))))
